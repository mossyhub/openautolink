package com.openautolink.app.data

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class WiredOnlyPreferencesTest {
    @get:Rule val temp = org.junit.rules.TemporaryFolder()
    private fun preferences(store: DataStore<Preferences>) = AppPreferences::class.java
        .getDeclaredConstructor(DataStore::class.java).apply { isAccessible = true }.newInstance(store)

    @Test fun diskReopenRetainsOverrideAndIndependentNormalModes() = runBlocking {
        val file = java.io.File(temp.root, "wired.preferences_pb")
        var job = SupervisorJob()
        fun open() = preferences(PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job), produceFile = { file }))
        var prefs = open()
        assertFalse(prefs.wiredOnly.first())
        prefs.setDirectTransport("wpp")
        prefs.setCarPlaySettings(CarPlaySettings(enabled = false, connectionMode = "wireless", ssid = "Keep network"))
        prefs.setHotspotPassword("retain secret")
        prefs.setWiredOnly(true)
        assertFalse(prefs.projectionConnectionPolicy.wirelessAllowed)
        assertEquals("usb", prefs.effectiveDirectTransport.first())
        assertEquals("usb", prefs.effectiveCarPlaySettings.first().connectionMode)
        job.cancelAndJoin()
        job = SupervisorJob()
        prefs = open()
        try {
            assertTrue(prefs.wiredOnly.first())
            assertEquals("usb", prefs.effectiveDirectTransport.first())
            assertEquals("wpp", prefs.directTransport.first())
            assertFalse(prefs.carPlaySettings.first().enabled)
            assertEquals("wireless", prefs.carPlaySettings.first().connectionMode)
            assertEquals("Keep network", prefs.carPlaySettings.first().ssid)
            assertEquals("retain secret", prefs.hotspotPassword.first())
            prefs.setWiredOnly(false)
            assertEquals("wpp", prefs.effectiveDirectTransport.first())
            assertEquals("wireless", prefs.effectiveCarPlaySettings.first().connectionMode)
        } finally { job.cancelAndJoin() }
    }

    @Test fun unknownAndFailedReadNeverAdmitWireless() = runBlocking {
        val read = CompletableDeferred<Preferences>()
        val store = object : DataStore<Preferences> {
            override val data = flow { emit(read.await()) }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = error("read-only")
        }
        val prefs = preferences(store)
        assertFalse(prefs.projectionConnectionPolicy.wirelessAllowed)
        read.completeExceptionally(java.io.IOException("unavailable"))
        delay(50)
        assertFalse(prefs.projectionConnectionPolicy.wirelessAllowed)
    }
}
