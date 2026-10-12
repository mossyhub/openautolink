package com.openautolink.app.carplay

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class UsbCarPlayPermissionWiringTest {
    private fun source(path: String): String {
        var dir = File(System.getProperty("user.dir"))
        repeat(8) {
            File(dir, path).takeIf { it.isFile }?.let { return it.readText() }
            dir = dir.parentFile ?: dir
        }
        error("Missing $path")
    }
    @Test fun initialAndReplacementPermissionUseBoundedReceiver() {
        val runtime = source("app/src/main/java/com/openautolink/app/carplay/OalUsbCarPlayRuntime.kt")
        assertTrue("Initial permission must actually request consent", runtime.contains("UsbCarPlayPermission.await(context, usb, device"))
        assertTrue("Replacement uses the same bounded consent helper", runtime.contains("UsbCarPlayPermission.await(context, usb, candidate"))
        assertFalse("No fire-and-forget permission polling", runtime.contains("host.requestPermission(candidate)"))
    }
    @Test fun appleReplacementIsNotAdoptedFromAttachVendorAlone() {
        val manager = source("app/src/main/java/com/openautolink/app/transport/usb/UsbConnectionManager.kt")
        val attach = manager.substringAfter("private fun handleDeviceAttached").substringBefore("private fun handleDeviceDetached")
        val apple = attach.substringAfter("selected?.route == UsbDispatchOwnership.Route.APPLE").substringBefore("if (selected?.reenumerating")
        assertFalse("Only runtime serial-qualified acceptance may adopt Apple replacement", apple.contains("adoptReenumeration"))
    }
}
