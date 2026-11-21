package com.openautolink.app.diagnostics

import com.openautolink.app.transport.ControlMessage
import com.openautolink.app.transport.VehiclePropertyObservation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class EvContributionHardeningTest {
    private fun dir(): File = Files.createTempDirectory("ev-hardening").toFile()
    private fun vehicleLine(vehicleClass: String = "0123456789abcdef") =
        """{"schema":2,"type":"vehicle","elapsedBucketS":60,"batteryWh":50000,"batteryPct":50,"rangeKm":200.0,"distanceM":42,"speedKmh":10.0,"capacityBandKwh":80,"chargeState":1,"chargeRateW":0.0,"chargePortConnected":false,"gearRaw":8,"ignition":4,"energyBasis":"absolute-wh","estimatorRevision":"rev2","vehicleClass":"$vehicleClass"}"""

    @Test fun `vehicle identity is normalized frozen non VIN and changes split drives`() {
        val first = EvVehicleCalibrationIdentity.from(ControlMessage.VehicleData(
            carMake = "  Chevrolet ", carModel = " Blazer EV ", carYear = "2024", evBatteryCapacityWh = 85_100f,
        ))!!
        val same = EvVehicleCalibrationIdentity.from(ControlMessage.VehicleData(
            carMake = "chevrolet", carModel = "blazer   ev", carYear = " 2024 ", evBatteryCapacityWh = 89_900f,
        ))!!
        val changed = EvVehicleCalibrationIdentity.from(ControlMessage.VehicleData(
            carMake = "Chevrolet", carModel = "Blazer EV", carYear = "2024", evBatteryCapacityWh = 91_000f,
        ))!!
        assertEquals(first, same)
        assertNotEquals(first, changed)
        assertTrue(first.pseudonymousLabel.startsWith("ev-class-"))
        assertFalse(first.frozenKey.contains("VIN", true))
        assertFalse(first.pseudonymousLabel.contains("Chevrolet", true))
    }

    @Test fun `continuous identity rollover closes A and creates distinct immutable B`() {
        var ids = 0
        val owner = EvContributionDriveIdentityOwner { "drive-${++ids}" }
        val q = EvContributionQueue(dir(), 100_000, 8)
        val a = EvVehicleCalibrationIdentity.from(ControlMessage.VehicleData(
            carMake = "Maker", carModel = "Model", carYear = "2024", evBatteryCapacityWh = 80_000f,
        ))!!
        val b = EvVehicleCalibrationIdentity.from(ControlMessage.VehicleData(
            carMake = "Maker", carModel = "Model", carYear = "2024", evBatteryCapacityWh = 90_000f,
        ))!!
        val started = mutableMapOf<String, Long>()
        fun tick(identity: EvVehicleCalibrationIdentity, at: Long) {
            val transition = owner.observe(identity)
            transition.closeId?.let { q.close(it, at) }
            val startedAt = started.getOrPut(transition.activeId) { at }
            q.append(transition.activeId, startedAt, vehicleLine(identity.pseudonymousLabel.removePrefix("ev-class-")), "owner", identity.pseudonymousLabel)
        }
        tick(a, 1); tick(a, 2); tick(b, 3)
        q.close(owner.close()!!, 4)

        assertEquals(listOf("drive-1", "drive-2"), q.pending().map { it.id })
        assertEquals(2, q.pending().map { it.vehicleLabel }.distinct().size)
        assertTrue(q.pending().none { it.vehicleLabel.contains("VIN", true) })
    }

    @Test fun `strict record schema rejects wrong types ranges enums and free text`() {
        assertEquals(vehicleLine(), EvContributionPrivacy.requireAllowedJsonLine(vehicleLine()))
        val forecast = """{"schema":2,"type":"forecast","elapsedBucketS":60,"forecastWh":49000,"forecastDistanceM":12000,"forecastQuality":2,"vehicleClass":"0123456789abcdef"}"""
        assertEquals(forecast, EvContributionPrivacy.requireAllowedJsonLine(forecast))
        val bad = listOf(
            """{"schema":"2","type":"vehicle"}""",
            """{"schema":2,"type":"vehicle","batteryPct":101}""",
            """{"schema":2,"type":"vehicle","speedKmh":"fast"}""",
            """{"schema":2,"type":"vehicle","energyBasis":"free text"}""",
            """{"schema":2,"type":"vehicle","reason":"whatever"}""",
            """{"schema":2,"type":"unknown","event":"anything"}""",
        )
        bad.forEach { line -> assertThrows(line, IllegalArgumentException::class.java) { EvContributionPrivacy.requireAllowedJsonLine(line) } }
    }

    @Test fun `closed drive rejects every later append and pending owner cannot rotate`() {
        val q = EvContributionQueue(dir(), 100_000, 8)
        q.append("drive", 1, vehicleLine(), "credential-a")
        q.close("drive", 2)
        assertThrows(IllegalStateException::class.java) { q.append("drive", 1, vehicleLine(), "credential-a") }
        var sent = false
        val uploader = EvContributionUploader(q) { _, _ -> sent = true; error("must not send") }
        assertEquals(EvContributionUploader.Outcome.OWNER_MISMATCH, uploader.uploadOldest(10, "credential-b"))
        assertFalse(sent)
        assertEquals(1, q.pending().size)
    }

    @Test fun `delete recursively removes open closed temp ack quarantine orphan and corrupt metadata bytes`() {
        val root = dir(); val q = EvContributionQueue(root, 1_000_000, 100)
        q.append("open", 1, vehicleLine(), "owner")
        q.append("closed", 2, vehicleLine(), "owner"); q.close("closed", 3)
        File(root, "crash.zip.tmp").writeBytes(byteArrayOf(1, 2, 3))
        File(root, "accepted.zip.acked").writeBytes(byteArrayOf(4))
        File(root, "orphan.zip.meta").writeText("broken")
        File(root, "quarantine").mkdirs()
        File(root, "quarantine/bad_422.zip").writeBytes(byteArrayOf(5, 6))
        val bytesBefore = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

        val result = q.deleteAllArtifacts()

        assertTrue(result.files >= 6)
        assertTrue(result.bytes >= bytesBefore)
        assertEquals(0, result.failures)
        assertTrue(root.walkTopDown().none { it.isFile })
    }

    @Test fun `one retention policy bounds open closed temporary acknowledged quarantine and corrupt artifacts`() {
        val root = dir(); val q = EvContributionQueue(root, maxBytes = 1_000_000, maxFiles = 3, maxAgeMs = 1_000_000)
        q.append("open", 1, vehicleLine(), "owner")
        File(root, "crash.zip.tmp").writeBytes(ByteArray(10)); File(root, "crash.zip.tmp").setLastModified(2)
        File(root, "quarantine").mkdirs(); File(root, "quarantine/bad.zip").writeBytes(ByteArray(10)); File(root, "quarantine/bad.zip").setLastModified(3)
        File(root, "orphan.zip.meta").writeText("bad"); File(root, "orphan.zip.meta").setLastModified(4)

        q.enforceRetention(10)

        assertTrue(q.storageUsage().units <= 3)
        assertTrue(q.counters().evicted > 0)
        assertTrue(q.counters().quarantined > 0)
    }

    @Test fun `stale blocked response is disconnected and cannot mutate queue`() {
        val q = EvContributionQueue(dir(), 100_000, 8)
        q.append("drive", 1, vehicleLine(), "owner"); q.close("drive", 2)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val disconnected = CountDownLatch(1)
        val connection = object : HttpURLConnection(URL("https://logs.example/upload")) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() { disconnected.countDown(); release.countDown() }
            override fun getOutputStream() = java.io.ByteArrayOutputStream()
            override fun getResponseCode(): Int { entered.countDown(); release.await(5, TimeUnit.SECONDS); return 200 }
            override fun getInputStream() = "{\"ok\":true,\"sha256\":\"${q.pending().single().sha256}\"}".byteInputStream()
        }
        val transport = EvContributionHttpTransport { connection }
        val fence = EvContributionGenerationFence(); val generation = fence.snapshot()
        var outcome: EvContributionUploader.Outcome? = null
        val worker = thread {
            outcome = EvContributionUploader(q, mayMutate = { fence.isCurrent(generation) }) { file, _ ->
                transport.send("https://logs.example/upload", "token", "ev-class-test", file)
            }.uploadOldest(10, "owner")
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        fence.invalidate(); transport.cancel(); worker.join(5_000)

        assertTrue(disconnected.await(1, TimeUnit.SECONDS))
        assertEquals(EvContributionUploader.Outcome.CANCELLED, outcome)
        assertEquals(1, q.pending().size)
        assertEquals(0, q.pending().single().attempts)
    }

    @Test fun `generation invalidation at mutation barrier leaves queue metadata untouched`() {
        val q = EvContributionQueue(dir(), 100_000, 8)
        q.append("drive", 1, vehicleLine(), "owner"); q.close("drive", 2)
        val gate = EvContributionLifecycleGate()
        val lease = gate.admitUpload { true }!!
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        var outcome: EvContributionUploader.Outcome? = null
        val worker = thread {
            outcome = EvContributionUploader(
                q,
                mayMutate = { gate.isCurrent(lease) },
                mutateIfCurrent = { action ->
                    entered.countDown(); release.await(5, TimeUnit.SECONDS)
                    gate.withCurrent(lease, action)
                },
            ) { _, _ -> EvContributionUploader.Response(422, "{}") }.uploadOldest(10, "owner")
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        gate.invalidate(); release.countDown(); worker.join(5_000)

        assertEquals(EvContributionUploader.Outcome.CANCELLED, outcome)
        assertEquals(1, q.pending().size)
        assertEquals(0, q.pending().single().attempts)
        assertEquals(0, q.counters().quarantined)
    }

    private fun assertSafetyRevocationWinsAcceptance(revoke: (EvFreshParkGate) -> Long) {
        val q = EvContributionQueue(dir(), 100_000, 8)
        q.append("a", 1, vehicleLine(), "owner"); q.close("a", 2)
        q.append("b", 3, vehicleLine(), "owner"); q.close("b", 4)
        val lifecycle = EvContributionLifecycleGate()
        val lease = lifecycle.admitUpload { true }!!
        val safety = EvFreshParkGate()
        var nowElapsed = 1_000L
        val parked = VehiclePropertyObservation(
            timestampElapsedNanos = nowElapsed * 1_000_000,
            receivedElapsedMs = nowElapsed,
            status = 0,
            registrationGeneration = 11,
            propertyId = 0x11400400,
            subscriptionActive = true,
            sequence = 1,
        )
        assertTrue(safety.observe(ControlMessage.VehicleData(
            gearRaw = 4,
            evObservationMetadata = mapOf("GEAR_SELECTION" to parked),
            vhalRegistrationGeneration = 11,
        ), nowElapsed))
        val oldCheckPassed = CountDownLatch(1)
        val allowMutation = CountDownLatch(1)
        val sent = mutableListOf<String>()
        var outcome: EvContributionUploader.Outcome? = null
        val worker = thread {
            outcome = EvContributionDrainLoop(q) { queue ->
                EvContributionUploader(
                    queue,
                    mayTransmit = { safety.authorization(nowElapsed) },
                    mayMutate = { true },
                    mutateIfCurrent = { action ->
                        oldCheckPassed.countDown()
                        allowMutation.await(5, TimeUnit.SECONDS)
                        lifecycle.withCurrent(lease) {
                            safety.mutateIfAuthorized(nowElapsed, action)
                                ?: EvContributionUploader.Outcome.CANCELLED
                        }
                    },
                ) { _, id ->
                    sent += id
                    val entry = queue.pending().first { it.id == id }
                    EvContributionUploader.Response(200, "{\"ok\":true,\"sha256\":\"${entry.sha256}\"}")
                }
            }.drainEligible(10, "owner")
        }
        assertTrue(oldCheckPassed.await(5, TimeUnit.SECONDS))
        nowElapsed = revoke(safety)
        allowMutation.countDown()
        worker.join(5_000)

        assertEquals(EvContributionUploader.Outcome.CANCELLED, outcome)
        assertEquals(listOf("a"), sent)
        assertEquals(listOf("a", "b"), q.pending().map { it.id })
        assertEquals(0, q.counters().accepted)
    }

    @Test fun `drive revocation ordered after old check wins atomic queue acceptance`() {
        assertSafetyRevocationWinsAcceptance { safety ->
            safety.observe(ControlMessage.VehicleData(
                gearRaw = 8,
                evObservationMetadata = mapOf("GEAR_SELECTION" to VehiclePropertyObservation(
                    timestampElapsedNanos = 1_001_000_000,
                    receivedElapsedMs = 1_001,
                    status = 0,
                    registrationGeneration = 11,
                    propertyId = 0x11400400,
                    subscriptionActive = true,
                    sequence = 2,
                )),
                vhalRegistrationGeneration = 11,
            ), 1_001)
            1_001L
        }
    }

    @Test fun `unavailable revocation ordered after old check wins atomic queue acceptance`() {
        assertSafetyRevocationWinsAcceptance { safety ->
            safety.observe(ControlMessage.VehicleData(
                gearRaw = 4,
                evObservationMetadata = mapOf("GEAR_SELECTION" to VehiclePropertyObservation(
                    timestampElapsedNanos = 1_001_000_000,
                    receivedElapsedMs = 1_001,
                    status = 1,
                    registrationGeneration = 11,
                    propertyId = 0x11400400,
                    subscriptionActive = true,
                    sequence = 2,
                )),
                vhalRegistrationGeneration = 11,
            ), 1_001)
            1_001L
        }
    }

    @Test fun `exact expiry ordered after old check wins atomic queue acceptance`() {
        assertSafetyRevocationWinsAcceptance { 1_000L + EvFreshParkGate.MAX_OBSERVATION_AGE_MS }
    }

    @Test fun `invalidated upload retains exact worker ownership until worker releases`() {
        val gate = EvContributionLifecycleGate()
        val old = gate.admitUpload { true }!!
        val oldEntered = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        val oldExited = CountDownLatch(1)
        val worker = thread {
            oldEntered.countDown()
            releaseOld.await(5, TimeUnit.SECONDS)
            gate.releaseUpload(old)
            oldExited.countDown()
        }
        assertTrue(oldEntered.await(5, TimeUnit.SECONDS))
        gate.invalidate()

        var openedConnections = 0
        if (gate.admitUpload { true } != null) openedConnections++
        assertEquals("replacement event must open no connection while old worker unwinds", 0, openedConnections)

        releaseOld.countDown()
        assertTrue(oldExited.await(5, TimeUnit.SECONDS))
        worker.join(5_000)
        if (gate.admitUpload { true } != null) openedConnections++
        assertEquals("a later event starts exactly one replacement after exact exit", 1, openedConnections)
    }

    @Test fun `cancel closes exact blocking request body and later clean attempt succeeds`() {
        val q = EvContributionQueue(dir(), 100_000, 8)
        q.append("drive", 1, vehicleLine(), "owner"); q.close("drive", 2)
        val entered = CountDownLatch(1); val released = CountDownLatch(1); val bodyClosed = AtomicBoolean(false)
        val blockingBody = object : java.io.OutputStream() {
            override fun write(b: Int) = Unit
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                entered.countDown()
                released.await(5, TimeUnit.SECONDS)
                if (bodyClosed.get()) throw java.io.IOException("cancelled")
            }
            override fun close() { bodyClosed.set(true); released.countDown() }
        }
        val blockedConnection = object : HttpURLConnection(URL("https://logs.example/upload")) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() { released.countDown() }
            override fun getOutputStream() = blockingBody
            override fun getResponseCode() = 200
            override fun getInputStream() = "{}".byteInputStream()
        }
        val transport = EvContributionHttpTransport { blockedConnection }
        val worker = thread { runCatching { transport.send("https://logs.example/upload", "token", "ev-class-test", q.pending().single().file) } }
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        transport.cancel(); worker.join(5_000)

        assertFalse("cancellation cleanup must not call a potentially blocking close", bodyClosed.get())
        assertFalse(worker.isAlive)

        val wire = java.io.ByteArrayOutputStream()
        val clean = object : HttpURLConnection(URL("https://logs.example/upload")) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() = Unit
            override fun getOutputStream() = wire
            override fun getResponseCode() = 200
            override fun getInputStream() = "{}".byteInputStream()
        }
        assertEquals(200, EvContributionHttpTransport { clean }.send(
            "https://logs.example/upload", "token", "ev-class-test", q.pending().single().file,
        ).statusCode)
        assertTrue(wire.size() > 0)
    }

    @Test fun `upload check to start is generation owned and invalidation wins barrier`() {
        val gate = EvContributionLifecycleGate()
        val checked = CountDownLatch(1); val releaseCheck = CountDownLatch(1)
        var lease: EvContributionLifecycleGate.Lease? = null
        val admission = thread {
            lease = gate.admitUpload {
                checked.countDown(); releaseCheck.await(5, TimeUnit.SECONDS); true
            }
        }
        assertTrue(checked.await(5, TimeUnit.SECONDS))
        val invalidated = CountDownLatch(1)
        val safety = thread { gate.invalidate(); invalidated.countDown() }
        releaseCheck.countDown(); admission.join(5_000); safety.join(5_000)

        assertTrue(invalidated.await(1, TimeUnit.SECONDS))
        assertNotNull(lease)
        assertFalse(gate.isCurrent(lease!!))
        assertNull(gate.withCurrent(lease!!) { "opened" })
    }

    @Test fun `capture admitted before revoke cannot append after revoke returns`() {
        val gate = EvContributionLifecycleGate()
        val lease = gate.admitCapture { true }!!
        gate.invalidate()
        var appended = false

        val result = gate.withCurrent(lease) { appended = true }

        assertNull(result)
        assertFalse(appended)
        assertNull(gate.admitCapture { false })
    }

    @Test fun `delete blocks new capture and upload admission until cleanup releases barrier`() {
        val gate = EvContributionLifecycleGate()
        val deletion = gate.beginDestructiveOperation()
        assertNull(gate.admitCapture { true })
        assertNull(gate.admitUpload { true })
        assertTrue(gate.finishDestructiveOperation(deletion, resumeAdmissions = true))
        assertNotNull(gate.admitCapture { true })
    }

    @Test fun `older destructive completion cannot reopen or clear latest owner`() {
        val gate = EvContributionLifecycleGate()
        val older = gate.beginDestructiveOperation()
        val newer = gate.beginDestructiveOperation()

        assertFalse(gate.finishDestructiveOperation(older, resumeAdmissions = true))
        assertNull(gate.admitCapture { true })
        assertTrue(gate.finishDestructiveOperation(newer, resumeAdmissions = false))
        assertNull(gate.admitUpload { true })
        val retry = gate.beginDestructiveOperation()
        assertTrue(gate.finishDestructiveOperation(retry, resumeAdmissions = true))
        assertNotNull(gate.admitCapture { true })
    }

    @Test fun `failed deletion marker survives recreation until verified empty retry`() {
        val root = dir()
        var fail = true
        var q = EvContributionQueue(root, deleteFile = { file -> if (fail) false else file.delete() })
        q.append("drive", 1, vehicleLine(), "owner")
        val failed = q.deleteAllArtifacts()
        assertFalse(q.completeDeletion(failed))
        assertTrue(q.isDeletionBlocked())

        q = EvContributionQueue(root)
        assertTrue(q.isDeletionBlocked())
        fail = false
        val retry = q.deleteAllArtifacts()
        assertTrue(q.completeDeletion(retry))
        assertFalse(EvContributionQueue(root).isDeletionBlocked())
        assertEquals(EvContributionQueue.StorageUsage(0, 0), q.storageUsage())
    }

    @Test fun `stale worker finally hands lost trigger to exactly one replacement`() {
        val gate = EvContributionLifecycleGate()
        val ownership = EvUploadJobOwnership<String>()
        val oldLease = gate.admitUpload { true }!!
        assertTrue(ownership.tryInstall("old"))
        gate.invalidate() // old worker is stale before its body starts
        assertFalse(gate.isCurrent(oldLease))
        assertNull(gate.admitUpload { true }) // replacement network callback loses admission to old owner
        ownership.markDrainPending()

        gate.releaseUpload(oldLease) // old worker's finally
        assertTrue(ownership.finish("old"))
        val replacementLease = gate.admitUpload { true }
        assertNotNull(replacementLease)
        assertTrue(ownership.tryInstall("replacement"))
        gate.releaseUpload(replacementLease!!)
        assertFalse(ownership.finish("replacement"))
        assertFalse(ownership.finish("replacement"))
    }

    @Test fun `unquiescent upload is orphaned after bounded wait and destructive deletion continues`() = runBlocking {
        val root = dir()
        val q = EvContributionQueue(root, 100_000, 8)
        q.append("drive", 1, vehicleLine(), "owner"); q.close("drive", 2)
        val gate = EvContributionLifecycleGate()
        val lease = gate.admitUpload { true }!!
        val ownership = EvUploadJobOwnership<String>()
        assertTrue(ownership.tryInstall("stuck"))
        val deletion = gate.beginDestructiveOperation()
        assertTrue(q.beginDeletion())
        var cancelled = false

        val result = EvUploadQuiescence.awaitExactOrOrphan(
            ownership = ownership,
            exact = ownership.current(),
            timeoutMs = 25,
            cancelExact = { cancelled = it == "stuck" },
            awaitExact = { _, timeout -> assertEquals(25, timeout); false },
        )
        val deleted = gate.exclusive { q.deleteAllArtifacts() }
        assertTrue(q.completeDeletion(deleted))
        gate.releaseUpload(lease)
        gate.finishDestructiveOperation(deletion, resumeAdmissions = result.quiesced)

        assertTrue(cancelled)
        assertFalse(result.quiesced)
        assertTrue(result.orphaned)
        assertTrue(ownership.isOrphaned())
        assertEquals(EvContributionQueue.StorageUsage(0, 0), q.storageUsage())
        assertFalse(q.isDeletionBlocked())
        assertNull(gate.admitCapture { true })
        assertNull(gate.admitUpload { true })
        assertFalse(ownership.tryInstall("replacement"))
        Unit
    }

    @Test fun `orphan valid ACK cannot mutate deleted queue or recreate artifacts`() = runBlocking {
        val q = EvContributionQueue(dir(), 100_000, 8)
        q.append("drive", 1, vehicleLine(), "owner"); q.close("drive", 2)
        val gate = EvContributionLifecycleGate()
        val lease = gate.admitUpload { true }!!
        val ownership = EvUploadJobOwnership<String>()
        ownership.tryInstall("old")
        val pending = q.pending().single()
        val deletion = gate.beginDestructiveOperation()
        q.beginDeletion()
        EvUploadQuiescence.awaitExactOrOrphan(ownership, "old", 1, {}, { _, _ -> false })
        val deleted = q.deleteAllArtifacts()
        assertTrue(q.completeDeletion(deleted))

        val staleAckMutation = gate.withCurrent(lease) { q.markAccepted(pending) }
        assertNull(staleAckMutation)
        assertEquals(EvContributionQueue.StorageUsage(0, 0), q.storageUsage())
        assertEquals(0, q.counters().accepted)
        assertEquals(0, q.counters().quarantined)
        assertTrue(q.pending().isEmpty())
        gate.finishDestructiveOperation(deletion, resumeAdmissions = false)
        Unit
    }

    @Test fun `reconsent stays closed until exact orphan exits then ownership can resume`() = runBlocking {
        val gate = EvContributionLifecycleGate()
        val upload = gate.admitUpload { true }!!
        val ownership = EvUploadJobOwnership<String>()
        ownership.tryInstall("old")
        val revoke = gate.beginDestructiveOperation()
        EvUploadQuiescence.awaitExactOrOrphan(ownership, "old", 1, {}, { _, _ -> false })
        gate.releaseUpload(upload)
        gate.finishDestructiveOperation(revoke, resumeAdmissions = false)

        val reconsent = gate.beginDestructiveOperation()
        gate.finishDestructiveOperation(reconsent, resumeAdmissions = !ownership.isOrphaned())
        assertNull(gate.admitCapture { true })
        assertFalse(ownership.tryInstall("replacement"))

        val completion = ownership.finishDetailed("old")
        assertTrue(completion.finished)
        assertTrue(completion.wasOrphaned)
        val recovery = gate.beginDestructiveOperation()
        assertTrue(gate.finishDestructiveOperation(recovery, resumeAdmissions = true))
        assertNotNull(gate.admitCapture { true })
        assertTrue(ownership.tryInstall("replacement"))
        Unit
    }

    @Test fun `concurrent destructive operations remain serialized in one bounded orphan batch`() = runBlocking {
        val gate = EvContributionLifecycleGate()
        val ownership = EvUploadJobOwnership<String>()
        ownership.tryInstall("stuck")
        val revoke = gate.beginDestructiveOperation()
        val manual = gate.beginDestructiveOperation()
        val first = EvUploadQuiescence.awaitExactOrOrphan(ownership, "stuck", 1, {}, { _, _ -> false })
        val second = EvUploadQuiescence.awaitExactOrOrphan(ownership, "stuck", 1, {}, { _, _ -> false })
        assertTrue(first.orphaned)
        assertTrue(second.orphaned)
        assertFalse(gate.finishDestructiveOperation(revoke, resumeAdmissions = false))
        assertTrue(gate.finishDestructiveOperation(manual, resumeAdmissions = false))
        assertNull(gate.admitUpload { true })
        Unit
    }

    @Test fun `normal cancellation waits for exact worker and does not orphan`() = runBlocking {
        val ownership = EvUploadJobOwnership<String>()
        ownership.tryInstall("worker")
        var cancelled = false
        val result = EvUploadQuiescence.awaitExactOrOrphan(
            ownership, "worker", 100,
            cancelExact = { cancelled = true },
            awaitExact = { exact, _ -> ownership.finish(exact); true },
        )
        assertTrue(cancelled)
        assertTrue(result.quiesced)
        assertFalse(result.orphaned)
        assertFalse(ownership.isOrphaned())
        assertNull(ownership.current())
        Unit
    }

    @Test fun `cancelled queued destructive turn bridges to its predecessor without running its body`() = runBlocking {
        val lane = EvDestructiveLane()
        val releaseFirst = CompletableDeferred<Unit>()
        val firstAcquired = CompletableDeferred<Unit>()
        val entries = mutableListOf<String>()
        val first = launch {
            lane.withTurn {
                entries += "A"
                firstAcquired.complete(Unit)
                releaseFirst.await()
            }
        }
        firstAcquired.await()

        var cancelledBodyRan = false
        val second = launch(start = CoroutineStart.UNDISPATCHED) {
            lane.withTurn {
                cancelledBodyRan = true
                entries += "B"
            }
        }
        second.cancelAndJoin()

        val third = async(start = CoroutineStart.UNDISPATCHED) {
            lane.withTurn { entries += "C" }
        }
        yield()
        assertFalse("C must remain behind active A", third.isCompleted)

        releaseFirst.complete(Unit)
        first.join()
        withTimeout(1_000) { third.await() }
        withTimeout(1_000) { lane.withTurn { entries += "D" } }

        assertFalse("cancelled B must skip its destructive body", cancelledBodyRan)
        assertEquals("each acquired turn runs exactly once", listOf("A", "C", "D"), entries)
    }

    @Test fun `cancelled queued manual delete cannot poison following consent revocation`() = runBlocking {
        val lane = EvDestructiveLane()
        val gate = EvContributionLifecycleGate()
        val queue = EvContributionQueue(dir(), 100_000, 8)
        queue.append("retained", 1, vehicleLine(), "owner")
        queue.close("retained", 2)
        val capture = gate.admitCapture { true }!!
        val upload = gate.admitUpload { true }!!
        val generationBefore = gate.snapshot()

        val releaseFirst = CompletableDeferred<Unit>()
        val firstAcquired = CompletableDeferred<Unit>()
        val first = launch {
            lane.withTurn {
                firstAcquired.complete(Unit)
                releaseFirst.await()
            }
        }
        firstAcquired.await()

        var manualDeleteRan = false
        val manualDelete = launch(start = CoroutineStart.UNDISPATCHED) {
            lane.withTurn {
                manualDeleteRan = true
                queue.deleteAllArtifacts()
            }
        }
        manualDelete.cancelAndJoin()

        val revocation = async(start = CoroutineStart.UNDISPATCHED) {
            lane.withTurn {
                val lease = gate.beginDestructiveOperation()
                try {
                    assertTrue(queue.beginDeletion())
                    val result = gate.exclusive { queue.deleteAllArtifacts() }
                    assertTrue(queue.completeDeletion(result))
                } finally {
                    gate.finishDestructiveOperation(lease, resumeAdmissions = false)
                }
            }
        }
        assertFalse("revocation must not pass active predecessor", revocation.isCompleted)

        releaseFirst.complete(Unit)
        first.join()
        withTimeout(1_000) { revocation.await() }

        assertFalse(manualDeleteRan)
        assertFalse("revocation advances the generation fence", gate.isCurrent(generationBefore))
        assertFalse("prior capture is fenced", gate.isCurrent(capture))
        assertFalse("prior upload is fenced", gate.isCurrent(upload))
        assertNull("capture remains closed after revocation", gate.admitCapture { true })
        assertNull("upload remains closed after revocation", gate.admitUpload { true })
        assertEquals(0, queue.storageUsage().units)
        assertFalse(queue.isDeletionBlocked())
    }

    @Test fun `cancellation during acquired destructive operation releases exactly one turn`() = runBlocking {
        val lane = EvDestructiveLane()
        val entered = CompletableDeferred<Unit>()
        var activeRuns = 0
        var successorRuns = 0
        val active = launch(start = CoroutineStart.UNDISPATCHED) {
            lane.withTurn {
                activeRuns++
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()
        val successor = async(start = CoroutineStart.UNDISPATCHED) {
            lane.withTurn { successorRuns++ }
        }
        assertFalse(successor.isCompleted)

        active.cancelAndJoin()
        withTimeout(1_000) { successor.await() }
        withTimeout(1_000) { lane.withTurn { successorRuns++ } }

        assertEquals(1, activeRuns)
        assertEquals(2, successorRuns)
    }

    @Test fun `validated default network replacement ignores stale loss`() {
        val tracker = ValidatedDefaultNetworkTracker<String>()
        assertEquals(ValidatedDefaultNetworkTracker.Change.REPLACED, tracker.capabilities("A", true))
        assertEquals(ValidatedDefaultNetworkTracker.Change.REPLACED, tracker.capabilities("B", true))
        assertEquals(ValidatedDefaultNetworkTracker.Change.IGNORED, tracker.lost("A"))
        assertEquals("B", tracker.current())
        assertEquals(ValidatedDefaultNetworkTracker.Change.LOST, tracker.lost("B"))
        assertNull(tracker.current())
    }

    @Test fun `deadline scheduler keeps earliest generation owned one shot`() {
        var now = 1_000L
        data class Pending(val delay: Long, val task: () -> Unit, var cancelled: Boolean = false)
        val pending = mutableListOf<Pending>()
        val scheduler = EarliestOneShotDeadline(
            now = { now },
            schedule = { delay, task ->
                val item = Pending(delay, task); pending += item
                object : EarliestOneShotDeadline.Cancellable { override fun cancel() { item.cancelled = true } }
            },
        )
        var generationCurrent = true
        var fires = 0
        assertTrue(scheduler.schedule(100, { generationCurrent }) { fires++ })
        assertFalse(scheduler.schedule(200, { generationCurrent }) { fires++ })
        assertTrue(scheduler.schedule(50, { generationCurrent }) { fires++ })
        assertEquals(2, pending.size)
        assertTrue(pending.first().cancelled)
        now += 50; pending.last().task(); pending.last().task()
        assertEquals(1, fires)
        generationCurrent = false
        assertTrue(scheduler.schedule(10, { generationCurrent }) { fires++ })
        pending.last().task()
        assertEquals(1, fires)
    }

    @Test fun `restart never inherits upload authorization and moving revokes it immediately`() {
        val gate = EvFreshParkGate()
        assertFalse(gate.freshParkObservedThisProcess)
        fun observation(timeMs: Long, status: Int = 0, generation: Long = 1) =
            VehiclePropertyObservation(
                timeMs * 1_000_000, timeMs, status,
                registrationGeneration = generation,
                subscriptionActive = true,
                sequence = timeMs,
            )
        assertTrue(gate.observe(ControlMessage.VehicleData(
            gearRaw = 4,
            evObservationMetadata = mapOf("GEAR_SELECTION" to observation(1_000)),
            vhalRegistrationGeneration = 1,
        ), 1_000))
        assertTrue("fresh OFF alone is sufficient", gate.observe(ControlMessage.VehicleData(
            ignitionState = 2,
            evObservationMetadata = mapOf("IGNITION_STATE" to observation(1_100)),
            vhalRegistrationGeneration = 1,
        ), 1_100))
        assertFalse(gate.observe(ControlMessage.VehicleData(
            gearRaw = null,
            ignitionState = 4,
            evObservationMetadata = mapOf(
                "GEAR_SELECTION" to observation(1_200, status = 1),
                "IGNITION_STATE" to observation(1_200),
            ),
            vhalRegistrationGeneration = 1,
        ), 1_200))
        assertFalse("repeating cached observations in an unrelated batch cannot refresh safety", gate.observe(
            ControlMessage.VehicleData(
                gearRaw = 4,
                ignitionState = 2,
                evObservationMetadata = mapOf(
                    "GEAR_SELECTION" to observation(1_200, status = 1),
                    "IGNITION_STATE" to observation(1_200, status = 1),
                ),
                vhalRegistrationGeneration = 1,
            ),
            1_300,
        ))
        assertFalse("stale observations are rejected", gate.observe(ControlMessage.VehicleData(
            gearRaw = 4,
            evObservationMetadata = mapOf("GEAR_SELECTION" to observation(1_000)),
            vhalRegistrationGeneration = 1,
        ), 1_000 + EvFreshParkGate.MAX_OBSERVATION_AGE_MS + 1))
        assertFalse("service registration change clears prior authorization", gate.observe(ControlMessage.VehicleData(
            vhalRegistrationGeneration = 2,
        ), 2_000))
    }

    @Test fun `failed delete remains blocked until verified empty successful retry`() {
        val root = dir()
        var fail = true
        val q = EvContributionQueue(root, deleteFile = { file -> if (fail) false else file.delete() })
        q.append("drive", 1, vehicleLine(), "owner")
        val first = q.deleteAllArtifacts()
        assertTrue(first.failures > 0)
        assertTrue(q.storageUsage().units > 0)
        assertFalse(EvDeleteAdmissionPolicy.mayResume(first, q.storageUsage()))

        fail = false
        val retry = q.deleteAllArtifacts()
        assertEquals(0, retry.failures)
        assertEquals(0, q.storageUsage().units)
        assertTrue(EvDeleteAdmissionPolicy.mayResume(retry, q.storageUsage()))
    }

    @Test fun `upload authorization expires at exact monotonic boundary and same millisecond newer event wins`() {
        val gate = EvFreshParkGate()
        fun observation(timeMs: Long, status: Int = 0, sequence: Long) = VehiclePropertyObservation(
            timestampElapsedNanos = timeMs * 1_000_000,
            receivedElapsedMs = timeMs,
            status = status,
            registrationGeneration = 7,
            propertyId = 0x11400400,
            subscriptionActive = true,
            sequence = sequence,
        )
        assertTrue(gate.observe(ControlMessage.VehicleData(
            gearRaw = 4,
            evObservationMetadata = mapOf("GEAR_SELECTION" to observation(1_000, sequence = 1)),
            vhalRegistrationGeneration = 7,
        ), 1_000))
        assertTrue(gate.authorization(30_999))
        assertFalse(gate.authorization(31_000))
        assertFalse(gate.authorization(31_001))

        assertFalse(gate.observe(ControlMessage.VehicleData(
            gearRaw = 4,
            evObservationMetadata = mapOf("GEAR_SELECTION" to observation(1_000, status = 1, sequence = 2)),
            vhalRegistrationGeneration = 7,
        ), 1_000))
        assertTrue(gate.observe(ControlMessage.VehicleData(
            gearRaw = 4,
            evObservationMetadata = mapOf("GEAR_SELECTION" to observation(31_001, sequence = 3)),
            vhalRegistrationGeneration = 7,
        ), 31_001))
        assertTrue(gate.authorization(31_001))
    }

    @Test fun `delete intent is durable before IO and unchecked partial failure survives recreation`() {
        val root = dir()
        var deletes = 0
        var q = EvContributionQueue(root, deleteFile = { file ->
            deletes++
            if (deletes == 1) file.delete() else throw IllegalStateException("injected partial deletion")
        })
        q.append("a", 1, vehicleLine(), "owner")
        q.append("b", 2, vehicleLine(), "owner")
        assertTrue(q.beginDeletion())
        val result = q.deleteAllArtifacts()
        assertTrue(result.failures > 0)
        q = EvContributionQueue(root)
        assertTrue(q.isDeletionBlocked())
    }

    @Test fun `expanded uploads use a media type legacy receivers reject`() {
        val q = EvContributionQueue(dir(), 100_000, 8)
        q.append("drive", 1, vehicleLine(), "owner"); q.close("drive", 2)
        val file = q.pending().single().file
        for ((schema, expected) in listOf(2 to "application/zip", 3 to "application/vnd.oal.ev-accuracy+zip")) {
            val conn = object : HttpURLConnection(URL("https://logs.example/upload")) {
                override fun connect() = Unit
                override fun usingProxy() = false
                override fun disconnect() = Unit
                override fun getOutputStream(): java.io.OutputStream {
                    assertEquals(expected, getRequestProperty("Content-Type"))
                    return java.io.ByteArrayOutputStream()
                }
                override fun getResponseCode() = 200
                override fun getInputStream() = "{}".byteInputStream()
            }
            assertEquals(200, EvContributionHttpTransport(openConnection = { conn }).send(
                "https://logs.example/upload", "token", "ev-class-test", file, payloadSchema = schema,
            ).statusCode)
        }
    }

    @Test fun `bounded HTTP rejects oversized response and later retry succeeds`() {
        val q = EvContributionQueue(dir(), 100_000, 8)
        q.append("drive", 1, vehicleLine(), "owner"); q.close("drive", 2)
        val oversized = object : HttpURLConnection(URL("https://logs.example/upload")) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() = Unit
            override fun getOutputStream() = java.io.ByteArrayOutputStream()
            override fun getResponseCode() = 200
            override fun getInputStream() = ByteArray(64 * 1024 + 1) { 'x'.code.toByte() }.inputStream()
        }
        assertThrows(java.io.IOException::class.java) {
            EvContributionHttpTransport(attemptTimeoutMs = 1_000, openConnection = { oversized }).send(
                "https://logs.example/upload", "token", "ev-class-test", q.pending().single().file,
            )
        }

        val clean = object : HttpURLConnection(URL("https://logs.example/upload")) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() = Unit
            override fun getOutputStream() = java.io.ByteArrayOutputStream()
            override fun getResponseCode() = 200
            override fun getInputStream() = "{}".byteInputStream()
        }
        assertEquals(200, EvContributionHttpTransport(attemptTimeoutMs = 1_000, openConnection = { clean }).send(
            "https://logs.example/upload", "token", "ev-class-test", q.pending().single().file,
        ).statusCode)
    }

    @Test fun `overall HTTP deadline closes blocked body and permits later attempt`() {
        val q = EvContributionQueue(dir(), 100_000, 8)
        q.append("drive", 1, vehicleLine(), "owner"); q.close("drive", 2)
        val closed = CountDownLatch(1)
        val blocked = object : HttpURLConnection(URL("https://logs.example/upload")) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() { closed.countDown() }
            override fun getOutputStream() = object : java.io.OutputStream() {
                @Volatile var stopped = false
                override fun write(b: Int) = Unit
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    while (!stopped) Thread.sleep(5)
                    throw java.io.InterruptedIOException("closed")
                }
                override fun close() { stopped = true; closed.countDown() }
            }
            override fun getResponseCode() = 200
        }
        val transport = EvContributionHttpTransport(
            attemptTimeoutMs = 100,
            monotonicNow = java.util.function.LongSupplier { System.nanoTime() / 1_000_000 },
            openConnection = { blocked },
        )
        val worker = thread { runCatching { transport.send("https://logs.example/upload", "token", "ev-class-test", q.pending().single().file) } }
        assertTrue(closed.await(2, TimeUnit.SECONDS))
        worker.join(2_000)
        assertFalse(worker.isAlive)

        val clean = object : HttpURLConnection(URL("https://logs.example/upload")) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() = Unit
            override fun getOutputStream() = java.io.ByteArrayOutputStream()
            override fun getResponseCode() = 200
            override fun getInputStream() = "{}".byteInputStream()
        }
        assertEquals(200, EvContributionHttpTransport(openConnection = { clean }).send(
            "https://logs.example/upload", "token", "ev-class-test", q.pending().single().file,
        ).statusCode)
    }

    @Test fun `overall HTTP deadline stops trickle response`() {
        val q = EvContributionQueue(dir(), 100_000, 8)
        q.append("drive", 1, vehicleLine(), "owner"); q.close("drive", 2)
        val closed = AtomicBoolean(false)
        val disconnectCalled = CountDownLatch(1)
        val trickle = object : HttpURLConnection(URL("https://logs.example/upload")) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() { closed.set(true); disconnectCalled.countDown() }
            override fun getOutputStream() = java.io.ByteArrayOutputStream()
            override fun getResponseCode() = 200
            override fun getInputStream() = object : java.io.InputStream() {
                override fun read(): Int {
                    if (closed.get()) throw java.io.InterruptedIOException("closed")
                    Thread.sleep(30)
                    return 'x'.code
                }
                override fun close() { closed.set(true) }
            }
        }
        val started = System.nanoTime()
        assertThrows(java.io.IOException::class.java) {
            EvContributionHttpTransport(
                attemptTimeoutMs = 100,
                monotonicNow = java.util.function.LongSupplier { System.nanoTime() / 1_000_000 },
                openConnection = { trickle },
            ).send("https://logs.example/upload", "token", "ev-class-test", q.pending().single().file)
        }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_000)
        assertTrue(disconnectCalled.await(1, TimeUnit.SECONDS))
        assertTrue(closed.get())
    }

    @Test fun `delayed first ACK after safety expiry retains both immutable ZIPs and opens no second request`() {
        val q = EvContributionQueue(dir(), 100_000, 8)
        q.append("a", 1, vehicleLine(), "owner"); q.close("a", 2)
        q.append("b", 3, vehicleLine(), "owner"); q.close("b", 4)
        var nowElapsed = 1_000L
        val safety = EvFreshParkGate()
        val observation = VehiclePropertyObservation(
            timestampElapsedNanos = nowElapsed * 1_000_000,
            receivedElapsedMs = nowElapsed,
            status = 0,
            registrationGeneration = 9,
            propertyId = 0x11400400,
            subscriptionActive = true,
            sequence = 1,
        )
        assertTrue(safety.observe(ControlMessage.VehicleData(
            gearRaw = 4,
            evObservationMetadata = mapOf("GEAR_SELECTION" to observation),
            vhalRegistrationGeneration = 9,
        ), nowElapsed))
        val sent = mutableListOf<String>()
        val drain = EvContributionDrainLoop(q) { queue ->
            EvContributionUploader(
                queue,
                mayTransmit = { safety.authorization(nowElapsed) },
                mayMutate = { true },
                mutateIfCurrent = { action ->
                    if (safety.authorization(nowElapsed)) action() else null
                },
            ) { _, id ->
                sent += id
                nowElapsed += EvFreshParkGate.MAX_OBSERVATION_AGE_MS
                val entry = queue.pending().first { it.id == id }
                EvContributionUploader.Response(200, "{\"ok\":true,\"sha256\":\"${entry.sha256}\"}")
            }
        }

        assertEquals(EvContributionUploader.Outcome.CANCELLED, drain.drainEligible(10, "owner"))
        assertEquals(listOf("a"), sent)
        assertEquals(listOf("a", "b"), q.pending().map { it.id })
        assertEquals(0, q.counters().accepted)
    }

    @Test fun `blocked connection creation obeys total deadline and later attempt succeeds`() {
        val q = EvContributionQueue(dir(), 100_000, 8)
        q.append("drive", 1, vehicleLine(), "owner"); q.close("drive", 2)
        val entered = CountDownLatch(1)
        val cleanWire = java.io.ByteArrayOutputStream()
        val clean = object : HttpURLConnection(URL("https://logs.example/upload")) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() = Unit
            override fun getOutputStream() = cleanWire
            override fun getResponseCode() = 200
            override fun getInputStream() = "{}".byteInputStream()
        }
        var calls = 0
        val transport = EvContributionHttpTransport(
            attemptTimeoutMs = 100,
            monotonicNow = java.util.function.LongSupplier { System.nanoTime() / 1_000_000 },
            openConnection = {
                if (++calls == 1) {
                    entered.countDown()
                    try {
                        while (true) Thread.sleep(10_000)
                    } catch (interrupted: InterruptedException) {
                        throw java.io.InterruptedIOException("open interrupted").apply { initCause(interrupted) }
                    }
                }
                clean
            },
        )
        val first = thread { runCatching { transport.send("https://logs.example/upload", "token", "ev-class-test", q.pending().single().file) } }
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        first.join(2_000)
        assertFalse("deadline must interrupt blocked openConnection", first.isAlive)

        assertEquals(200, transport.send(
            "https://logs.example/upload", "token", "ev-class-test", q.pending().single().file,
        ).statusCode)
        assertTrue(cleanWire.size() > 0)
    }

    @Test fun `timeout disconnects before blocking body close and replacement succeeds`() {
        val q = EvContributionQueue(dir(), 100_000, 8)
        q.append("drive", 1, vehicleLine(), "owner"); q.close("drive", 2)
        val writeEntered = CountDownLatch(1)
        val disconnectCalled = CountDownLatch(1)
        val disconnected = AtomicBoolean(false)
        val blockingBody = object : java.io.OutputStream() {
            override fun write(b: Int) = Unit
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                writeEntered.countDown()
                while (!disconnected.get()) Thread.sleep(5)
                throw java.io.InterruptedIOException("disconnected")
            }
            override fun close() {
                while (!disconnected.get()) Thread.sleep(5)
            }
        }
        val blocked = object : HttpURLConnection(URL("https://logs.example/upload")) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() { disconnected.set(true); disconnectCalled.countDown() }
            override fun getOutputStream() = blockingBody
            override fun getResponseCode() = 200
        }
        val transport = EvContributionHttpTransport(
            attemptTimeoutMs = 100,
            monotonicNow = java.util.function.LongSupplier { System.nanoTime() / 1_000_000 },
            openConnection = { blocked },
        )
        val worker = thread { runCatching { transport.send("https://logs.example/upload", "token", "ev-class-test", q.pending().single().file) } }
        assertTrue(writeEntered.await(1, TimeUnit.SECONDS))
        assertTrue(disconnectCalled.await(2, TimeUnit.SECONDS))
        worker.join(2_000)
        assertFalse(worker.isAlive)

        val clean = object : HttpURLConnection(URL("https://logs.example/upload")) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() = Unit
            override fun getOutputStream() = java.io.ByteArrayOutputStream()
            override fun getResponseCode() = 200
            override fun getInputStream() = "{}".byteInputStream()
        }
        assertEquals(200, EvContributionHttpTransport(openConnection = { clean }).send(
            "https://logs.example/upload", "token", "ev-class-test", q.pending().single().file,
        ).statusCode)
    }

    @Test fun `cancel disconnects before blocking body close and worker exits`() {
        val q = EvContributionQueue(dir(), 100_000, 8)
        q.append("drive", 1, vehicleLine(), "owner"); q.close("drive", 2)
        val writeEntered = CountDownLatch(1)
        val disconnectCalled = CountDownLatch(1)
        val disconnected = AtomicBoolean(false)
        val body = object : java.io.OutputStream() {
            override fun write(b: Int) = Unit
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                writeEntered.countDown()
                while (!disconnected.get()) Thread.sleep(5)
                throw java.io.InterruptedIOException("disconnected")
            }
            override fun close() { while (!disconnected.get()) Thread.sleep(5) }
        }
        val blocked = object : HttpURLConnection(URL("https://logs.example/upload")) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() { disconnected.set(true); disconnectCalled.countDown() }
            override fun getOutputStream() = body
            override fun getResponseCode() = 200
        }
        val transport = EvContributionHttpTransport(openConnection = { blocked })
        val worker = thread { runCatching { transport.send("https://logs.example/upload", "token", "ev-class-test", q.pending().single().file) } }
        assertTrue(writeEntered.await(1, TimeUnit.SECONDS))
        transport.cancel()
        assertTrue(disconnectCalled.await(1, TimeUnit.SECONDS))
        worker.join(2_000)
        assertFalse(worker.isAlive)
        assertEquals(1, q.pending().size)
        assertEquals(0, q.deleteAllArtifacts().failures)
        assertTrue(q.pending().isEmpty())
    }

    @Test fun `blocking connection configuration is covered by initial deadline`() {
        val q = EvContributionQueue(dir(), 100_000, 8)
        q.append("drive", 1, vehicleLine(), "owner"); q.close("drive", 2)
        val configuring = CountDownLatch(1)
        val disconnectCalled = CountDownLatch(1)
        val disconnected = AtomicBoolean(false)
        val connection = object : HttpURLConnection(URL("https://logs.example/upload")) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() { disconnected.set(true); disconnectCalled.countDown() }
            override fun setRequestProperty(key: String?, value: String?) {
                configuring.countDown()
                while (!disconnected.get()) Thread.sleep(5)
                throw java.io.InterruptedIOException("configuration disconnected")
            }
            override fun getOutputStream() = java.io.ByteArrayOutputStream()
            override fun getResponseCode() = 200
        }
        val transport = EvContributionHttpTransport(
            attemptTimeoutMs = 100,
            monotonicNow = java.util.function.LongSupplier { System.nanoTime() / 1_000_000 },
            openConnection = { connection },
        )
        val worker = thread { runCatching {
            transport.send("https://logs.example/upload", "token", "ev-class-test", q.pending().single().file)
        } }
        assertTrue(configuring.await(1, TimeUnit.SECONDS))
        worker.join(2_000)
        assertTrue(disconnectCalled.await(1, TimeUnit.SECONDS))
        assertFalse(worker.isAlive)
    }

    @Test fun `stale watchdog from completed attempt cannot disconnect replacement`() {
        val q = EvContributionQueue(dir(), 100_000, 8)
        q.append("drive", 1, vehicleLine(), "owner"); q.close("drive", 2)
        val watchdogs = mutableListOf<() -> Unit>()
        val bEntered = CountDownLatch(1)
        val bDisconnectCalled = CountDownLatch(1)
        val bDisconnected = AtomicBoolean(false)
        val a = object : HttpURLConnection(URL("https://logs.example/upload")) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() = Unit
            override fun getOutputStream() = java.io.ByteArrayOutputStream()
            override fun getResponseCode() = 200
            override fun getInputStream() = "{}".byteInputStream()
        }
        val b = object : HttpURLConnection(URL("https://logs.example/upload")) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() { bDisconnected.set(true); bDisconnectCalled.countDown() }
            override fun getOutputStream() = object : java.io.OutputStream() {
                override fun write(value: Int) = Unit
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    bEntered.countDown()
                    while (!bDisconnected.get()) Thread.sleep(5)
                    throw java.io.InterruptedIOException("disconnected")
                }
            }
            override fun getResponseCode() = 200
        }
        var opens = 0
        val transport = EvContributionHttpTransport(
            openConnection = { if (opens++ == 0) a else b },
            scheduleDeadline = { _, task ->
                watchdogs += task
                EvContributionDeadline.Cancellable { }
            },
        )
        assertEquals(200, transport.send(
            "https://logs.example/upload", "token", "ev-class-test", q.pending().single().file,
        ).statusCode)
        val replacement = thread { runCatching {
            transport.send("https://logs.example/upload", "token", "ev-class-test", q.pending().single().file)
        } }
        assertTrue(bEntered.await(1, TimeUnit.SECONDS))

        watchdogs.first().invoke()
        assertFalse("attempt A watchdog must not touch attempt B", bDisconnected.get())

        transport.cancel()
        replacement.join(2_000)
        assertFalse(replacement.isAlive)
        assertTrue(bDisconnectCalled.await(1, TimeUnit.SECONDS))
        assertTrue(bDisconnected.get())
    }

    @Test fun `authorized stale deadline cannot interrupt replacement on reused worker`() {
        val q = EvContributionQueue(dir(), 100_000, 8)
        q.append("drive", 1, vehicleLine(), "owner"); q.close("drive", 2)
        val deadlines = mutableListOf<() -> Unit>()
        val aResponseEntered = CountDownLatch(1)
        val cancellationAuthorized = CountDownLatch(1)
        val releaseStaleCancellation = CountDownLatch(1)
        val bEntered = CountDownLatch(1)
        val releaseB = CountDownLatch(1)
        val bInterrupted = AtomicBoolean(false)
        val aDisconnects = AtomicInteger()
        val bDisconnects = AtomicInteger()
        val a = object : HttpURLConnection(URL("https://logs.example/upload")) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() { aDisconnects.incrementAndGet() }
            override fun getOutputStream() = java.io.ByteArrayOutputStream()
            override fun getResponseCode(): Int {
                aResponseEntered.countDown()
                assertTrue(cancellationAuthorized.await(5, TimeUnit.SECONDS))
                return 200
            }
            override fun getInputStream() = "{}".byteInputStream()
        }
        val b = object : HttpURLConnection(URL("https://logs.example/upload")) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() { bDisconnects.incrementAndGet(); releaseB.countDown() }
            override fun getOutputStream() = java.io.ByteArrayOutputStream()
            override fun getResponseCode(): Int {
                bEntered.countDown()
                try {
                    assertTrue(releaseB.await(5, TimeUnit.SECONDS))
                } catch (interrupted: InterruptedException) {
                    bInterrupted.set(true)
                    throw java.io.InterruptedIOException("replacement interrupted").apply { initCause(interrupted) }
                }
                return 200
            }
            override fun getInputStream() = "{}".byteInputStream()
        }
        val c = object : HttpURLConnection(URL("https://logs.example/upload")) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() = Unit
            override fun getOutputStream() = java.io.ByteArrayOutputStream()
            override fun getResponseCode() = 200
            override fun getInputStream() = "{}".byteInputStream()
        }
        var opens = 0
        val transport = EvContributionHttpTransport(
            openConnection = { when (opens++) { 0 -> a; 1 -> b; else -> c } },
            scheduleDeadline = { _, task ->
                synchronized(deadlines) { deadlines += task }
                EvContributionDeadline.Cancellable { }
            },
            requestCancellation = { connection ->
                assertTrue("stale callback must remain bound to A", connection === a)
                cancellationAuthorized.countDown()
                assertTrue(releaseStaleCancellation.await(5, TimeUnit.SECONDS))
                connection.disconnect()
            },
        )
        var bResponse: EvContributionUploader.Response? = null
        val worker = thread {
            runCatching { transport.send(
                "https://logs.example/upload", "token", "ev-class-test", q.pending().single().file,
            ) }
            bResponse = runCatching { transport.send(
                "https://logs.example/upload", "token", "ev-class-test", q.pending().single().file,
            ) }.getOrNull()
        }
        assertTrue(aResponseEntered.await(1, TimeUnit.SECONDS))
        val staleDeadline = synchronized(deadlines) { deadlines.first() }
        val callback = thread { staleDeadline() }
        assertTrue(cancellationAuthorized.await(1, TimeUnit.SECONDS))
        assertTrue("same worker must start B after A clears", bEntered.await(1, TimeUnit.SECONDS))

        releaseStaleCancellation.countDown()
        callback.join(1_000)
        assertFalse(callback.isAlive)
        releaseB.countDown()
        worker.join(2_000)

        assertFalse(worker.isAlive)
        assertFalse("A deadline must not interrupt B", bInterrupted.get())
        assertEquals(200, bResponse?.statusCode)
        assertEquals(1, aDisconnects.get())
        assertEquals("A cancellation must not disconnect B", 0, bDisconnects.get())
        assertEquals(200, transport.send(
            "https://logs.example/upload", "token", "ev-class-test", q.pending().single().file,
        ).statusCode)
    }

    @Test fun `completed fixed length body never invokes hostile output close`() {
        val q = EvContributionQueue(dir(), 100_000, 8)
        q.append("drive", 1, vehicleLine(), "owner"); q.close("drive", 2)
        val closeEntered = CountDownLatch(1)
        val wire = java.io.ByteArrayOutputStream()
        val body = object : java.io.OutputStream() {
            override fun write(value: Int) { wire.write(value) }
            override fun write(bytes: ByteArray, offset: Int, length: Int) { wire.write(bytes, offset, length) }
            override fun close() {
                closeEntered.countDown()
                while (true) try { Thread.sleep(10_000) } catch (_: InterruptedException) { }
            }
        }
        val connection = object : HttpURLConnection(URL("https://logs.example/upload")) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() = Unit
            override fun getOutputStream() = body
            override fun getResponseCode() = 200
            override fun getInputStream() = "{}".byteInputStream()
        }
        var response: EvContributionUploader.Response? = null
        val worker = thread { response = EvContributionHttpTransport(openConnection = { connection }).send(
            "https://logs.example/upload", "token", "ev-class-test", q.pending().single().file,
        ) }
        worker.join(1_000)
        assertFalse("successful upload must not wait on output close", worker.isAlive)
        assertEquals(200, response?.statusCode)
        assertEquals(q.pending().single().file.length(), wire.size().toLong())
        assertEquals(1L, closeEntered.count)
    }

    @Test fun `completed response never invokes hostile stream close`() {
        val q = EvContributionQueue(dir(), 100_000, 8)
        q.append("drive", 1, vehicleLine(), "owner"); q.close("drive", 2)
        val closeEntered = CountDownLatch(1)
        val responseStream = object : java.io.ByteArrayInputStream("{}".toByteArray()) {
            override fun close() {
                closeEntered.countDown()
                while (true) try { Thread.sleep(10_000) } catch (_: InterruptedException) { }
            }
        }
        val connection = object : HttpURLConnection(URL("https://logs.example/upload")) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() = Unit
            override fun getOutputStream() = java.io.ByteArrayOutputStream()
            override fun getResponseCode() = 200
            override fun getInputStream() = responseStream
        }
        var response: EvContributionUploader.Response? = null
        val worker = thread { response = EvContributionHttpTransport(openConnection = { connection }).send(
            "https://logs.example/upload", "token", "ev-class-test", q.pending().single().file,
        ) }
        worker.join(1_000)
        assertFalse("successful upload must not wait on response close", worker.isAlive)
        assertEquals("{}", response?.body)
        assertEquals(1L, closeEntered.count)
    }

    @Test fun `deadline and explicit cancel request exact attempt cancellation once`() {
        val q = EvContributionQueue(dir(), 100_000, 8)
        q.append("drive", 1, vehicleLine(), "owner"); q.close("drive", 2)
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val disconnects = AtomicInteger()
        val connection = object : HttpURLConnection(URL("https://logs.example/upload")) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() { disconnects.incrementAndGet(); released.countDown() }
            override fun getOutputStream() = java.io.ByteArrayOutputStream()
            override fun getResponseCode(): Int { entered.countDown(); released.await(5, TimeUnit.SECONDS); return 200 }
            override fun getInputStream() = "{}".byteInputStream()
        }
        val pendingCancellation = java.util.concurrent.ConcurrentLinkedQueue<HttpURLConnection>()
        lateinit var deadline: () -> Unit
        val transport = EvContributionHttpTransport(
            openConnection = { connection },
            scheduleDeadline = { _, task -> deadline = task; EvContributionDeadline.Cancellable { } },
            requestCancellation = { pendingCancellation.add(it) },
        )
        val worker = thread { runCatching { transport.send(
            "https://logs.example/upload", "token", "ev-class-test", q.pending().single().file,
        ) } }
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        val explicit = thread { transport.cancel() }
        val timed = thread { deadline() }
        explicit.join(); timed.join(); worker.join(2_000)
        assertFalse(worker.isAlive)
        // Worker interruption is synchronous; disconnect dispatch is not. Hold the
        // dispatcher so this checks exactly-once ownership independently of scheduling.
        assertEquals("one cancellation request despite racing cancellation", 1, pendingCancellation.size)
        assertSame("cancellation stays bound after worker cleanup", connection, pendingCancellation.single())
        pendingCancellation.remove().disconnect()
        assertEquals("one exact-owner disconnect despite racing cancellation", 1, disconnects.get())
    }

    @Test fun `cancel before connection bind requests teardown exactly once`() {
        val q = EvContributionQueue(dir(), 100_000, 8)
        q.append("drive", 1, vehicleLine(), "owner"); q.close("drive", 2)
        val opening = CountDownLatch(1)
        val releaseOpen = CountDownLatch(1)
        val cancellations = AtomicInteger()
        val connection = object : HttpURLConnection(URL("https://logs.example/upload")) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() = Unit
            override fun getOutputStream() = java.io.ByteArrayOutputStream()
            override fun getResponseCode() = 200
        }
        val transport = EvContributionHttpTransport(
            requestCancellation = { cancellations.incrementAndGet() },
            openConnection = {
                opening.countDown()
                while (true) {
                    try {
                        if (releaseOpen.await(10, TimeUnit.MILLISECONDS)) break
                    } catch (_: InterruptedException) {
                        // Model connection creation that ignores interruption.
                    }
                }
                connection
            },
        )
        val worker = thread { runCatching { transport.send(
            "https://logs.example/upload", "token", "ev-class-test", q.pending().single().file,
        ) } }
        assertTrue(opening.await(1, TimeUnit.SECONDS))
        val first = thread { transport.cancel() }
        val second = thread { transport.cancel() }
        first.join(); second.join()
        releaseOpen.countDown()
        worker.join(1_000)
        assertFalse(worker.isAlive)
        assertEquals(1, cancellations.get())
    }

    @Test fun `hostile disconnect stays on one bounded shared lane and later request succeeds`() {
        val q = EvContributionQueue(dir(), 100_000, 8)
        q.append("drive", 1, vehicleLine(), "owner"); q.close("drive", 2)
        val disconnectEntered = CountDownLatch(1)
        val releaseDisconnect = CountDownLatch(1)
        val baseline = Thread.getAllStackTraces().keys.count { it.isAlive && it.name == "ev-upload-cancel" }
        repeat(5) {
            val responseEntered = CountDownLatch(1)
            val connection = object : HttpURLConnection(URL("https://logs.example/upload")) {
                override fun connect() = Unit
                override fun usingProxy() = false
                override fun disconnect() {
                    disconnectEntered.countDown()
                    while (!releaseDisconnect.await(10, TimeUnit.MILLISECONDS)) { /* ignore interruption */ }
                }
                override fun getOutputStream() = java.io.ByteArrayOutputStream()
                override fun getResponseCode(): Int {
                    responseEntered.countDown()
                    try {
                        while (true) Thread.sleep(10_000)
                    } catch (interrupted: InterruptedException) {
                        throw java.io.InterruptedIOException("cancelled").apply { initCause(interrupted) }
                    }
                }
            }
            val transport = EvContributionHttpTransport(openConnection = { connection })
            val worker = thread { runCatching { transport.send(
                "https://logs.example/upload", "token", "ev-class-test", q.pending().single().file,
            ) } }
            assertTrue(responseEntered.await(1, TimeUnit.SECONDS))
            val started = System.nanoTime()
            transport.cancel()
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 500)
            worker.join(1_000)
            assertFalse(worker.isAlive)
        }
        assertTrue(disconnectEntered.await(1, TimeUnit.SECONDS))
        val live = Thread.getAllStackTraces().keys.count { it.isAlive && it.name == "ev-upload-cancel" }
        assertTrue("bounded cancellation must add at most one shared thread", live <= maxOf(1, baseline))

        val clean = object : HttpURLConnection(URL("https://logs.example/upload")) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() = Unit
            override fun getOutputStream() = java.io.ByteArrayOutputStream()
            override fun getResponseCode() = 200
            override fun getInputStream() = "{}".byteInputStream()
        }
        assertEquals(200, EvContributionHttpTransport(openConnection = { clean }).send(
            "https://logs.example/upload", "token", "ev-class-test", q.pending().single().file,
        ).statusCode)
        releaseDisconnect.countDown()
    }

    @Test fun `event driven drain uploads offline batches oldest first when network later arrives`() {
        val root = dir()
        EvContributionQueue(root, 100_000, 8).apply {
            append("a", 1, vehicleLine(), "owner"); close("a", 2)
        }
        val recreated = EvContributionQueue(root, 100_000, 8).apply {
            append("b", 3, vehicleLine(), "owner"); close("b", 4)
        }
        val sent = mutableListOf<String>()
        val drain = EvContributionDrainLoop(recreated) { q ->
            EvContributionUploader(q) { _, id ->
                sent += id
                EvContributionUploader.Response(200, "{\"ok\":true,\"sha256\":\"${q.pending().first().sha256}\"}")
            }
        }

        assertEquals(EvContributionUploader.Outcome.EMPTY, drain.drainEligible(10, "owner"))
        assertEquals(listOf("a", "b"), sent)
        assertTrue(recreated.pending().isEmpty())
    }
}
