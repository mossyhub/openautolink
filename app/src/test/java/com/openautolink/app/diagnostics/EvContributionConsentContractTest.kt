package com.openautolink.app.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EvContributionConsentContractTest {
    @Test fun `automatic EV contribution has independent default-off preference and explicit UI copy`() {
        val prefs = projectFile("app/src/main/java/com/openautolink/app/data/AppPreferences.kt").readText()
        val vm = projectFile("app/src/main/java/com/openautolink/app/ui/settings/SettingsViewModel.kt").readText()
        val screen = projectFile("app/src/main/java/com/openautolink/app/ui/settings/SettingsScreen.kt").readText()
        assertTrue(prefs.contains("EV_CONTRIBUTION_CONSENT"))
        assertTrue(prefs.contains("DEFAULT_EV_CONTRIBUTION_CONSENT = false"))
        assertTrue(vm.contains("updateEvContributionConsent"))
        assertTrue(screen.contains("Automatic compact EV contribution"))
        assertTrue(screen.contains("No locations, destinations, VIN, device identifiers or general logs"))
        assertTrue(screen.contains("deletePendingEvContributions"))
        assertTrue(screen.contains("evContributionStatus"))
        assertTrue(screen.contains("consentInvalidReason"))
        assertTrue(screen.contains("retainedCount"))
        assertTrue(screen.contains("lastUploadOutcome"))
        assertTrue(screen.contains("evictedCount"))
        assertTrue(screen.contains("quarantinedCount"))
        assertTrue(screen.contains("lastDeleteResult"))
        assertTrue(screen.contains("uploadStalled"))
        assertTrue(screen.contains("hashed vehicle-class label"))
        assertFalse(screen.substringAfter("Automatic compact EV contribution").substringBefore("Log Upload (maintainer)").contains("logUploadEnabled"))
    }

    @Test fun `process runtime and vehicle path initialize shadow learner and compact contribution independently`() {
        val app = projectFile("app/src/main/java/com/openautolink/app/OalApplication.kt").readText()
        val session = projectFile("app/src/main/java/com/openautolink/app/session/SessionManager.kt").readText()
        val runtime = projectFile("app/src/main/java/com/openautolink/app/input/ProcessVehicleDataRuntime.kt").readText()
        val aasdk = projectFile("app/src/main/java/com/openautolink/app/transport/aasdk/AasdkSession.kt").readText()
        assertTrue(app.contains("EvContributionService.initialize(this)"))
        assertTrue(Regex("""ProcessVehicleDataRuntime\.initialize\(\s*this,\s*learnedEstimator,\s*learnQualificationEnabled\s*=\s*preferences\.evLearnEvaluationEnabled\.stateIn\(""").containsMatchIn(app))
        assertTrue(runtime.contains("EvContributionService.onVehicle(data)"))
        assertTrue(aasdk.contains("EvContributionService.onForecast(receivedForecast)"))
        assertTrue(Regex("""ProcessVehicleDataCoordinator\.withEvLearner\(\s*estimator\s*=\s*estimator,""").containsMatchIn(runtime))
        assertTrue(runtime.contains("if (qualification == null) learn(data, now)"))
        assertTrue(runtime.contains("qualification.onRawBatch(data, now)"))
        assertTrue(runtime.contains("IgnitionMonitor.acceptProcessVehicleData(data)"))
        assertFalse(app.contains("IgnitionMonitor.start(this)"))
        assertTrue(session.contains("ProcessVehicleDataRuntime.attachSessionConsumer(::forwardVehicleData)"))
        assertFalse(session.contains("_vehicleDataForwarder?.stop()"))
    }

    @Test fun `every destructive service caller delegates acquisition and release to the lane`() {
        val service = projectFile("app/src/main/java/com/openautolink/app/diagnostics/EvContributionService.kt").readText()
        assertFalse(service.contains("destructiveLane.enqueue()"))
        assertFalse(service.contains("turn.awaitTurn()"))
        assertFalse(service.contains("turn.finish()"))
        assertEquals(4, "destructiveLane.withTurn".toRegex().findAll(service).count())
        val accuracy = service.substringAfter("private suspend fun recordAccuracyInputs()").substringBefore("fun onProjectionStateChanged")
        assertTrue(accuracy.contains("destructiveLane.withTurn"))
        assertTrue(accuracy.contains("accuracyEpoch.get() == input.epoch && captureCurrent(input.receipt)"))
        assertTrue(service.substringAfter("private fun recoverAfterOrphanExit()").substringBefore("fun reportConsentInvalid").contains("destructiveLane.withTurn"))
        assertTrue(service.substringAfter("suspend fun deletePendingEvContributions()").substringBefore("private suspend fun quiesceUpload").contains("destructiveLane.withTurn"))
        assertTrue(service.substringAfter("private fun observeConsent(").substringBefore("private fun installPassiveNetworkObserver").contains("destructiveLane.withTurn"))
    }

    @Test fun `service carries the saved receipt through upload and conditionally clears that receipt`() {
        val service = projectFile("app/src/main/java/com/openautolink/app/diagnostics/EvContributionService.kt").readText()
        val observe = service.substringAfter("private fun observeConsent(").substringBefore("private fun installPassiveNetworkObserver(")
        val drain = service.substringAfter("private fun attemptNaturalDrain(").substringBefore("private fun scheduleReevaluation(")
        assertTrue(observe.contains("combine(prefs.evContributionScope, prefs.logUploadDeviceLabel)"))
        assertTrue(observe.contains("ConsentReceipt(prefs, saved, it)"))
        assertTrue(service.contains("receipt.prefs.isEvCaptureScopeCurrent(receipt.saved.revision)"))
        assertTrue(drain.contains("mayTransmit = ::uploadCurrent"))
        assertTrue(drain.contains("mayMutate = ::uploadCurrent"))
        assertTrue(drain.contains("if (captureCurrent(receipt)) action() else EvContributionUploader.Outcome.CANCELLED"))
        assertTrue(drain.contains("receipt.prefs.clearEvContributionIfCurrent(receipt.saved)"))
        assertFalse(drain.contains("setEvContributionConsent"))
    }

    private fun projectFile(path: String): File {
        var dir = File(System.getProperty("user.dir") ?: error("user.dir unavailable"))
        repeat(8) {
            val candidate = File(dir, path)
            if (candidate.isFile) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        error("project file not found: $path")
    }
}
