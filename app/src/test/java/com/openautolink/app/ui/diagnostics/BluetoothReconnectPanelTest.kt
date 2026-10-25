package com.openautolink.app.ui.diagnostics

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.test.core.app.ApplicationProvider
import com.openautolink.app.diagnostics.BluetoothReconnectProcess
import com.openautolink.app.diagnostics.BluetoothReconnectRecorder
import com.openautolink.app.ui.testing.createOalComposeRule
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [32], qualifiers = "w800dp-h900dp-mdpi")
class BluetoothReconnectPanelTest {
    @get:Rule val compose = createOalComposeRule()
    @get:Rule val temp = TemporaryFolder()

    @Test fun actualRecoveryReadFailureThroughArmIsVisibleFiniteAndRetryable() {
        val bad = java.io.File(temp.root, "bluetooth_reconnect_run_0000000000000000001.log").apply { mkdir() }
        val held = java.io.File(bad, "held").apply { writeText("private retained evidence") }
        val recorder = BluetoothReconnectRecorder(temp.root)
        val uncaught = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, error -> uncaught.add(error) })
        val process = BluetoothReconnectProcess(ApplicationProvider.getApplicationContext(), recorder, scope, 32)
        try {
            compose.setContent { MaterialTheme { BluetoothReconnectPanel(process) } }
            compose.onNodeWithTag("bt-recorder-consent").performScrollTo().performClick()
            compose.onNodeWithTag("bt-recorder-arm").performScrollTo().performClick()
            compose.waitUntil(5000) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                (!process.busy.value && process.notice.value.isNotEmpty()) || uncaught.isNotEmpty()
            }
            assertTrue("Arm recovery must not escape its coroutine", uncaught.isEmpty())
            assertFalse(recorder.isRunning()); assertFalse(process.busy.value)
            compose.onNodeWithTag("bt-recorder-status").assertTextContains("Storage failed — incomplete evidence; coverage unknown; retry with fresh consent")
            compose.onNodeWithTag("bt-recorder-stop").assertIsNotEnabled()
            assertTrue(process.coverage.value.contains("not registered"))
            assertEquals("private retained evidence", held.readText())
            assertTrue(bad.isDirectory)
            compose.onNodeWithTag("bt-recorder-delete").performScrollTo().performClick()
            compose.waitUntil(5000) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                !process.busy.value && process.notice.value.startsWith("Delete incomplete")
            }
            assertFalse(process.notice.value.contains("deleted"))
            compose.onNodeWithTag("bt-recorder-status").assertTextContains("Deletion failed — evidence retained or unknown; retry")
        } finally {
            compose.runOnIdle { process.stop(); scope.cancel() }
            recorder.flush(2000); recorder.close()
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
        }
    }
    @Test fun actualTerminalStorageFailureIsVisibleAndNeverAcknowledgedSaved() {
        val recorder = BluetoothReconnectRecorder(temp.root)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val process = BluetoothReconnectProcess(ApplicationProvider.getApplicationContext(), recorder, scope, 32)
        try {
            assertTrue(recorder.start(true)); assertTrue(recorder.flush(2000))
            val file = temp.root.listFiles()!!.single { it.extension == "log" }
            val pending = java.io.File(temp.root, file.name + ".pending").apply { mkdirs() }
            java.io.File(pending, "held").writeText("fixture")
            compose.setContent { MaterialTheme { BluetoothReconnectPanel(process) } }
            compose.onNodeWithTag("bt-recorder-stop").performScrollTo().performClick()
            compose.waitUntil(5000) { recorder.status.value.startsWith("Storage failed") }
            assertFalse(recorder.flush(2000))
            compose.onNodeWithTag("bt-recorder-status")
                .assertTextContains("Storage failed — incomplete evidence; trailing durability unknown; fresh consent required")
            compose.onNodeWithTag("bt-recorder-stop").assertIsNotEnabled()
            assertTrue(file.readText().contains("persistence=pending"))
            assertFalse(file.readText().contains("terminal_durable=true"))
            pending.deleteRecursively()
        } finally {
            compose.runOnIdle { process.stop(); scope.cancel() }
            recorder.flush(2000); recorder.close()
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
        }
    }

    @Test fun explicitConsentArmSetupAttestationBookmarkStopAndDeleteUseRealStore() {
        val recorder = BluetoothReconnectRecorder(temp.root)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val process = BluetoothReconnectProcess(ApplicationProvider.getApplicationContext(), recorder, scope, 32)
        try {
            compose.setContent { MaterialTheme { BluetoothReconnectPanel(process) } }
            compose.onNodeWithTag("bt-recorder-arm").assertIsNotEnabled()
            compose.onNodeWithTag("bt-recorder-consent").performScrollTo().performClick()
            compose.onNodeWithTag("bt-recorder-setup-media-off").performScrollTo().performClick()
            compose.onNodeWithTag("bt-recorder-arm").performScrollTo().performClick()
            compose.waitUntil(5000) { recorder.isRunning() }
            compose.onNodeWithTag("bt-bookmark-oal_selected").performScrollTo().performClick()
            compose.onNodeWithTag("bt-recorder-stop").performScrollTo().performClick()
            compose.waitUntil(5000) { recorder.status.value.startsWith("Saved locally") }
            val text = temp.root.listFiles()!!.single { it.extension == "log" }.readText()
            assertTrue(text.contains("kind=bookmark state=phone_on_media_off"))
            assertTrue(text.contains("kind=bookmark state=oal_selected"))
            assertTrue(text.contains("authority=user_report"))
            assertFalse(text.contains("policy=off"))
            compose.onNodeWithTag("bt-recorder-arm").assertIsNotEnabled()
            compose.onNodeWithTag("bt-recorder-delete").performScrollTo().performClick()
            compose.waitUntil(5000) { recorder.status.value.contains("deleted") }
            assertTrue(temp.root.listFiles()!!.isEmpty())
        } finally {
            compose.runOnIdle { process.stop(); scope.cancel() }
            recorder.flush(2000); recorder.close()
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
        }
    }
}
