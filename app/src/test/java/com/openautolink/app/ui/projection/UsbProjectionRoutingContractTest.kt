package com.openautolink.app.ui.projection

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class UsbProjectionRoutingContractTest {
    private fun source(path: String) = File("src/main/java/com/openautolink/app/$path").readText()

    @Test fun usbStartsDeviceSelectionBeforeWirelessCarPlayValidation() {
        val vm = source("ui/projection/ProjectionViewModel.kt")
        val startup = vm.substringAfter("fun startProjectionOnce()").substringBefore("fun connectCarPlay()")
        assertTrue(startup.indexOf("startUnifiedUsb()") >= 0)
        assertTrue(startup.indexOf("startUnifiedUsb()") < startup.indexOf("carPlayStartupIssue(saved)"))
        assertTrue(vm.contains("OalUsbCarPlayRuntime"))
    }

    @Test fun aaAcceptsExternallyOwnedUsbWithoutStartingSecondDeviceWatcher() {
        val aa = source("transport/aasdk/AasdkSession.kt")
        assertTrue(aa.contains("externalUsbTransport"))
        assertTrue(aa.contains("fun acceptUsbTransport("))
        assertTrue(aa.contains("fun detachUsbTransport("))
        assertTrue(aa.substringAfter("private fun startUsb()").substringBefore("private fun handleUsbConnection")
            .contains("if (externalUsbTransport) return"))
    }
    @Test fun idleSavedAaUsbDoesNotInventAnInflightWirelessOwnerBeforeCableAutostart() {
        val vm = source("ui/projection/ProjectionViewModel.kt")
        val startup = vm.substringAfter("fun startProjectionOnce()").substringBefore("private suspend fun releaseCarPlayUsbOwner()")
        assertTrue(startup.contains("val aaNormalTransport = preferences.directTransport.first()"))
        assertTrue(startup.contains("if (aaNormalTransport != AppPreferences.DIRECT_TRANSPORT_USB) connect()"))
    }

    @Test fun settingsDoNotIntroduceCarPlaySpecificUsbMode() {
        val settings = source("ui/settings/SettingsScreen.kt")
        assertFalse(settings.contains("CarPlay USB"))
        assertFalse(settings.contains("USB CarPlay mode"))
    }
}
