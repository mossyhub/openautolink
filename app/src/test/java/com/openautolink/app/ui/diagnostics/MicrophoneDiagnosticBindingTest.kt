package com.openautolink.app.ui.diagnostics

import android.app.Application
import android.content.Context
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [32])
@ConscryptMode(ConscryptMode.Mode.OFF)
class MicrophoneDiagnosticBindingTest {
    @Test fun productionBindingIsIdleGatedAndPublishesRealPassiveResultToUiAndLog() {
        val type = try { Class.forName("com.openautolink.app.ui.diagnostics.MicrophoneDiagnosticBinding") }
        catch (_: ClassNotFoundException) { fail("Diagnostics must be wired to the real engine"); throw AssertionError() }
        val app = RuntimeEnvironment.getApplication()
        val allowed = AtomicBoolean(false)
        val binding = type.getConstructor(Context::class.java, kotlin.jvm.functions.Function0::class.java,
            Boolean::class.javaPrimitiveType, kotlin.jvm.functions.Function0::class.java)
            .newInstance(app, { allowed.get() }, false, { false })
        val controller = type.getMethod("getController").invoke(binding) as MicrophoneDiagnosticController
        try {
            assertEquals(MicrophoneDiagnosticPhase.IDLE, controller.state.phase)
            controller.updateForeground(true);controller.updateConsent(true);controller.updateStationary(true)
            controller.inspect()
            assertEquals(MicrophoneDiagnosticPhase.IDLE, controller.state.phase)
            allowed.set(true);controller.refresh();controller.inspect()
            val file = File(app.getExternalFilesDir(null), "openautolink/logs/microphone_diagnostic.log")
            val deadline = System.nanoTime() + 5_000_000_000L
            while ((controller.state.phase != MicrophoneDiagnosticPhase.COMPLETE || !file.exists()) && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10)
            }
            assertEquals(MicrophoneDiagnosticPhase.COMPLETE, controller.state.phase)
            assertEquals(MicrophoneDiagnosticReason.NONE, controller.state.result?.reason)
            assertTrue(controller.state.result!!.arms.isEmpty())
            assertTrue(file.readText().contains("\"comparison\":\"INSPECT\""))
            allowed.set(false);controller.refresh()
            assertFalse(controller.canStart)
        } finally {
            (binding as Closeable).close()
            // close() writes Compose State outside a Compose rule. Once an earlier
            // UI fixture starts GlobalSnapshotManager, deliver its due callback
            // before Robolectric resets the looper and strands the cached dispatcher.
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertFalse(controller.canStart)
    }
    @Test fun completedRunsAreRetainedEvenWhenMainNotificationsAreDelayed() {
        val app = RuntimeEnvironment.getApplication()
        val binding = MicrophoneDiagnosticBinding(app, { true }, false, { false })
        val controller = binding.controller
        fun histories() = File(app.getExternalFilesDir(null), "openautolink/logs").listFiles().orEmpty()
            .filter { it.name.startsWith("microphone_diagnostic_run_") && it.extension == "log" }
        try {
            controller.updateForeground(true);controller.updateConsent(true);controller.updateStationary(true)
            repeat(3) {
                controller.inspect()
                val end = System.nanoTime() + 3_000_000_000L
                while (controller.backend!!.state.phase != MicrophoneDiagnosticPhase.COMPLETE && System.nanoTime() < end) Thread.sleep(5)
                assertEquals(MicrophoneDiagnosticPhase.COMPLETE, controller.backend!!.state.phase)
                // Do not drain Main before the next explicit attempt replaces current state.
            }
            val end = System.nanoTime() + 3_000_000_000L
            while (histories().size < 3 && System.nanoTime() < end) {
                shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10)
            }
            assertEquals("Capture every completion, not only the latest callback-visible result", 3, histories().size)
        } finally { binding.close();shadowOf(Looper.getMainLooper()).idle() }
    }

    @Test fun bothProductionDiagnosticEntrypointsReceiveSameBinding() {
        val source = File("src/main/java/com/openautolink/app/ui/navigation/AppNavHost.kt").readText()
        assertEquals("Both overlay and navigation diagnostics must use real binding",2,
            Regex("microphoneController = microphoneBinding.controller").findAll(source).count())
        assertTrue(source.contains("projectionViewModel::microphoneDiagnosticEligible"))
        assertTrue(source.contains("onDispose { microphoneBinding.close() }"))
    }
}
