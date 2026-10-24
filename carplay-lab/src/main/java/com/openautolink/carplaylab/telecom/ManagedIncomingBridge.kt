package com.openautolink.carplaylab.telecom

import android.telecom.Connection
import android.telecom.DisconnectCause
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Incoming observations with optional native Answer/active-End diagnostics.
 * NOT complete inventory or production native phone support. Main-serialized call effects; ingress
 * revocation is immediate. No call expiry, count reconciliation, cold active import or audio mutation.
 */
class ManagedIncomingBridge(
    private val enabled: () -> Boolean,
    private val announce: (String) -> Unit,
    private val dispatch: (() -> Unit) -> Boolean,
    private val current: () -> Boolean = { true },
    private val retireDispatch: (() -> Unit) -> Boolean = dispatch,
    private val controlIntent: ((com.shilapi.xcertplay.airplay.PhoneControl, () -> Boolean) -> Boolean)? = null,
) : AutoCloseable {
    private val revoked = AtomicBoolean()
    private val cleanupQueued = AtomicBoolean()
    private val closed = java.util.concurrent.CountDownLatch(1)
    enum class Cleanup { OPEN, PENDING, FAILED, CLOSED }
    @Volatile var cleanup: Cleanup = Cleanup.OPEN
        private set
    /** Never wait on main for main-owned effects. False includes rejection/failure, not success. */
    fun awaitClosed(timeoutMs: Long): Boolean {
        require(timeoutMs >= 0)
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return closed.count == 0L
        return closed.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    }
    private val decoder = CallTlvDecoder()
    private val controlRevision = java.util.concurrent.atomic.AtomicLong()
    private var appliedRevision = 0L // main-only; no command while newer observations are queued
    private val multiHint = AtomicBoolean()
    @Volatile var controlStatus: String = "IDLE_OR_DISPLAY_ONLY"
        private set
    private fun controlsLive(record: Record): Boolean {
        if (record.confirmationDeadline != 0L && android.os.SystemClock.elapsedRealtime() >= record.confirmationDeadline) {
            record.commandFailed = true; controlStatus = "UNCONFIRMED_COMMAND_FAILED"
        }
        return !record.commandFailed
    }
    @Volatile var capacityDrops: Int = 0
        private set
    @Volatile var queueDrops: Int = 0
        private set
    private class Record {
        val token = UUID.randomUUID().toString() // local request correlation, NEVER a wire selector
        var status: Int? = null
        var direction: Int? = null
        var admitted = false
        var consumed = false
        var terminal = false
        var conference: Boolean? = null
        var conferenceGroup: Int? = null
        @Volatile var commandFailed = false
        @Volatile var confirmationDeadline = 0L
        var pending: com.shilapi.xcertplay.airplay.PhoneControl? = null
        var connection: Connection? = null
    }
    private val records = LinkedHashMap<String, Record>()
    private fun live() = !revoked.get() && current() && runCatching(enabled).getOrDefault(false)
    fun handle(frame: Iap2Frame): Boolean {
        if (frame.messageId != 0x4155 && frame.messageId != 0x4158) return false
        if (!live()) { close(); return true }
        val observation = try { decoder.decode(frame.messageId, frame.payload) }
            catch (_: IllegalArgumentException) { return true }
        val revision = controlRevision.incrementAndGet() // fence commands BEFORE main delivery
        if (observation is PartialCommunicationsUpdate) {
            if (((observation.currentCallCountHint as? Presence.Present)?.value ?: 0) > 1) multiHint.set(true)
            return true // count never ends calls or establishes complete/single-call inventory
        }
        if (observation !is PartialCallObservation) return true
        if (!dispatch {
            if (live()) { merge(observation); appliedRevision = revision } else close()
        }) {
            queueDrops = (queueDrops.toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(); close()
        }
        return true
    }
    private fun merge(value: PartialCallObservation) {
        val id = (value.callUuid as? Presence.Present)?.value ?: return
        if (!UUID_PATTERN.matches(id)) return
        val key = id.lowercase(java.util.Locale.ROOT)
        val record = records[key] ?: run {
            if (records.size >= 16) {
                capacityDrops = (capacityDrops.toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                controlRevision.incrementAndGet()
                return // reconnect required; never evict identity/tombstones or invent expiry
            }
            Record().also { records[key] = it }
        }
        if (record.terminal) return
        (value.status as? Presence.Present)?.let { record.status = it.value.raw }
        (value.direction as? Presence.Present)?.let { record.direction = it.value }
        (value.isConferenced as? Presence.Present)?.let { record.conference = it.value }
        (value.conferenceGroup as? Presence.Present)?.let { record.conferenceGroup = it.value }
        controlsLive(record) // a delayed phone fact does not retry a failed operation
        if ((record.pending == com.shilapi.xcertplay.airplay.PhoneControl.ANSWER && record.status == 4) ||
            (record.pending == com.shilapi.xcertplay.airplay.PhoneControl.END && record.status == 0)) {
            controlStatus = "PHONE_CONFIRMED_${record.pending!!.name}"
            record.pending = null; record.confirmationDeadline = 0
        }
        if (record.status != 0 && (record.status !in setOf(2,4) || record.direction != 1 ||
                record.conference == true || (record.conferenceGroup ?: 0) != 0) ||
            records.values.count { it.status != 0 } > 1) {
            records.values.filter { it.status != 0 }.forEach { it.commandFailed = true }
            controlStatus = "AMBIGUOUS_CALL_WITHHELD"
        }
        if (record.admitted && record.status == 0) { record.terminal = true; record.consumed = true }
        // Optional timestamp and private caller fields are deliberately not published in this slice.
        if (!record.admitted && record.status == 2 && record.direction == 1) {
            record.admitted = true // before OS call, so denied/replayed events cannot ring again
            try { announce(record.token) } catch (_: RuntimeException) { record.consumed = true }
        }
        record.connection?.let { update(record, it) }
    }
    /** Called only by the main-thread ConnectionService, against the current exact local request. */
    fun admissionFailed(token: String) {
        if (!live()) { close(); return }
        val record = records.values.singleOrNull { it.token == token } ?: return
        record.consumed = true
        record.connection?.let {
            if (it.state != Connection.STATE_DISCONNECTED) {
                it.setDisconnected(DisconnectCause(DisconnectCause.LOCAL, "Telecom admission unavailable"))
                it.destroy()
            }
        }
    }
    fun create(token: String): Connection {
        if (!live()) { retire(); return failed() }
        val record = records.values.singleOrNull { it.token == token } ?: return failed()
        if (!record.admitted || record.consumed || record.status !in setOf(2, 4, 5)) {
            record.consumed = true
            return failed()
        }
        record.consumed = true
        return object : Connection() {
            // Distinct native actions: only explicitly enabled Answer/active-End may queue.
            // No setActive on Answer, no fake Reject acknowledgement and no Drop/Reject alias.
            override fun onAnswer() = intent(record, com.shilapi.xcertplay.airplay.PhoneControl.ANSWER)
            override fun onAnswer(videoState: Int) = onAnswer()
            override fun onReject() = Unit
            override fun onReject(rejectReason: Int) = onReject()
            override fun onReject(replyMessage: String?) = onReject()
            override fun onDisconnect() = intent(record, com.shilapi.xcertplay.airplay.PhoneControl.END)
        }.also {
            record.connection = it
            it.connectionCapabilities = 0 // no hold, conference, mute or outgoing promises
            it.statusHints = android.telecom.StatusHints("Reject does nothing; decline on iPhone/CarPlay", null, null)
            it.setAudioModeIsVoip(false)
            update(record, it)
        }
    }
    /** HID is not call-targeted: require one known, unambiguous incoming call per action. */
    private fun intent(record: Record, control: com.shilapi.xcertplay.airplay.PhoneControl) {
        // No sender without explicit enablement; timeout fails the operation, not remote call state.
        val sender = controlIntent ?: return
        if (!controlsLive(record) || !live() || multiHint.get() || appliedRevision != controlRevision.get() || capacityDrops != 0 ||
            record.pending != null || !record.admitted || record.terminal ||
            record.direction != 1 || record.conference != false || (record.conferenceGroup ?: 0) != 0 ||
            records.values.count { it.status != 0 } != 1 ||
            record.status != (if (control == com.shilapi.xcertplay.airplay.PhoneControl.ANSWER) 2 else 4)) return
        val revision = controlRevision.get()
        record.pending = control // no retries after queue/write failure
        record.confirmationDeadline = android.os.SystemClock.elapsedRealtime() + 5000
        controlStatus = "QUEUED_${control.name}_UNCONFIRMED"
        if (!sender(control) { controlsLive(record) && !revoked.get() && current() && controlRevision.get() == revision }) {
            record.commandFailed = true; controlStatus = "COMMAND_WITHHELD"
        }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ controlsLive(record) }, 5000)
    }
    private fun update(record: Record, connection: Connection) {
        if (!live()) { retire(); return }
        when (record.status) {
            0 -> { connection.setDisconnected(DisconnectCause(DisconnectCause.REMOTE)); connection.destroy() }
            2 -> if (connection.state == Connection.STATE_NEW) connection.setRinging()
            4 -> connection.setActive() // only explicit remote fact, never a button acknowledgement
            5 -> connection.setOnHold() // observed fact; no hold capability advertised
            else -> Unit // raw unknown preserved; no fabricated active/ended or controls
        }
    }
    private fun retire() {
        records.values.forEach { record ->
            record.connection?.let {
                if (it.state != Connection.STATE_DISCONNECTED) {
                    it.setDisconnected(DisconnectCause(DisconnectCause.LOCAL, "Projection observation unavailable"))
                }
                it.destroy() // a prior disconnect is NOT proof that destroy succeeded
            }
        }
        records.clear()
    }
    override fun close() {
        revoked.set(true) // revoke even when main is stalled or posting fails
        controlRevision.incrementAndGet()
        if (closed.count == 0L || !cleanupQueued.compareAndSet(false, true)) return
        cleanup = Cleanup.PENDING
        val action = {
            try { retire(); cleanup = Cleanup.CLOSED; closed.countDown() }
            catch (_: RuntimeException) { cleanup = Cleanup.FAILED }
            finally { cleanupQueued.set(false) }
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) action()
        else if (!runCatching { retireDispatch(action) }.getOrDefault(false)) {
            cleanup = Cleanup.FAILED; cleanupQueued.set(false)
        }
    }
    companion object {
        // Validate canonical UUID spelling before parsing/normalization; no UUID.fromString repair.
        private val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        fun failed(): Connection = Connection.createFailedConnection(DisconnectCause(DisconnectCause.ERROR))
    }
}
