package com.openautolink.app.diagnostics

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BluetoothReconnectRecorderTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun unavailableDirectoryCannotAcknowledgeDeletionAndRetriesAfterReturn() {
        val directory = temp.newFolder("volume")
        val evidence = File(directory, "bluetooth_reconnect_run_0000000000000000001.log").apply { writeText("retained") }
        val parked = File(temp.root, "offline-volume")
        assertTrue(directory.renameTo(parked))
        // Existing but unlistable path: real File.listFiles returns null, not an empty inventory.
        directory.writeText("unavailable mount")
        val recorder = BluetoothReconnectRecorder(directory)
        assertFalse("Unknown inventory must never acknowledge deleted", recorder.delete())
        assertEquals("Deletion failed — evidence retained or unknown; retry", recorder.status.value)
        assertFalse(recorder.isRunning())
        assertEquals("retained", File(parked, evidence.name).readText())
        assertTrue(directory.delete()); assertTrue(parked.renameTo(directory))
        assertTrue(recorder.delete()); assertFalse(evidence.exists())
        recorder.close()
    }

    @Test fun inventorySecurityFailureIsFiniteAndNeverDeleted() {
        val directory = object : File(temp.root.path) {
            override fun listFiles(): Array<File>? = throw SecurityException("private filesystem detail")
        }
        val recorder = BluetoothReconnectRecorder(directory)
        assertFalse("Filesystem exception must be an explicit false receipt", recorder.delete())
        assertEquals("Deletion failed — evidence retained or unknown; retry", recorder.status.value)
        recorder.close()
    }

    @Test fun deletionOfAbsentOrEmptyDirectorySucceeds() {
        for (directory in listOf(File(temp.root, "absent"), temp.newFolder("empty"))) {
            val recorder = BluetoothReconnectRecorder(directory)
            assertTrue(recorder.delete())
            assertEquals("Off — local evidence deleted", recorder.status.value)
            recorder.close()
        }
    }
    @Test fun recoveryReadFailureWithGoodAndBadFilesRemainsUnknownAndRetryable() {
        val good = File(temp.root, "bluetooth_reconnect_run_0000000000000000001.log").apply {
            writeText("coverage terminal_durable=true persistence=complete\n")
        }
        val goodBytes = good.readBytes()
        val bad = File(temp.root, "bluetooth_reconnect_run_0000000000000000002.log").apply { mkdir() }
        val retained = File(bad, "held").apply { writeText("private retained evidence") }
        val recorder = BluetoothReconnectRecorder(temp.root)
        val result = runCatching { recorder.recoverPreviousRuns() }
        assertTrue("Recovery read IOException must be caught at storage boundary", result.isSuccess)
        assertEquals(false, result.getOrNull())
        assertEquals("Storage failed — incomplete evidence; coverage unknown; retry with fresh consent", recorder.status.value)
        assertFalse(recorder.flush(2000))
        assertFalse(recorder.start(true)); assertFalse(recorder.isRunning())
        recorder.stop(); assertFalse(recorder.flush(2000))
        assertArrayEquals(goodBytes, good.readBytes())
        assertEquals("private retained evidence", retained.readText())
        assertTrue(bad.isDirectory)
        retained.delete(); bad.delete()
        assertTrue(recorder.start(true)); recorder.stop(); assertTrue(recorder.flush(2000))
        assertTrue(temp.root.listFiles()!!.count { it.extension == "log" } <= 3)
        recorder.close()
    }

    @Test fun secondAdmissionInventoryNullCannotSilentlyBecomeEmpty() {
        var listings = 0
        val directory = object : File(temp.root.path) {
            override fun listFiles(): Array<File>? = if (++listings == 3) null else super.listFiles()
        }
        val recorder = BluetoothReconnectRecorder(directory)
        assertFalse("Admission inventory failure is not zero retained files", recorder.start(true))
        assertFalse(recorder.isRunning()); assertFalse(recorder.flush(2000))
        assertTrue(recorder.status.value.startsWith("Storage failed"))
        recorder.close()
    }

    @Test fun recoveryInventorySecurityFailureIsNotUncaughtOrEmpty() {
        val directory = object : File(temp.root.path) {
            override fun listFiles(): Array<File>? = throw SecurityException("private inventory")
        }
        val recorder = BluetoothReconnectRecorder(directory)
        val result = runCatching { recorder.start(true) }
        assertTrue("Start must catch filesystem permission failures", result.isSuccess)
        assertEquals(false, result.getOrNull())
        assertFalse(recorder.isRunning()); assertFalse(recorder.flush(2000))
        assertEquals("Storage failed — incomplete evidence; coverage unknown; retry with fresh consent", recorder.status.value)
        recorder.close()
    }

    @Test fun successfulCheckpointsRemainPendingUntilTerminalAndCensusResetsPerRun() {
        val recorder = BluetoothReconnectRecorder(temp.root)
        repeat(2) {
            assertTrue(recorder.start(true)); assertTrue(recorder.flush(2000))
            val file = File(temp.root, "bluetooth_reconnect_run_%019d.log".format(java.util.Locale.ROOT, recorder.token()))
            assertTrue(file.readText().contains("persistence=pending attempted=1 saved=1 lost=0 failed_batches=0 terminal_durable=false"))
            assertTrue(recorder.bookmark("play_pressed")); assertTrue(recorder.flush(2000))
            recorder.stop(); assertTrue(recorder.flush(2000))
            assertTrue(file.readText().contains("persistence=complete attempted=3 saved=3 lost=0 failed_batches=0 terminal_durable=true"))
            assertTrue(recorder.status.value.startsWith("Saved locally"))
        }
        recorder.close()
    }

    @Test fun trailingEventOrTerminalFailureLeavesDurablePendingNotFalseComplete() {
        for (terminalFailure in listOf(false, true)) {
            val dir = temp.newFolder("trailing-$terminalFailure")
            val recorder = BluetoothReconnectRecorder(dir)
            assertTrue(recorder.start(true)); assertTrue(recorder.flush(2000))
            val file = dir.listFiles()!!.single { it.extension == "log" }
            val checkpoint = file.readBytes()
            val pending = File(dir, file.name + ".pending").apply { mkdirs() }
            File(pending, "held").writeText("fixture")
            if (terminalFailure) recorder.stop() else assertTrue(recorder.bookmark("play_pressed"))
            assertFalse(recorder.flush(2000))
            assertTrue(recorder.status.value.contains("Storage failed"))
            assertTrue(recorder.status.value.contains("durability unknown"))
            assertArrayEquals("Failed atomic output cannot replace the prior checkpoint", checkpoint, file.readBytes())
            assertTrue(file.readText().contains("persistence=pending"))
            assertFalse(file.readText().contains("terminal_durable=true"))
            assertFalse("No automatic retries", recorder.flush(2000))
            pending.deleteRecursively() // Explicitly remove test obstruction before fresh access.
            recorder.close()
            val fresh = BluetoothReconnectRecorder(dir)
            assertTrue(fresh.recoverPreviousRuns())
            val recovered = file.readText()
            assertTrue(recovered.contains("persistence=unknown incomplete=true"))
            assertFalse(fresh.status.value.startsWith("Saved"))
            assertTrue(fresh.recoverPreviousRuns())
            assertEquals("Recovery does not append unbounded markers", recovered, file.readText())
            // A new run resets only its own census, never reclassifies the earlier file.
            assertTrue(fresh.start(true)); fresh.stop(); assertTrue(fresh.flush(2000))
            assertEquals(recovered, file.readText())
            assertTrue(dir.listFiles()!!.filter { it.extension == "log" && it != file }.single().readText().contains("lost=0 failed_batches=0 terminal_durable=true"))
            fresh.close()
        }
    }

    @Test fun expiryWhileFailedBatchPendingRetainsLossInSuccessfulExpiredTerminal() {
        var now = 0L
        val recorder = BluetoothReconnectRecorder(temp.root).apply { clock = { now } }
        assertTrue(recorder.start(true)); assertTrue(recorder.flush(2000))
        val file = temp.root.listFiles()!!.single { it.extension == "log" }
        val pending = File(temp.root, file.name + ".pending").apply { mkdirs() }
        val held = File(pending, "held").apply { writeText("fixture") }
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        var writes = 0
        recorder.beforeWrite = {
            if (++writes == 1) { entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
            else { held.delete(); pending.delete() }
        }
        assertTrue(recorder.bookmark("play_pressed")); assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
        now = BluetoothReconnectRecorder.DURATION_MS
        recorder.expire(); assertFalse(recorder.isRunning())
        assertFalse(recorder.flush(1))
        release.countDown(); assertFalse(recorder.flush(2000))
        assertTrue(file.readText().contains("kind=run state=expired"))
        assertTrue(file.readText().contains("persistence=incomplete attempted=3 saved=2 lost=1 failed_batches=1 terminal_durable=true"))
        assertTrue(recorder.status.value.startsWith("Storage failed"))
        // Explicit replacement resets only the new run; old failure survives in the same process.
        val incomplete = file.readText()
        recorder.beforeWrite = {}
        assertTrue(recorder.start(true)); recorder.stop(); assertTrue(recorder.flush(2000))
        assertEquals(incomplete, file.readText())
        recorder.close()
    }

    @Test fun legacySavedReasonWithoutDurableCensusIsUnknownAfterRecovery() {
        val file = File(temp.root, "bluetooth_reconnect_run_0000000000000000001.log")
        file.writeText("bt_reconnect_v1 kind=run state=saved\n")
        val recorder = BluetoothReconnectRecorder(temp.root)
        assertTrue(recorder.recoverPreviousRuns())
        assertTrue(file.readText().contains("persistence=unknown incomplete=true"))
        recorder.close()
    }

    @Test fun storageFailureDoesNotPreventExplicitDeletionAndOrphanCheckpointIsPruned() {
        val orphan = java.io.File(temp.root, "bluetooth_reconnect_run_0000000000000000001.log.pending").apply { writeText("old incomplete") }
        val recorder = BluetoothReconnectRecorder(temp.root)
        assertTrue(recorder.start(true)); assertTrue(recorder.flush(2000))
        assertFalse("Crash-left temporary checkpoint must not accumulate outside run budget", orphan.exists())
        recorder.beforeWrite = { throw java.io.IOException() }
        recorder.stop(); assertFalse(recorder.flush(2000))
        assertTrue("Revocation must be able to delete even after durability failed", recorder.delete())
        assertTrue(temp.root.listFiles()!!.isEmpty())
        recorder.close()
    }

    @Test fun revocationFencesLateTerminalPublicationAndDeletesAfterHeldIo() {
        val recorder = BluetoothReconnectRecorder(temp.root)
        assertTrue(recorder.start(true)); assertTrue(recorder.flush(2000))
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        recorder.beforeWrite = { entered.countDown(); release.await(2, java.util.concurrent.TimeUnit.SECONDS) }
        recorder.stop(); assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
        recorder.revoke()
        assertFalse(recorder.bookmark("phone_on_media_off"))
        release.countDown(); recorder.flush(2000)
        assertTrue(recorder.status.value.startsWith("Revoked"))
        assertTrue(recorder.delete())
        recorder.close()
    }
    @Test fun repeatedEdgesKeepUniqueSequencesAndDiscontinuityCensus() {
        val recorder = BluetoothReconnectRecorder(temp.root)
        recorder.start(true)
        repeat(3) { assertTrue(recorder.offer("a2dp-sink", "connected", "connecting", 1)) }
        recorder.stop(); assertTrue(recorder.flush(2000))
        val text = temp.root.listFiles()!!.single { it.extension == "log" }.readText()
        val edges = text.lines().filter { it.contains("kind=a2dp-sink") }
        assertEquals(3, edges.size)
        assertEquals(3, edges.map { it.substringAfter("seq=").substringBefore(" ") }.distinct().size)
        assertTrue("previous != last observed current must disclose a gap", text.contains("discontinuities=2"))
        recorder.close()
    }

    @Test fun boundedOverflowRetainsTerminalAndRejectsPrivateSuffixes() {
        val recorder = BluetoothReconnectRecorder(temp.root)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        recorder.beforeWrite = { entered.countDown(); release.await(2, java.util.concurrent.TimeUnit.SECONDS) }
        assertTrue(recorder.start(true)); assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
        repeat(2000) { recorder.offer("hfp-client", "connected", "connecting", 1) }
        assertFalse(recorder.offer("hfp-client", "connected private", "connecting", 1))
        assertFalse(recorder.offer("hfp-client", "connected\n", "connecting", 1))
        assertFalse(recorder.bookmark("phone_on_media_off private"))
        recorder.stop(); assertFalse(recorder.flush(1)); release.countDown()
        assertTrue(recorder.flush(2000))
        val file = temp.root.listFiles()!!.single { it.extension == "log" }
        val text = file.readText()
        assertTrue(file.length() <= BluetoothReconnectRecorder.MAX_BYTES)
        assertTrue(text.contains("kind=run state=saved"))
        assertFalse(text.contains("private"))
        assertTrue(Regex("dropped=[1-9][0-9]*").containsMatchIn(text))
        assertFalse(BluetoothReconnectRecorder.validRow(text.lines().first() + "\nprivate"))
        recorder.close()
    }

    @Test fun deadlineRejectsLateCallbacksAndRestartRequiresConsentWithExplicitGap() {
        var now = 100L
        val recorder = BluetoothReconnectRecorder(temp.root).apply { clock = { now } }
        recorder.start(true); assertTrue(recorder.flush(2000))
        // A fresh owner cannot claim coverage for the earlier still-open process run.
        val second = BluetoothReconnectRecorder(temp.root)
        assertTrue(second.start(true)); second.stop(); assertTrue(second.flush(2000))
        assertTrue(temp.root.listFiles()!!.any { it.readText().contains("unclean_process_gap") })
        now += BluetoothReconnectRecorder.DURATION_MS
        assertFalse(recorder.offer("hfp-client", "connected", "connecting", 1))
        assertTrue(recorder.flush(2000))
        assertTrue(recorder.status.value.contains("Expired"))
        assertFalse(recorder.start(false))
        recorder.close(); second.close()
    }

    @Test fun failedAtomicPersistenceNeverAcknowledgesSaved() {
        val recorder = BluetoothReconnectRecorder(temp.root)
        recorder.beforeWrite = { throw java.io.IOException("private") }
        recorder.start(true); recorder.stop()
        assertFalse(recorder.flush(2000))
        assertTrue(recorder.status.value.contains("Storage failed"))
        assertFalse(recorder.status.value.contains("private"))
        recorder.close()
    }
    @Test fun processRecorderPersistsUserAttestationWithoutAnyAuthenticatedSession() {
        val type = runCatching { Class.forName("com.openautolink.app.diagnostics.BluetoothReconnectRecorder") }.getOrNull()
        assertNotNull("Missing consented process recorder: session-only evidence loses reconnect", type)
        val recorder = type!!.getConstructor(File::class.java).newInstance(temp.root)
        assertEquals(false, type.getMethod("start", Boolean::class.javaPrimitiveType).invoke(recorder, false))
        assertEquals(true, type.getMethod("start", Boolean::class.javaPrimitiveType).invoke(recorder, true))
        type.getMethod("bookmark", String::class.java).invoke(recorder, "phone_on_media_off")
        type.getMethod("stop").invoke(recorder)
        assertEquals(true, type.getMethod("flush", Long::class.javaPrimitiveType).invoke(recorder, 2000L))
        val text = temp.root.listFiles()!!.single { it.extension == "log" }.readText()
        assertTrue(text.contains("state=phone_on_media_off"))
        assertTrue(text.contains("authority=user_report"))
        assertTrue(text.contains("state=saved"))
        assertFalse(text.contains("policy=off"))
        type.getMethod("close").invoke(recorder)
    }
}
