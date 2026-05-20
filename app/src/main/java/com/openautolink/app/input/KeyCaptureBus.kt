package com.openautolink.app.input

import android.view.KeyEvent
import com.openautolink.app.diagnostics.OalLog

/**
 * Global hook so the Settings "Assign key" dialog can capture hardware key
 * presses from the Activity's `dispatchKeyEvent`. Steering wheel / remote
 * buttons don't route through Compose focus chains (especially inside an
 * AlertDialog window), so we intercept at the Activity level.
 *
 * When `listener` is non-null, MainActivity consumes the next ACTION_UP
 * KeyEvent (after observing the matching ACTION_DOWN) and reports its
 * keycode here, suppressing forwarding to the projection / bridge.
 */
object KeyCaptureBus {
    private const val TAG = "KeyCaptureBus"

    @Volatile
    private var _listener: ((Int) -> Unit)? = null

    var listener: ((Int) -> Unit)?
        get() = _listener
        set(value) {
            val prev = _listener
            _listener = value
            OalLog.i(
                TAG,
                "listener ${if (value != null) "INSTALLED" else "CLEARED"} " +
                    "(was=${if (prev != null) "present" else "null"})",
            )
        }

    /** Returns true if the event was consumed by capture. */
    fun handle(event: KeyEvent): Boolean {
        val cb = _listener
        if (cb == null) {
            // Don't log every keystroke — only useful when we expect capture
            // to be active. Caller paths that route here always log the event
            // separately at the input/dispatchKeyEvent layer.
            return false
        }
        OalLog.i(
            TAG,
            "handle: keycode=${event.keyCode} (${KeyEvent.keyCodeToString(event.keyCode)}) " +
                "action=${event.action} repeat=${event.repeatCount} listenerPresent=true",
        )
        if (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                OalLog.i(TAG, "handle: invoking listener with keycode=${event.keyCode}")
                cb(event.keyCode)
            }
            return true
        }
        return false
    }
}
