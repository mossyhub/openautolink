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
    /** Finite numeric-only receipts; the owner routes these through its normal sanitized export. */
    @Volatile var diagnosticReceipt: (String) -> Unit = {}
    /** Separate source-qualified iAP2 EndCall(action=0), NEVER HID Drop for ringing calls.
     * The installing owner must apply the same explicit enable/epoch/authority/writer fences. */
    @Volatile var rejectIntent: ((() -> Boolean) -> Boolean)? = null
    private var receiptCount = 0 // main-owned, capped independently of opt-in lifetime
    private fun receipt(action: String, reason: String, record: Record) {
        if (receiptCount >= 64) return
        receiptCount++
        val conference = record.conference?.let { if (it) 1 else 0 } ?: -1
        val line = "media stage=native-call action=$action reason=$reason status=${record.status ?: -1} " +
            "pending=${record.pending?.name ?: "NONE"} calls=${records.values.count { it.status != 0 }} " +
            "conference=$conference queued=${appliedRevision != controlRevision.get()}"
        runCatching { diagnosticReceipt(line) } // diagnostics cannot break call state/control
    }
    @Volatile var controlStatus: String = "IDLE_OR_DISPLAY_ONLY"
        private set
    private fun controlsLive(record: Record): Boolean {
        if (record.confirmationDeadline != 0L && android.os.SystemClock.elapsedRealtime() >= record.confirmationDeadline) {
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper() && !record.timeoutReported) {
                record.timeoutReported = true
                receipt(if (record.pendingReject) "REJECT" else record.pending?.name ?: "OBSERVATION", "TIMEOUT", record)
            }
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
        // Private phone-provided display facts, retained only by this exact call record.
        // Never include these in receipts/toString or resolve contacts/remote URIs.
        var address: android.net.Uri? = null
        var displayName: String? = null
        @Volatile var commandFailed = false
        @Volatile var confirmationDeadline = 0L
        var pending: com.shilapi.xcertplay.airplay.PhoneControl? = null
        var pendingReject = false
        var timeoutReported = false // main only: socket predicates never publish a receipt
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
            // Still acknowledge delivery on the same main queue. Leaving this revision unapplied
            // strands controls even after a phone-confirmed active call and an innocuous count=1.
            // The hint never ends calls or establishes complete/single-call inventory.
        }
        if (!dispatch {
            if (live()) {
                if (observation is PartialCallObservation) merge(observation)
                appliedRevision = revision
            } else close()
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
        // No sourced presentation/restriction flag exists in this schema. Disclose only a
        // supplied safe value; empty/invalid revokes LOCAL disclosure, not a wire clear claim.
        (value.remoteId as? Presence.Present)?.let {
            record.address = it.value.takeIf { number -> number.length in 1..128 &&
                number.any { c -> c in '0'..'9' } && TELEPHONE_PATTERN.matches(number) }
                ?.let { number -> android.net.Uri.fromParts("tel", number, null) }
        }
        (value.displayName as? Presence.Present)?.let {
            record.displayName = it.value.takeIf { name -> name.length in 1..256 && name.isNotBlank() &&
                name.none { c -> c.isISOControl() || c in '\u202a'..'\u202e' || c in '\u2066'..'\u2069' } }
        }
        controlsLive(record) // a delayed phone fact does not retry a failed operation
        if ((record.pending == com.shilapi.xcertplay.airplay.PhoneControl.ANSWER && record.status == 4) ||
            (record.pending == com.shilapi.xcertplay.airplay.PhoneControl.END && record.status == 0)) {
            controlStatus = "PHONE_CONFIRMED_${if (record.pendingReject) "REJECT" else record.pending!!.name}"
            receipt(if (record.pendingReject) "REJECT" else record.pending!!.name, "PHONE_CONFIRMED", record)
            record.pending = null; record.pendingReject = false; record.confirmationDeadline = 0
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
        receipt("OBSERVATION", "OBSERVED", record)
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
            override fun onReject() = reject(record)
            override fun onReject(rejectReason: Int) = onReject()
            override fun onReject(replyMessage: String?) = onReject() // never send SMS
            override fun onDisconnect() = intent(record, com.shilapi.xcertplay.airplay.PhoneControl.END)
        }.also {
            record.connection = it
            it.connectionCapabilities = 0 // no hold, conference, mute or outgoing promises
            if (rejectIntent == null) it.statusHints = android.telecom.StatusHints("Reject does nothing; decline on iPhone/CarPlay", null, null)
            it.setAudioModeIsVoip(false)
            update(record, it)
        }
    }
    private fun reject(record: Record) {
        if (rejectIntent == null) { receipt("REJECT", "UNSUPPORTED", record); return }
        intent(record, com.shilapi.xcertplay.airplay.PhoneControl.END, rejecting = true)
    }
    /** Commands are not call-targeted: require one known, unambiguous incoming call per action. */
    private fun intent(record: Record, control: com.shilapi.xcertplay.airplay.PhoneControl, rejecting: Boolean = false) {
        // No sender without explicit enablement; timeout fails the operation, not remote call state.
        val action = if (rejecting) "REJECT" else control.name
        val sender = if (rejecting) rejectIntent?.let { reject ->
            { _: com.shilapi.xcertplay.airplay.PhoneControl, fence: () -> Boolean -> reject(fence) }
        } else controlIntent
        val withheld = when {
            sender == null -> "DISPLAY_ONLY"
            !controlsLive(record) -> "FAILED"
            !live() -> "OWNER"
            multiHint.get() -> "MULTI_HINT"
            appliedRevision != controlRevision.get() -> "OBSERVATIONS_QUEUED"
            capacityDrops != 0 -> "CAPACITY"
            record.pending != null -> "PENDING"
            !record.admitted || record.terminal -> "TERMINAL_OR_UNADMITTED"
            record.direction != 1 -> "DIRECTION"
            record.conference != false || (record.conferenceGroup ?: 0) != 0 -> "CONFERENCE"
            records.values.count { it.status != 0 } != 1 -> "MULTIPLE"
            record.status != (if (rejecting || control == com.shilapi.xcertplay.airplay.PhoneControl.ANSWER) 2 else 4) -> "STATE"
            else -> null
        }
        if (withheld != null) { receipt(action, withheld, record); return }
        val revision = controlRevision.get()
        record.pending = control // no retries after queue/write failure
        record.pendingReject = rejecting
        record.confirmationDeadline = android.os.SystemClock.elapsedRealtime() + 5000
        controlStatus = "QUEUED_${action}_UNCONFIRMED"
        receipt(action, "QUEUED", record)
        if (!sender!!(control) { controlsLive(record) && !revoked.get() && current() && controlRevision.get() == revision }) {
            record.commandFailed = true; controlStatus = "COMMAND_WITHHELD"
            receipt(action, "SENDER_WITHHELD", record)
        }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ controlsLive(record) }, 5000)
    }
    private fun update(record: Record, connection: Connection) {
        if (!live()) { retire(); return }
        // Caller facts go only to the local OS call presentation; never to diagnostic receipts.
        connection.setAddress(record.address, if (record.address == null)
            android.telecom.TelecomManager.PRESENTATION_UNKNOWN else android.telecom.TelecomManager.PRESENTATION_ALLOWED)
        connection.setCallerDisplayName(record.displayName, if (record.displayName == null)
            android.telecom.TelecomManager.PRESENTATION_UNKNOWN else android.telecom.TelecomManager.PRESENTATION_ALLOWED)
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
        private val TELEPHONE_PATTERN = Regex("[+]?[0-9(). -]+")
        private val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        fun failed(): Connection = Connection.createFailedConnection(DisconnectCause(DisconnectCause.ERROR))
    }
}
