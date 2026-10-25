package com.openautolink.app.diagnostics

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class BluetoothReconnectProcessTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun initializationAndRequestStartReadFailureNeverEscapeOrRegisterObservation() = kotlinx.coroutines.runBlocking {
        val volume = temp.newFolder("unreadable-volume")
        val logs = java.io.File(volume, "openautolink/logs").apply { mkdirs() }
        val good = java.io.File(logs, "bluetooth_reconnect_run_0000000000000000001.log").apply {
            writeText("coverage terminal_durable=true persistence=complete\n")
        }
        val bytes = good.readBytes()
        val bad = java.io.File(logs, "bluetooth_reconnect_run_0000000000000000002.log").apply { mkdir() }
        val held = java.io.File(bad, "held").apply { writeText("private evidence") }
        val app = mockk<Application>(relaxed = true)
        every { app.getExternalFilesDir(null) } returns volume
        val uncaught = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val job = kotlinx.coroutines.SupervisorJob()
        val scope = CoroutineScope(job + kotlinx.coroutines.Dispatchers.Unconfined + kotlinx.coroutines.CoroutineExceptionHandler { _, error -> uncaught.add(error) })
        try {
            BluetoothReconnectService.initialize(app, scope)
            val process = requireNotNull(BluetoothReconnectService.process)
            kotlinx.coroutines.withTimeout(5000) {
                while (!process.recorder.status.value.startsWith("Storage failed") && uncaught.isEmpty()) kotlinx.coroutines.delay(10)
            }
            assertTrue("Initialization filesystem failure must not escape launch", uncaught.isEmpty())
            assertFalse(process.recorder.flush(2000))
            process.requestStart(true, true)
            kotlinx.coroutines.withTimeout(5000) { while (process.busy.value) kotlinx.coroutines.delay(10) }
            assertTrue(uncaught.isEmpty()); assertFalse(process.recorder.isRunning())
            assertEquals("Start unavailable — storage/previous save pending; no new observation", process.notice.value)
            assertTrue(process.coverage.value.contains("not registered"))
            verify(exactly = 0) { app.registerReceiver(any(), any(), any(), any(), any()) }
            verify(exactly = 0) { app.registerActivityLifecycleCallbacks(any()) }
            process.stop(); assertFalse(process.recorder.flush(2000))
            assertArrayEquals(bytes, good.readBytes())
            process.requestDelete()
            kotlinx.coroutines.withTimeout(5000) { while (process.busy.value) kotlinx.coroutines.delay(10) }
            assertEquals("Delete incomplete — retry; no new observation", process.notice.value)
            assertFalse(process.recorder.status.value.contains("deleted"))
            assertEquals("private evidence", held.readText())
        } finally { BluetoothReconnectService.close(); job.cancel() }
    }
    @Test fun receiverPermissionExportFlagsDeadlineAndRevocationAcrossSupportedApis() = runTest {
        for (sdk in listOf(32, 33, 34)) {
            val app = mockk<Application>(relaxed = true)
            val manager = mockk<BluetoothManager>()
            var granted = true
            every { app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) } answers { if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED }
            every { app.getSystemService(BluetoothManager::class.java) } returns manager
            every { manager.adapter } returns mockk<BluetoothAdapter>()
            val callback = slot<BroadcastReceiver>()
            val lifecycle = mutableListOf<Application.ActivityLifecycleCallbacks>()
            every { app.registerActivityLifecycleCallbacks(capture(lifecycle)) } just Runs
            every { app.registerReceiver(capture(callback), any(), Manifest.permission.BLUETOOTH_CONNECT, null, Context.RECEIVER_EXPORTED) } returns null
            every { app.registerReceiver(capture(callback), any(), Manifest.permission.BLUETOOTH_CONNECT, null) } returns null
            val recorder = BluetoothReconnectRecorder(temp.newFolder("api$sdk")).apply { clock = { testScheduler.currentTime } }
            assertTrue(recorder.start(true))
            val process = BluetoothReconnectProcess(app, recorder, backgroundScope, sdk)
            process.arm(); runCurrent()
            if (sdk >= 33) verify(exactly = 1) { app.registerReceiver(any(), any(), Manifest.permission.BLUETOOTH_CONNECT, null, Context.RECEIVER_EXPORTED) }
            else verify(exactly = 1) { app.registerReceiver(any(), any(), Manifest.permission.BLUETOOTH_CONNECT, null) }
            granted = false
            lifecycle.toList().forEach { it.onActivityResumed(mockk()) }
            assertEquals("permission_missing", process.coverage.value)
            // Retired registration cannot parse a private/malformed late payload.
            val late = mockk<Intent>()
            callback.captured.onReceive(app, late)
            verify(exactly = 0) { late.action }
            advanceTimeBy(BluetoothReconnectRecorder.DURATION_MS + 1); runCurrent()
            assertFalse(recorder.isRunning())
            verify(exactly = 1) { app.unregisterReceiver(any()) }
            verify(exactly = 2) { app.unregisterActivityLifecycleCallbacks(any()) }
            assertTrue(recorder.flush(2000)); recorder.close()
        }
    }

    @Test fun eightOpaqueDevicesCapacityPowerEpochAndNoPrivateGetter() = runTest {
        val app = mockk<Application>(relaxed = true)
        val manager = mockk<BluetoothManager>()
        every { app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) } returns PackageManager.PERMISSION_GRANTED
        every { app.getSystemService(BluetoothManager::class.java) } returns manager
        every { manager.adapter } returns mockk<BluetoothAdapter>()
        val callback = slot<BroadcastReceiver>()
        every { app.registerReceiver(capture(callback), any(), any(), any(), any()) } returns null
        val recorder = BluetoothReconnectRecorder(temp.root)
        recorder.start(true)
        val process = BluetoothReconnectProcess(app, recorder, backgroundScope, 33)
        process.arm(); runCurrent()
        val devices = (0..9).map { mockk<BluetoothDevice>() }
        fun profile(device: BluetoothDevice): Intent = mockk {
            every { action } returns "android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED"
            every { getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) } returns device
            every { getIntExtra(BluetoothProfile.EXTRA_STATE, any()) } returns 2
            every { getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, any()) } returns 1
        }
        devices.forEach { callback.captured.onReceive(app, profile(it)) }
        assertEquals("capacity", process.coverage.value)
        val power = mockk<Intent> {
            every { action } returns BluetoothAdapter.ACTION_STATE_CHANGED
            every { getIntExtra(BluetoothAdapter.EXTRA_STATE, any()) } returns BluetoothAdapter.STATE_ON
        }
        callback.captured.onReceive(app, power)
        callback.captured.onReceive(app, profile(devices.first()))
        process.stop(); assertTrue(recorder.flush(2000))
        val text = temp.root.listFiles()!!.single { it.extension == "log" }.readText()
        assertEquals(9, text.lines().count { it.contains("kind=hfp-client state=connected") })
        assertFalse(text.contains("device=9"))
        val firstDeviceRows = text.lines().filter { it.contains("device=1 kind=hfp-client") }
        assertEquals(2, firstDeviceRows.size)
        assertNotEquals(firstDeviceRows[0].substringAfter("epoch=").substringBefore(" "), firstDeviceRows[1].substringAfter("epoch=").substringBefore(" "))
        devices.forEach { device -> verify(exactly = 0) { device.name }; verify(exactly = 0) { device.address } }
        recorder.close()
    }
    @Test fun failedAdmittedBatchSurvivesTerminalFreshProcessAndActualManualZip() = runTest {
        val volume = temp.newFolder("loss-volume")
        val logs = java.io.File(volume, "openautolink/logs").apply { mkdirs() }
        val recorder = BluetoothReconnectRecorder(logs)
        assertTrue(recorder.start(true)); assertTrue(recorder.flush(2000))
        val saved = logs.listFiles()!!.single { it.extension == "log" }
        // Fail the real atomic output boundary, not an exception from beforeWrite.
        val pending = java.io.File(logs, saved.name + ".pending").apply { mkdirs() }
        val obstacle = java.io.File(pending, "held").apply { writeText("fixture") }
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        var writes = 0
        recorder.beforeWrite = {
            if (++writes == 1) { entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
            else { obstacle.delete(); pending.delete() }
        }
        assertTrue(recorder.bookmark("returned_to_car"))
        assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(recorder.bookmark("oal_selected")); recorder.stop()
        release.countDown(); assertFalse(recorder.flush(2000))
        assertTrue(recorder.status.value.startsWith("Storage failed"))
        val context = mockk<Context>()
        every { context.getExternalFilesDirs(null) } returns arrayOf(volume)
        every { context.cacheDir } returns temp.newFolder("loss-cache")
        mockkObject(OalLog)
        every { OalLog.i(any(), any()) } just Runs
        every { OalLog.w(any(), any()) } just Runs
        every { OalLog.e(any(), any()) } just Runs
        val processField = BluetoothReconnectService::class.java.getDeclaredField("process").apply { isAccessible = true }
        suspend fun uploadWith(owner: BluetoothReconnectRecorder): Map<String, ByteArray> {
            processField.set(null, BluetoothReconnectProcess(mockk(relaxed = true), owner, backgroundScope, 33))
            val wire = java.io.ByteArrayOutputStream()
            val http = object : java.net.HttpURLConnection(java.net.URL("https://example.invalid/upload")) {
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getOutputStream() = wire
                override fun getResponseCode() = 200
            }
            assertTrue(LogUploader(context, { http }, { true }).upload("https://example.invalid/upload", "fixture", "fixture") is UploadResult.Success)
            val members = mutableMapOf<String, ByteArray>()
            java.util.zip.ZipInputStream(wire.toByteArray().inputStream()).use { zip ->
                while (true) { val entry = zip.nextEntry ?: break; members[entry.name] = zip.readBytes() }
            }
            return members
        }
        try {
            val first = uploadWith(recorder)
            assertTrue(first.getValue("upload_manifest.log").toString(Charsets.UTF_8).contains("bluetooth_flush_confirmed=false"))
            val text = first.getValue(saved.name).toString(Charsets.UTF_8)
            assertTrue("Admitted lost row needs durable per-run census in real manual ZIP", text.contains("persistence=incomplete attempted=4 saved=3 lost=1 failed_batches=1 terminal_durable=true"))
            assertFalse(text.contains("state=returned_to_car"))
            assertTrue(text.contains("state=oal_selected"))
            recorder.close()
            val fresh = BluetoothReconnectRecorder(logs)
            assertTrue(fresh.recoverPreviousRuns())
            assertFalse(fresh.status.value.startsWith("Saved"))
            assertTrue(fresh.start(true)); fresh.stop(); assertTrue(fresh.flush(2000))
            val afterReset = uploadWith(fresh)
            assertArrayEquals(saved.readBytes(), afterReset[saved.name])
            assertTrue(afterReset.getValue(saved.name).toString(Charsets.UTF_8).contains("persistence=incomplete attempted=4 saved=3 lost=1 failed_batches=1 terminal_durable=true"))
            fresh.close()
        } finally { processField.set(null, null); unmockkObject(OalLog); release.countDown(); recorder.close() }
    }

    @Test fun realReceiverPreSessionSurvivesStopAndRejectsRetiredCallback() = runTest {
        val type = runCatching { Class.forName("com.openautolink.app.diagnostics.BluetoothReconnectProcess") }.getOrNull()
        assertNotNull("Missing process-owned actual receiver to durable recorder wiring", type)
        val app = mockk<Application>(relaxed = true)
        val manager = mockk<BluetoothManager>()
        every { app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) } returns PackageManager.PERMISSION_GRANTED
        every { app.getSystemService(BluetoothManager::class.java) } returns manager
        every { manager.adapter } returns mockk<BluetoothAdapter>()
        val callback = slot<BroadcastReceiver>()
        every { app.registerReceiver(capture(callback), any(), any(), any(), any()) } returns null
        val volume = temp.newFolder("volume")
        val logs = java.io.File(volume, "openautolink/logs").apply { mkdirs() }
        val recorder = BluetoothReconnectRecorder(logs)
        assertTrue(recorder.start(true))
        val process = type!!.getConstructor(Application::class.java, BluetoothReconnectRecorder::class.java, CoroutineScope::class.java, Int::class.javaPrimitiveType)
            .newInstance(app, recorder, backgroundScope, 33)
        type.getMethod("arm").invoke(process)
        runCurrent()
        val device = mockk<BluetoothDevice>()
        val intent = mockk<Intent> {
            every { action } returns "android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED"
            every { getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) } returns device
            every { getIntExtra(BluetoothProfile.EXTRA_STATE, any()) } returns 2
            every { getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, any()) } returns 1
        }
        callback.captured.onReceive(app, intent)
        val service = runCatching { Class.forName("com.openautolink.app.diagnostics.BluetoothReconnectService") }.getOrNull()
        assertNotNull("Application recorder and actual USB/wireless metadata links must share process ownership", service)
        val processField = service!!.getDeclaredField("process").apply { isAccessible = true }
        processField.set(null, process)
        try {
            repeat(2) {
                val delegate = mockk<com.openautolink.carplaylab.metadata.RuntimeMetadataLink>(relaxed = true)
                val link = com.openautolink.app.carplay.CarPlayBluetoothDiagnosticLink(delegate, { null }, {})
                link.authenticate(); link.revoke(); link.close()
            }
        } finally { processField.set(null, null) }
        type.getMethod("stop").invoke(process)
        runCurrent()
        callback.captured.onReceive(app, intent)
        assertTrue(recorder.flush(2000))
        val text = logs.listFiles()!!.single { it.extension == "log" }.readText()
        assertEquals(1, text.lines().count { it.contains("kind=a2dp-sink state=connected") })
        assertTrue(text.contains("device=1 kind=a2dp-sink"))
        assertEquals(2, text.lines().count { it.contains("kind=session state=authenticated") })
        assertEquals(2, text.lines().count { it.contains("kind=session state=retired") })
        verify(exactly = 1) { app.unregisterReceiver(any()) }
        verify(exactly = 0) { device.name }
        verify(exactly = 0) { device.address }
        // Actual manual uploader + archive, with OS receiver and HTTP boundary mocks only.
        val saved = logs.listFiles()!!.single { it.extension == "log" }
        saved.setLastModified(System.currentTimeMillis() - 8 * 3_600_000L)

        val context = mockk<Context>()
        every { context.getExternalFilesDirs(null) } returns arrayOf(volume)
        every { context.cacheDir } returns temp.newFolder("cache")
        mockkObject(OalLog)
        every { OalLog.i(any(), any()) } just Runs
        every { OalLog.w(any(), any()) } just Runs
        every { OalLog.e(any(), any()) } just Runs
        val wire = java.io.ByteArrayOutputStream()
        val http = object : java.net.HttpURLConnection(java.net.URL("https://example.invalid/upload")) {
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun getOutputStream() = wire
            override fun getResponseCode() = 200
        }
        processField.set(null, process)
        try {
            val result = LogUploader(context, { http }, { true }).upload("https://example.invalid/upload", "fixture", "fixture")
            assertTrue("Pre-session completed recorder run must survive ordinary six-hour age cutoff", result is UploadResult.Success)
            val members = mutableMapOf<String, ByteArray>()
            java.util.zip.ZipInputStream(wire.toByteArray().inputStream()).use { zip ->
                while (true) { val entry = zip.nextEntry ?: break; members[entry.name] = zip.readBytes() }
            }
            assertArrayEquals(saved.readBytes(), members[saved.name])
            assertTrue(members.getValue("upload_manifest.log").toString(Charsets.UTF_8).contains("bluetooth_flush_confirmed=true"))
        } finally { processField.set(null, null); unmockkObject(OalLog) }
        recorder.close()
    }
}
