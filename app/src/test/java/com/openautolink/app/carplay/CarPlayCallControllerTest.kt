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
    private class Profile : android.bluetooth.BluetoothProfile {
        @Volatile var state = android.bluetooth.BluetoothProfile.STATE_DISCONNECTED
        lateinit var device: android.bluetooth.BluetoothDevice
        override fun getConnectedDevices() = getDevicesMatchingConnectionStates(intArrayOf(2))
        override fun getDevicesMatchingConnectionStates(states: IntArray): List<android.bluetooth.BluetoothDevice> {
            val result = if (state in states) listOf(device) else emptyList()
            afterRead.getAndSet(null)?.invoke()
            return result
        }
        val afterRead = java.util.concurrent.atomic.AtomicReference<(() -> Unit)?>()
        override fun getConnectionState(device: android.bluetooth.BluetoothDevice) = state
    }
    private fun guardWorker(guard: com.openautolink.carplaylab.telecom.HfpCallGuard) =
        guard.javaClass.getDeclaredField("worker").apply { isAccessible = true }.get(guard) as java.util.concurrent.ScheduledExecutorService
    private fun refreshFixtureGuard(guard: com.openautolink.carplaylab.telecom.HfpCallGuard) {
        // A direct refresh may lose its revision to the scheduled census and return
        // without publishing. Use that same worker as the fixture's completion barrier.
        guardWorker(guard).submit { guard.refresh() }.get(5, java.util.concurrent.TimeUnit.SECONDS)
    }
    @Test @Config(sdk = [32,34])
    fun fixtureCensusRunsOnGuardWorker() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(android.Manifest.permission.BLUETOOTH_CONNECT)
        val adapter = app.getSystemService(android.bluetooth.BluetoothManager::class.java).adapter
        shadowOf(adapter).setState(android.bluetooth.BluetoothAdapter.STATE_ON)
        val profile = Profile().apply { device = adapter.getRemoteDevice("02:00:00:00:00:01"); state = android.bluetooth.BluetoothProfile.STATE_CONNECTED }
        val guard = com.openautolink.carplaylab.telecom.HfpCallGuard(app)
        val listener = guard.javaClass.getDeclaredField("listener").apply { isAccessible = true }.get(guard) as android.bluetooth.BluetoothProfile.ServiceListener
        try {
            listener.onServiceConnected(16, profile)
            val expected = guardWorker(guard).submit<Thread> { Thread.currentThread() }.get(5, java.util.concurrent.TimeUnit.SECONDS)
            var actual: Thread? = null
            profile.afterRead.set { actual = Thread.currentThread() }
            refreshFixtureGuard(guard)
            assertSame("fixture refresh must serialize with the actual scheduled census worker", expected, actual)
            assertEquals(com.openautolink.carplaylab.telecom.HfpCallGuard.Status.HFP_OWNED, guard.status)
        } finally { guard.close() }
    }
    @Test @Config(sdk = [32,34])
    fun overlappingManualCensusCanReturnBeforeNewerReadPublishes() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(android.Manifest.permission.BLUETOOTH_CONNECT)
        val adapter = app.getSystemService(android.bluetooth.BluetoothManager::class.java).adapter
        shadowOf(adapter).setState(android.bluetooth.BluetoothAdapter.STATE_ON)
        val profile = Profile().apply { device = adapter.getRemoteDevice("02:00:00:00:00:01"); state = android.bluetooth.BluetoothProfile.STATE_CONNECTED }
        val guard = com.openautolink.carplaylab.telecom.HfpCallGuard(app)
        val listener = guard.javaClass.getDeclaredField("listener").apply { isAccessible = true }.get(guard) as android.bluetooth.BluetoothProfile.ServiceListener
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val worker = guardWorker(guard)
        try {
            listener.onServiceConnected(16, profile)
            profile.afterRead.set {
                worker.submit {
                    profile.afterRead.set {
                        entered.countDown()
                        check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    }
                    guard.refresh()
                }
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            }
            guard.refresh()
            assertEquals("a losing manual refresh returned before the newer worker publication", com.openautolink.carplaylab.telecom.HfpCallGuard.Status.UNKNOWN, guard.status)
            release.countDown()
            worker.submit {}.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals("the actual newer census publishes the connected conflict", com.openautolink.carplaylab.telecom.HfpCallGuard.Status.HFP_OWNED, guard.status)
        } finally { release.countDown(); guard.close() }
    }
    @Test @Config(sdk = [32,34])
    fun failedHfpAttemptPreservesExactActiveOwnerAndEndWhileBlockingNewAdmission() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(android.Manifest.permission.READ_PHONE_NUMBERS, android.Manifest.permission.BLUETOOTH_CONNECT)
        val adapter = app.getSystemService(android.bluetooth.BluetoothManager::class.java).adapter
        shadowOf(adapter).setState(android.bluetooth.BluetoothAdapter.STATE_ON)
        val prefs = AppPreferences::class.java.getDeclaredConstructor(DataStore::class.java).apply { isAccessible = true }.newInstance(Store())
        val controller = CarPlayCallController(app, prefs)
        val backend = ManagedTelecomExperiment.get(app)
        val guard = controller.javaClass.getDeclaredField("guard").apply { isAccessible = true }.get(controller) as com.openautolink.carplaylab.telecom.HfpCallGuard
        val listener = guard.javaClass.getDeclaredField("listener").apply { isAccessible = true }.get(guard) as android.bluetooth.BluetoothProfile.ServiceListener
        val profile = Profile().apply { device = adapter.getRemoteDevice("02:00:00:00:00:01") }
        val service = Robolectric.buildService(com.openautolink.carplaylab.telecom.ManagedCarPlayConnectionService::class.java).create().get()
        try {
            controller.prepare()
            val os = shadowOf(app.getSystemService(TelecomManager::class.java))
            os.enablePhoneAccount(backend.handle, true); controller.prepare(); os.setConnectionService(service)
            listener.onServiceConnected(16, profile); refreshFixtureGuard(guard)
            val reports = java.util.concurrent.CopyOnWriteArrayList<Int>()
            val key = ByteArray(32)
            val peer = com.shilapi.xcertplay.airplay.ControlCipher(key, key)
            val session = com.shilapi.xcertplay.airplay.AirPlaySession(java.net.Socket(),
                com.shilapi.xcertplay.airplay.AirPlayConfig("test", "id", "mac", "950.7.1", com.shilapi.xcertplay.airplay.AirPlayDisplayConfig(800,480)),
                com.shilapi.xcertplay.airplay.AirPlayIdentity.generate(), com.shilapi.xcertplay.airplay.PairingStore(), null,
                object : com.shilapi.xcertplay.airplay.AirPlaySessionListener {}, object : com.shilapi.xcertplay.airplay.AirPlayMediaHandler {})
            fun field(name: String) = session.javaClass.getDeclaredField(name).apply { isAccessible = true }
            val output = object : java.io.OutputStream() {
                override fun write(value: Int) = error("array writes expected")
                override fun write(bytes: ByteArray) {
                    val plain = peer.decrypt(bytes).data
                    val body = plain.copyOfRange(plain.toString(Charsets.ISO_8859_1).indexOf("\r\n\r\n") + 4, plain.size)
                    val command = com.shilapi.xcertplay.airplay.BplistCodec.decode(body) as Map<*,*>
                    assertEquals("hidSendReport", command["type"])
                    reports.add((command["hidReport"] as ByteArray).single().toInt())
                }
            }
            field("eventSocket").set(session, object : java.net.Socket() { override fun getOutputStream() = output })
            field("eventCipher").set(session, com.shilapi.xcertplay.airplay.ControlCipher(key, key))
            (field("authenticated").get(session) as java.util.concurrent.atomic.AtomicBoolean).set(true)
            val commands = java.util.concurrent.CopyOnWriteArrayList<com.shilapi.xcertplay.airplay.PhoneControl>()
            val link = backend.authenticatedLink(controlSender = { command, current ->
                val written = session.sendPhoneControl(command, current) == com.shilapi.xcertplay.airplay.PhoneControlOutcome.WRITTEN
                if (written) commands.add(command)
                written
            }) { true }!!
            link.handle(call()); shadowOf(android.os.Looper.getMainLooper()).idle()
            val connection = os.allowIncomingCall(os.onlyIncomingCall)!!
            connection.onAnswer(); backend.refresh()
            assertEquals(1, commands.size)
            link.handle(call(3)); link.handle(call(4)); shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(android.telecom.Connection.STATE_ACTIVE, connection.state)
            profile.state = android.bluetooth.BluetoothProfile.STATE_CONNECTING
            refreshFixtureGuard(guard); shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals("profile attempt must not retire an admitted call", android.telecom.Connection.STATE_ACTIVE, connection.state)
            assertTrue(backend.hasAdmittedCall)
            profile.state = android.bluetooth.BluetoothProfile.STATE_DISCONNECTED
            refreshFixtureGuard(guard); shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(android.telecom.Connection.STATE_ACTIVE, connection.state)
            profile.state = android.bluetooth.BluetoothProfile.STATE_CONNECTED
            refreshFixtureGuard(guard); shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals("unproven late HFP handover must preserve Active", android.telecom.Connection.STATE_ACTIVE, connection.state)
            assertEquals("HFP must not manufacture a remote command", 1, commands.size)
            connection.onDisconnect(); backend.refresh()
            assertEquals("End must retain the original sender", listOf(com.shilapi.xcertplay.airplay.PhoneControl.ANSWER, com.shilapi.xcertplay.airplay.PhoneControl.END), commands)
            assertEquals("real encrypted Hook/Drop press-release bytes", listOf(1, 0, 3, 0), reports)
            session.close()
            link.handle(call(0)); shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(android.telecom.Connection.STATE_DISCONNECTED, connection.state)
            assertFalse(backend.hasAdmittedCall)
        } finally { controller.close(); backend.disable(); shadowOf(android.os.Looper.getMainLooper()).idle(); service.onDestroy() }
    }
    @Test @Config(sdk = [32,34])
    fun lateConnectedPreservesOwnerWithoutManufacturingHandoverOrCommands() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(android.Manifest.permission.READ_PHONE_NUMBERS, android.Manifest.permission.BLUETOOTH_CONNECT)
        val adapter = app.getSystemService(android.bluetooth.BluetoothManager::class.java).adapter
        shadowOf(adapter).setState(android.bluetooth.BluetoothAdapter.STATE_ON)
        val prefs = AppPreferences::class.java.getDeclaredConstructor(DataStore::class.java).apply { isAccessible = true }.newInstance(Store())
        val controller = CarPlayCallController(app, prefs)
        val backend = ManagedTelecomExperiment.get(app)
        val guard = controller.javaClass.getDeclaredField("guard").apply { isAccessible = true }.get(controller) as com.openautolink.carplaylab.telecom.HfpCallGuard
        val listener = guard.javaClass.getDeclaredField("listener").apply { isAccessible = true }.get(guard) as android.bluetooth.BluetoothProfile.ServiceListener
        val profile = Profile().apply { device = adapter.getRemoteDevice("02:00:00:00:00:01") }
        val service = Robolectric.buildService(com.openautolink.carplaylab.telecom.ManagedCarPlayConnectionService::class.java).create().get()
        try {
            controller.prepare()
            val os = shadowOf(app.getSystemService(TelecomManager::class.java))
            os.enablePhoneAccount(backend.handle, true); controller.prepare(); os.setConnectionService(service)
            listener.onServiceConnected(16, profile); refreshFixtureGuard(guard)
            var rejects = 0
            val link = backend.authenticatedLink(rejectSender = { current -> if (current()) { rejects++; true } else false }) { true }!!
            link.handle(call()); shadowOf(android.os.Looper.getMainLooper()).idle()
            val connection = os.allowIncomingCall(os.onlyIncomingCall)!!
            profile.state = android.bluetooth.BluetoothProfile.STATE_CONNECTED
            refreshFixtureGuard(guard); shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(android.telecom.Connection.STATE_RINGING, connection.state)
            assertEquals(0, rejects)
            connection.onReject(); backend.refresh()
            assertEquals("unproven HFP handover must not withhold exact owner's Reject", 1, rejects)
            link.handle(call(0)); shadowOf(android.os.Looper.getMainLooper()).idle()
            for (state in listOf(android.bluetooth.BluetoothProfile.STATE_CONNECTED, android.bluetooth.BluetoothProfile.STATE_CONNECTING, android.bluetooth.BluetoothProfile.STATE_DISCONNECTING)) {
                profile.state = state; refreshFixtureGuard(guard)
                link.handle(call(suffix = (state + 2).toString())); shadowOf(android.os.Looper.getMainLooper()).idle()
                assertEquals("conflict escaped new-call gate", 1, os.allIncomingCalls.size)
                link.handle(call(0, (state + 2).toString())); shadowOf(android.os.Looper.getMainLooper()).idle()
            }
            assertTrue(com.openautolink.carplaylab.telecom.NativeCallDiagnostics.flush())
            val receipts = java.io.File(app.getExternalFilesDir(null), "openautolink/logs/native_call_controls.log").readLines()
            assertTrue("guard decision must survive manual export", receipts.any { it.contains("stage=native-policy actor=HFP") && it.contains("action=PRESERVED") })
            assertTrue(receipts.any { it.contains("stage=native-policy") && it.contains("action=BLOCKED") })
            // Genuine OS account revocation still retires this exact owner, with no remote command.
            profile.state = android.bluetooth.BluetoothProfile.STATE_DISCONNECTED; refreshFixtureGuard(guard)
            link.handle(call(suffix = "8")); shadowOf(android.os.Looper.getMainLooper()).idle()
            val revoked = os.allowIncomingCall(os.lastIncomingCall)!!
            os.enablePhoneAccount(backend.handle, false); backend.refresh(); shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(android.telecom.Connection.STATE_DISCONNECTED, revoked.state)
            revoked.onReject(); backend.refresh(); assertEquals(1, rejects)
            assertTrue(com.openautolink.carplaylab.telecom.NativeCallDiagnostics.flush())
            assertTrue(java.io.File(app.getExternalFilesDir(null), "openautolink/logs/native_call_controls.log").readText().contains("actor=AUTHORITY state=DISABLED"))
            val file = java.io.File(app.getExternalFilesDir(null), "openautolink/logs/native_call_controls.log")
            val bytes = file.readBytes()
            for (line in bytes.toString(Charsets.UTF_8).lines().filter { it.isNotEmpty() }) {
                assertNotNull(CarPlayAudioDiagnostics.sanitize(1, line))
                assertNull(com.openautolink.carplaylab.telecom.NativeCallDiagnostics.sanitize(line + " uuid=private"))
            }
            com.openautolink.app.diagnostics.LogUploadArchive.create(
                com.openautolink.app.diagnostics.LogUploadArchive.select(listOf(file), System.currentTimeMillis()), app.cacheDir).use { archive ->
                java.util.zip.ZipFile(archive.file).use { zip ->
                    assertArrayEquals(bytes, zip.getInputStream(zip.getEntry(file.name)).readBytes())
                }
            }
        } finally { controller.close(); backend.disable(); shadowOf(android.os.Looper.getMainLooper()).idle(); service.onDestroy() }
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
