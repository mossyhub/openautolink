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
    private fun call(status: Int = 2, suffix: String = "1"): com.shilapi.xcertplay.iap2.wire.Iap2Frame {
        fun field(id: Int, bytes: ByteArray) = byteArrayOf(0, (bytes.size + 4).toByte(), 0, id.toByte()) + bytes
        return com.shilapi.xcertplay.iap2.wire.Iap2Frame(0x4155,
            field(4, "00000000-0000-4000-8000-00000000000$suffix\u0000".toByteArray()) +
                field(2, byteArrayOf(status.toByte())) + field(3, byteArrayOf(1)) + field(9, byteArrayOf(0)))
    }
    @Test fun explicitCarPlayAdmitsWithUnknownHfpWithoutClaimingDisconnected() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(android.Manifest.permission.READ_PHONE_NUMBERS)
        val prefs = AppPreferences::class.java.getDeclaredConstructor(DataStore::class.java).apply { isAccessible = true }.newInstance(Store())
        val controller = CarPlayCallController(app, prefs)
        val backend = ManagedTelecomExperiment.get(app)
        try {
            controller.prepare()
            val manager = app.getSystemService(TelecomManager::class.java)
            shadowOf(manager).enablePhoneAccount(backend.handle, true)
            assertEquals(CarPlayCallController.Setup.READY, controller.prepare().setup)
            assertEquals(com.openautolink.carplaylab.telecom.HfpCallGuard.Status.UNKNOWN, controller.state.value.hfp)
            val link = backend.authenticatedLink { true }!!
            link.handle(call()); shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals("explicit CarPlay must not require unsupported automatic absence proof", 1, shadowOf(manager).allIncomingCalls.size)
            controller.setHandler(CarPlayCallPreferences.Handler.CAR_BLUETOOTH)
            link.handle(call(0)); link.handle(call(suffix = "2")); shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals("car Bluetooth must never announce a new OAL call", 1, shadowOf(manager).allIncomingCalls.size)
        } finally { controller.close(); backend.disable(); shadowOf(android.os.Looper.getMainLooper()).idle() }
    }
    @Test fun handlerSwitchKeepsExactAdmittedConnectionControlsButWithholdsNewCalls() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(android.Manifest.permission.READ_PHONE_NUMBERS)
        val prefs = AppPreferences::class.java.getDeclaredConstructor(DataStore::class.java).apply { isAccessible = true }.newInstance(Store())
        val controller = CarPlayCallController(app, prefs)
        val backend = ManagedTelecomExperiment.get(app)
        val service = Robolectric.buildService(com.openautolink.carplaylab.telecom.ManagedCarPlayConnectionService::class.java).create().get()
        try {
            controller.prepare()
            val manager = app.getSystemService(TelecomManager::class.java)
            val os = shadowOf(manager)
            os.enablePhoneAccount(backend.handle, true); controller.prepare(); os.setConnectionService(service)
            val commands = java.util.concurrent.CopyOnWriteArrayList<com.shilapi.xcertplay.airplay.PhoneControl>()
            val link = backend.authenticatedLink(controlSender = { command, current ->
                if (current()) { commands.add(command); true } else false
            }) { true }!!
            link.handle(call()); shadowOf(android.os.Looper.getMainLooper()).idle()
            val connection = os.allowIncomingCall(os.onlyIncomingCall)!!
            controller.setHandler(CarPlayCallPreferences.Handler.CAR_BLUETOOTH)
            assertTrue(controller.state.value.appliesToNextCall)
            connection.onAnswer()
            // Join the real account-authority executor, not a fixture-supplied admission predicate.
            backend.refresh()
            assertEquals("ordinary next-call switch revoked current owner's guarded Answer", 1, commands.size)
            link.handle(call(4)); shadowOf(android.os.Looper.getMainLooper()).idle()
            connection.onDisconnect(); backend.refresh()
            assertEquals("ordinary next-call switch revoked current owner's guarded End", 2, commands.size)
            link.handle(call(0)); link.handle(call(suffix = "2")); shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals("new OAL ring escaped saved car-Bluetooth selection", 1, os.allIncomingCalls.size)
        } finally { controller.close(); backend.disable(); shadowOf(android.os.Looper.getMainLooper()).idle(); service.onDestroy() }
    }
    @Test fun failedRepairCannotBeForgottenByAReplacementLinkOrSuccessfulSuccessorRepair() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(android.Manifest.permission.READ_PHONE_NUMBERS)
        val prefs = AppPreferences::class.java.getDeclaredConstructor(DataStore::class.java).apply { isAccessible = true }.newInstance(Store())
        val controller = CarPlayCallController(app, prefs)
        val backend = ManagedTelecomExperiment.get(app)
        try {
            controller.prepare()
            val manager = app.getSystemService(TelecomManager::class.java)
            shadowOf(manager).enablePhoneAccount(backend.handle, true); controller.prepare()
            val old = backend.authenticatedLink { true }!!
            old.handle(call()); shadowOf(android.os.Looper.getMainLooper()).idle()
            old.javaClass.getDeclaredField("retireDispatch").apply { isAccessible = true }.set(old, { _: () -> Unit -> false })
            assertFalse(controller.repairLocalPresentation())
            assertTrue("failed acknowledgement must remain visible", controller.state.value.localCleanupFailed)
            val successor = backend.authenticatedLink { true }!!
            shadowOf(android.os.Looper.getMainLooper()).idle()
            successor.handle(call(suffix = "2")); shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals("replacement link bypassed predecessor cleanup debt", 1, shadowOf(manager).allIncomingCalls.size)
            val repair = async(Dispatchers.IO) { controller.repairLocalPresentation() }
            withTimeout(5000) {
                while (!repair.isCompleted) { shadowOf(android.os.Looper.getMainLooper()).idle(); delay(10) }
            }
            assertFalse("repairing only a successor cannot acknowledge predecessor debt", repair.await())
            assertEquals(CarPlayCallController.Setup.UNAVAILABLE, controller.prepare().setup)
        } finally { controller.close(); backend.disable(); shadowOf(android.os.Looper.getMainLooper()).idle() }
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
