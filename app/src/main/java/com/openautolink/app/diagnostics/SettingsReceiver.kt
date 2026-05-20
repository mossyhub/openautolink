package com.openautolink.app.diagnostics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.openautolink.app.data.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Debug broadcast receiver for changing app settings via ADB without rebuilding.
 * Saves to DataStore immediately. Pass --ez restart true to force-stop and relaunch.
 *
 * Set a preference:
 *   adb shell am broadcast -a com.openautolink.app.SET_PREF \
 *     --es key aa_dpi --ei value 400 com.openautolink.app
 *
 * Set and auto-restart (uses am command via shell):
 *   adb shell "am broadcast -a com.openautolink.app.SET_PREF \
 *     --es key aa_dpi --ei value 400 com.openautolink.app; \
 *     am force-stop com.openautolink.app; \
 *     am start -n com.openautolink.app/.MainActivity"
 *
 * Supported keys:
 *   aa_dpi              --ei value <int>       160, 200, 321, 400
 *   aa_auto_dpi         --ez bvalue <bool>     true/false
 *   aa_pixel_aspect     --ei value <int>       -1=auto, 0=off, >0=manual e4
 *   aa_resolution       --es svalue <str>      480p, 720p, 1080p, 1440p, 4k
 *   aa_width_margin     --ei value <int>       0+
 *   aa_height_margin    --ei value <int>       0+
 *   aa_auto_margins     --ez bvalue <bool>     true/false
 *   aa_target_layout_dp --ei value <int>       0=off, 960/1280/1920
 *   video_scaling_mode  --es svalue <str>      crop, letterbox
 *   video_auto_negotiate --ez bvalue <bool>    true/false
 *   video_codec         --es svalue <str>      h264, h265
 *   video_fps           --ei value <int>       30, 60
 *   direct_transport    --es svalue <str>      hotspot, usb
 *   drive_side          --es svalue <str>      left, right
 *   manual_ip_enabled   --ez bvalue <bool>     true/false
 *   manual_ip_address   --es svalue <str>      e.g. 10.0.2.2
 *   display_mode        --es svalue <str>      full, split
 *   mic_source          --es svalue <str>      builtin, usb, none
 */
class SettingsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_RECONNECT) {
            handleReconnect(context)
            return
        }
        if (intent.action != ACTION) return

        val key = intent.getStringExtra("key") ?: return
        val prefs = AppPreferences.getInstance(context)

        runBlocking {
            when (key) {
                "aa_dpi" -> {
                    val v = intent.getIntExtra("value", -1)
                    if (v > 0) { prefs.setAaDpi(v); log("aa_dpi=$v") }
                }
                "aa_auto_dpi" -> {
                    if (intent.hasExtra("bvalue")) {
                        val v = intent.getBooleanExtra("bvalue", true)
                        prefs.setAaAutoDpi(v); log("aa_auto_dpi=$v")
                    }
                }
                "aa_pixel_aspect" -> {
                    val v = intent.getIntExtra("value", Int.MIN_VALUE)
                    if (v != Int.MIN_VALUE) { prefs.setAaPixelAspect(v); log("aa_pixel_aspect=$v") }
                }
                "aa_resolution" -> {
                    val v = intent.getStringExtra("svalue") ?: return@runBlocking
                    prefs.setAaResolution(v); log("aa_resolution=$v")
                }
                "aa_width_margin" -> {
                    val v = intent.getIntExtra("value", -1)
                    if (v >= 0) { prefs.setAaWidthMargin(v); log("aa_width_margin=$v") }
                }
                "aa_height_margin" -> {
                    val v = intent.getIntExtra("value", -1)
                    if (v >= 0) { prefs.setAaHeightMargin(v); log("aa_height_margin=$v") }
                }
                "aa_auto_margins" -> {
                    if (intent.hasExtra("bvalue")) {
                        val v = intent.getBooleanExtra("bvalue", true)
                        prefs.setAaAutoMargins(v); log("aa_auto_margins=$v")
                    }
                }
                "aa_target_layout_dp" -> {
                    val v = intent.getIntExtra("value", -1)
                    if (v >= 0) { prefs.setAaTargetLayoutWidthDp(v); log("aa_target_layout_dp=$v") }
                }
                "video_scaling_mode" -> {
                    val v = intent.getStringExtra("svalue") ?: return@runBlocking
                    prefs.setVideoScalingMode(v); log("video_scaling_mode=$v")
                }
                "video_auto_negotiate" -> {
                    val v = intent.getBooleanExtra("bvalue", true)
                    prefs.setVideoAutoNegotiate(v); log("video_auto_negotiate=$v")
                }
                "video_codec" -> {
                    val v = intent.getStringExtra("svalue") ?: return@runBlocking
                    prefs.setVideoCodec(v); log("video_codec=$v")
                }
                "video_fps" -> {
                    val v = intent.getIntExtra("value", -1)
                    if (v > 0) { prefs.setVideoFps(v); log("video_fps=$v") }
                }
                "direct_transport" -> {
                    val v = intent.getStringExtra("svalue") ?: return@runBlocking
                    prefs.setDirectTransport(v); log("direct_transport=$v")
                }
                "drive_side" -> {
                    val v = intent.getStringExtra("svalue") ?: return@runBlocking
                    prefs.setDriveSide(v); log("drive_side=$v")
                }
                "manual_ip_enabled" -> {
                    val v = intent.getBooleanExtra("bvalue", false)
                    prefs.setManualIpEnabled(v); log("manual_ip_enabled=$v")
                }
                "manual_ip_address" -> {
                    val v = intent.getStringExtra("svalue") ?: return@runBlocking
                    prefs.setManualIpAddress(v); log("manual_ip_address=$v")
                }
                "display_mode" -> {
                    val v = intent.getStringExtra("svalue") ?: return@runBlocking
                    prefs.setDisplayMode(v); log("display_mode=$v")
                }
                "mic_source" -> {
                    val v = intent.getStringExtra("svalue") ?: return@runBlocking
                    prefs.setMicSource(v); log("mic_source=$v")
                }
                "bt_mac_override" -> {
                    val v = intent.getStringExtra("svalue") ?: ""
                    prefs.setBtMacOverride(v); log("bt_mac_override=$v")
                }
                else -> log("Unknown key: $key")
            }
        }
    }

    private fun log(msg: String) {
        OalLog.i("SettingsRcv", "SET_PREF: $msg")
    }

    /**
     * Debug-only: trigger a SessionManager.reconnect() with the user's current
     * preferences. Used to test reconnect/abort behavior without hitting the UI.
     *
     *   adb shell am broadcast -a com.openautolink.app.RECONNECT com.openautolink.app
     */
    private fun handleReconnect(context: Context) {
        val sm = com.openautolink.app.session.SessionManager.instanceOrNull()
        if (sm == null) {
            OalLog.w("SettingsRcv", "RECONNECT: SessionManager not initialized")
            return
        }
        val prefs = AppPreferences.getInstance(context)
        // Don't block the broadcast thread — it's the Main thread for unregistered
        // receivers and even reading DataStore via .first() can stall it. Use a
        // pendingResult to keep the receiver alive while a background coroutine runs.
        val pending = goAsync()
        receiverScope.launch {
            try {
                sm.reconnect(
                    codecPreference = prefs.videoCodec.first(),
                    micSourcePreference = prefs.micSource.first(),
                    scalingMode = prefs.videoScalingMode.first(),
                    directTransport = prefs.directTransport.first(),
                    hotspotSsid = prefs.hotspotSsid.first(),
                    hotspotPassword = prefs.hotspotPassword.first(),
                    videoAutoNegotiate = prefs.videoAutoNegotiate.first(),
                    aaResolution = prefs.aaResolution.first(),
                    aaDpi = prefs.aaDpi.first(),
                    aaAutoDpi = prefs.aaAutoDpi.first(),
                    aaWidthMargin = prefs.aaWidthMargin.first(),
                    aaHeightMargin = prefs.aaHeightMargin.first(),
                    aaPixelAspect = prefs.aaPixelAspect.first(),
                    aaTargetLayoutWidthDp = prefs.aaTargetLayoutWidthDp.first(),
                    aaViewingDistanceMm = prefs.aaViewingDistanceMm.first(),
                    aaDecoderAdditionalDepth = prefs.aaDecoderAdditionalDepth.first(),
                    aaAutoMargins = prefs.aaAutoMargins.first(),
                    videoFps = prefs.videoFps.first(),
                    driveSide = prefs.driveSide.first(),
                    manualIpAddress = if (prefs.manualIpEnabled.first())
                        prefs.manualIpAddress.first().takeIf { it.isNotBlank() } else null,
                )
                OalLog.i("SettingsRcv", "RECONNECT: triggered")
            } catch (e: Exception) {
                OalLog.e("SettingsRcv", "RECONNECT failed: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "com.openautolink.app.SET_PREF"
        const val ACTION_RECONNECT = "com.openautolink.app.RECONNECT"
        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
