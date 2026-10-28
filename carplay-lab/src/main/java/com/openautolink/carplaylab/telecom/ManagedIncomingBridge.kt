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
    private val localRepairBlocked = AtomicBoolean()
    @Volatile var hasAdmittedCall: Boolean = false
        private set
    /** Local repair/conflict only. Retain tombstones so a restored guard cannot reannounce a call. */
    fun retireLocalCalls(): java.util.concurrent.CompletableFuture<Boolean> {
        localRepairBlocked.set(true)
        val completion = java.util.concurrent.CompletableFuture<Boolean>()
        controlRevision.incrementAndGet()
        val action = {
            try {
                records.values.filter { it.admitted }.forEach { record ->
                    latch(record, Failure.STATE)
                    record.consumed = true
                    record.connection?.let {
                        if (it.state != Connection.STATE_DISCONNECTED)
                            it.setDisconnected(DisconnectCause(DisconnectCause.LOCAL, "Local presentation retired"))
                        it.destroy()
                    }
                    record.connection = null
                }
                hasAdmittedCall = false
                localRepairBlocked.set(false)
                completion.complete(true)
            } catch (_: RuntimeException) { completion.complete(false) }
            Unit
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) action()
        else if (!runCatching { retireDispatch(action) }.getOrDefault(false)) completion.complete(false)
        return completion
    }
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
            "conference=$conference queued=${appliedRevision != controlRevision.get()} first=${record.firstFailure.get()?.name ?: "NONE"}"
        runCatching { diagnosticReceipt(line) } // diagnostics cannot break call state/control
    }
    @Volatile var controlStatus: String = "IDLE_OR_DISPLAY_ONLY"
        private set
    private fun controlsLive(record: Record): Boolean {
        if (record.confirmationDeadline != 0L && android.os.SystemClock.elapsedRealtime() >= record.confirmationDeadline) {
            latch(record, Failure.TIMEOUT)
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper() && !record.timeoutReported) {
                record.timeoutReported = true
                receipt(if (record.pendingReject) "REJECT" else record.pending?.name ?: "OBSERVATION", "TIMEOUT", record)
            }
            controlStatus = "UNCONFIRMED_COMMAND_FAILED"
        }
        return record.failure.get() == null
    }
    private enum class Failure { ANSWER_TRANSITION, TIMEOUT, SENDER_WITHHELD, STATE, DIRECTION, CONFERENCE, MULTIPLE }
    private fun latch(record: Record, cause: Failure) {
        record.firstFailure.compareAndSet(null, cause) // retained provenance, never caller data
        if (cause == Failure.ANSWER_TRANSITION) record.failure.compareAndSet(null, cause)
        else record.failure.set(cause) // a later hard failure must defeat transition recovery
    }
    @Volatile var capacityDrops: Int = 0
        private set
    @Volatile var queueDrops: Int = 0
        private set
    private fun identityReceipt(boundary: String, record: Record?, remote: String, name: String,
                                result: String = "OBSERVED", connection: Connection? = record?.connection) {
        if (receiptCount >= 64) return
        receiptCount++
        val native = when (connection?.state) {
            null -> "NONE"
            Connection.STATE_NEW -> "NEW"
            Connection.STATE_RINGING -> "RINGING"
            Connection.STATE_ACTIVE -> "ACTIVE"
            Connection.STATE_HOLDING -> "HELD"
            Connection.STATE_DISCONNECTED -> "ENDED"
            else -> "OTHER"
        }
        val line = "media stage=native-identity boundary=$boundary seq=$receiptCount call=${record?.ordinal ?: 0} " +
            "status=${record?.status ?: -1} native=$native remote=$remote name=$name result=$result"
        runCatching { diagnosticReceipt(line) }
    }
    private fun safeNumber(number: String) = number.length in 1..128 &&
        number.any { it in '0'..'9' } && TELEPHONE_PATTERN.matches(number)
    private fun safeName(name: String) = name.length in 1..256 && name.isNotBlank() &&
        name.none { it.isISOControl() || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' }
    private fun presence(value: Presence<String>, valid: (String) -> Boolean): String = when (value) {
        Presence.Absent -> "ABSENT"
        is Presence.Present -> if (value.value.isEmpty()) "EMPTY" else if (valid(value.value)) "VALID" else "INVALID"
    }
    private class Record(val ordinal: Int) {
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
        val failure = java.util.concurrent.atomic.AtomicReference<Failure?>()
        val firstFailure = java.util.concurrent.atomic.AtomicReference<Failure?>()
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
            catch (_: IllegalArgumentException) {
                // No partial private parse survives rejection. UNKNOWN is not wire-absent.
                if (frame.messageId == 0x4155) dispatch {
                    if (live()) identityReceipt("DECODE", null, "UNKNOWN", "UNKNOWN", "REJECTED")
                } // diagnostic queue loss must not change existing malformed-frame/control policy
                return true
            }
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
            Record(records.size + 1).also { records[key] = it }
        }
        if (record.terminal) return
        (value.status as? Presence.Present)?.let { record.status = it.value.raw }
        identityReceipt("DECODE", record, presence(value.remoteId, ::safeNumber), presence(value.displayName, ::safeName))
        (value.direction as? Presence.Present)?.let { record.direction = it.value }
        (value.isConferenced as? Presence.Present)?.let { record.conference = it.value }
        (value.conferenceGroup as? Presence.Present)?.let { record.conferenceGroup = it.value }
        // No sourced presentation/restriction flag exists in this schema. Disclose only a
        // supplied safe value; empty/invalid revokes LOCAL disclosure, not a wire clear claim.
        (value.remoteId as? Presence.Present)?.let {
            record.address = it.value.takeIf(::safeNumber)
                ?.let { number -> android.net.Uri.fromParts("tel", number, null) }
        }
        (value.displayName as? Presence.Present)?.let {
            record.displayName = it.value.takeIf(::safeName)
        }
        identityReceipt("MERGE", record, if (record.address == null) "EMPTY" else "VALID",
            if (record.displayName == null) "EMPTY" else "VALID")
        controlsLive(record) // a delayed phone fact does not retry a failed operation
        // Status3 stays raw/unclassified (not Held, waiting or an Active acknowledgement).
        // Only an explicit same-record Active fact can resolve this pending Answer uncertainty.
        // No retry/rearm: timeout, sender failure and other ambiguity are never cleared here.
        if (record.pending == com.shilapi.xcertplay.airplay.PhoneControl.ANSWER &&
            (value.status as? Presence.Present)?.value?.raw == 4 && live() &&
            !multiHint.get() && capacityDrops == 0 && record.admitted && !record.terminal &&
            record.direction == 1 && record.conference == false && (record.conferenceGroup ?: 0) == 0 &&
            records.values.count { it.status != 0 } == 1) {
            record.failure.compareAndSet(Failure.ANSWER_TRANSITION, null)
        }
        if ((record.pending == com.shilapi.xcertplay.airplay.PhoneControl.ANSWER && record.status == 4) ||
            (record.pending == com.shilapi.xcertplay.airplay.PhoneControl.END && record.status == 0)) {
            controlStatus = "PHONE_CONFIRMED_${if (record.pendingReject) "REJECT" else record.pending!!.name}"
            receipt(if (record.pendingReject) "REJECT" else record.pending!!.name, "PHONE_CONFIRMED", record)
            record.pending = null; record.pendingReject = false; record.confirmationDeadline = 0
        }
        val ambiguity = when {
            records.values.count { it.status != 0 } > 1 -> Failure.MULTIPLE
            record.status == 0 -> null
            record.direction != 1 -> Failure.DIRECTION
            record.conference == true || (record.conferenceGroup ?: 0) != 0 -> Failure.CONFERENCE
            record.status == 3 && record.pending == com.shilapi.xcertplay.airplay.PhoneControl.ANSWER -> Failure.ANSWER_TRANSITION
            record.status !in setOf(2,4) -> Failure.STATE
            else -> null
        }
        if (ambiguity != null) {
            records.values.filter { it.status != 0 }.forEach { latch(it, ambiguity) }
            controlStatus = "AMBIGUOUS_CALL_WITHHELD"
        }
        if (record.admitted && record.status == 0) { record.terminal = true; record.consumed = true }
        // Optional timestamp is not published; private identity goes only to the exact Connection.
        if (!record.admitted && record.status == 2 && record.direction == 1) {
            record.admitted = true // before OS call, so denied/replayed events cannot ring again
            try {
                check(!localRepairBlocked.get())
                announce(record.token); hasAdmittedCall = true
            } catch (_: RuntimeException) { record.consumed = true }
        }
        if (record.status == 0) hasAdmittedCall = records.values.any { it.admitted && it.status != 0 && (it.connection != null || !it.consumed) }
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
            // This exact provider owns CarPlay IP PCM, not Android SIM/HFP audio. Public
            // Connection audio classification asks Telecom for communication mode; it neither
            // forces AudioManager mode nor proves physical microphone routing/nonzero capture.
            it.setAudioModeIsVoip(true)
            update(record, it, creating = true)
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
            !live() || localRepairBlocked.get() -> "OWNER"
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
        if (!sender!!(control) { controlsLive(record) && !localRepairBlocked.get() && !revoked.get() && current() && controlRevision.get() == revision }) {
            latch(record, Failure.SENDER_WITHHELD); controlStatus = "COMMAND_WITHHELD"
            receipt(action, "SENDER_WITHHELD", record)
        }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ controlsLive(record) }, 5000)
    }
    private fun update(record: Record, connection: Connection, creating: Boolean = false) {
        if (!live()) { retire(); return }
        // Caller facts go only to the local OS call presentation; never to diagnostic receipts.
        connection.setAddress(record.address, if (record.address == null)
            android.telecom.TelecomManager.PRESENTATION_UNKNOWN else android.telecom.TelecomManager.PRESENTATION_ALLOWED)
        connection.setCallerDisplayName(record.displayName, if (record.displayName == null)
            android.telecom.TelecomManager.PRESENTATION_UNKNOWN else android.telecom.TelecomManager.PRESENTATION_ALLOWED)
        // Own in-process Connection getters only, no Telecom/Binder read. Not OEM rendering proof.
        identityReceipt(if (creating) "CREATE" else "UPDATE", record,
            if (connection.address == null) "EMPTY" else "VALID",
            if (connection.callerDisplayName == null) "EMPTY" else "VALID",
            if (connection.address == record.address && connection.callerDisplayName == record.displayName)
                "MATCH" else "MISMATCH", connection)
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
