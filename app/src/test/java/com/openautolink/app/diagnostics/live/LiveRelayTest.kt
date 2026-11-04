package com.openautolink.app.diagnostics.live

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32],application=Application::class, manifest=Config.NONE)
class LiveRelayTest {
    @Test fun failedSendRetainsOrderedActualRecordsAndReconnectReportsLoss() {
        val q=LiveBuffer(3,4096); val sent=mutableListOf<JSONObject>();var fail=true
        val wire=object:LiveWire {
            override fun post(path:String,body:JSONObject,bearer:String?):JSONObject {
                if(path=="/v2/connect")return JSONObject().put("token","ephemeral")
                if(fail)throw java.io.IOException("bench outage")
                sent.add(body); return JSONObject().put("ack",body.getJSONArray("events").getJSONObject(body.getJSONArray("events").length()-1).getLong("seq"))
            }
            override fun close() {}
        }
        val relay=LiveRelay(q,wire,"bench-process","remembered",{JSONObject()},{true})
        q.offer(123,1,"INFO","BENCH","first\nsecond","diagnostic",0)
        try { relay.sendOnce(); fail("expected outage") }catch(_:java.io.IOException){}
        repeat(5){q.offer(124L+it,2L+it,"WARN","BENCH","bench $it","diagnostic",0)}
        fail=false;relay.sendOnce()
        val b=sent.single();assertEquals(3,b.getLong("dropped"));assertEquals(4,b.getJSONArray("events").getJSONObject(0).getLong("seq"))
        assertTrue(q.batch(64).isEmpty())
    }
    @Test fun staleOwnerCannotSendOrAcknowledge() {
        val q=LiveBuffer();q.offer(1,1,"INFO","BENCH","marker","diagnostic",0)
        var calls=0
        val wire=object:LiveWire {override fun post(path:String,body:JSONObject,bearer:String?):JSONObject { calls++;return JSONObject() };override fun close(){}}
        LiveRelay(q,wire,"bench","credential",{JSONObject()},{false}).sendOnce()
        assertEquals(0,calls);assertEquals(1,q.batch(64).size)
    }
}
