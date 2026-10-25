package com.openautolink.app.carplay

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.openautolink.app.data.*
import com.openautolink.app.ui.settings.CarPlaySettingsDraft
import com.openautolink.app.ui.settings.carPlayEditError
import com.openautolink.carplaylab.media.CarPlayDisplayPreferences
import android.content.Context
import android.hardware.usb.UsbDevice
import android.view.Surface
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File

class ManualBluetoothPreferencesTest {
    @get:Rule val temp=TemporaryFolder()
    @Test fun sharedCarPlayCommitPreservesWirelessValueAndNeverRewritesAndroidAuto() = runBlocking {
        val job=SupervisorJob()
        val store=PreferenceDataStoreFactory.create(scope=CoroutineScope(Dispatchers.IO+job),
            produceFile={ File(temp.root,"settings.preferences_pb") })
        val ctor=AppPreferences::class.java.getDeclaredConstructor(DataStore::class.java).apply { isAccessible=true }
        val prefs=ctor.newInstance(store)
        try {
            prefs.setBtMacOverride("CC:DD:EE:FF:00:10")
            prefs.setCarPlaySettings(CarPlaySettings(connectionMode="wireless",receiverAddress="aa:bb:cc:dd:ee:10"))
            for(mode in listOf("usb","wireless")) {
                val draft=CarPlaySettingsDraft()
                draft.update { it.copy(connectionMode=mode) }
                assertTrue(draft.commit { merge,_ -> prefs.updateCarPlaySettings(merge) { check(carPlayEditError(it)==null) } })
                assertEquals("aa:bb:cc:dd:ee:10",prefs.carPlaySettings.first().receiverAddress)
                assertEquals("CC:DD:EE:FF:00:10",prefs.btMacOverride.first())
            }
            val saved=prefs.carPlaySettings.first()
            prefs.setBtMacOverride("DD:EE:FF:00:11:20")
            assertEquals(saved,prefs.carPlaySettings.first())
        } finally { job.cancelAndJoin() }
    }
    @Test fun constructorSnapshotsCanonicalAddressBeforeAnyUsbOperationAndBlankPreservesLegacy() {
        for ((raw,want) in listOf(" aa-bb-cc-dd-ee-10 " to "AA:BB:CC:DD:EE:10", "" to null, "  " to null)) {
            val runtime=OalUsbCarPlayRuntime(mockk<Context>(),mockk<UsbDevice>(relaxed=true),
                CarPlayDisplayPreferences(),mockk<Surface>(),{},receiverAddress=raw)
            try {
                val field=runtime.javaClass.getDeclaredField("manualBluetoothAddress").apply { isAccessible=true }
                assertEquals(want,field.get(runtime))
            } finally { runtime.close() }
        }
        try {
            OalUsbCarPlayRuntime(mockk<Context>(),mockk<UsbDevice>(),CarPlayDisplayPreferences(),mockk<Surface>(),{},receiverAddress="private-invalid-token")
            fail("Invalid entry fell back to Ethernet identity")
        } catch(error:IllegalArgumentException) { assertFalse(error.message.orEmpty().contains("private-invalid-token")) }
    }
}
