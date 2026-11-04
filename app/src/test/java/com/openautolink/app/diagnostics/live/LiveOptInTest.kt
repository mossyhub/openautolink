package com.openautolink.app.diagnostics.live

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32,35],application=Application::class,manifest=Config.NONE)
class LiveOptInTest {
    @Test fun initializeAndRepeatedStopNeverRestoreEnabledOrCreateProjection() {
        LiveDiagnostics.initialize(RuntimeEnvironment.getApplication())
        assertEquals("Off",LiveDiagnostics.status.value)
        LiveDiagnostics.stop()
        assertEquals("Off",LiveDiagnostics.status.value)
        val snapshot=LiveSnapshot.read(RuntimeEnvironment.getApplication(),-1)
        assertEquals("unknown",snapshot.getString("oemVisibility"))
        assertEquals("unavailable",snapshot.getString("state"))
        assertTrue(snapshot.has("displays"))
    }
}
