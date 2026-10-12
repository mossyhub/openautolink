package com.openautolink.carplaylab.settings

import org.junit.Assert.*
import org.junit.Test

class BenchSettingsPolicyTest {
    @Test fun newInstallKeepsCarPlayAndMicrophoneOff() {
        val settings = BenchSettingsPolicy.read(emptyMap())
        assertFalse(settings.carPlayEnabled)
        assertFalse(BenchSettingsPolicy.canRequestMicrophone(settings.carPlayEnabled, false))
        assertEquals(BenchDisplayProfile.HD_720, settings.display)
    }

    @Test fun explicitOptInIsReadWithoutChangingAndroidAutoKeys() {
        val source = mapOf<String, Any?>("carplay_enabled" to true, "direct_transport" to "wpp", "resolution" to "1440p")
        assertTrue(BenchSettingsPolicy.read(source).carPlayEnabled)
        assertEquals("wpp", source["direct_transport"])
        assertEquals("1440p", source["resolution"])
    }

    @Test fun fullHdDisplayReadsExactPersistedContract() {
        val settings = BenchSettingsPolicy.read(mapOf("carplay_display_width" to 1920, "carplay_display_height" to 1080, "carplay_display_fps" to 30))
        assertEquals(BenchDisplayProfile.FULL_HD_1080, settings.display)
    }

    @Test fun corruptOrPartialDisplayNeverOffersUnvalidatedProfile() {
        for (values in listOf(
            mapOf("carplay_display_width" to 1920),
            mapOf("carplay_display_width" to 1920, "carplay_display_height" to 1080, "carplay_display_fps" to 60),
            mapOf("carplay_display_width" to "1920", "carplay_display_height" to 1080, "carplay_display_fps" to 30),
            mapOf("carplay_display_width" to 3840, "carplay_display_height" to 2160, "carplay_display_fps" to 30),
        )) assertEquals(BenchDisplayProfile.HD_720, BenchSettingsPolicy.read(values).display)
    }

    @Test fun explicitSaveRoundTripsSetupAndDisplayWithoutSecretsOrAaKeys() {
        val settings = BenchSettings(carPlayEnabled = true, display = BenchDisplayProfile.FULL_HD_1080,
            selectedPhoneAddress = "AA:BB:CC:DD:EE:01", peerIp = "192.0.2.12", ssid = "Test Wi-Fi", receiverAddress = "AA:BB:CC:DD:EE:02")
        val payload = BenchSettingsPolicy.write(settings)
        assertEquals(settings, BenchSettingsPolicy.read(payload))
        assertEquals(setOf("carplay_enabled", "carplay_display_width", "carplay_display_height", "carplay_display_fps",
            "carplay_selected_phone", "carplay_peer_ip", "carplay_ssid", "receiver_bt_address"), payload.keys)
        assertFalse(payload.keys.any { it.contains("password") || it.contains("microphone") })
    }

    @Test fun startsAndOptionalConsentRequireOptInAndNoRetiringAttempt() {
        for (enabled in listOf(false, true)) for (busy in listOf(false, true)) {
            assertEquals(enabled && !busy, BenchSettingsPolicy.canStart(enabled, busy))
            assertEquals(enabled && !busy, BenchSettingsPolicy.canRequestMicrophone(enabled, busy))
        }
    }

    @Test fun refreshRestoresExactSelectedIdentityRatherThanFirstIphoneName() {
        assertEquals(1, BenchSettingsPolicy.selectedPhoneIndex("B", listOf("A", "B")))
        assertEquals(-1, BenchSettingsPolicy.selectedPhoneIndex("missing", listOf("A", "B")))
        assertEquals(-1, BenchSettingsPolicy.selectedPhoneIndex("", emptyList()))
    }

    @Test fun malformedSetupValuesDoNotInventPhoneOrAddress() {
        val settings = BenchSettingsPolicy.read(mapOf("carplay_selected_phone" to 1, "carplay_peer_ip" to true, "carplay_ssid" to 2))
        assertEquals("", settings.selectedPhoneAddress)
        assertEquals("", settings.peerIp)
        assertEquals("", settings.ssid)
    }

    @Test fun settingsEscapeCannotBeDisabledByFeatureOrSessionState() {
        for (enabled in listOf(false, true)) for (active in listOf(false, true)) {
            assertTrue(BenchSettingsPolicy.refreshKeepsSettingsEscape(enabled, active))
        }
    }

    @Test fun malformedOptInFailsClosed() {
        for (value in listOf<Any?>(null, "true", 1, false)) {
            assertFalse(BenchSettingsPolicy.read(mapOf("carplay_enabled" to value)).carPlayEnabled)
        }
    }
}
