package com.openautolink.app.input

import android.media.AudioManager
import android.util.Log
import android.view.KeyEvent
import com.openautolink.app.diagnostics.DiagnosticLog
import com.openautolink.app.transport.ControlMessage

/**
 * Intercepts AAOS KeyEvents from steering wheel controls and routes them:
 * - Media buttons (play/pause, next, previous) → bridge → phone AA
 * - Voice button → bridge as KEYCODE_SEARCH (AA voice trigger)
 * - Volume buttons → local AudioManager
 *
 * Android keycodes match AA protobuf KeyCode values, so media keys forward directly.
 * Voice button (KEYCODE_VOICE_ASSIST=231) maps to AA's KEYCODE_SEARCH=84.
 *
 * GM AAOS quirk: steering wheel track buttons send F-keys instead of standard
 * KEYCODE_MEDIA_NEXT/PREVIOUS: F7 (137) → Next; Equinox F8 (138) → Previous.
 * Per-key custom remaps override these defaults.
 */
class SteeringWheelController(
    private val sendMessage: (ControlMessage.Button) -> Unit,
    private val audioManager: AudioManager? = null
) {

    companion object {
        private const val TAG = "SteeringWheelCtrl"

        // AA protobuf keycode for voice/search trigger
        private const val AA_KEYCODE_SEARCH = 84

        // Keycodes we forward to the bridge (media + voice)
        private val MEDIA_KEYCODES = setOf(
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,  // 85
            KeyEvent.KEYCODE_MEDIA_STOP,         // 86
            KeyEvent.KEYCODE_MEDIA_NEXT,         // 87
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,     // 88
            KeyEvent.KEYCODE_MEDIA_REWIND,       // 89
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, // 90
            KeyEvent.KEYCODE_MEDIA_PLAY,         // 126
            KeyEvent.KEYCODE_MEDIA_PAUSE,        // 127
        )

        // GM AAOS steering wheel F-key mappings (built-in defaults).
        // Used when no custom key remap is configured for a given key.
        private val GM_FKEY_TO_AA = mapOf(
            KeyEvent.KEYCODE_F6 to KeyEvent.KEYCODE_MEDIA_PREVIOUS,     // 136 → track prev (suspected)
            KeyEvent.KEYCODE_F7 to KeyEvent.KEYCODE_MEDIA_NEXT,         // 137 → track next (confirmed)
            KeyEvent.KEYCODE_F8 to KeyEvent.KEYCODE_MEDIA_PREVIOUS,     // 138 → track prev (Equinox owner-confirmed)
            KeyEvent.KEYCODE_F9 to KeyEvent.KEYCODE_MEDIA_PREVIOUS,     // 139 → alt mapping (suspected)
        )

        private val VOICE_KEYCODES = setOf(
            KeyEvent.KEYCODE_VOICE_ASSIST,  // 231 → maps to AA KEYCODE_SEARCH
            KeyEvent.KEYCODE_SEARCH,        // 84  → already AA KEYCODE_SEARCH
        )

        private val VOLUME_KEYCODES = setOf(
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
        )
    }

    /** User-configurable key remapping: Android keycode → AA keycode.
     *  Takes priority over built-in GM mappings. Set from preferences. */
    @Volatile var customKeyMap: Map<Int, Int> = emptyMap()

    /**
     * Handle a KeyEvent from the activity's dispatchKeyEvent.
     * Returns true if the event was consumed (caller should not propagate further).
     */
    fun onKeyEvent(event: KeyEvent): Boolean {
        val keycode = event.keyCode
        val isDown = event.action == KeyEvent.ACTION_DOWN

        // 1. Check user-configured custom key map first (highest priority)
        val customMapped = customKeyMap[keycode]
        if (customMapped != null) {
            DiagnosticLog.i("input", "Custom remap: ${KeyEvent.keyCodeToString(keycode)} ($keycode) → AA $customMapped")
            sendButtonToAA(customMapped, isDown, event)
            return true
        }

        return when {
            keycode in MEDIA_KEYCODES -> {
                // Media keys: forward directly (Android keycodes == AA keycodes)
                sendButtonToAA(keycode, isDown, event)
                true
            }
            keycode in GM_FKEY_TO_AA -> {
                // GM F-key steering wheel buttons → map to standard AA media keycodes
                val aaKeycode = GM_FKEY_TO_AA[keycode]!!
                DiagnosticLog.i("input", "GM F-key mapped: keycode=$keycode → AA keycode=$aaKeycode (${KeyEvent.keyCodeToString(aaKeycode)})")
                sendButtonToAA(aaKeycode, isDown, event)
                true
            }
            keycode in VOICE_KEYCODES -> {
                // Voice: map to AA KEYCODE_SEARCH to trigger voice assistant on phone
                sendButtonToAA(AA_KEYCODE_SEARCH, isDown, event)
                true
            }
            keycode in VOLUME_KEYCODES -> {
                handleVolume(keycode, isDown)
                true
            }
            else -> {
                DiagnosticLog.d("input", "KeyEvent not handled: keycode=$keycode")
                false
            }
        }
    }

    private fun sendButtonToAA(aaKeycode: Int, down: Boolean, event: KeyEvent) {
        val longpress = event.repeatCount > 0 && down
        sendMessage(
            ControlMessage.Button(
                keycode = aaKeycode,
                down = down,
                metastate = 0,
                longpress = longpress
            )
        )
        Log.d(TAG, "button → bridge: keycode=$aaKeycode down=$down longpress=$longpress")
        DiagnosticLog.d("input", "KeyEvent intercepted: keycode=$aaKeycode down=$down longpress=$longpress")
    }

    private fun handleVolume(keycode: Int, isDown: Boolean) {
        if (!isDown) return // Only act on key down
        val manager = audioManager ?: return

        when (keycode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                manager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_RAISE,
                    AudioManager.FLAG_SHOW_UI
                )
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                manager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_LOWER,
                    AudioManager.FLAG_SHOW_UI
                )
            }
            KeyEvent.KEYCODE_VOLUME_MUTE -> {
                manager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_TOGGLE_MUTE,
                    AudioManager.FLAG_SHOW_UI
                )
            }
        }
        Log.d(TAG, "volume handled locally: keycode=$keycode")
    }
}
