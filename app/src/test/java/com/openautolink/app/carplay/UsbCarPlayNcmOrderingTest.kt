package com.openautolink.app.carplay

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Wiring contract for the one-variable DiPlay startup-order comparison, not phone proof. */
class UsbCarPlayNcmOrderingTest {
    private fun runtime() = File("src/main/java/com/openautolink/app/carplay/OalUsbCarPlayRuntime.kt").readText()

    @Test fun ncmIsOwnedImmediatelyAfterUsbMuxPipeBeforeMuxHostAndPairing() {
        val source = runtime()
        val pipe = source.indexOf("val pipe=own {")
        val mux = source.indexOf("val mux=own { Iap2UsbMuxHost.open(pipe) }")
        assertTrue("USBMUX pipe must be opened before the mux host", pipe >= 0 && mux > pipe)
        val beforeMux = source.substring(pipe, mux)
        val expected = """
            val configuration=IphoneCarPlayConfiguration.find(selected) ?: error("USB CarPlay configuration missing")
            val function=NcmFunctionDiscovery.find(configuration) ?: error("USB NCM function missing")
            val ncm=own {
                val ncmConnection=usb.openDevice(selected) ?: error("USB NCM unavailable")
                NcmUsbBridge.open(ncmConnection,function)
            }
        """.trimIndent()
        assertTrue("NCM discovery/open/ownership must immediately precede Iap2UsbMuxHost.open (DiPlay openNcm before runStack)",
            beforeMux.trimEnd().endsWith(expected.prependIndent("        ")))
        assertEquals("NCM must be opened once", 1, Regex("NcmUsbBridge\\.open").findAll(source).count())
        assertTrue(source.indexOf("LockdownPlistChannel(mux.connect())") > mux)
        assertTrue(source.indexOf("LockdownPairingClient(mux).pair") > mux)
    }

    @Test fun vpnAndTunStillStartAfterCarKitAndAuthenticationAssetLoad() {
        val source = runtime()
        val carkit = source.indexOf("val carkit=own { LockdownCarKitClient(mux).open")
        val auth = source.indexOf("val auth=LocalMfiAuthenticationClient.load")
        val vpn = source.indexOf("val vpn=bindVpn()")
        val tun = source.indexOf("fd=vpn.attach(token,address)")
        val bridge = source.indexOf("val bridge=own { Ipv6NcmBridge(ncm,fd,mac)")
        assertTrue(carkit >= 0 && auth > carkit && vpn > auth && tun > vpn && bridge > tun)
        assertTrue(source.indexOf("active();bridge.start()") > bridge)
        assertTrue(source.substringAfter("val identification=Iap2IdentificationConfig").substringBefore("val projection=")
            .contains("function.data.id"))
    }

    @Test fun resourceRetirementRemainsReverseAcquisitionOrder() {
        val source = runtime()
        assertTrue(source.contains("val resource=create()"))
        assertTrue(source.contains("resources.add(resource)"))
        assertTrue(source.contains("resources.toList().asReversed()"))
        val close = source.substringAfter("override fun close()")
        assertTrue(close.contains("try { owner.close() }"))
        assertTrue(close.contains("is Iap2UsbMuxHost -> check(owner.awaitClosed(5000))"))
        assertTrue(close.contains("resources.removeAll { it === owner }"))
    }
}
