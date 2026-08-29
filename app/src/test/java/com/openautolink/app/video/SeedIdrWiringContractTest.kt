package com.openautolink.app.video

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SeedIdrWiringContractTest {

    @Test
    fun `per-codec thresholds are persisted and reachable from Video settings`() {
        val preferences = source("app/src/main/java/com/openautolink/app/data/AppPreferences.kt")
        val settingsVm = source("app/src/main/java/com/openautolink/app/ui/settings/SettingsViewModel.kt")
        val settingsUi = source("app/src/main/java/com/openautolink/app/ui/settings/SettingsScreen.kt")

        listOf("VIDEO_SEED_THRESHOLD_H264", "VIDEO_SEED_THRESHOLD_H265", "VIDEO_SEED_THRESHOLD_VP9")
            .forEach { assertTrue(preferences.contains(it)) }
        assertTrue(preferences.contains("val seedIdrThresholds: Flow<SeedIdrThresholds>"))
        assertTrue(preferences.contains("setVideoSeedThresholdH264"))
        assertTrue(preferences.contains("setVideoSeedThresholdH265"))
        assertTrue(preferences.contains("setVideoSeedThresholdVp9"))

        assertTrue(settingsVm.contains("videoSeedThresholdH264"))
        assertTrue(settingsVm.contains("videoSeedThresholdH265"))
        assertTrue(settingsVm.contains("videoSeedThresholdVp9"))
        assertTrue(settingsVm.contains("updateVideoSeedThresholdH264"))
        assertTrue(settingsVm.contains("updateVideoSeedThresholdH265"))
        assertTrue(settingsVm.contains("updateVideoSeedThresholdVp9"))

        assertTrue(settingsUi.contains("Seed keyframe thresholds"))
        assertTrue(settingsUi.contains("seedThreshold_h264"))
        assertTrue(settingsUi.contains("seedThreshold_h265"))
        assertTrue(settingsUi.contains("seedThreshold_vp9"))
    }

    @Test
    fun `saved thresholds reach every decoder creation path`() {
        val projectionVm = source("app/src/main/java/com/openautolink/app/ui/projection/ProjectionViewModel.kt")
        val settingsVm = source("app/src/main/java/com/openautolink/app/ui/settings/SettingsViewModel.kt")
        val settingsReceiver = source("app/src/main/java/com/openautolink/app/diagnostics/SettingsReceiver.kt")
        val manager = source("app/src/main/java/com/openautolink/app/session/SessionManager.kt")
        val decoder = source("app/src/main/java/com/openautolink/app/video/MediaCodecDecoder.kt")

        assertTrue(projectionVm.contains("val seedIdrThresholds = preferences.seedIdrThresholds.first()"))
        assertTrue(projectionVm.contains("seedIdrThresholds = seedIdrThresholds"))
        assertTrue(settingsVm.contains("val seedIdrThresholds = preferences.seedIdrThresholds.first()"))
        assertTrue(settingsVm.contains("seedIdrThresholds = seedIdrThresholds"))
        assertTrue(settingsReceiver.contains("seedIdrThresholds = prefs.seedIdrThresholds.first()"))

        assertTrue(manager.contains("private var lastSeedIdrThresholds = SeedIdrThresholds()"))
        assertTrue(manager.contains("seedIdrThresholds = lastSeedIdrThresholds"))
        assertTrue(manager.contains("seedIdrThresholds = seedIdrThresholds"))
        assertTrue(decoder.contains("private val seedIdrThresholds: SeedIdrThresholds"))
        assertTrue(decoder.contains("val newCodec = SeedIdrPolicy.codecForAaType(aaCodecType) ?: return"))
        assertTrue(decoder.contains("Seed IDR thresholds: h264="))
        assertTrue(decoder.contains("SeedIdrPolicy.thresholdBytes(detectedCodec, seedIdrThresholds)"))
        assertFalse(decoder.contains("MIN_REAL_IDR_BYTES"))
    }

    private fun source(path: String): String = projectFile(path).readText()

    private fun projectFile(path: String): File {
        var dir = File(System.getProperty("user.dir") ?: error("user.dir unavailable"))
        repeat(8) {
            val candidate = File(dir, path)
            if (candidate.isFile) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        error("Cannot locate project file: $path")
    }
}
