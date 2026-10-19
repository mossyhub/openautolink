package com.openautolink.app.ui.diagnostics

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import com.openautolink.app.ui.testing.createOalComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [32], qualifiers = "w800dp-h600dp-mdpi")
class MicrophoneDiagnosticConsoleTest {
    @get:Rule val compose = createOalComposeRule()

    @Test fun liveArmRechecksActualParkOnNonautomotiveTabletWithoutLocalConsent() {
        val type = Class.forName("com.shilapi.xcertplay.media.CarPlayMicrophoneExperiments")
        val registry = type.getField("INSTANCE").get(null)
        type.getMethod("disarm").invoke(registry)
        fun armed(): Boolean = type.getMethod("status").invoke(registry).let {
            it.javaClass.getMethod("getArmed").invoke(it) as Boolean
        }
        var park = true
        val controller = MicrophoneDiagnosticController(FakeMicrophoneBackend(), automotive = false,
            eligibility = { true }, freshPark = { park })
        try {
            compose.setContent { MaterialTheme { MicrophoneDiagnosticPanel(controller) } }
            compose.onNodeWithTag("mic-live-consent").performScrollTo().performClick()
            compose.onNodeWithTag("mic-live-arm").performScrollTo().assertIsEnabled()
            compose.runOnIdle { park = false }
            compose.onNodeWithTag("mic-live-arm").performClick()
            compose.runOnIdle { assertFalse(armed()); park = true; controller.refresh() }
            compose.onNodeWithTag("mic-live-arm").performClick()
            compose.runOnIdle { assertTrue(armed()); assertFalse(controller.consent); assertFalse(controller.stationary) }
            compose.onNodeWithTag("mic-live-reset").performScrollTo().performClick()
            compose.runOnIdle { assertFalse(armed()) }
            compose.onNodeWithTag("mic-live-source-AUTO").assertIsSelected()
            compose.onNodeWithTag("mic-live-rate-NEGOTIATED").assertIsSelected()
            compose.onNodeWithTag("mic-live-codec-AUTO").assertIsSelected()
        } finally { type.getMethod("disarm").invoke(registry) }
    }

    @Test fun liveExpiryDisplayKeepsCompletedLocalSummary() {
        val registryType = Class.forName("com.shilapi.xcertplay.media.CarPlayMicrophoneExperiments")
        val registryObject = registryType.getField("INSTANCE").get(null)
        registryType.getMethod("disarm").invoke(registryObject)
        val result = MicrophoneDiagnosticResult(MicrophoneDiagnosticComparison.RATE,
            MicrophoneDiagnosticInventory(MicrophoneDiagnosticMatch.UNIQUE, emptyList()), emptyList(), MicrophoneDiagnosticReason.NONE)
        val backend = FakeMicrophoneBackend().apply {
            state = MicrophoneDiagnosticState(phase = MicrophoneDiagnosticPhase.COMPLETE, result = result)
        }
        val controller = MicrophoneDiagnosticController(backend, automotive = true, eligibility = { true }, freshPark = { true })
        try {
            compose.setContent { MaterialTheme { MicrophoneDiagnosticPanel(controller) } }
            compose.onNodeWithTag("mic-live-consent").performScrollTo().performClick()
            compose.onNodeWithTag("mic-live-selection-FIRST").assertIsNotEnabled()
            compose.onNodeWithTag("mic-live-channels-TWO").performScrollTo().performClick()
            compose.onNodeWithTag("mic-live-selection-FIRST").performScrollTo().performClick()
            compose.onNodeWithTag("mic-live-channels-MONO").performScrollTo().performClick()
            compose.onNodeWithTag("mic-live-selection-AVERAGE").assertIsSelected()
            compose.onNodeWithTag("mic-live-selection-SECOND").assertIsNotEnabled()
            compose.onNodeWithTag("mic-live-arm").performScrollTo().performClick()
            compose.onNodeWithTag("mic-live-status").assertTextContains("Armed", substring = true)
            compose.runOnIdle {
                // Force only this registry's monotonic expiry boundary; no wall clock sleep.
                val registry = registryType.getDeclaredField("registry").apply { isAccessible = true }.get(registryObject)
                registry.javaClass.getDeclaredField("armedAt").apply { isAccessible = true }
                    .setLong(registry, System.nanoTime() / 1_000_000 - 900_001)
                controller.refresh()
                assertSame(result, controller.state.result)
            }
            compose.onNodeWithTag("mic-live-status").assertTextContains("Expired", substring = true)
            compose.onNodeWithTag("mic-inventory").performScrollTo().assertTextContains("UNIQUE", substring = true)
        } finally { registryType.getMethod("disarm").invoke(registryObject) }
    }

    @Test fun liveArmRequiresSeparateConsentForegroundParkAndEligibilityAndSurvivesPageExit() {
        val registry = runCatching { Class.forName("com.shilapi.xcertplay.media.CarPlayMicrophoneExperiments") }.getOrNull()
        assertNotNull("live registry API must exist", registry)
        val instance = registry!!.getField("INSTANCE").get(null)
        registry.getMethod("disarm").invoke(instance)
        fun armed(): Boolean {
            val status = registry.getMethod("status").invoke(instance)
            return status.javaClass.getMethod("getArmed").invoke(status) as Boolean
        }
        var park = false
        var eligible = false
        val controller = MicrophoneDiagnosticController(FakeMicrophoneBackend(), automotive = true,
            eligibility = { eligible }, freshPark = { park })
        try {
            compose.setContent { MaterialTheme { MicrophoneDiagnosticPanel(controller) } }
            compose.onNodeWithTag("mic-live-arm").assertExists().assertIsNotEnabled()
            compose.onNodeWithTag("mic-live-consent").performScrollTo().performClick()
            compose.onNodeWithTag("mic-live-arm").assertIsNotEnabled()
            compose.runOnIdle { park = true; controller.refresh() }
            compose.onNodeWithTag("mic-live-arm").assertIsNotEnabled()
            compose.runOnIdle { eligible = true; controller.refresh() }
            for (tag in listOf("mic-live-source-MIC", "mic-live-rate-HZ_48000", "mic-live-channels-TWO",
                "mic-live-selection-SECOND", "mic-live-codec-OPUS")) {
                compose.onNodeWithTag(tag).performScrollTo().performClick()
            }
            compose.onNodeWithTag("mic-live-arm").performScrollTo().assertIsEnabled().performClick()
            compose.runOnIdle {
                val profile = registry.getMethod("snapshot").invoke(instance)
                assertEquals("MIC", profile.javaClass.getMethod("getSource").invoke(profile).toString())
                assertEquals("HZ_48000", profile.javaClass.getMethod("getRate").invoke(profile).toString())
                assertEquals("TWO", profile.javaClass.getMethod("getChannels").invoke(profile).toString())
                assertEquals("SECOND", profile.javaClass.getMethod("getSelection").invoke(profile).toString())
                assertEquals("OPUS", profile.javaClass.getMethod("getCodec").invoke(profile).toString())
                assertTrue(armed())
                controller.updateForeground(false)
                assertTrue("page exit intentionally retains next-session profile", armed())
                controller.updateForeground(true)
            }
            compose.onNodeWithTag("mic-live-consent").assertIsOff()
            compose.onNodeWithTag("mic-live-disarm").performScrollTo().performClick()
            compose.runOnIdle { assertFalse(armed()) }
            compose.onNodeWithTag("mic-live-status").assertTextContains("OFF", substring = true)
            compose.onNodeWithTag("mic-live-codec-note").assertExists()
        } finally { registry.getMethod("disarm").invoke(instance) }
    }

    @Test fun customSelectionsReachBackendUnchangedAndBusyBlocksEdits() {
        var state = MicrophoneDiagnosticState()
        val configs = mutableListOf<Any>()
        val backend = Proxy.newProxyInstance(MicrophoneDiagnosticBackend::class.java.classLoader,
            arrayOf(MicrophoneDiagnosticBackend::class.java)) { _, method, args ->
            when (method.name) {
                "getState" -> state
                "startCustom" -> { configs.add(args!![0]); state = state.copy(phase = MicrophoneDiagnosticPhase.RUNNING); true }
                "inspect", "start" -> false
                "cancel" -> null
                else -> null
            }
        } as MicrophoneDiagnosticBackend
        val controller = MicrophoneDiagnosticController(backend, automotive = true, eligibility = { true }, freshPark = { true })
        compose.setContent { MaterialTheme { MicrophoneDiagnosticPanel(controller) } }
        compose.onNodeWithTag("mic-custom-run").assertExists().assertIsNotEnabled()
        compose.onNodeWithTag("mic-consent").performScrollTo().performClick()
        for (tag in listOf("mic-custom-source-1", "mic-custom-rate-16000", "mic-custom-channels-2", "mic-custom-input-named")) {
            compose.onNodeWithTag(tag).performScrollTo().performClick()
        }
        compose.onNodeWithTag("mic-custom-run").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, configs.size)
            val config = configs.single()
            assertEquals(1, config.javaClass.getMethod("getSource").invoke(config))
            assertEquals(16000, config.javaClass.getMethod("getSampleRate").invoke(config))
            assertEquals(2, config.javaClass.getMethod("getChannelCount").invoke(config))
            assertEquals(true, config.javaClass.getMethod("getPreferredNamedInput").invoke(config))
        }
        for (tag in listOf("mic-custom-source-6", "mic-custom-rate-48000", "mic-custom-channels-1", "mic-custom-input-default", "mic-custom-run")) {
            compose.onNodeWithTag(tag).assertIsNotEnabled()
        }
        for (tag in listOf("mic-live-source-MIC", "mic-live-rate-HZ_48000", "mic-live-channels-TWO",
            "mic-live-selection-SECOND", "mic-live-codec-OPUS", "mic-live-arm", "mic-live-reset")) {
            compose.onNodeWithTag(tag).assertIsNotEnabled()
        }
        compose.onNodeWithTag("mic-start-SOURCES").assertIsNotEnabled()
        compose.onNodeWithTag("mic-cancel").performScrollTo().assertIsEnabled()
    }
}
