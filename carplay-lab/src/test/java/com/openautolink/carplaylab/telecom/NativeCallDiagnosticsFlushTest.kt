package com.openautolink.carplaylab.telecom

import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [32, 34], manifest = Config.NONE, application = android.app.Application::class)
class NativeCallDiagnosticsFlushTest {
    private fun observation(outcome: String, level: Int) {
        val logs = ShadowLog.getLogsForTag("OALNativeCall")
            .filter { it.msg.startsWith("media stage=native-call-flush ") }
        assertEquals("production flush must emit exactly one terminal observation", 1, logs.size)
        assertEquals(level, logs.single().type)
        assertTrue(logs.single().msg.matches(Regex(
            "media stage=native-call-flush outcome=$outcome queueDepth=([0-9]|[1-5][0-9]|6[0-4]) activeWorkerCount=[01] elapsedMs=[0-9]{1,10}",
        )))
        assertNull(logs.single().throwable)
        assertNull("flush telemetry must not enter receipt persistence", NativeCallDiagnostics.sanitize(logs.single().msg))
    }

    @Test fun productionFlushReportsCompleteWithoutChangingPersistence() {
        val persisted = NativeCallDiagnostics.persisted
        ShadowLog.clear()
        assertTrue(NativeCallDiagnostics.flush())
        observation("COMPLETE", Log.INFO)
        assertEquals(persisted, NativeCallDiagnostics.persisted)
    }

    @Test fun productionFlushReportsHeldWorkerTimeoutAndLaterCompletion() {
        assertTrue(NativeCallDiagnostics.flush())
        // Observe the actual singleton boundary without replacing/resetting its owner.
        val worker = NativeCallDiagnostics::class.java.getDeclaredField("worker")
            .apply { isAccessible = true }.get(null) as ThreadPoolExecutor
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        worker.execute { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val persisted = NativeCallDiagnostics.persisted
            ShadowLog.clear()
            assertFalse(NativeCallDiagnostics.flush(1))
            observation("TIMEOUT", Log.WARN)
            assertEquals(1, worker.queue.size)
            assertEquals(persisted, NativeCallDiagnostics.persisted)
        } finally {
            release.countDown()
            assertTrue(NativeCallDiagnostics.flush())
        }
        ShadowLog.clear()
        assertTrue(NativeCallDiagnostics.flush())
        observation("COMPLETE", Log.INFO)
    }
}
