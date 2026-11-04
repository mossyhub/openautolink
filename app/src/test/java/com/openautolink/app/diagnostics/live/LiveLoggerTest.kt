package com.openautolink.app.diagnostics.live

import android.app.Application
import com.openautolink.app.diagnostics.DiagnosticLog
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32,35],application=Application::class, manifest=Config.NONE)
class LiveLoggerTest {
    @Test fun centralLoggerTeePreservesFullMessageAndRedactsBeforeWire() {
        val q=LiveBuffer(); val capture=LiveLogSink.attach(q)
        try {
            val exact="BENCH actual central logger marker\n"+"preserved ".repeat(80)
            DiagnosticLog.i("BENCH",exact)
            DiagnosticLog.w("BENCH","password=not-for-network\nnormal line")
            val sent=mutableListOf<JSONObject>()
            val wire=object:LiveWire {override fun close(){};override fun post(path:String,body:JSONObject,bearer:String?):JSONObject {
                if(path=="/v2/connect")return JSONObject().put("token","ephemeral")
                sent.add(body); return JSONObject().put("ack",body.getJSONArray("events").getJSONObject(1).getLong("seq"))
            }}
            LiveRelay(q,wire,"bench-process","fake-bench-credential",{JSONObject()},{true}).sendOnce()
            val rows=sent.single().getJSONArray("events")
            assertEquals(exact,rows.getJSONObject(0).getString("message"))
            assertEquals("INFO",rows.getJSONObject(0).getString("level"))
            assertEquals("BENCH",rows.getJSONObject(0).getString("tag"))
            assertTrue(rows.getJSONObject(0).getLong("wallMs")>0)
            assertFalse(rows.getJSONObject(1).getString("message").contains("not-for-network"))
            assertTrue(rows.getJSONObject(1).getString("message").contains("normal line"))
        }finally{LiveLogSink.detach(capture)}
        DiagnosticLog.i("BENCH","after detach")
        assertTrue(q.batch(64).isEmpty())
    }
    @Test fun actualSaveProducerIsRetainedAsTimestampedSnapshotReceipt() {
        val q=LiveBuffer();val capture=LiveLogSink.attach(q)
        try {
            com.openautolink.app.ui.settings.SettingsSaveTransaction.next().event("bench","success","synthetic=true")
            val receipts=LiveSnapshot.read(org.robolectric.RuntimeEnvironment.getApplication(),-1).getJSONArray("receipts")
            assertEquals(1,receipts.length());assertEquals("SAVE",receipts.getJSONObject(0).getString("kind"))
            assertTrue(receipts.getJSONObject(0).getString("message").startsWith("SETTINGS_SAVE_V1 "))
            assertTrue(receipts.getJSONObject(0).getString("message").contains("synthetic=true"))
            assertTrue(receipts.getJSONObject(0).getLong("wallMs")>0)
        }finally{LiveLogSink.detach(capture);q.clear()}
    }
    @Test fun existingOwnProcessCaptureLineKeepsRawTimestampAndSeverity() {
        val q=LiveBuffer();val capture=LiveLogSink.attach(q)
        try {
            LiveLogSink.logcat("11-01 12:34:56.123  1234  1235 W OAL-BENCH: own process bench marker")
            val row=q.batch(64).single()
            assertEquals("WARN",row.level);assertEquals("OAL-BENCH",row.tag)
            assertEquals("logcat",row.source)
            assertTrue(row.message.startsWith("11-01 12:34:56.123"))
        }finally{LiveLogSink.detach(capture)}
    }
}
