package com.openautolink.app.diagnostics.live

import android.app.Application
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32,35],application=Application::class,manifest=Config.NONE)
class LiveNetworkRetryTest {
    private class Network:LiveNetwork {
        override val state=MutableStateFlow(LiveInternet.OFFLINE)
        var closed=false
        override fun close(){closed=true}
    }
    private fun await(predicate:()->Boolean){val end=System.nanoTime()+5_000_000_000;while(!predicate()&&System.nanoTime()<end)Thread.sleep(5);assertTrue(predicate())}
    @Test fun offlineFirstConnectWaitsThenValidatedReturnWakesWithoutParallelRequests() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO);val net=Network();val posts=AtomicInteger();val concurrent=AtomicInteger();val maximum=AtomicInteger()
        val c=LiveController(scope,{object:LiveWire {
            override fun post(path:String,body:JSONObject,bearer:String?):JSONObject {
                maximum.accumulateAndGet(concurrent.incrementAndGet(),::maxOf)
                try{posts.incrementAndGet();throw java.io.IOException()}finally{concurrent.decrementAndGet()}
            };override fun close(){}
        }},{JSONObject()},networkFactory={net})
        try {
            c.start("bench");await{c.status.value.startsWith("Waiting for internet")};assertEquals(0,posts.get())
            net.state.value=LiveInternet.VALIDATED;await{posts.get()==1}
            repeat(1000){net.state.value=LiveInternet.VALIDATED};Thread.sleep(300);assertEquals(1,posts.get())
            net.state.value=LiveInternet.OFFLINE;Thread.sleep(100)
            val start=System.nanoTime();net.state.value=LiveInternet.VALIDATED
            await{posts.get()==2};assertTrue(System.nanoTime()-start<1_500_000_000)
            c.stop();assertTrue(net.closed);val stopped=posts.get()
            repeat(100){net.state.value=LiveInternet.OFFLINE;net.state.value=LiveInternet.VALIDATED}
            Thread.sleep(300);assertEquals(stopped,posts.get());assertEquals("Off",c.status.value);assertEquals(1,maximum.get())
        }finally{c.stop();scope.cancel()}
    }
    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    @Test fun delayedInitialValidatedCallbackCannotShortenFirstFailureBackoff() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val raw=MutableStateFlow(LiveInternet.OFFLINE)
        val collectors=AtomicInteger();val entered=java.util.concurrent.CountDownLatch(1)
        val release=java.util.concurrent.CountDownLatch(1);val posts=AtomicInteger()
        val network=object:LiveNetwork {
            override val state=object:kotlinx.coroutines.flow.StateFlow<LiveInternet> by raw {
                override suspend fun collect(collector:kotlinx.coroutines.flow.FlowCollector<LiveInternet>):Nothing {
                    val isWatcher=collectors.incrementAndGet()==1
                    var firstValidated=true
                    raw.collect { value ->
                        if(isWatcher && value==LiveInternet.VALIDATED && firstValidated) {
                            firstValidated=false;entered.countDown()
                            withContext(Dispatchers.IO){check(release.await(5,java.util.concurrent.TimeUnit.SECONDS))}
                        }
                        collector.emit(value)
                    }
                    error("StateFlow returned")
                }
            }
            override fun close(){release.countDown()}
        }
        val controller=LiveController(scope,{object:LiveWire {
            override fun post(path:String,body:JSONObject,bearer:String?):JSONObject {posts.incrementAndGet();throw java.io.IOException()}
            override fun close(){}
        }},{JSONObject()},networkFactory={network})
        try {
            controller.start("fixture");await{controller.status.value.startsWith("Waiting for internet")}
            await{collectors.get()>=2} // watcher returned from UNDISTPATCHED startup; outer first{} installed
            raw.value=LiveInternet.VALIDATED
            assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS))
            await{posts.get()==1}
            // Outer first{} already saw validated and attempted. The delayed watcher delivery
            // is NOT a second physical network return and must not cancel the 2s+ backoff.
            release.countDown();Thread.sleep(300);assertEquals(1,posts.get())
        }finally{release.countDown();controller.stop();scope.cancel()}
    }
    @Test fun lostNetworkCannotRemainLiveWhileAnOldUploadIsHeld() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO);val net=Network();net.state.value=LiveInternet.VALIDATED
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        val c=LiveController(scope,{object:LiveWire {
            override fun post(path:String,body:JSONObject,bearer:String?):JSONObject {
                if(path=="/v2/connect")return JSONObject().put("token","session")
                entered.countDown();release.await(30,java.util.concurrent.TimeUnit.SECONDS);return JSONObject().put("ack",0)
            };override fun close(){release.countDown()}
        }},{JSONObject()},networkFactory={net})
        try{c.start("bench");assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));net.state.value=LiveInternet.OFFLINE
            await{c.status.value.startsWith("Waiting for internet")};release.countDown();Thread.sleep(150);assertTrue(c.status.value,c.status.value.startsWith("Waiting for internet"))
        }finally{release.countDown();c.stop();scope.cancel()}
    }
    @Test fun missingIngestAckCannotPublishLiveEvenWithAnEmptyBatch() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO);val calls=AtomicInteger()
        val c=LiveController(scope,{object:LiveWire {
            override fun post(path:String,body:JSONObject,bearer:String?):JSONObject {calls.incrementAndGet();return if(path=="/v2/connect")JSONObject().put("token","session") else JSONObject()}
            override fun close(){}
        }},{JSONObject()})
        try{c.start("bench");await{calls.get()>=2};Thread.sleep(100);assertFalse(c.status.value,c.status.value.startsWith("Live"))}finally{c.stop();scope.cancel()}
    }
    @Test fun liveRequiresSuccessfulIngestAck() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO);val net=Network();net.state.value=LiveInternet.VALIDATED
        val c=LiveController(scope,{object:LiveWire {
            override fun post(path:String,body:JSONObject,bearer:String?)=if(path=="/v2/connect")JSONObject().put("token","session") else JSONObject().put("ack",0)
            override fun close(){}
        }},{JSONObject()},networkFactory={net})
        try{c.start("bench");await{c.status.value.startsWith("Live")};net.state.value=LiveInternet.OFFLINE;await{c.status.value.startsWith("Waiting for internet")}}finally{c.stop();scope.cancel()}
    }
}
