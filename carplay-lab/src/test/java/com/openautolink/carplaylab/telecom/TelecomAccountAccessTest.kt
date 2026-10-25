package com.openautolink.carplaylab.telecom

import android.Manifest
import android.content.Context
import android.telecom.TelecomManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], manifest = Config.NONE)
class TelecomAccountAccessTest {
    @Test fun obsoletePermissionGenerationCannotEnableAfterDisposalOrSupersedeNewSelection() {
        val app = RuntimeEnvironment.getApplication()
        val experiment = ManagedTelecomExperiment(app)
        val stale = experiment.prepareEnable()
        experiment.cancelPendingEnable(stale)
        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_NUMBERS)
        assertEquals("DENIED_OR_UNAVAILABLE", experiment.enablePrepared(stale).name)
        assertFalse(experiment.enabledForProcess)
        val current = experiment.prepareEnable()
        try {
            assertEquals("REGISTERED_USER_ENABLE_REQUIRED", experiment.enablePrepared(current).name)
            experiment.cancelPendingEnable(current) // completed process selection survives route departure
            assertTrue(experiment.enabledForProcess)
            assertEquals("DENIED_OR_UNAVAILABLE", experiment.enablePrepared(stale).name)
            assertTrue("stale result mutated current selection", experiment.enabledForProcess)
        } finally { experiment.disable() }
    }
    @Test fun revokedGrantImmediatelyRemovesCachedAuthorityAndDoesNotReportAbsent() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_NUMBERS)
        val experiment = ManagedTelecomExperiment(app)
        experiment.enable()
        shadowOf(app.getSystemService(TelecomManager::class.java)).enablePhoneAccount(experiment.handle, true)
        experiment.refresh()
        assertTrue(experiment.armed)
        shadowOf(app).denyPermissions(Manifest.permission.READ_PHONE_NUMBERS)
        assertFalse("cached enabled authority survived grant revocation", experiment.armed)
        assertEquals("READ_PERMISSION_REQUIRED", experiment.refresh().name)
        assertEquals(PassiveTelecomCensus.Account.UNKNOWN, experiment.census().ownAccount)
        experiment.disable()
    }
    @Test fun missingPermissionNeverRegistersOrReportsAbsent() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).denyPermissions(Manifest.permission.READ_PHONE_NUMBERS)
        val experiment = ManagedTelecomExperiment(app)
        try {
            assertEquals("READ_PERMISSION_REQUIRED", experiment.enable().name)
            assertFalse(experiment.enabledForProcess)
            assertFalse(experiment.armed)
            assertEquals(PassiveTelecomCensus.Account.UNKNOWN, experiment.census().ownAccount)
            assertNull(app.getSystemService(TelecomManager::class.java).getPhoneAccount(experiment.handle))
        } finally { experiment.disable() }
    }
}
