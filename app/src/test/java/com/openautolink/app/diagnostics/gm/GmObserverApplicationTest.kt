package com.openautolink.app.diagnostics.gm

import android.app.Application
import com.openautolink.app.OalApplication
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@Implements(Application::class)
class ObserverProcessApplicationShadow:org.robolectric.shadows.ShadowApplication() {
    companion object {
        @JvmStatic @Implementation fun getProcessName():String="com.openautolink.app:gmPageObserver"
    }
}
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32,34],application=OalApplication::class,shadows=[ObserverProcessApplicationShadow::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class GmObserverApplicationTest {
    @Test fun isolatedObserverColdStartDoesNotInitializeNormalProductionApplication() {
        val app=RuntimeEnvironment.getApplication()
        assertTrue(app is OalApplication)
        assertEquals(app.packageName+":gmPageObserver",Application.getProcessName())
        assertNull((app as OalApplication).carBluetoothConnectionObserver)
        assertNull(com.openautolink.app.session.SessionManager.instanceOrNull())
        assertNull(com.openautolink.app.secondary.SecondaryOutputRuntime.peekForDiagnostics())
        assertFalse(com.openautolink.app.diagnostics.live.LiveDiagnostics.ready.value)
    }
}
