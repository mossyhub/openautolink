package com.openautolink.app.carplay

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Projection policy contains no preference writers and owns only a transient USB generation. */
internal class UsbHotPlugSwitchPolicy {
    private val mutex = Mutex()
    enum class Protocol { ANDROID_AUTO, CARPLAY }
    @Volatile var generation: Long? = null
        private set
    fun shouldPrompt(aaActive: Boolean, aaConnecting: Boolean, cpSelected: Boolean): Boolean =
        aaActive || aaConnecting || cpSelected || generation != null
    fun wirelessCarPlay(enabled: Boolean, mode: String): Boolean = enabled && mode == "wireless"
    suspend fun accept(request: Long, resolve: () -> Protocol?, eligible: suspend (Protocol) -> Boolean,
        claimed: (Protocol) -> Unit, retire: suspend () -> Unit, admit: (Protocol) -> Boolean,
        cleared: () -> Unit, restore: suspend () -> Unit, replaceCurrent: Boolean = false): Boolean = mutex.withLock {
        if (generation != null && (!replaceCurrent || generation == request)) return@withLock false
        val protocol = resolve() ?: return@withLock false
        if (!eligible(protocol) || resolve() != protocol) return@withLock false
        generation = request
        claimed(protocol)
        // Failure intentionally retains the generation: a failed close is not retirement proof.
        retire()
        if (resolve() == protocol && eligible(protocol)) {
            val admitted = try { admit(protocol) } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Synchronous startup can have partially installed resources. Await their owner again.
                retire()
                false
            }
            if (admitted) return@withLock true
        }
        generation = null
        cleared()
        restore()
        false
    }
    suspend fun finish(request: Long, retire: suspend () -> Unit, cleared: () -> Unit,
        restore: suspend () -> Unit): Boolean = mutex.withLock {
        if (generation != request) return@withLock false
        retire()
        generation = null
        cleared()
        restore()
        true
    }
    /** Caller has already awaited retirement, e.g. global Save or explicit Retry. */
    fun clearRetired() { generation = null }
}
