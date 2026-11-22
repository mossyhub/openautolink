package com.openautolink.carplaylab.intake

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.*
import org.junit.Test

/** These wiring assertions supplement executable adapter, AEAD and synchronous tunnel tests. */
class RuntimeCarDataWiringTest {
    @Test fun usbBootstrapAndRuntimeConsumeOneFrozenContractWithBoundedRequestHandoff() {
        val usb=source("app/src/main/java/com/openautolink/app/carplay/OalUsbCarPlayRuntime.kt")
        assertTrue(usb.contains("OalCarDataBootstrap(capabilities"))
        assertTrue(usb.contains("val vehicleBootstrap=own { createVehicleBootstrap(capabilities, evCapture) }"))
        assertTrue(usb.contains("::status, evReceipt = evCapture::record"))
        assertTrue(usb.substringAfter("vehicleBootstrap.wrap(").substringBefore("runtimeMetadataFactory")
            .contains("evCapture = evCapture"))
        assertTrue(usb.contains("vehicleBootstrap.wrap("))
        assertTrue(usb.contains("frozenCapabilities=capabilities"))
        assertTrue(usb.contains("onOtherFrame=vehicleBootstrap::handle"))
        assertTrue(usb.contains("vehicleBootstrap.handle(frame)"))
        assertTrue(usb.contains("Iap2IdentificationClient(csm,vehicleBootstrap::identification)"))
    }
    private fun source(relative: String) = listOf(Path.of(".."),Path.of(".")).map { it.resolve(relative) }
        .first { Files.exists(it) }.toFile().readText()
    @Test fun productionRuntimeUsesActualPerTunnelLifecycleAndRawSources() {
        val app = source("app/src/main/java/com/openautolink/app/carplay/OalCarPlayRuntime.kt")
        assertTrue("real runtime factory absent", app.contains("runtimeCarDataFactory ="))
        assertTrue(app.substringAfter("runtimeCarDataFactory =").substringBefore("runtimeMetadataFactory")
            .contains("evCapture = CarPlayEvLogCapture.Link()"))
        assertTrue(app.contains("GnssForwarderImpl(context.applicationContext, sink, true)"))
        assertTrue(app.contains("ACCESS_FINE_LOCATION"))
        assertTrue(app.contains("isProviderEnabled(LocationManager.GPS_PROVIDER)"))
        assertFalse(app.contains("attachSessionConsumer"))
        val receiver = source("carplay-lab/src/main/java/com/openautolink/carplaylab/media/ProjectionReceiver.kt")
        assertTrue(receiver.contains("val carData = runtimeCarDataFactory"))
        assertTrue(receiver.contains("carData?.identification(base) ?: base"))
        assertTrue(receiver.indexOf("carData?.authenticate()") > receiver.indexOf("Iap2MfiAuthenticationClient(mfi).run(channel"))
        assertTrue(receiver.contains("channel.recv(carData?.pollTimeout(1000) ?: 1000)"))
        assertTrue(receiver.contains("carData?.handle(frame) != true"))
        assertTrue(receiver.contains("carData?.tick(session.encryptedEventReady)"))
        assertTrue(receiver.contains("channel.sendAndAwaitWritten(frame, 5_000)"))
        assertTrue(receiver.contains("carData?.let { retireCarData(session, it) }"))
        assertTrue(receiver.contains("runCatching { owner.close() }.isSuccess"))
        assertTrue(receiver.indexOf("carData?.let { retireCarData(session, it) }") < receiver.indexOf("closeRuntime({ channel.close() }"))
    }
}
