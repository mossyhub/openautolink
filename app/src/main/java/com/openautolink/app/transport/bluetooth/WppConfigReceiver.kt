package com.openautolink.app.transport.bluetooth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

data class AppliedWppConfig(val version: Long, val ssid: String, val bssid: String)

internal data class WppConfigValues(val ssid: String, val bssid: String)

internal enum class WppConfigEvent { LISTENER_UP, LISTENER_FAILED, APPLIED, REJECTED, STOPPED }

internal typealias WppConfigCommit = (() -> Unit) -> Boolean

internal interface WppConfigListener : Closeable {
    fun accept(): WppConfigClient
}

internal interface WppConfigClient : Closeable {
    val input: InputStream
    val output: OutputStream
}

/** The RFCOMM receiver's lifecycle and transfer coordinator, independent of Android. */
internal class WppConfigReceiver(
    private val parentScope: CoroutineScope,
    private val listen: () -> WppConfigListener,
    private val decode: (String) -> WppConfigValues,
    private val persist: suspend (WppConfigValues, WppConfigCommit) -> Boolean,
    private val transferTimeoutMillis: Long = 5_000,
    private val onEvent: (WppConfigEvent) -> Unit = {},
) {
    private class Session(parentScope: CoroutineScope) {
        val job = SupervisorJob(parentScope.coroutineContext[Job])
        val scope = CoroutineScope(parentScope.coroutineContext + job + Dispatchers.IO)
        var listener: WppConfigListener? = null
        val clients = mutableSetOf<WppConfigClient>()
        val transferMutex = Mutex()
    }

    private val lock = Any()
    private var current: Session? = null
    private val _status = MutableStateFlow("Not started")
    val status: StateFlow<String> = _status
    private val _appliedConfig = MutableStateFlow<AppliedWppConfig?>(null)
    val appliedConfig: StateFlow<AppliedWppConfig?> = _appliedConfig

    fun start() = synchronized(lock) {
        if (!parentScope.isActive) return
        if (current != null) return
        val session = Session(parentScope)
        current = session
        _status.value = "Starting RFCOMM listener…"
        // Unconfined cleanup runs on cancellation, not behind blocked IO workers.
        // Completion handlers alone are too late: accept/read prevent job completion.
        session.scope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                synchronized(lock) { retire(session, "Stopped") }
            }
        }
        session.scope.launch { acceptLoop(session) }
        Unit
    }

    private fun isCurrent(session: Session) = current === session && session.job.isActive

    private fun acceptLoop(session: Session) {
        var opened: WppConfigListener? = null
        try {
            val listener = listen()
            opened = listener
            synchronized(lock) {
                if (!isCurrent(session)) return
                session.listener = listener
                _status.value = "Listening for companion SSID/BSSID config"
            }
            emit(WppConfigEvent.LISTENER_UP)
            while (synchronized(lock) { isCurrent(session) }) {
                val client = listener.accept()
                synchronized(lock) {
                    if (!isCurrent(session)) {
                        runCatching { client.close() }
                        return
                    }
                    session.clients.add(client)
                    session.scope.launch { handle(session, client) }
                }
            }
        } catch (e: Exception) {
            val failed = synchronized(lock) {
                if (isCurrent(session)) {
                    retire(session, "Listener failed: ${e.javaClass.simpleName}")
                    true
                } else false
            }
            if (failed) emit(WppConfigEvent.LISTENER_FAILED)
        } finally {
            runCatching { opened?.close() }
            emit(WppConfigEvent.STOPPED)
        }
    }

    private suspend fun handle(session: Session, client: WppConfigClient) {
        var outcomeReported = false
        // A coroutine timeout cannot interrupt InputStream.read(). Close from a
        // separate dispatcher, and revoke this client before unblocking its read.
        val deadline = session.scope.launch(Dispatchers.Default) {
            delay(transferTimeoutMillis)
            synchronized(lock) {
                if (session.clients.remove(client)) runCatching { client.close() }
            }
        }
        try {
            val input = DataInputStream(client.input)
            val length = input.readInt()
            if (length !in 1..4096) {
                outcomeReported = true
                emit(WppConfigEvent.REJECTED)
                DataOutputStream(client.output).writeUTF("ERR")
                return
            }
            val bytes = ByteArray(length)
            input.readFully(bytes)
            val config = decode(bytes.toString(Charsets.UTF_8))
            if (config.ssid.isEmpty() || config.bssid.isBlank()) {
                outcomeReported = true
                emit(WppConfigEvent.REJECTED)
                DataOutputStream(client.output).writeUTF("ERR")
                return
            }
            // Decode independently, but allow only one transfer to commit, publish,
            // acknowledge and retire this generation. Never hold the lifecycle monitor
            // across suspending persistence or socket writes: stop must stay responsive.
            session.transferMutex.withLock {
                if (!synchronized(lock) { isCurrent(session) && client in session.clients }) return
                val persisted = persist(config) { mutation ->
                    synchronized(lock) {
                        if (!isCurrent(session) || client !in session.clients) {
                            false
                        } else {
                            mutation()
                            true
                        }
                    }
                }
                if (!persisted) return
                try {
                    synchronized(lock) {
                        if (!isCurrent(session) || client !in session.clients) return
                        _appliedConfig.value = AppliedWppConfig(
                            (_appliedConfig.value?.version ?: 0L) + 1L, config.ssid, config.bssid,
                        )
                    }
                    outcomeReported = true
                    emit(WppConfigEvent.APPLIED)
                    DataOutputStream(client.output).writeUTF("OK")
                } finally {
                    // Even an ACK failure must not admit a second committed config.
                    synchronized(lock) {
                        if (isCurrent(session)) retire(session, "Stopped")
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A malformed/closed client must not kill the listener.
            if (!outcomeReported && synchronized(lock) { isCurrent(session) && client in session.clients }) {
                emit(WppConfigEvent.REJECTED)
            }
        } finally {
            deadline.cancel()
            runCatching { client.close() }
            synchronized(lock) { session.clients.remove(client) }
        }
    }

    fun stop() = synchronized(lock) {
        current?.let { retire(it, "Stopped") }
        _status.value = "Stopped"
    }

    // Invoked only by IO workers outside the lifecycle monitor. Observers receive
    // bounded outcomes, never payloads or exception messages containing identities.
    private fun emit(event: WppConfigEvent) {
        runCatching { onEvent(event) }
    }

    /** Called under lock: revoke the exact generation before closing any blocking I/O. */
    private fun retire(session: Session, status: String) {
        if (current !== session) return
        current = null
        _status.value = status
        session.job.cancel()
        session.clients.forEach { runCatching { it.close() } }
        session.clients.clear()
        runCatching { session.listener?.close() }
        session.listener = null
    }
}
