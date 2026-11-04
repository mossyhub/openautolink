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
@Config(sdk=[32,35],application=Application::class,manifest=Config.NONE)
class LiveLaunchNetworkTest {
    @Test fun failedUploadIsConnectingNotLiveOrAmbiguousOffline() {
        val entered=CountDownLatch(1)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val c=LiveController(scope,{object:LiveWire {
            override fun post(path:String,body:JSONObject,bearer:String?):JSONObject {entered.countDown();throw java.io.IOException()}
            override fun close(){}
        }},{JSONObject()})
        try {
            c.start("bench")
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
            while(!c.status.value.contains("retry") && System.nanoTime()<deadline) Thread.sleep(5)
            assertTrue(c.status.value,c.status.value.startsWith("Connecting"))
            assertFalse(c.status.value.startsWith("Live"))
        }finally{c.stop();scope.cancel()}
    }
}
