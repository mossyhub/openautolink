package com.openautolink.app.carplay

import android.hardware.usb.UsbDevice
import android.view.Surface
import com.openautolink.app.cluster.*
import com.openautolink.carplaylab.media.*
import com.shilapi.xcertplay.airplay.AirPlayInsets
import com.shilapi.xcertplay.media.CarPlayVideoSelection
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import io.mockk.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Actual wired/wireless runtime constructor-boundary -> real receiver/sink, not phone acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32],manifest=Config.NONE,application=android.app.Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class CarPlayClusterConstructorTest {
    @Test fun preparePassesExactQualifiedAvcIdentityIntoCommonBridgeBeforeHostAdmission() {
        val app = RuntimeEnvironment.getApplication()
        val profile = ClusterDisplayProfiles.AEGEAN_ORANGE_239
        val host = ClusterHostGeometry(7, profile.panel, profile.panel.bounds, profile.panel.bounds)
        val display = ClusterDisplayProfiles.qualify(profile.vehicle,
            listOf(ClusterDisplayBinding(ClusterVehicleRole.DRIVER_CLUSTER, 7)), host)!!
        val qualified = CarPlayVideoSelection(1280, 480, 30, "c2.exact.secondary.avc")
        val captured = slot<ClusterHostOutputBridge>()
        mockkObject(ClusterProjectionOutput.Companion, com.shilapi.xcertplay.media.CarPlayVideoQualification)
        mockkConstructor(ClusterManager::class)
        try {
            every { ClusterProjectionOutput.qualifiedInitialDisplay(app) } returns display
            every { com.shilapi.xcertplay.media.CarPlayVideoQualification.select(1280, 480, 30, false) } returns qualified
            // Reject host admission after inspecting the real bridge. No host/GPU ownership is invented.
            every { anyConstructed<ClusterManager>().beginMapOutput(any(), any(), any(), any(), capture(captured), any()) } returns null
            val output = OalCarPlayClusterOutput.prepare(app, ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO))
            assertNull(output.configuration)
            val bridge = captured.captured as ClusterProjectionOutput
            assertEquals(qualified.avcDecoderName, bridge.avcDecoderName)
            assertTrue(output.retire().get(2, java.util.concurrent.TimeUnit.SECONDS))
        } finally { unmockkAll() }
    }

    @Test fun wiredAndWirelessConstructRealReceiverWithExactFrozenConfigAndEncodedOnlyListener() {
        val app=RuntimeEnvironment.getApplication()
        for (usb in listOf(false,true)) for (enabled in listOf(false,true)) {
            java.io.File(app.noBackupFilesDir,"pairing-probe").deleteRecursively()
            val saved=CarPlayDisplayPreferences(preferHevc=true,
                qualifiedVideoSelection=CarPlayVideoSelection(1280,720,30,"main.avc","main.hevc"))
            val config=if(enabled) CarPlayClusterVideoConfiguration.create(AirPlayInsets(0,0,0,0),
                AirPlayInsets(40,40,360,360),CarPlayVideoSelection(1280,480,30,"secondary.avc")) else null
            val selection=ClusterOutputSelection(if(enabled) ClusterDisplayMode.MAP_VIDEO else ClusterDisplayMode.OFF)
            val output=OalCarPlayClusterOutput(selection,config,null,null,null)
            val surface=mockk<Surface>(relaxed=true)
            val runtime:CarPlayRuntime=if(usb) OalUsbCarPlayRuntime(app,mockk<UsbDevice>(relaxed=true),saved,surface,{},
                clusterOutput=output,clusterSelection=selection) else OalCarPlayRuntime(app,
                CarPlayRuntimeConfig("","","",false,"","",0,saved),surface,{},clusterOutput=output,clusterSelection=selection)
            var constructed:ProjectionReceiver?=null
            try {
                val factory: (CarPlayClusterVideoConfiguration?,CarPlayClusterVideoListener?) -> ProjectionReceiver = { actual,listener ->
                    assertSame(config,actual)
                    if(enabled) assertSame(output,listener) else assertNull(listener)
                    ProjectionReceiver(app,"02:00:00:00:00:01",surface,mockk<MfiAuthenticator>(relaxed=true),saved,
                        wired=usb,clusterVideoConfiguration=actual,clusterVideoListener=listener,onStatus={}).also { constructed=it }
                }
                val receiver=if(runtime is OalUsbCarPlayRuntime) runtime.createClusterReceiver(factory)
                    else (runtime as OalCarPlayRuntime).createClusterReceiver(factory)
                assertSame(config,receiver.clusterVideoConfiguration)
                if (enabled) assertEquals("secondary.avc", receiver.clusterVideoConfiguration!!.videoSelection.avcDecoderName)
                assertEquals("main.avc", receiver.displayConfiguration.qualifiedVideoSelection!!.avcDecoderName)
                assertEquals("main.avc", saved.qualifiedVideoSelection!!.avcDecoderName)
                assertEquals("main.hevc", saved.qualifiedVideoSelection!!.hevcDecoderName)
                assertEquals(!enabled,receiver.displayConfiguration.preferHevc)
                assertEquals(!enabled,receiver.displayConfiguration.qualifiedVideoSelection!!.hevcOffered)
                assertTrue(saved.preferHevc)
                assertTrue(saved.qualifiedVideoSelection!!.hevcOffered)
                assertEquals(if(enabled) CarPlayClusterVideoConfiguration.PILOT_STATUS else null,receiver.clusterVideoPilotStatus)
                assertNull("physical host surfaces must never attach to the lab decoder in encoded mode",receiver.attachClusterSurface(surface))
                assertFalse(runtime.clusterTelemetrySnapshot()!!.output.hostAttached)
            } finally {
                runtime.close()
                constructed?.let { assertTrue(it.awaitClosed(5000)) }
                unmockkAll()
            }
        }
    }
}
