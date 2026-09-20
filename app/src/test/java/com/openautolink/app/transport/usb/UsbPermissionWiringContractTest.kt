package com.openautolink.app.transport.usb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UsbPermissionWiringContractTest {

    @Test
    fun `USB permission PendingIntent is mutable and package explicit on Android 14`() {
        val source = usbManagerSource()
        val permissionBlock = source.substringAfter("val permissionIntent = PendingIntent.getBroadcast(")
            .substringBefore("usbManager.requestPermission(device, permissionIntent)")

        assertTrue(permissionBlock.contains("Intent(ACTION_USB_PERMISSION)"))
        assertTrue(permissionBlock.contains("setPackage(context.packageName)"))
        assertTrue(permissionBlock.contains("PendingIntent.FLAG_MUTABLE"))
        assertFalse(permissionBlock.contains("PendingIntent.FLAG_IMMUTABLE"))
    }

    @Test
    fun `USB permission request failure resets the in-flight prompt instead of crashing`() {
        val source = usbManagerSource()
        val permissionPath = source.substringAfter("private fun requestPermissionOrConnect(device: UsbDevice)")
            .substringBefore("private fun onPermissionGranted(device: UsbDevice)")

        assertTrue(permissionPath.contains("runCatching"))
        assertTrue(permissionPath.contains("usbManager.requestPermission(device, permissionIntent)"))
        assertTrue(permissionPath.contains("pendingPermissionDevice.compareAndSet(key, null)"))
        assertTrue(permissionPath.contains("_connectionState.value = UsbConnectionState.IDLE"))
        assertTrue(permissionPath.contains("USB permission request failed"))
    }

    private fun usbManagerSource(): String = projectFile(
        "app/src/main/java/com/openautolink/app/transport/usb/UsbConnectionManager.kt",
    ).readText()

    private fun projectFile(path: String): File {
        var dir = File(System.getProperty("user.dir") ?: error("user.dir unavailable"))
        repeat(8) {
            val candidate = File(dir, path)
            if (candidate.isFile) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        error("Could not locate $path from ${System.getProperty("user.dir")}")
    }
}
