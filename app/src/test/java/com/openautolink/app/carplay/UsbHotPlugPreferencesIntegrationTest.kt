package com.openautolink.app.carplay

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.openautolink.app.data.AppPreferences
import com.openautolink.app.data.CarPlaySettings
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class UsbHotPlugPreferencesIntegrationTest {
    @Test fun transientAcceptAndUnplugNeverWriteSavedAaOrCarPlayModes() = runTest {
        for (cpMode in listOf("usb", "wireless")) for (aaMode in listOf("wpp", "usb")) {
            withPreferences { preferences, store ->
                preferences.setDirectTransport(aaMode)
                preferences.setCarPlaySettings(CarPlaySettings(enabled = true, connectionMode = cpMode))
                val before = store.data.first()
                val policy = UsbHotPlugSwitchPolicy()
                val events = mutableListOf<String>()
                assertTrue(policy.accept(42, { UsbHotPlugSwitchPolicy.Protocol.CARPLAY },
                    { preferences.carPlaySettings.first().enabled }, { events += "override" },
                    { events += "retired" }, { events += "usb"; true }, {}, {}))
                assertEquals(before, store.data.first())
                assertTrue(policy.finish(42, { events += "usb-retired" }, { events += "clear" }, {
                    val saved = preferences.carPlaySettings.first()
                    events += if (policy.wirelessCarPlay(saved.enabled, saved.connectionMode)) "cp-wireless"
                        else if (preferences.directTransport.first() == "wpp") "aa-wpp-gm-priority" else "aa-usb-idle"
                }))
                assertEquals(before, store.data.first())
                assertEquals(if (cpMode == "wireless") "cp-wireless" else if (aaMode == "wpp") "aa-wpp-gm-priority" else "aa-usb-idle", events.last())
                assertTrue(events.indexOf("usb-retired") < events.indexOf("clear"))
            }
        }
    }
    @Test fun disabledCarPlayStillAdmitsAndroidAndRestoresNormalWpp() = runTest {
        withPreferences { preferences, store ->
            preferences.setDirectTransport("wpp")
            preferences.setCarPlaySettings(CarPlaySettings(enabled = false, connectionMode = "usb"))
            val before = store.data.first()
            val policy = UsbHotPlugSwitchPolicy()
            var starts = 0
            val eligible: suspend (UsbHotPlugSwitchPolicy.Protocol) -> Boolean = {
                it == UsbHotPlugSwitchPolicy.Protocol.ANDROID_AUTO || preferences.carPlaySettings.first().enabled
            }
            assertFalse(policy.accept(1, { UsbHotPlugSwitchPolicy.Protocol.CARPLAY }, eligible,
                { fail("disabled claim") }, { fail("disabled retirement") }, { fail("disabled startup"); true }, {}, {}))
            assertTrue(policy.accept(2, { UsbHotPlugSwitchPolicy.Protocol.ANDROID_AUTO }, eligible,
                {}, {}, { starts++; true }, {}, {}))
            assertEquals(1, starts)
            assertEquals(before, store.data.first())
        }
    }
    private suspend fun withPreferences(block: suspend (AppPreferences, DataStore<androidx.datastore.preferences.core.Preferences>) -> Unit) {
        val directory = File("build/tmp/usb-hotplug-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job),
            produceFile = { File(directory, "settings.preferences_pb") })
        val constructor = AppPreferences::class.java.getDeclaredConstructor(DataStore::class.java).apply { isAccessible = true }
        try { block(constructor.newInstance(store), store) }
        finally { job.cancelAndJoin(); directory.deleteRecursively() }
    }
}
