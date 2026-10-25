package com.openautolink.app.carplay

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Source routing guards; actual serialized /info is covered by ManualBluetoothReceiverTest. */
class ManualBluetoothRuntimeWiringTest {
    private val root = generateSequence(File(System.getProperty("user.dir")!!)) { it.parentFile }
        .first { File(it,"app/src/main").isDirectory }
    private fun source(path: String) = File(root,"app/src/main/java/com/openautolink/app/$path").readText()
    @Test fun usbFactoryUsesExistingSavedFieldAndOnlyBluetoothAdvertisementChanges() {
        val vm=source("ui/projection/ProjectionViewModel.kt")
        val branch=vm.substringAfter("if (usbDevice != null) {").substringBefore("// The captured valid surface")
        assertTrue(branch.contains("receiverAddress = saved.receiverAddress"))
        val usb=source("carplay/OalUsbCarPlayRuntime.kt")
        assertTrue(usb.contains("CarPlayReceiverAddressPolicy.runtimeAddress(receiverAddress)"))
        assertTrue(usb.contains("advertisedBluetoothAddress = manualBluetoothAddress"))
        assertTrue(usb.contains("val mac=ncm.hostMac ?: ByteArray(6)"))
        assertTrue(usb.contains("val identity=mac.joinToString(\":\")"))
        assertTrue(usb.contains("val address=linkLocal(mac)"))
        assertTrue(usb.contains("Ipv6NcmBridge(ncm,fd,mac,hostIpv6=InetAddress.getByName(address).address)"))
        assertTrue(usb.contains("ProjectionReceiver(context,identity,surface"))
        assertEquals(2,Regex("deviceIdentifier=identity").findAll(usb).count())
        assertEquals(2,Regex("wiredIpv6Addresses=listOf\\(address\\)").findAll(usb).count())
        assertFalse(usb.contains("btMacOverride"))
        assertFalse(usb.contains("setBtMacOverride"))
        assertFalse(usb.contains("setCarPlayReceiverAddress"))
    }
}
