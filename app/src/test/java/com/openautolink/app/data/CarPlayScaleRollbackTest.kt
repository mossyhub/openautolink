package com.openautolink.app.data

import android.app.Application
import com.openautolink.carplaylab.media.CarPlayAutoScalePolicy
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.media.VideoDecoderProbe
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Product-policy rollback, not a claim about an iPhone protocol limit or visible output. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [32, 34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CarPlayScaleRollbackTest {
    private val aliasedMetrics = CarPlayAutoScalePolicy.Input(2914, 1134, 2914, 1134,
        200.04200744628906, 200.02499389648438, 200)

    @Test fun persisted551ExtendedCalibrationRestores550FallbackBeforeActualBinaryPlist() {
        for (auto in listOf(false, true)) {
            for (width in listOf(401, 422, 1178, 2000)) {
                val original = CarPlaySettings(widthPhysicalMm = width, autoScale = auto,
                    autoResolution = false, fitToWindow = false, resolution = "custom",
                    widthPixels = 3076, heightPixels = 1196, fps = 60,
                    ssid = "preserve-network", phoneAddress = "00:11:22:33:44:55", videoCodec = "hevc")
                val restored = CarPlaySettings.decode(CarPlaySettings.encode(original))
                assertEquals("Only extended calibration should migrate", original.copy(widthPhysicalMm = 200), restored)
                val qualified = restored.qualifiedDisplay(aliasedMetrics, VideoDecoderProbe { "fixture.decoder" })
                assertEquals(if (auto) CarPlayAutoScalePolicy.Basis.CALIBRATION_NEEDED
                    else CarPlayAutoScalePolicy.Basis.MANUAL, qualified.physicalSize.basis)
                val config = AirPlayConfig("fixture", "00:11:22:33:44:55", "00:11:22:33:44:55",
                    "950.7.1", qualified.toAirPlayDisplay())
                val bytes = BplistCodec.encode(AirPlayInfoPlist.build(config, emptySet()))
                val reparsed = BplistCodec.decode(bytes) as Map<*, *>
                val display = (reparsed["displays"] as List<*>).single() as Map<*, *>
                assertEquals(200, (display["widthPhysical"] as Number).toInt())
                assertEquals(78, (display["heightPhysical"] as Number).toInt())
                assertEquals(3076, (display["widthPixels"] as Number).toInt())
                assertEquals(1196, (display["heightPixels"] as Number).toInt())
                assertEquals(60, (display["maxFPS"] as Number).toInt())
            }
        }
    }

    @Test fun every550ValidSavedCalibrationAndOtherSettingsRemainExact() {
        for (width in 100..400) {
            val original = CarPlaySettings(widthPhysicalMm = width, autoScale = false,
                connectionMode = "usb", alwaysInPark = true, safeLeft = 100, videoCodec = "hevc")
            assertEquals(original, CarPlaySettings.decode(CarPlaySettings.encode(original)))
        }
    }
}
