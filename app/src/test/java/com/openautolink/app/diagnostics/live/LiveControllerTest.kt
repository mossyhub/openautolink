package com.openautolink.app.diagnostics.live

import android.app.Application
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32],application=Application::class,manifest=Config.NONE)
class LiveControllerTest {
    @Test fun offByDefaultStopClosesExactWireAndRejectsLateCompletion() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);var closed=false
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val wire=object:LiveWire {override fun post(path:String,body:JSONObject,bearer:String?):JSONObject {
            entered.countDown();release.await(5,TimeUnit.SECONDS)
            return JSONObject().put("token","ephemeral").put("ack",0)
        };override fun close(){closed=true;release.countDown()}}
        val c=LiveController(scope,{wire},{JSONObject()})
        try {
            assertEquals("Off",c.status.value)
            c.start("bench-credential")
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            c.stop();assertTrue(closed);assertEquals("Off",c.status.value)
            Thread.sleep(100);assertEquals("Off",c.status.value)
        }finally{c.stop();scope.cancel();release.countDown()}
    }
}
