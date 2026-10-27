package com.openautolink.app.ui.settings

import com.openautolink.app.carplay.CarPlayCallController
import com.openautolink.app.data.CarPlayCallPreferences
import com.openautolink.carplaylab.telecom.HfpCallGuard
import org.junit.Assert.*
import org.junit.Test

class ProductionAudioBindingsTest {
    @Test fun explicitCarPlayConfigurationReadyDoesNotClaimBluetoothDisconnectedAuthority() {
        val value = productionCallUi(CarPlayCallController.State(CarPlayCallPreferences(),
            CarPlayCallController.Setup.READY, false, HfpCallGuard.Status.UNKNOWN), false, "")
        assertEquals(CarPlayCallSetup.READY, value.setup)
        assertTrue(value.message.contains("Keep the iPhone disconnected"))
        assertTrue(value.preferencesLoaded)
    }
    @Test fun currentDisconnectedAndApprovedAccountCanShowReady() {
        val value = productionCallUi(CarPlayCallController.State(CarPlayCallPreferences(),
            CarPlayCallController.Setup.READY, false, HfpCallGuard.Status.KNOWN_DISCONNECTED), false, "")
        assertEquals(CarPlayCallSetup.READY, value.setup)
        assertFalse(value.extraCarRingtone)
        assertEquals("", value.message)
    }
    @Test fun actualAndroidApprovalIsNotHiddenByAnUnknownBluetoothObservation() {
        val value = productionCallUi(CarPlayCallController.State(CarPlayCallPreferences(),
            CarPlayCallController.Setup.PERMISSION_NEEDED, false, HfpCallGuard.Status.UNKNOWN), false, "")
        assertEquals(CarPlayCallSetup.SETUP_REQUIRED, value.setup)
        val disabled = productionCallUi(CarPlayCallController.State(CarPlayCallPreferences(),
            CarPlayCallController.Setup.ACCOUNT_DISABLED, false, HfpCallGuard.Status.UNKNOWN), false, "")
        assertEquals(CarPlayCallSetup.ENABLE_ACCOUNT, disabled.setup)
    }
    @Test fun savedBluetoothChoiceAndNextCallStatusArePreservedWithoutRawEnums() {
        val value = productionCallUi(CarPlayCallController.State(
            CarPlayCallPreferences(CarPlayCallPreferences.Handler.CAR_BLUETOOTH, true),
            CarPlayCallController.Setup.READY, true, HfpCallGuard.Status.HFP_OWNED), true, "Setting not saved." )
        assertEquals(CarPlayCallHandler.CAR_BLUETOOTH, value.handler)
        assertTrue(value.appliesToNextCall)
        assertTrue(value.busy)
        assertEquals("Setting not saved.", value.message)
    }
}
