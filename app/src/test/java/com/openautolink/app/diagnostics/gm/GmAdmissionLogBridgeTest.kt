package com.openautolink.app.diagnostics.gm

import android.app.Application
import com.openautolink.app.diagnostics.DiagnosticLog
import com.openautolink.app.diagnostics.MAX_LOCAL_DIAGNOSTIC_MESSAGE_LENGTH
import com.openautolink.app.diagnostics.live.LiveBuffer
import com.openautolink.app.diagnostics.live.LiveLogSink
import com.openautolink.app.diagnostics.live.LiveRedaction
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32,34],application=Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class GmAdmissionLogBridgeTest {
    @Test fun persistedControlMetadataReachesRealMainLoggerAndOptedInBufferWithoutTruncationOrPayload() {
        val context=RuntimeEnvironment.getApplication()
        val directory=File(context.noBackupFilesDir,"gm-page-admission").apply{mkdirs()}
        val json=JSONObject().put("version",1)
        GmAdmissionDiagnostics.Code.values().forEach{json.put(it.name,Long.MAX_VALUE)}
        json.put("lastElapsed",1).put("diagnosticDroppedAtLastWrite",Long.MAX_VALUE)
            .put("UNTRUSTED","SECRET payload")
        File(directory,"counts").writeText(json.toString())
        DiagnosticLog.clearLocal()
        val buffer=LiveBuffer()
        // Existing capture attached explicitly by this fixture: no owner, credential or HTTP.
        val capture=LiveLogSink.attach(buffer)
        try {
            GmAdmissionDiagnostics.initializeMain(context)
            GmObserverDiagnostics.snapshot(context)
            val deadline=System.nanoTime()+5_000_000_000L
            while(buffer.batch(128).none{it.tag=="gmAdmission" && it.message.contains("senderVisibility:")} && System.nanoTime()<deadline)Thread.sleep(5)
            val events=buffer.batch(128).filter{it.tag=="gmAdmission"}
            assertTrue(events.isNotEmpty())
            assertTrue(events.any{it.message.contains("senderVisibility:")})
            val text=events.joinToString("\n"){LiveRedaction.clean(it.message)}
            assertTrue(text.contains("COMMIT_OK: ${Long.MAX_VALUE}"))
            assertTrue(text.contains("diagnosticDroppedAtLastWrite: ${Long.MAX_VALUE}"))
            assertTrue(text.contains("absence: UNKNOWN"))
            assertTrue(text.contains("oemCachedMembership: UNKNOWN"))
            assertFalse(text.contains("SECRET"));assertFalse(text.contains("UNTRUSTED"))
            events.forEach{assertTrue(it.message.length<=MAX_LOCAL_DIAGNOSTIC_MESSAGE_LENGTH)}
            val until=System.nanoTime()+5_000_000_000L
            while(DiagnosticLog.localLogs.value.none{it.tag=="gmAdmission"} && System.nanoTime()<until){
                // DiagnosticLog coalesces on a delayed HandlerThread. Advance the fixture's
                // paused clock; wall-clock sleep alone never reaches its250ms deadline.
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
                    .idleFor(300,java.util.concurrent.TimeUnit.MILLISECONDS)
                Thread.sleep(5)
            }
            assertTrue(DiagnosticLog.localLogs.value.any{it.tag=="gmAdmission"})
            assertNull(com.openautolink.app.session.SessionManager.instanceOrNull())
        }finally{LiveLogSink.detach(capture)}
    }
}
