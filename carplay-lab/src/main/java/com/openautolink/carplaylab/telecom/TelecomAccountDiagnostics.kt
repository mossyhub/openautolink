package com.openautolink.carplaylab.telecom

import android.content.Context
import android.util.AtomicFile
import java.io.File

/** Finite numeric producer boundary. Last outcome per stage plus last Inspect, never a call log.
 * Own-account worker only; independent of optional file logging. Manual ZIP only.
 */
object TelecomAccountDiagnostics {
    enum class Stage { MANAGER_LOOKUP, REGISTER, FIRST_OWN_READ, REFRESH_OWN_READ, CENSUS_OWN_READ, CLEANUP_OWN_READ, SETTINGS_LOOKUP, SETTINGS_LAUNCH }
    enum class Outcome {
        RETURNED, NO_MANAGER, READ_PERMISSION_REQUIRED, NULL_ACCOUNT, ACCOUNT_DISABLED, ACCOUNT_ENABLED,
        SECURITY_EXCEPTION, UNSUPPORTED_OPERATION, OTHER_RUNTIME, SUPERSEDED,
        EXACT_CANDIDATE, PUBLIC_ACTION_CANDIDATE, UNAVAILABLE,
        EXACT_CANDIDATE_LAUNCHED, PUBLIC_ACTION_CANDIDATE_LAUNCHED, ACTIVITY_NOT_FOUND
    }
    const val FILE_NAME = "telecom_account_access.log"
    const val MAX_BYTES = 4096
    private val lock = Any()
    private var directoryPath: String? = null
    private val latest = linkedMapOf<Stage, String>()
    private var inspection: String? = null
    @Volatile var persisted: Boolean = false
        private set
    fun sanitize(stage: Int, outcome: Int, generation: Long): String? {
        val s = Stage.entries.getOrNull(stage) ?: return null
        val o = Outcome.entries.getOrNull(outcome) ?: return null
        if (generation !in 0..999999999L) return null
        return "telecom_account_v1 stage=${s.name} outcome=${o.name} stageCode=$stage outcomeCode=$outcome generation=$generation"
    }
    fun sanitizeInspection(values: IntArray, generation: Long): String? {
        if (values.size != 9 || values[0] !in 32..1000 || generation !in 0..999999999L) return null
        if ((1..3).any { values[it] !in -1..1 } || values[4] !in 0..1 || values[5] !in -1..1 || values[6] !in 0..1) return null
        if (Outcome.entries.getOrNull(values[7]) == null || TelecomAccountSettings.Availability.entries.getOrNull(values[8]) == null) return null
        return "telecom_inspect_v1 sdk=${values[0]} connectionService=${values[1]} telecomFeature=${values[2]} automotive=${values[3]} telecomPresent=${values[4]} voiceCapable=${values[5]} readGrant=${values[6]} ownOutcomeCode=${values[7]} settingsCode=${values[8]} generation=$generation"
    }
    fun record(context: Context, stage: Stage, outcome: Outcome, generation: Long): Boolean {
        val line = checkNotNull(sanitize(stage.ordinal, outcome.ordinal, generation.coerceIn(0, 999999999)))
        return write(context) { (latest.put(stage, line) != line) }
    }
    fun recordInspection(context: Context, values: IntArray, generation: Long): Boolean {
        val line = sanitizeInspection(values, generation.coerceIn(0, 999999999)) ?: return false
        return write(context) { (inspection != line).also { inspection = line } }
    }
    private fun write(context: Context, update: () -> Boolean): Boolean = synchronized(lock) {
        try {
            val base = context.getExternalFilesDir(null) ?: return@synchronized false.also { persisted = it }
            val directory = File(base, "openautolink/logs")
            if (directoryPath != directory.absolutePath) {
                latest.clear(); inspection = null; directoryPath = directory.absolutePath; persisted = false
            }
            val changed = update()
            if (!changed && persisted && File(directory, FILE_NAME).isFile) return@synchronized true
            val bytes = (latest.values + listOfNotNull(inspection)).joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8)
            check(bytes.size <= MAX_BYTES)
            check(directory.isDirectory || directory.mkdirs())
            val file = AtomicFile(File(directory, FILE_NAME))
            val stream = file.startWrite()
            try { stream.write(bytes); stream.fd.sync(); file.finishWrite(stream) }
            catch (failure: Exception) { file.failWrite(stream); throw failure }
            persisted = true
        } catch (_: Exception) { persisted = false }
        persisted
    }
    fun failure(error: RuntimeException): Outcome = when (error) {
        is SecurityException -> Outcome.SECURITY_EXCEPTION
        is UnsupportedOperationException -> Outcome.UNSUPPORTED_OPERATION
        else -> Outcome.OTHER_RUNTIME
    }
}
