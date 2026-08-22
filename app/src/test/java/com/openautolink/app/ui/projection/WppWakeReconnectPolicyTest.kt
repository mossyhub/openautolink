package com.openautolink.app.ui.projection

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WppWakeReconnectPolicyTest {

    @Test
    fun `idle WPP wake starts without default phone or discovery`() {
        assertTrue(
            WppWakeReconnectPolicy.shouldKickWake(
                wppSelected = true,
                wirelessDiscoveryEnabled = false,
                alwaysAskPhone = true,
                defaultPhonePresent = false,
                resolvedPhonePresent = false,
                connectInFlight = false,
                sessionIdle = true,
            ),
        )
    }

    @Test
    fun `WPP wake does not overlap a connect or live session`() {
        assertFalse(
            WppWakeReconnectPolicy.shouldKickWake(
                wppSelected = true,
                wirelessDiscoveryEnabled = false,
                alwaysAskPhone = false,
                defaultPhonePresent = false,
                resolvedPhonePresent = false,
                connectInFlight = true,
                sessionIdle = true,
            ),
        )
        assertFalse(
            WppWakeReconnectPolicy.shouldKickWake(
                wppSelected = true,
                wirelessDiscoveryEnabled = false,
                alwaysAskPhone = false,
                defaultPhonePresent = false,
                resolvedPhonePresent = false,
                connectInFlight = false,
                sessionIdle = false,
            ),
        )
    }

    @Test
    fun `active WPP owner suppresses delayed wake and ignition edges`() {
        assertFalse(
            WppWakeReconnectPolicy.shouldKickWake(
                wppSelected = true,
                wirelessDiscoveryEnabled = false,
                alwaysAskPhone = false,
                defaultPhonePresent = false,
                resolvedPhonePresent = false,
                connectInFlight = false,
                sessionIdle = true,
                currentWppOwnerPresent = true,
            ),
        )
        assertFalse(
            WppWakeReconnectPolicy.shouldKickIgnition(
                wppSelected = true,
                wirelessDiscoveryEnabled = false,
                alwaysAskPhone = false,
                defaultPhonePresent = false,
                connectInFlight = false,
                sessionIdle = true,
                currentWppOwnerPresent = true,
            ),
        )
    }

    @Test
    fun `legacy hotspot wake still requires configured resolved default`() {
        assertFalse(
            WppWakeReconnectPolicy.shouldKickWake(
                wppSelected = false,
                wirelessDiscoveryEnabled = true,
                alwaysAskPhone = false,
                defaultPhonePresent = true,
                resolvedPhonePresent = false,
                connectInFlight = false,
                sessionIdle = true,
            ),
        )
        assertTrue(
            WppWakeReconnectPolicy.shouldKickWake(
                wppSelected = false,
                wirelessDiscoveryEnabled = true,
                alwaysAskPhone = false,
                defaultPhonePresent = true,
                resolvedPhonePresent = true,
                connectInFlight = false,
                sessionIdle = true,
            ),
        )
    }

    @Test
    fun `idle WPP ignition starts without legacy phone selection state`() {
        assertTrue(
            WppWakeReconnectPolicy.shouldKickIgnition(
                wppSelected = true,
                wirelessDiscoveryEnabled = false,
                alwaysAskPhone = true,
                defaultPhonePresent = false,
                connectInFlight = false,
                sessionIdle = true,
            ),
        )
    }

    @Test
    fun `WPP owner rearm bypasses legacy discovery cooldown`() {
        assertTrue(
            WppWakeReconnectPolicy.cooldownAllows(
                wppSelected = true,
                elapsedSinceAttemptMs = 0L,
                minimumGapMs = 10_000L,
            ),
        )
        assertFalse(
            WppWakeReconnectPolicy.cooldownAllows(
                wppSelected = false,
                elapsedSinceAttemptMs = 0L,
                minimumGapMs = 10_000L,
            ),
        )
    }

    @Test
    fun `stale WPP rearm is rejected immediately before session start`() {
        assertTrue(
            WppWakeReconnectPolicy.preStartRejection(
                wppSelectedNow = false,
                ignitionOff = false,
                sessionIdle = true,
            ) == "transport-changed",
        )
        assertTrue(
            WppWakeReconnectPolicy.preStartRejection(
                wppSelectedNow = true,
                ignitionOff = true,
                sessionIdle = true,
            ) == "ignition-off",
        )
        assertTrue(
            WppWakeReconnectPolicy.preStartRejection(
                wppSelectedNow = true,
                ignitionOff = false,
                sessionIdle = false,
            ) == "session-not-idle",
        )
        assertTrue(
            WppWakeReconnectPolicy.preStartRejection(
                wppSelectedNow = true,
                ignitionOff = false,
                sessionIdle = true,
                currentWppOwnerPresent = true,
            ) == "session-owner-active",
        )
        assertTrue(
            WppWakeReconnectPolicy.preStartRejection(
                wppSelectedNow = true,
                ignitionOff = false,
                sessionIdle = true,
                currentWppOwnerPresent = false,
            ) == null,
        )
    }

    @Test
    fun `release builds never register exported debug controls`() {
        val manager = projectFile(
            "app/src/main/java/com/openautolink/app/session/SessionManager.kt",
        ).readText()
        val btControl = projectFile(
            "app/src/main/java/com/openautolink/app/transport/bluetooth/AaWirelessBtControl.kt",
        ).readText()
        val mainManifest = projectFile("app/src/main/AndroidManifest.xml").readText()
        val debugManifest = projectFile("app/src/debug/AndroidManifest.xml").readText()

        assertTrue(manager.contains("android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE"))
        assertTrue(btControl.contains("private fun isDebuggableBuild(context: Context): Boolean"))
        assertTrue(btControl.contains("if (isDebuggableBuild(context))"))
        val debugGate = btControl.indexOf("if (isDebuggableBuild(context))")
        val exportedRegistration = btControl.indexOf(
            "androidx.core.content.ContextCompat.RECEIVER_EXPORTED",
        )
        assertTrue(debugGate >= 0)
        assertTrue(exportedRegistration > debugGate)
        val settingsReceiver = "android:name=\".diagnostics.SettingsReceiver\""
        assertTrue(mainManifest.contains(settingsReceiver))
        assertTrue(mainManifest.substringAfter(settingsReceiver).substringBefore("</receiver>")
            .contains("android:exported=\"false\""))
        assertTrue(debugManifest.contains(settingsReceiver))
        assertTrue(debugManifest.substringAfter(settingsReceiver).substringBefore("/>")
            .contains("android:exported=\"true\""))
    }

    @Test
    fun `wake and ignition collectors read persisted transport and log WPP owner rearm`() {
        val source = projectFile(
            "app/src/main/java/com/openautolink/app/ui/projection/ProjectionViewModel.kt",
        ).readText()
        val manager = projectFile(
            "app/src/main/java/com/openautolink/app/session/SessionManager.kt",
        ).readText()

        assertTrue(source.contains("WppWakeReconnectPolicy.shouldKickWake("))
        assertTrue(source.contains("WppWakeReconnectPolicy.shouldKickIgnition("))
        assertTrue(source.windowed("preferences.directTransport.first()".length)
            .count { it == "preferences.directTransport.first()" } >= 4)
        assertTrue(source.contains("connectForWppRearm(source = \"wake\")"))
        assertTrue(source.contains("connectForWppRearm(source = \"ignition\")"))
        assertTrue(source.contains("wppRearmSource = wppRearmSource"))
        assertFalse(source.contains("wppRearmFinalAdmission"))
        assertFalse(manager.contains("wppRearmFinalAdmission"))
        assertFalse(manager.contains("admission-check-missing"))
        assertTrue(manager.contains("private suspend fun currentWppRearmRejection("))
        assertTrue(manager.contains("currentWppRearmRejection(wppRearmSource, stage = \"initial\")"))
        assertTrue(manager.contains("currentWppRearmRejection(wppRearmSource, stage = \"final\")"))
        assertTrue(manager.contains("WPP rearm admission checked locally: source=${'$'}wppRearmSource stage=${'$'}stage result=${'$'}{rejection ?: \"accepted\"}"))
        assertTrue(manager.contains("AppPreferences.getInstance(ctx).directTransport.first()"))
        assertTrue(manager.contains("IgnitionMonitor.isOffOrLocked()"))
        assertTrue(source.windowed("currentWppOwnerPresent = sessionManager.hasCurrentWppOwner()".length)
            .count { it == "currentWppOwnerPresent = sessionManager.hasCurrentWppOwner()" } >= 2)
        assertTrue(manager.contains("fun hasCurrentWppOwner(): Boolean = AaWirelessBtControl.hasCurrentWppOwner()"))
        assertFalse(source.contains("AaWirelessBtControl.hasCurrentWppOwner()"))

        val observeBlock = manager
            .substringAfter("val newObserveJob = scope.launch")
            .substringBefore("newObserveJob.invokeOnCompletion")
        val finalAdmission = observeBlock.indexOf(
            "currentWppRearmRejection(wppRearmSource, stage = \"final\")",
        )
        val sessionStart = observeBlock.indexOf("startSession(", startIndex = finalAdmission)
        val finalRejectionBranch = observeBlock.indexOf("if (finalWppRejection != null)")
        val rollback = observeBlock.indexOf(
            "stopWhileLifecycleLocked(cancelObserveJob = false)",
            startIndex = finalRejectionBranch,
        )
        val rejectedComplete = observeBlock.indexOf(
            "startupOutcome.complete(Unit)",
            startIndex = finalRejectionBranch,
        )
        assertTrue(finalRejectionBranch >= 0)
        assertTrue(rollback > finalRejectionBranch)
        assertTrue(rejectedComplete > rollback)
        val exceptionBranch = observeBlock.lastIndexOf("catch (e: Exception)")
        val exceptionComplete = observeBlock.indexOf(
            "startupOutcome.completeExceptionally(e)",
            startIndex = exceptionBranch,
        )
        assertTrue(exceptionBranch >= 0)
        assertTrue(exceptionComplete > exceptionBranch)
        assertFalse(
            observeBlock.substring(exceptionBranch, exceptionComplete)
                .contains("stopWhileLifecycleLocked(cancelObserveJob = false)"),
        )
        val registerScreen = observeBlock.indexOf("registerScreenReceiver()")
        val registerDebug = observeBlock.indexOf("registerDebugReceiver()")
        assertTrue(registerScreen >= 0 && registerScreen < finalAdmission)
        assertTrue(registerDebug > registerScreen && registerDebug < finalAdmission)
        assertTrue(observeBlock.contains("if (isDebuggableBuild()) registerDebugReceiver()"))
        assertTrue(manager.contains("if (!isDebuggableBuild()) return"))
        assertTrue(manager.contains("android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE"))
        assertTrue(finalAdmission >= 0)
        assertTrue(sessionStart > finalAdmission)
        assertTrue(observeBlock.contains("WPP rearm rejected: source="))
        assertTrue(observeBlock.contains("reason=${'$'}finalWppRejection"))

        assertTrue(manager.contains("private val startMutex = kotlinx.coroutines.sync.Mutex()"))
        assertTrue(manager.contains("private suspend fun rollbackLifecycleFailure("))
        val lifecycleStartBlock = manager
            .substringAfter("suspend fun start(")
            .substringBefore("private fun startSession(")
        assertTrue(lifecycleStartBlock.contains("rollbackLifecycleFailure(\"start\", e)"))
        assertTrue(lifecycleStartBlock.indexOf("try {") < lifecycleStartBlock.indexOf("observeJob?.cancelAndJoin()"))
        assertTrue(manager.contains("observeJob?.cancelAndJoin()"))
        assertTrue(manager.contains("val startupOutcome = kotlinx.coroutines.CompletableDeferred<Unit>()"))
        assertTrue(manager.contains("val newObserveJob = scope.launch"))
        assertTrue(manager.contains("observeJob = newObserveJob"))
        assertTrue(manager.contains("newObserveJob.invokeOnCompletion"))
        assertFalse(manager.contains("observeJob?.invokeOnCompletion"))
        assertTrue(manager.contains("suspend fun stop()"))
        assertTrue(manager.contains("private suspend fun stopWhileLifecycleLocked("))
        assertTrue(manager.windowed("startMutex.lock()".length)
            .count { it == "startMutex.lock()" } >= 2)
        assertTrue(manager.windowed("startMutex.unlock()".length)
            .count { it == "startMutex.unlock()" } >= 2)
        assertTrue(manager.contains("stopWhileLifecycleLocked()"))
        assertTrue(manager.contains("@Volatile private var lifecycleGeneration = 0L"))
        assertTrue(manager.contains("val requestGeneration = lifecycleGeneration"))
        assertTrue(manager.contains("requestGeneration != lifecycleGeneration"))
        assertTrue(manager.contains("lifecycleGeneration += 1L"))
        val reconnectBlock = manager
            .substringAfter("suspend fun reconnect(")
            .substringBefore("private suspend fun doReconnectAfterCancel(")
        assertTrue(reconnectBlock.contains("var lifecycleLockAcquired = false"))
        assertTrue(reconnectBlock.contains("lifecycleLockAcquired = true"))
        assertTrue(reconnectBlock.contains("if (lifecycleLockAcquired) startMutex.unlock()"))
        assertTrue(reconnectBlock.contains("startMutex.lock()"))
        assertTrue(reconnectBlock.contains("kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO)"))
        assertTrue(reconnectBlock.contains("rollbackLifecycleFailure(\"reconnect\", e)"))
        assertFalse(reconnectBlock.contains("scope.launch(kotlinx.coroutines.Dispatchers.IO)"))
        val reconnectWorker = manager
            .substringAfter("private suspend fun doReconnectAfterCancel(")
            .substringBefore("    /**")
        val reconnectSessionStart = reconnectWorker.indexOf("startSession(")
        val reconnectWatchers = reconnectWorker.indexOf("observeJob = scope.launch")
        assertTrue(reconnectSessionStart >= 0)
        assertTrue(reconnectWatchers > reconnectSessionStart)
        assertTrue(manager.windowed("kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable)".length)
            .count { it == "kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable)" } >= 3)
        val stopBlock = manager
            .substringAfter("suspend fun stop()")
            .substringBefore("private suspend fun stopWhileLifecycleLocked(")
        assertTrue(stopBlock.contains("kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable)"))
        assertTrue(reconnectBlock.contains("kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable)"))
        assertTrue(manager.contains("decoderWatchJob?.cancelAndJoin()"))
        assertTrue(manager.contains("keyframeWatchJob?.cancelAndJoin()"))
        assertTrue(manager.contains("videoStallWatchJob?.cancelAndJoin()"))
        assertTrue(manager.contains("callStateJob?.cancelAndJoin()"))
        assertTrue(manager.windowed("observeJob?.cancelAndJoin()".length)
            .count { it == "observeJob?.cancelAndJoin()" } >= 2)
        assertTrue(manager.contains("startupOutcome.await()"))

        val startBlock = manager
            .substringAfter("private fun startSession(")
            .substringBefore("private fun prepareNativeSessionStart")
        val ownerInstall = startBlock.indexOf("val ownerToken = installWirelessSessionAdmission(directTransport)")
        val ownerQuery = startBlock.indexOf("AaWirelessBtControl.isCurrentSessionOwner(ownerToken)", startIndex = ownerInstall)
        val outcomeLog = startBlock.indexOf("WPP rearm outcome: source=", startIndex = ownerQuery)
        assertTrue(ownerInstall >= 0)
        assertTrue(ownerQuery > ownerInstall)
        assertTrue(outcomeLog > ownerQuery)
        assertFalse(startBlock.contains("hasCurrentWppOwner()"))
        val control = projectFile(
            "app/src/main/java/com/openautolink/app/transport/bluetooth/AaWirelessBtControl.kt",
        ).readText()
        assertTrue(control.contains("fun isCurrentSessionOwner(token: WppSessionAdmission.Token)"))
        assertTrue(source.contains("WPP rearm rejected: source=${'$'}wppRearmSource reason=${'$'}rejection"))
        assertFalse(source.contains("WPP wake — binding session owner immediately"))
        assertFalse(source.contains("WPP ignition — binding session owner immediately"))
    }

    private fun projectFile(relativePath: String): File {
        val workingDir = File(checkNotNull(System.getProperty("user.dir")))
        return generateSequence(workingDir) { it.parentFile }
            .map { root -> File(root, relativePath) }
            .first { it.isFile }
    }
}
