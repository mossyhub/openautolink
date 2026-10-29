package com.openautolink.app.diagnostics

import android.Manifest
import com.openautolink.carplaylab.telecom.ManagedTelecomExperiment
import com.openautolink.carplaylab.telecom.TelecomAccountDiagnostics
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@Config(sdk = [32, 34], application = android.app.Application::class)
class TelecomAccountExportTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val experiment get() = ManagedTelecomExperiment.get(app)
    private val directory get() = File(app.getExternalFilesDir(null), "openautolink/logs")
    private val file get() = File(directory, TelecomAccountDiagnostics.FILE_NAME)
    @Before fun reset() { com.openautolink.app.ui.testing.resetNativeTelecomTestProcess(app) }
    @After fun cleanup() { com.openautolink.app.ui.testing.resetNativeTelecomTestProcess(app) }
    private fun manualZipMatchesSource() {
        // Match LogUploader's existing producer barriers before reading/selecting ANY log.
        // enable() completes the account read, not NativeCallDiagnostics' queued atomic write.
        assertTrue(com.openautolink.app.carplay.HostMediaDiagnosticSink.flush())
        assertTrue(com.openautolink.carplaylab.telecom.NativeCallDiagnostics.flush())
        val bytes = file.readBytes()
        val text = bytes.toString(Charsets.UTF_8)
        assertFalse(text.contains(experiment.handle.id)); assertFalse(text.contains(app.packageName))
        assertFalse(text.contains("sensitive"))
        assertTrue(bytes.size <= TelecomAccountDiagnostics.MAX_BYTES)
        file.setLastModified(1) // bounded terminal receipt survives generic six-hour retention
        LogUploadArchive.create(LogUploadArchive.select(directory.listFiles()!!.toList(), System.currentTimeMillis()), app.cacheDir).use { archive ->
            ZipFile(archive.file).use { zip ->
                val entry = zip.getEntry(file.name)
                assertNotNull("manual archive omitted terminal receipt", entry)
                assertArrayEquals(bytes, zip.getInputStream(entry).readBytes())
            }
        }
    }
    private fun nativeReceiptWorker(): java.util.concurrent.ThreadPoolExecutor =
        com.openautolink.carplaylab.telecom.NativeCallDiagnostics::class.java.getDeclaredField("worker")
            .apply { isAccessible=true }.get(null) as java.util.concurrent.ThreadPoolExecutor

    @Test fun queuedRealAccountAuthorityReceiptReproducesExactAtomicSnapshotFailureWithoutPhoneResume() {
        val native=com.openautolink.carplaylab.telecom.NativeCallDiagnostics
        assertTrue(native.flush())
        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_NUMBERS)
        experiment.enable();assertTrue(native.flush())
        val target=File(directory,native.FILE_NAME)
        assertTrue(target.readText().contains("actor=AUTHORITY state=DISABLED"))
        assertTrue(target.setLastModified(1))
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        nativeReceiptWorker().execute {entered.countDown();assertTrue(release.await(3,java.util.concurrent.TimeUnit.SECONDS))}
        assertTrue(entered.await(1,java.util.concurrent.TimeUnit.SECONDS))
        try {
            shadowOf(app).denyPermissions(Manifest.permission.READ_PHONE_NUMBERS)
            experiment.enable() // REAL readAuthority -> NativeCallDiagnostics.record, queued behind barrier.
            assertTrue(nativeReceiptWorker().queue.isNotEmpty())
            val plan=LogUploadArchive.select(listOf(target),System.currentTimeMillis())
            val first=java.util.concurrent.atomic.AtomicBoolean(true)
            val observed=object:File(target.path) {
                override fun lastModified():Long {
                    val original=target.lastModified()
                    if(first.compareAndSet(true,false)) {
                        // create already opened the original inode; complete the real atomic writer
                        // after its initial metadata read, before its post-copy identity check.
                        release.countDown();assertTrue(native.flush())
                    }
                    return original
                }
            }
            val held=plan.copy(selected=plan.selected.map {it.copy(source=observed)})
            val error=assertThrows(java.io.IOException::class.java) {LogUploadArchive.create(held,app.cacheDir).close()}
            assertEquals("log changed during snapshot",error.message)
            assertTrue(error.stackTrace.any {it.fileName=="LogUploadArchive.kt" && it.lineNumber==163})
            assertTrue(target.readText().contains("actor=AUTHORITY state=UNKNOWN"))
            assertFalse(target.readText().contains("phone-resume"))
        } finally {release.countDown();assertTrue(native.flush())}
    }

    @Test fun actualUploaderFlushesQueuedAccountAuthorityBeforeSelectingSnapshot() {
        val native=com.openautolink.carplaylab.telecom.NativeCallDiagnostics
        assertTrue(native.flush())
        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_NUMBERS)
        experiment.enable();assertTrue(native.flush())
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        nativeReceiptWorker().execute {entered.countDown();assertTrue(release.await(3,java.util.concurrent.TimeUnit.SECONDS))}
        assertTrue(entered.await(1,java.util.concurrent.TimeUnit.SECONDS))
        val worker=java.util.concurrent.Executors.newSingleThreadExecutor()
        val wire=java.io.ByteArrayOutputStream();val sent=java.util.concurrent.CountDownLatch(1)
        val http=object:java.net.HttpURLConnection(java.net.URL("https://example.invalid/upload")) {
            override fun connect() {};override fun disconnect() {};override fun usingProxy()=false
            override fun getOutputStream():java.io.OutputStream {sent.countDown();return wire}
            override fun getResponseCode()=200
        }
        try {
            shadowOf(app).denyPermissions(Manifest.permission.READ_PHONE_NUMBERS);experiment.enable()
            assertEquals(1,nativeReceiptWorker().queue.size)
            val result=worker.submit<UploadResult> {LogUploader(app,{http},{true},{true}).upload("https://example.invalid/upload","token-canary","fixture")}
            val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(1)
            while(nativeReceiptWorker().queue.size<2 && System.nanoTime()<deadline) Thread.sleep(2)
            assertEquals("actual uploader queued its flush behind the exact producer",2,nativeReceiptWorker().queue.size)
            assertEquals("no HTTP/selection completion before writer retirement",1L,sent.count)
            release.countDown();assertTrue(result.get(3,java.util.concurrent.TimeUnit.SECONDS) is UploadResult.Success)
            var bytes:ByteArray?=null
            java.util.zip.ZipInputStream(wire.toByteArray().inputStream()).use {zip ->
                while(true) {val entry=zip.nextEntry ?: break;if(entry.name==native.FILE_NAME) bytes=zip.readBytes()}
            }
            assertNotNull(bytes);assertTrue(bytes!!.toString(Charsets.UTF_8).contains("actor=AUTHORITY state=UNKNOWN"))
            assertArrayEquals(File(directory,native.FILE_NAME).readBytes(),bytes)
        } finally {release.countDown();assertTrue(native.flush());worker.shutdown();assertTrue(worker.awaitTermination(3,java.util.concurrent.TimeUnit.SECONDS))}
    }

    private fun assertSanitizedOwnRead(outcome: TelecomAccountDiagnostics.Outcome) {
        val line = file.readLines().single { it.contains("stage=FIRST_OWN_READ ") }
        val generation = line.substringAfter("generation=").toLong()
        assertEquals(TelecomAccountDiagnostics.sanitize(TelecomAccountDiagnostics.Stage.FIRST_OWN_READ.ordinal, outcome.ordinal, generation), line)
    }
    @Test fun deniedAndReturnedOwnReadReceiptsSurviveManualZipWithoutFileLogging() {
        shadowOf(app).denyPermissions(Manifest.permission.READ_PHONE_NUMBERS)
        experiment.enable()
        assertTrue("account producer did not persist receipt", file.isFile)
        assertSanitizedOwnRead(TelecomAccountDiagnostics.Outcome.READ_PERMISSION_REQUIRED)
        manualZipMatchesSource()
        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_NUMBERS)
        experiment.enable()
        assertTrue(file.readText().contains("stage=REGISTER outcome=RETURNED"))
        assertSanitizedOwnRead(TelecomAccountDiagnostics.Outcome.ACCOUNT_DISABLED)
        manualZipMatchesSource()
    }
    @Test fun inspectionProducerExportsSameFiniteFeatureAndGrantValues() {
        shadowOf(app).denyPermissions(Manifest.permission.READ_PHONE_NUMBERS)
        val census = experiment.census()
        fun number(value: Boolean?) = if (value == null) -1 else if (value) 1 else 0
        val generation = file.readLines().single { it.contains("stage=CENSUS_OWN_READ ") }.substringAfter("generation=")
        val expected = "telecom_inspect_v1 sdk=${census.sdk} connectionService=${number(census.connectionServiceFeature)} telecomFeature=${number(census.telecomFeature)} automotive=${number(census.automotiveFeature)} telecomPresent=${number(census.telecomPresent)} voiceCapable=${number(census.voiceCapable)} readGrant=0 ownOutcomeCode=${census.ownReadOutcome.ordinal} settingsCode=${census.settingsAvailability.ordinal} generation=$generation"
        assertTrue("Inspect feature producer did not persist same finite values", file.readLines().contains(expected))
        manualZipMatchesSource()
    }
    @Test fun nativeCallReceiptSurvivesBridgeRetirementAndManualZipWithOptionalFileLoggingOff() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_NUMBERS)
        experiment.enable()
        val manager = app.getSystemService(android.telecom.TelecomManager::class.java)
        shadowOf(manager).enablePhoneAccount(experiment.handle, true); experiment.refresh()
        experiment.enableNativeControls()
        val commands = mutableListOf<com.shilapi.xcertplay.airplay.PhoneControl>()
        val service = org.robolectric.Robolectric.buildService(com.openautolink.carplaylab.telecom.ManagedCarPlayConnectionService::class.java).create().get()
        shadowOf(manager).setConnectionService(service)
        val bridge = experiment.authenticatedLink({ command, fence ->
            assertTrue(fence()); commands.add(command); true
        }) { true }!!
        fun field(id: Int, bytes: ByteArray) = byteArrayOf(0, (bytes.size + 4).toByte(), 0, id.toByte()) + bytes
        val id = "00000000-0000-4000-8000-000000000077"
        bridge.handle(com.shilapi.xcertplay.iap2.wire.Iap2Frame(0x4155,
            field(4, id.toByteArray() + byteArrayOf(0)) + field(2, byteArrayOf(2)) +
                field(3, byteArrayOf(1)) + field(9, byteArrayOf(0)) + field(1, "private caller".toByteArray() + byteArrayOf(0))))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val native = shadowOf(manager).allowIncomingCall(shadowOf(manager).onlyIncomingCall)!!
        assertEquals(android.telecom.Connection.STATE_RINGING, native.state)
        assertEquals("private caller", native.callerDisplayName)
        bridge.handle(com.shilapi.xcertplay.iap2.wire.Iap2Frame(0x4155,
            field(4, id.toByteArray() + byteArrayOf(0)) + field(1, "Late 私密".toByteArray() + byteArrayOf(0))))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("Late 私密", native.callerDisplayName)
        assertEquals(android.telecom.Connection.STATE_RINGING, native.state)
        native.onAnswer(); experiment.refresh()
        for (status in listOf(3, 4)) {
            bridge.handle(com.shilapi.xcertplay.iap2.wire.Iap2Frame(0x4155,
                field(4, id.toByteArray() + byteArrayOf(0)) + field(2, byteArrayOf(status.toByte()))))
            shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        native.onDisconnect(); experiment.refresh()
        assertEquals(listOf(com.shilapi.xcertplay.airplay.PhoneControl.ANSWER, com.shilapi.xcertplay.airplay.PhoneControl.END), commands)
        bridge.close(); experiment.disable(); shadowOf(android.os.Looper.getMainLooper()).idle()
        service.onDestroy()
        assertTrue(com.openautolink.carplaylab.telecom.NativeCallDiagnostics.flush())
        val receipt = File(directory, "native_call_controls.log")
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
        while (!receipt.isFile && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue("finite native-call producer is not exported", receipt.isFile)
        val bytes = receipt.readBytes()
        val text = bytes.toString(Charsets.UTF_8)
        assertTrue(text.contains("action=OBSERVATION reason=OBSERVED status=2"))
        assertTrue(text.contains("stage=native-identity boundary=DECODE"))
        assertTrue(text.contains("stage=native-identity boundary=MERGE"))
        assertTrue(text.lines().any { it.contains("boundary=CREATE") && it.contains("native=NEW") && it.contains("result=MATCH") })
        assertTrue(text.lines().any { it.contains("boundary=UPDATE") && it.contains("native=RINGING") && it.contains("result=MATCH") })
        assertFalse(text.contains("私密"))
        assertTrue("manual export lost original latch provenance", text.contains("first=ANSWER_TRANSITION"))
        for (line in text.lineSequence().filter { it.isNotEmpty() }) {
            assertNotNull("actual app sanitizer rejected producer", com.openautolink.app.carplay.CarPlayAudioDiagnostics.sanitize(1, line))
        }
        assertFalse(text.contains(id)); assertFalse(text.contains("private caller"))
        assertTrue(bytes.size <= 16384)
        receipt.setLastModified(1)
        LogUploadArchive.create(LogUploadArchive.select(listOf(receipt), System.currentTimeMillis()), app.cacheDir).use { archive ->
            ZipFile(archive.file).use { zip ->
                val entry = zip.getEntry(receipt.name)
                assertNotNull("manual ZIP lost terminal native-call receipt after UI gone", entry)
                assertArrayEquals(bytes, zip.getInputStream(entry).readBytes())
            }
        }
    }
    @Test fun finiteNativeCallSchemasRejectPrivateSuffixUnknownEnumAndInvalidNumericFields() {
        val safe = "media stage=native-call action=REJECT reason=STATE status=255 pending=NONE calls=1 conference=0 queued=false"
        val sanitizer = com.openautolink.carplaylab.telecom.NativeCallDiagnostics
        assertEquals(safe, sanitizer.sanitize(safe))
        assertNotNull(com.openautolink.app.carplay.CarPlayAudioDiagnostics.sanitize(1, safe))
        for (cause in listOf("NONE", "ANSWER_TRANSITION", "TIMEOUT", "SENDER_WITHHELD", "STATE", "DIRECTION", "CONFERENCE", "MULTIPLE")) {
            assertEquals(safe + " first=$cause", sanitizer.sanitize(safe + " first=$cause"))
        }
        assertNull(sanitizer.sanitize(safe + " first=private"))
        for (bad in listOf(safe + " uuid=private", safe.replace("STATE", "private"), safe.replace("status=255", "status=256"),
            safe.replace("calls=1", "calls=17"), safe.replace("conference=0", "conference=2"), safe.replace("pending=NONE", "pending=private"))) {
            assertNull(sanitizer.sanitize(bad))
            assertNull(com.openautolink.app.carplay.CarPlayAudioDiagnostics.sanitize(1, bad))
        }
        repeat(100) {
            assertTrue(sanitizer.record(app, safe))
            assertTrue(sanitizer.flush()) // bounded writer backpressure is tested separately from storage bounds
        }
        val stored = File(directory, "native_call_controls.log")
        assertTrue(stored.length() <= 16384); assertTrue(stored.readLines().size <= 64)
    }
    @Test fun numericSanitizerRejectsUnknownCodesAndStoreRemainsBoundedAcrossRefreshes() {
        assertNull(TelecomAccountDiagnostics.sanitize(-1, 0, 1))
        assertNull(TelecomAccountDiagnostics.sanitize(0, 1000, 1))
        assertNull(TelecomAccountDiagnostics.sanitize(0, 0, Long.MAX_VALUE))
        shadowOf(app).denyPermissions(Manifest.permission.READ_PHONE_NUMBERS)
        repeat(100) { experiment.refresh() }
        assertTrue(file.length() <= TelecomAccountDiagnostics.MAX_BYTES)
        assertTrue(file.readLines().size <= TelecomAccountDiagnostics.Stage.entries.size + 1)
        manualZipMatchesSource()
    }
}
