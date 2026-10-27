package com.openautolink.app.carplay

import android.app.Application
import android.content.Context
import android.telecom.TelecomManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.openautolink.app.data.*
import com.openautolink.carplaylab.telecom.ManagedTelecomExperiment
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32,33,34], manifest = Config.NONE, application = Application::class)
class CarPlayCallControllerTest {
    private class Store : DataStore<Preferences> {
        val state = MutableStateFlow<Preferences>(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = transform(state.value).also { state.value = it }
    }
    @Test fun startupWithoutSettingsSeparatesMissingPermissionDisabledAndReadyAndNeverLaunches() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val store = Store()
        val prefs = AppPreferences::class.java.getDeclaredConstructor(DataStore::class.java).apply { isAccessible = true }.newInstance(store)
        val controller = CarPlayCallController(context, prefs)
        try {
            assertEquals(CarPlayCallController.Setup.PERMISSION_NEEDED, controller.prepare().setup)
            shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.READ_PHONE_NUMBERS)
            assertEquals(CarPlayCallController.Setup.ACCOUNT_DISABLED, controller.prepare().setup)
            val backend = ManagedTelecomExperiment.get(context)
            val manager = context.getSystemService(TelecomManager::class.java)
            shadowOf(manager).enablePhoneAccount(backend.handle, true)
            assertEquals(CarPlayCallController.Setup.READY, controller.prepare().setup)
            assertTrue(manager.getPhoneAccount(backend.handle).isEnabled)
            controller.setHandler(CarPlayCallPreferences.Handler.CAR_BLUETOOTH)
            controller.setExtraRingtone(true)
            assertEquals(CarPlayCallPreferences(CarPlayCallPreferences.Handler.CAR_BLUETOOTH, true), prefs.carPlayCallPreferences.first())
            assertNotNull(manager.getPhoneAccount(backend.handle))
            shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(android.Manifest.permission.READ_PHONE_NUMBERS)
            assertEquals(CarPlayCallController.Setup.PERMISSION_NEEDED, controller.prepare().setup)
            assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
        } finally { controller.close() }
    }
}
