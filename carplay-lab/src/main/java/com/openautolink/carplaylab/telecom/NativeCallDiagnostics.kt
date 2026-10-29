package com.openautolink.carplaylab.telecom

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.util.concurrent.*

/** Bounded terminal receipts independent of optional logging/UI/session lifetime; manual ZIP only.
 * No private strings accepted. Disk work never runs on Telecom main or the socket writer.
 */
object NativeCallDiagnostics {
    const val FILE_NAME = "native_call_controls.log"
    const val MAX_BYTES = 16384
    private val reasons = "OBSERVED|UNSUPPORTED|DISPLAY_ONLY|FAILED|OWNER|MULTI_HINT|OBSERVATIONS_QUEUED|CAPACITY|PENDING|TERMINAL_OR_UNADMITTED|DIRECTION|CONFERENCE|MULTIPLE|STATE|QUEUED|SENDER_WITHHELD|PHONE_CONFIRMED|TIMEOUT"
    // Optional suffix retains already-persisted receipts from before first-latch provenance.
    private val bridge = Regex("media stage=native-call action=(ANSWER|END|REJECT|OBSERVATION) reason=($reasons) status=(-1|[0-9]{1,3}) pending=(NONE|ANSWER|END) calls=([0-9]{1,2}) conference=(-1|0|1) queued=(true|false)( first=(NONE|ANSWER_TRANSITION|TIMEOUT|SENDER_WITHHELD|STATE|DIRECTION|CONFERENCE|MULTIPLE))?")
    private val outcomes = com.shilapi.xcertplay.airplay.PhoneControlOutcome.entries.joinToString("|") { it.name } + "|FAILED"
    private val transport = Regex("media stage=phone-control command=(ANSWER|END|REJECT) outcome=($outcomes)")
    private val ringtone = Regex("media stage=native-ring policy=(NORMAL|EXTERNAL_COMPAT|IN_BAND_COMPAT) sdk=([0-9]{2,4})")
    private val identity = Regex("media stage=native-identity boundary=(DECODE|MERGE|CREATE|UPDATE) seq=([0-9]{1,2}) call=([0-9]{1,2}) status=(-1|[0-9]{1,3}) native=(NONE|NEW|RINGING|ACTIVE|HELD|ENDED|OTHER) remote=(ABSENT|EMPTY|VALID|INVALID|UNKNOWN) name=(ABSENT|EMPTY|VALID|INVALID|UNKNOWN) result=(OBSERVED|REJECTED|MATCH|MISMATCH)")
    private val policy = Regex("media stage=native-policy actor=(HFP|ADMISSION|REPAIR|AUTHORITY|PROJECTION) state=(UNKNOWN|KNOWN_DISCONNECTED|HFP_OWNED|ENABLED|DISABLED|ABSENT|CLOSED) admitted=(true|false) action=(OBSERVED|PRESERVED|BLOCKED|RETIRED|REQUESTED|FAILED) cause=(PROFILE_CENSUS|NEW_CALL|MANUAL|CONTROLLER_CLOSE|ACCOUNT|OWNER_CLOSED|CLEANUP)")
    fun sanitize(line: String): String? {
        if (policy.matches(line)) return line
        if (line.length > 256) return null
        val facts = identity.matchEntire(line)
        if (facts != null) return line.takeIf {
            facts.groupValues[2].toInt() in 1..64 && facts.groupValues[3].toInt() in 0..16 &&
                facts.groupValues[4].toInt() in -1..255
        }
        val match = bridge.matchEntire(line)
        if (match != null) {
            if (match.groupValues[3].toInt() !in -1..255 || match.groupValues[5].toInt() !in 0..16) return null
            return line
        }
        val ring = ringtone.matchEntire(line)
        return line.takeIf { transport.matches(it) || ring != null && ring.groupValues[2].toInt() in 32..1000 }
    }
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(64),
        { task -> Thread(task, "native-call-receipts").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    @Volatile var persisted = false
        private set
    fun record(context: Context, raw: String): Boolean {
        val line = sanitize(raw) ?: return false
        val app = context.applicationContext
        return try {
            worker.execute {
                try {
                    val base = app.getExternalFilesDir(null) ?: return@execute
                    val directory = File(base, "openautolink/logs")
                    check(directory.isDirectory || directory.mkdirs())
                    val target = File(directory, FILE_NAME)
                    val previous = if (target.isFile && target.length() <= MAX_BYTES) target.readLines().mapNotNull(::sanitize).takeLast(63) else emptyList()
                    val bytes = (previous + line).joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8)
                    check(bytes.size <= MAX_BYTES)
                    val atomic = AtomicFile(target); val stream = atomic.startWrite()
                    try { stream.write(bytes); atomic.finishWrite(stream); persisted = true }
                    catch (error: Exception) { atomic.failWrite(stream); throw error }
                } catch (_: Exception) { persisted = false }
            }
            true
        } catch (_: RejectedExecutionException) { persisted = false; false }
    }
    fun flush(timeoutMs: Long = 2000): Boolean = try {
        worker.submit {}.get(timeoutMs, TimeUnit.MILLISECONDS); true
    } catch (_: Exception) { false }
}
