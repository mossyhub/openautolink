package com.openautolink.app.carplay

import android.content.Context
import com.openautolink.app.diagnostics.CaptureTerminalLogStore
import com.shilapi.xcertplay.media.CaptureTerminalSummary

/** Freeze one store/notification closure into its receiver, never resolve a mutable latest owner.
 * Receiver invokes this only on its bounded passive status worker after concrete capture release.
 */
internal fun captureTerminalSink(context: Context, notify: (String) -> Unit): (CaptureTerminalSummary) -> Boolean {
    val store = CaptureTerminalLogStore(context.applicationContext)
    return { summary ->
        if (!store.save(summary)) false
        else {
            // Persistence acknowledgment cannot depend on an optional UI/reporter succeeding.
            summary.lines().forEach { line -> runCatching { notify(line) } }
            true
        }
    }
}
