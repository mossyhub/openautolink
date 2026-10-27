package com.openautolink.app.carplay

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Wiring contracts complement lifecycle/real receiver tests; not platform admission evidence. */
class CarPlayCaptureWiringTest {
    private fun source(path: String) = File("src/main/$path").readText()
    @Test fun bothTransportsCarryAttemptAdmissionAndMainReconcilesOnlyRealLifecycle() {
        for (name in listOf("OalCarPlayRuntime","OalUsbCarPlayRuntime")) {
            val text=source("java/com/openautolink/app/carplay/$name.kt")
            assertTrue("$name must forward the exact attempt capture capability",text.contains("captureAdmission ="))
            assertTrue(text.contains("attemptContext?.captureAdmission"))
        }
        val activity=source("java/com/openautolink/app/MainActivity.kt")
        assertTrue(activity.contains("CarPlayForegroundVisibility.resumed(this)"))
        assertTrue(activity.contains("CarPlayForegroundVisibility.paused(this)"))
        assertTrue(activity.contains("CarPlaySessionLeases.reconcile(this)"))
    }
    @Test fun notificationUsesExistingTokenAndRefreshNeverRepromotes() {
        val service=source("java/com/openautolink/app/carplay/CarPlaySessionService.kt")
        assertTrue(service.contains("instanceOrNull()?.getSessionToken()"))
        assertTrue(service.contains("Notification.MediaStyle()"))
        assertTrue(service.contains("Notification.CATEGORY_TRANSPORT"))
        val refresh=service.substringAfter("internal fun refreshNotification()")
        assertTrue(refresh.contains(".notify(NOTIFICATION,"))
        assertFalse(refresh.substringBefore("override fun onDestroy").contains("startForeground("))
    }
    @Test fun manifestDeclaresOnlyLegitimateAddedForegroundPurposes() {
        val manifest=source("AndroidManifest.xml")
        assertTrue(manifest.contains("android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK"))
        assertTrue(manifest.contains("android.permission.FOREGROUND_SERVICE_MICROPHONE"))
        assertTrue(manifest.contains("connectedDevice|mediaPlayback|microphone"))
    }
}
