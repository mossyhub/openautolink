package com.openautolink.app.diagnostics.live

import android.app.Application
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32,35],application=Application::class,manifest=Config.NONE)
class LiveEndpointTest {
    private val a=LiveEndpoint.parse("https://localhost:10443/oal")
    private val b=LiveEndpoint.parse("https://localhost:10444/custom/oal")
    private class Consent(var endpoint:LiveEndpoint,var enabled:Boolean=false):LiveConsent {
        override fun load()=enabled
        override fun save(enabled:Boolean){this.enabled=enabled}
        override fun loadEndpoint()=endpoint
        override fun saveEndpoint(endpoint:LiveEndpoint){this.endpoint=endpoint;enabled=false}
    }
    private class Credentials:LiveCredentials {
        val entries=java.util.concurrent.ConcurrentHashMap<LiveEndpoint,String>()
        override fun load():String?=error("unscoped read")
        override fun save(credential:String)=error("unscoped write")
        override fun clear()=error("unscoped clear")
        override fun load(endpoint:LiveEndpoint)=entries[endpoint]
        override fun save(endpoint:LiveEndpoint,credential:String){entries[endpoint]=credential}
        override fun clear(endpoint:LiveEndpoint){entries.remove(endpoint)}
    }
    private fun await(p:()->Boolean){val end=System.nanoTime()+5_000_000_000;while(!p()&&System.nanoTime()<end)Thread.sleep(5);assertTrue(p())}
    @Test fun canonicalHttpsAndLanBasesAndInvalidUrls() {
        assertEquals("https://example.com/oal",LiveEndpoint.parse(" HTTPS://EXAMPLE.COM:443/oal/// ").base)
        listOf("https://192.168.1.50:8443/oal","https://localhost/oal","https://[::1]/oal","https://diagnostics.lan").forEach{assertEquals(it,LiveEndpoint.parse(it).base)}
        listOf("http://localhost/oal","https://user:password@host/oal","https://@host/oal","https://host/oal?token=secret","https://host/oal#secret","https://host/a/../oal","https://host/%2e%2e/oal","https://host/%252f/oal","https://host/a%2foal","https://host/oal\r\nX: bad","https://host\\bad/oal","https://host:0/oal","https://host:65536/oal","https://host/a//oal","").forEach{
            assertTrue("must reject unsafe endpoint",runCatching{LiveEndpoint.parse(it)}.isFailure)
        }
        assertNotEquals(a,b)
        assertNotEquals(a,LiveEndpoint.parse("https://localhost:10443/other"))
    }
    @Test fun atomicEndpointConsentColdRestoreAndLegacyMigration() {
        val context=RuntimeEnvironment.getApplication();val file=java.io.File(context.noBackupFilesDir,"oal-live-launch-consent.bin");file.delete()
        try {
            file.writeBytes(byteArrayOf(1));val store=LiveConsentStore(context)
            assertEquals(LiveEndpoint.LEGACY,store.loadEndpoint());assertTrue(store.load())
            store.saveEndpoint(b);assertFalse(store.load());assertEquals(b,LiveConsentStore(context).loadEndpoint())
            store.save(true);assertEquals(b,LiveConsentStore(context).loadEndpoint());assertTrue(LiveConsentStore(context).load())
            file.writeText("corrupt");assertFalse(store.load())
        }finally{file.delete()}
    }
    @Test fun switchStopsARequiresExplicitBAndRevokeUsesOnlyAppliedEndpoint() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO);val store=Consent(a,true);val credentials=Credentials().apply{entries[a]="credential-A"}
        val requests=CopyOnWriteArrayList<Triple<LiveEndpoint,String,String>>()
        val owner=LiveDiagnosticsOwner(RuntimeEnvironment.getApplication(),scope,credentials,store,networkFactory={UnknownLiveNetwork},endpointWireFactory={endpoint->object:LiveWire{
            override fun post(path:String,body:JSONObject,bearer:String?):JSONObject {
                requests.add(Triple(endpoint,path,body.optString("deviceCredential",body.optString("code"))))
                return when(path){"/v2/pair"->JSONObject().put("deviceCredential","credential-B");"/v2/connect"->JSONObject().put("token","session");else->JSONObject().put("ack",0)}
            }
            override fun close(){}
        }})
        try {
            owner.restore();await{owner.status.value.startsWith("Live")};assertEquals(a,owner.endpoint.value)
            owner.applyEndpoint(b.base);await{!owner.busy.value && owner.endpoint.value==b}
            assertFalse(store.enabled);assertFalse(owner.autoConnect.value);assertTrue(requests.none{it.first==b});assertEquals("credential-A",credentials.entries[a])
            owner.enable("");await{owner.status.value.contains("pairing required")};assertTrue(requests.none{it.first==b})
            owner.enable("one-two-three-four-five");await{owner.status.value.startsWith("Live")}
            assertTrue(requests.any{it.first==b && it.second=="/v2/pair"})
            assertTrue(requests.none{it.first==b && it.third=="credential-A"})
            owner.revoke();await{owner.status.value.contains("owner revoked")}
            assertTrue(requests.any{it.first==b && it.second=="/v2/revoke" && it.third=="credential-B"})
            assertTrue(requests.none{it.first==a && it.second=="/v2/revoke"})
            assertEquals("credential-A",credentials.entries[a]);assertNull(credentials.entries[b])
            owner.applyEndpoint(a.base);await{!owner.busy.value && owner.endpoint.value==a};owner.enable("");await{owner.status.value.startsWith("Live")}
            assertEquals(0,requests.count{it.first==a && it.second=="/v2/pair"})
        }finally{owner.stop();scope.cancel()}
    }
    @Test fun heldOldPairCompletionAndNetworkCallbackCannotActivateAfterApply() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO);val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val credentials=Credentials();val network=MutableStateFlow(LiveInternet.OFFLINE);val posts=CopyOnWriteArrayList<LiveEndpoint>()
        val owner=LiveDiagnosticsOwner(RuntimeEnvironment.getApplication(),scope,credentials,Consent(a),networkFactory={object:LiveNetwork{override val state=network;override fun close(){}}},endpointWireFactory={endpoint->object:LiveWire{
            override fun post(path:String,body:JSONObject,bearer:String?):JSONObject {posts.add(endpoint);entered.countDown();release.await(5,TimeUnit.SECONDS);return JSONObject().put("deviceCredential","old-A")}
            override fun close(){}
        }})
        try {
            owner.restore();await{owner.ready.value};owner.enable("one-two-three-four-five");assertTrue(entered.await(5,TimeUnit.SECONDS))
            owner.applyEndpoint(b.base);release.countDown();await{!owner.busy.value && owner.endpoint.value==b}
            network.value=LiveInternet.VALIDATED;Thread.sleep(200)
            assertNull(credentials.entries[a]);assertNull(credentials.entries[b]);assertEquals(listOf(a),posts.toList());assertTrue(owner.status.value.startsWith("Off"))
        }finally{release.countDown();owner.stop();scope.cancel()}
    }
    @Test fun pendingApplyRejectsPairEnableRevokeAndPolicyMutations() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO);val entered=CountDownLatch(1);val release=CountDownLatch(1)
        var saved=a;var consent=false
        val store=object:LiveConsent {
            override fun load()=consent;override fun save(enabled:Boolean){consent=enabled}
            override fun loadEndpoint()=saved
            override fun saveEndpoint(endpoint:LiveEndpoint){entered.countDown();release.await(5,TimeUnit.SECONDS);saved=endpoint;consent=false}
        }
        val owner=LiveDiagnosticsOwner(RuntimeEnvironment.getApplication(),scope,Credentials(),store,endpointWireFactory={error("busy transaction must not contact any endpoint")})
        try {
            owner.restore();await{owner.ready.value};owner.applyEndpoint(b.base);assertTrue(entered.await(5,TimeUnit.SECONDS));assertTrue(owner.busy.value)
            owner.enable("one-two-three-four-five");owner.updateAutoConnect(true);owner.revoke();owner.applyEndpoint("https://third.example/oal")
            release.countDown();await{!owner.busy.value};assertEquals(b,owner.endpoint.value);assertEquals(b,saved);assertFalse(consent);assertFalse(owner.autoConnect.value)
        }finally{release.countDown();owner.stop();scope.cancel()}
    }
    @Test fun failedApplyRetainsEndpointAndPairingWithoutNetworkOrAutomaticRestart() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO);val credentials=Credentials().apply{entries[a]="A"}
        val owner=LiveDiagnosticsOwner(RuntimeEnvironment.getApplication(),scope,credentials,object:LiveConsent {
            override fun load()=false;override fun save(enabled:Boolean){}
            override fun loadEndpoint()=a;override fun saveEndpoint(endpoint:LiveEndpoint){throw java.io.IOException()}
        },endpointWireFactory={error("failed switch must not connect")})
        try {
            owner.restore();await{owner.ready.value};owner.applyEndpoint(b.base);await{!owner.busy.value && owner.status.value.contains("not saved")}
            assertEquals(a,owner.endpoint.value);assertEquals("A",credentials.entries[a]);assertNull(credentials.entries[b])
        }finally{owner.stop();scope.cancel()}
    }
    @Test fun lateConnectCompletionCannotIngestOrKeepOldCaptureAfterSwitch() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO);val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val requests=CopyOnWriteArrayList<String>()
        val owner=LiveDiagnosticsOwner(RuntimeEnvironment.getApplication(),scope,Credentials().apply{entries[a]="A"},Consent(a,true),networkFactory={UnknownLiveNetwork},endpointWireFactory={endpoint->object:LiveWire{
            override fun post(path:String,body:JSONObject,bearer:String?):JSONObject {assertEquals(a,endpoint);requests.add(path);entered.countDown();release.await(5,TimeUnit.SECONDS);return JSONObject().put("token","stale")}
            override fun close(){}
        }})
        try {
            owner.restore();assertTrue(entered.await(5,TimeUnit.SECONDS))
            LiveLogSink.diagnostic(1,"INFO","test","SETTINGS_SAVE_V1 test active owner")
            assertEquals(1,LiveLogSink.snapshotReceipts().length())
            owner.applyEndpoint(b.base);await{!owner.busy.value && owner.endpoint.value==b}
            assertEquals(0,LiveLogSink.snapshotReceipts().length())
            release.countDown();Thread.sleep(200)
            assertEquals(listOf("/v2/connect"),requests.toList());assertTrue(owner.status.value.startsWith("Off"))
        }finally{release.countDown();owner.stop();scope.cancel()}
    }
    @Test fun optedInBRestoresOfflineThenNetworkReturnStaysOnBAndStopFencesCallback() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO);val network=MutableStateFlow(LiveInternet.OFFLINE);val requests=CopyOnWriteArrayList<LiveEndpoint>()
        val owner=LiveDiagnosticsOwner(RuntimeEnvironment.getApplication(),scope,Credentials().apply{entries[b]="B";entries[a]="A"},Consent(b,true),networkFactory={object:LiveNetwork{override val state=network;override fun close(){}}},endpointWireFactory={endpoint->object:LiveWire{
            override fun post(path:String,body:JSONObject,bearer:String?):JSONObject {requests.add(endpoint);if(path=="/v2/connect")assertEquals("B",body.getString("deviceCredential"));return JSONObject().put("token","session").put("ack",0)}
            override fun close(){}
        }})
        try {
            owner.restore();await{owner.status.value.startsWith("Waiting")};assertTrue(requests.isEmpty());assertEquals(b,owner.endpoint.value)
            network.value=LiveInternet.VALIDATED;await{owner.status.value.startsWith("Live")};assertTrue(requests.all{it==b})
            owner.stop();val count=requests.size;network.value=LiveInternet.OFFLINE;network.value=LiveInternet.VALIDATED;Thread.sleep(200);assertEquals(count,requests.size);assertEquals("Off",owner.status.value)
        }finally{owner.stop();scope.cancel()}
    }
}
