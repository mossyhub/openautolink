package com.openautolink.app.diagnostics.live

import android.app.Application
import com.openautolink.app.diagnostics.DiagnosticLog
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Explicit host-orchestrated real public TLS bench. Never fabricate car facts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32],application=Application::class,manifest=Config.NONE)
class LivePublicRoundTripTest {
    private fun waitFor(file:File) { val end=System.nanoTime()+90_000_000_000L;while(!file.exists()&&System.nanoTime()<end)Thread.sleep(100);assertTrue("bench coordination deadline",file.exists()) }
    private fun page(token:String):JSONObject {
        val c=URL(LiveEndpoint.LEGACY.base+"/v1/events?after=0").openConnection() as HttpsURLConnection
        c.connectTimeout=5000;c.readTimeout=5000;c.instanceFollowRedirects=false
        c.setRequestProperty("Authorization","Bearer $token")
        try{assertEquals(200,c.responseCode);return JSONObject(c.inputStream.bufferedReader().use{it.readText()})}finally{c.disconnect()}
    }
    @Test fun actualLoggerThroughPublicTlsWithRealSidecarOutageAndReconnect() {
        val path=System.getenv("OAL_LIVE_BENCH")
        assumeTrue("explicit isolated public bench only",path!=null)
        val state=File(path!!); val dir=state.parentFile!!;val auth=JSONObject(state.readText())
        val q=LiveBuffer(3,4096);val capture=LiveLogSink.attach(q);val wire=LiveHttps(LiveEndpoint.LEGACY)
        val relay=LiveRelay(q,wire,"android-bench-process",auth.getString("deviceCredential"),
            {LiveSnapshot.read(RuntimeEnvironment.getApplication(),-1)},{true})
        try {
            val exact="BENCH actual DiagnosticLog producer\nmultiline survives network"
            DiagnosticLog.i("BENCH",exact)
            DiagnosticLog.w("BENCH","password=not-for-network\nnormal line")
            relay.sendOnce()
            val first=page(auth.getString("reader")).getJSONArray("events")
            assertEquals(2,first.length());assertEquals(exact,first.getJSONObject(0).getString("message"))
            assertFalse(first.getJSONObject(1).getString("message").contains("not-for-network"))
            File(dir,"outage.request").writeText("bench ready")
            waitFor(File(dir,"offline.ready"))
            repeat(8){DiagnosticLog.i("BENCH","BENCH offline marker $it")}
            var failed=false
            try{relay.sendOnce()}catch(_:java.io.IOException){failed=true}
            assertTrue("actual network outage must fail",failed)
            assertEquals(5L,q.dropped)
            File(dir,"outage.observed").writeText("actual network request failed; bounded queue retained")
            waitFor(File(dir,"online.ready"))
            // Restart loses ephemeral bearer: first request must be denied, next reauthenticates.
            var denied=false
            try{relay.sendOnce()}catch(e:LiveHttpFailure){denied=e.status==401}
            assertTrue("restart must revoke old connection",denied)
            relay.sendOnce()
            val freshAuth=JSONObject(state.readText());val after=page(freshAuth.getString("reader"))
            assertEquals(5L,after.getLong("dropped"));val rows=after.getJSONArray("events")
            assertEquals(3,rows.length());assertEquals(8L,rows.getJSONObject(0).getLong("seq"))
            assertEquals("BENCH offline marker 5",rows.getJSONObject(0).getString("message"))
            assertEquals("BENCH offline marker 7",rows.getJSONObject(2).getString("message"))
            assertEquals("unknown",after.getJSONObject("snapshot").getString("oemVisibility"))
            assertTrue(q.batch(64).isEmpty())
        }finally{wire.close();LiveLogSink.detach(capture);q.clear()}
    }
}
