package com.openautolink.app.carplay

import android.app.Application
import com.openautolink.app.diagnostics.*
import com.openautolink.app.transport.ControlMessage
import com.openautolink.carplaylab.intake.*
import com.openautolink.carplaylab.media.CarPlayDisplayPreferences
import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import com.shilapi.xcertplay.transport.Iap2IdentificationClient
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.ShadowStatFs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE, application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class CarPlayEvWireCaptureIntegrationTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val config = Iap2IdentificationConfig("private-fixture-name", "fixture", "fixture",
        "private-fixture-serial", "1", "1", 0)
    private val startRequest = Iap2Messages.buildRaw(0xA100) { void(21) }

    @Test fun wiredIdentifyReachesSendAndAcceptWhileTheActualFileWriterLockIsHeld() = withFileLogging { writer, _ ->
        val lock = FileLogWriter::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(writer)
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val usb = usbRuntime()
        try {
            val holder = pool.submit {
                synchronized(lock) {
                    held.countDown()
                    check(release.await(10, SECONDS))
                }
            }
            assertTrue(held.await(3, SECONDS))
            usb.createVehicleBootstrap(OalCarDataCapabilities(currentEnergy = true), CarPlayEvLogCapture.Link()).use { bootstrap ->
                try {
                    withSessions(tunnel = false) { csm, peer ->
                        val worker = pool.submit { identify(csm, bootstrap) }
                        assertTrue(peer.awaitReady(3000))
                        peer.send(Iap2Frame(0x1D00, byteArrayOf()))
                        val frame = checkNotNull(peer.recv(3000)) { "1D01 did not reach the peer while the writer was held" }
                        assertEquals(0x1D01, frame.messageId)
                        assertTrue(Iap2BodyReader.of(frame).group(21).has(21))
                        peer.send(Iap2Frame(0x1D02, byteArrayOf()))
                        worker.get(3, SECONDS)
                        assertFalse("the writer must still be held when Identify completes", holder.isDone)
                    }
                } finally { release.countDown() }
            }
        } finally {
            release.countDown()
            pool.shutdown()
            check(pool.awaitTermination(10, SECONDS))
            usb.close()
        }
        val entry = upload().getValue("carplay_ev_wire.log")
        assertTrue(entry.contains("carplay-ev-offer stage=prepared connectors=none dcW=none acW=none groups=21"))
        assertFalse(entry.contains("private-fixture"))
    }

    @Test fun failedWiredIdentifySendCanOnlyAppearAsPreparedInTheFirstZip() = withFileLogging { _, _ ->
        val attempts = mutableListOf<Iap2Frame>()
        val csm = identificationCsm { attempts += it; throw IOException("private-fixture-io") }
        usbRuntime().use { usb ->
            usb.createVehicleBootstrap(OalCarDataCapabilities(currentEnergy = true), CarPlayEvLogCapture.Link()).use { bootstrap ->
                assertThrows(IOException::class.java) { identify(csm, bootstrap) }
            }
        }
        assertEquals(0x1D01, attempts.single().messageId)
        val entry = upload().getValue("carplay_ev_wire.log")
        assertEquals(1, entry.lineSequence().count { "carplay-ev-offer stage=prepared" in it })
        assertFalse(entry.contains("carplay-ev-wire send="))
        assertFalse(entry.contains("private-fixture"))
    }

    @Test fun wiredFirstCompleteModelAndLatestStatusReachImmediateZipUnderOrdinaryPressure() = captureModel(wired = true)

    @Test fun wirelessFirstCompleteModelAndLatestStatusReachImmediateZipUnderOrdinaryPressure() = captureModel(wired = false)

    @Test fun captureIsBoundedByLinkAndStopsWithItsFileLoggingRun() = withFileLogging { writer, _ ->
        val base = Iap2IdentificationClient.identificationInformation(config)
        val offer = checkNotNull(OalEvWireDiagnostics.preparedOffer(OalCarDataIdentification.apply(base,
            OalCarDataCapabilities(currentEnergy = true))))
        repeat(9) { assertTrue(CarPlayEvLogCapture.Link().record(offer)) }
        val lines = upload().getValue("carplay_ev_wire.log").lineSequence().filter { it.isNotEmpty() }.toList()
        assertEquals("carplay_ev_wire_capture=1 links=8 evicted_links=1", lines.first())
        assertEquals(8, lines.drop(1).map { it.substringBefore(" carplay-ev-") }.distinct().size)

        writer.stop()
        assertFalse(CarPlayEvLogCapture.Link().record(offer))
        assertFalse(upload().containsKey("carplay_ev_wire.log"))
    }

    private fun captureModel(wired: Boolean) = withFileLogging { _, file ->
        withSessions(tunnel = true) { csm, peer -> captureModel(wired, file, csm, peer) }
    }

    private fun captureModel(wired: Boolean, file: File, csm: Iap2Session, peer: Iap2Session) {
        assertTrue(csm.awaitReady(3000)); assertTrue(peer.awaitReady(3000))
        val calibration = model()
        val caps = OalCarDataCapabilities(currentEnergy = true, evRoutingModel = calibration,
            vehicleDisplayName = "private-fixture-car", chargingConnectorMask = 5,
            maximumDcChargePowerWatts = 150000, maximumAcChargePowerWatts = 11500)
        var now = 1000L
        var chargeWh = 20_000L
        val clock = { now }
        val vehicle = mockk<OalCarPlayVehicleSnapshotAdapter>()
        every { vehicle.snapshot(any()) } answers { OalEvSnapshot(currentChargeWh = chargeWh, routingModel = calibration) }
        val ordinary = mutableListOf<String>()
        val reporter = CarPlayAudioDiagnosticReporter(1, { 0L }, ordinary::add)
        val writes = mutableListOf<Iap2Frame>()
        val link = CarPlayEvLogCapture.Link()
        val intake = OalCarPlayIntakeLink(false, vehicle, OalCarPlayGnssSnapshotAdapter(clock) { error("no GPS requested") },
            clock, CarPlayAppearanceBridge(MutableStateFlow(ControlMessage.VehicleData()), Dispatchers.IO, clock, 5000),
            { false }, { csm.sendAndAwaitWritten(it, 3000); writes += it }, reporter::record,
            frozenCapabilities = caps, evCapture = link)
        val offer = Iap2IdentificationClient.identificationInformation(config)
        val usb = if (wired) usbRuntime() else null
        val bootstrap = usb?.createVehicleBootstrap(caps, link)
        val exact = bootstrap?.wrap(intake) ?: intake
        try {
            val encodedOffer = exact.identification(offer)
            assertTrue(Iap2BodyReader.of(encodedOffer).group(21).has(21))
            val info = Iap2BodyReader.of(encodedOffer).group(20)
            assertEquals(150000L, info.u32(12)); assertEquals(11500L, info.u32(14))
            assertEquals("private-fixture-car", info.string(6))
            repeat(CarPlayAudioDiagnosticReporter.MAX_EVENTS_PER_SECOND) {
                reporter.record("carplay-car-data-offer location=false range=false current=true maximum=false")
            }
            assertEquals(CarPlayAudioDiagnosticReporter.MAX_EVENTS_PER_SECOND, ordinary.size)
            exact.handle(startRequest)
            assertTrue(writes.isEmpty())
            exact.authenticate()
            exact.handle(startRequest)
            assertEquals(0xA101, writes.single().messageId)
            val first = checkNotNull(peer.recv(3000))
            assertArrayEquals(writes.single().encodedFrame(), first.encodedFrame())
            val expectedModel = modelLines(Iap2BodyReader.of(first))
            assertTrue("exercise a vector longer than the ordinary logger message cap", expectedModel.any { it.length > 500 })
            now += 29_999
            exact.tick(false)
            assertEquals(1, writes.size)
            now++
            chargeWh--
            exact.tick(false)
            assertEquals(2, writes.size)
            val second = checkNotNull(peer.recv(3000))
            assertArrayEquals(writes.last().encodedFrame(), second.encodedFrame())
            assertEquals(19_999L, Iap2BodyReader.of(second).u32(22))
            assertFalse(ordinary.any { "carplay-ev-wire" in it || "carplay-ev-offer" in it })

            // No logcat, stop, sleeps, or synthetic log file: the real writer has an on-disk prefix
            // and an unflushed short INFO tail. EV capture does not depend on that buffer.
            assertTrue(file.readText().contains("already-on-disk"))
            assertFalse(file.readText().contains("still-buffered"))
            assertFalse(file.readText().contains("carplay-ev-wire"))
            val entries = upload()
            assertTrue(entries.getValue(file.name).contains("already-on-disk"))
            val lines = entries.getValue("carplay_ev_wire.log").lineSequence().filter { "carplay-ev-" in it }.toList()
            val owners = lines.map { it.substringBefore(" carplay-ev-") }.distinct()
            assertEquals("the encoded offer and writes belong to one exact link", 1, owners.size)
            assertEquals(listOf("carplay-ev-offer stage=prepared connectors=0,2 dcW=150000 acW=11500 groups=21",
                "carplay-ev-wire send=1 status=22:20000") + expectedModel + "carplay-ev-wire send=2 status=22:19999",
                lines.map { it.substringAfter("${owners.single()} ") })
            assertFalse(entries.values.any { "private-fixture" in it || "vehicle_parameters" in it })
        } finally {
            exact.close()
            usb?.close()
        }
    }

    private fun model(): OalEvRoutingModel {
        // Long (but legal) decimal spellings exercise the previous 500-char file cap.
        val steps = (0..20).joinToString(",") { if (it == 0) "0" else "0.${"0".repeat(100)}${it + 10}123456789012345" }
        val energy = (0..10).joinToString(",") { (6000 * it).toString() }
        return checkNotNull(OalEvRoutingModel.parse(
            """{"model_id":12582922,"vehicle_parameters":{"vehicle_energy_axis_wh":[$energy],"vehicle_charge_axis_w":[${List(11) { 100000 }.joinToString(",")}],"energy_w_per_h":[$steps],"efficiency_factor":0.91}}""",
            """{"model_id":12582921,"vehicle_parameters":{"vehicle_consumption_values_wh_per_m":[${List(10) { 0.2 }.joinToString(",")}],"vehicle_auxiliary_power_w":900,"vehicle_altitude_gain_consumption_wh_per_m":0.3,"vehicle_altitude_loss_consumption_wh_per_m":0.4}}""", 60000))
    }

    private fun modelLines(body: Iap2BodyReader): List<String> {
        val expectedKeys = mapOf(26 to listOf("energy" to "vehicle_energy_axis_wh", "watts" to "vehicle_charge_axis_w",
            "steps" to "energy_w_per_h", "eff" to "efficiency_factor"), 27 to listOf(
            "consumption" to "vehicle_consumption_values_wh_per_m", "aux" to "vehicle_auxiliary_power_w",
            "gain" to "vehicle_altitude_gain_consumption_wh_per_m", "loss" to "vehicle_altitude_loss_consumption_wh_per_m"))
        return expectedKeys.flatMap { (field, keys) ->
            val json = JSONObject(body.string(field))
            val values = json.getJSONObject("vehicle_parameters")
            keys.map { (label, wireKey) ->
                val array = values.optJSONArray(wireKey)
                val exact = if (array == null) values.get(wireKey).toString()
                    else (0 until array.length()).joinToString(",") { array.get(it).toString() }
                "carplay-ev-wire send=1 model=$field id=${json.getInt("model_id")} $label=$exact"
            }
        }
    }

    private fun usbRuntime() = OalUsbCarPlayRuntime(app, mockk(relaxed = true), CarPlayDisplayPreferences(), mockk(), {})

    private fun identificationCsm(send: (Iap2Frame) -> Unit): Iap2Session = mockk<Iap2Session>().also { csm ->
        every { csm.awaitReady(any()) } returns true
        every { csm.recv(any()) } returnsMany listOf(Iap2Frame(0x1D00, byteArrayOf()), Iap2Frame(0x1D02, byteArrayOf()))
        every { csm.send(any<Iap2Frame>(), any()) } answers { send(firstArg()) }
    }

    private fun identify(csm: Iap2Session, bootstrap: OalCarDataBootstrap) =
        Iap2IdentificationClient(csm, bootstrap::identification).identify(config, 8000, onOtherFrame = bootstrap::handle)

    private fun withSessions(tunnel: Boolean, block: (Iap2Session, Iap2Session) -> Unit) {
        val left = LinkedBlockingQueue<ByteArray>()
        val right = LinkedBlockingQueue<ByteArray>()
        fun stream(incoming: LinkedBlockingQueue<ByteArray>, outgoing: LinkedBlockingQueue<ByteArray>) =
            object : BlockingDuplexByteStream {
                override fun send(data: ByteArray) { outgoing.add(data.copyOf()) }
                override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? =
                    incoming.poll(timeoutMillis, MILLISECONDS)?.also { check(it.size <= maxBytes) }
                override fun close() { incoming.offer(byteArrayOf()); outgoing.offer(byteArrayOf()) }
            }
        fun open(stream: BlockingDuplexByteStream) =
            if (tunnel) Iap2Session.openTunnel(stream) else Iap2Session.open(stream)
        val a = open(stream(left, right))
        val b = open(stream(right, left))
        try { block(a, b) } finally { runCatching { a.close() }; runCatching { b.close() } }
    }

    private fun withFileLogging(block: (FileLogWriter, File) -> Unit) {
        val previous = DiagnosticLog.fileLogWriter
        val writer = FileLogWriter(app)
        val directory = File(app.getExternalFilesDir(null), "openautolink/logs")
        ShadowStatFs.registerStats(directory, 100000, 100000, 100000)
        var file: File? = null
        try {
            val opened = File(checkNotNull(writer.start()))
            file = opened
            DiagnosticLog.fileLogWriter = writer
            writer.write(LocalLogEntry(System.currentTimeMillis(), DiagnosticLevel.WARN, "fixture", "already-on-disk"))
            writer.write(LocalLogEntry(System.currentTimeMillis(), DiagnosticLevel.INFO, "fixture", "still-buffered"))
            block(writer, opened)
        } finally {
            DiagnosticLog.fileLogWriter = previous
            writer.stop()
            file?.delete()
            ShadowStatFs.unregisterStats(directory)
        }
    }

    private fun upload(): Map<String, String> {
        val body = ByteArrayOutputStream()
        val http = object : HttpURLConnection(URL("https://example.invalid/upload")) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() = Unit
            override fun getOutputStream() = body
            override fun getResponseCode() = 200
        }
        val result = LogUploader(app, { http }, { true }, { true }, { MediaStoreStatus.UNAVAILABLE })
            .upload("https://example.invalid/upload", "fixture-token", "fixture")
        assertTrue(result.toString(), result is UploadResult.Success)
        return buildMap {
            ZipInputStream(body.toByteArray().inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    put(entry.name, zip.readBytes().toString(Charsets.UTF_8))
                }
            }
        }
    }
}
