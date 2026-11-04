package com.openautolink.app.ui.settings

import com.openautolink.app.diagnostics.DiagnosticLog
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext

/** One bounded receipt stream per explicit Save; contains stages/geometry, never settings or errors. */
internal class SettingsSaveTransaction(val click: Long) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SettingsSaveTransaction> {
        private val sequence = AtomicLong()
        fun next() = SettingsSaveTransaction(sequence.incrementAndGet())
    }
    fun event(stage: String, result: String, detail: String = "") {
        // Observer failure must not affect the settings transaction.
        try { DiagnosticLog.i("cluster", "SETTINGS_SAVE_V1 click=$click stage=$stage result=$result $detail") }
        catch (_: Exception) { }
    }
}

internal suspend fun settingsSaveEvent(stage: String, result: String, detail: String = "") {
    currentCoroutineContext()[SettingsSaveTransaction]?.event(stage, result, detail)
}
