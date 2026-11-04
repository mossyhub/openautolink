package com.openautolink.app.diagnostics.live

import android.app.Application
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.security.KeyStore
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.*
import kotlin.concurrent.thread

/** Loopback TLS only. Test trust is restored in finally; production verifier/client is unchanged. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32],application=Application::class,manifest=Config.NONE)
class LiveEndpointTlsTest {
    private class Server(context:SSLContext,val credential:String):AutoCloseable {
        val requests=CopyOnWriteArrayList<Pair<String,JSONObject>>()
        @Volatile var redirect:String?=null
        private val socket=context.serverSocketFactory.createServerSocket(0,20,InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        val endpoint=LiveEndpoint.parse("https://localhost:${socket.localPort}/custom/oal")
        private val workers=CopyOnWriteArrayList<Thread>()
        private val acceptor=thread(isDaemon=true,name="endpoint-test-tls") {
            while(!socket.isClosed)try{
                val connection=socket.accept() as SSLSocket
                workers.add(thread(isDaemon=true){
                    connection.use { client->
                        try {
                            client.soTimeout=5000
                            val reader=client.inputStream.bufferedReader()
                            val request=reader.readLine()?:return@use
                            var length=0
                            while(true){val line=reader.readLine()?:return@use;if(line.isEmpty())break;if(line.startsWith("Content-Length:",true))length=line.substringAfter(':').trim().toInt()}
                            val chars=CharArray(length);var read=0;while(read<length){val n=reader.read(chars,read,length-read);if(n<0)return@use;read+=n}
                            val path=request.split(' ')[1];val body=JSONObject(String(chars));requests.add(path to body)
                            val location=redirect
                            val payload=when {
                                path.endsWith("/v2/pair")->JSONObject().put("deviceCredential",credential)
                                path.endsWith("/v2/connect")-> {check(body.getString("deviceCredential")==credential);JSONObject().put("token","session-$credential")}
                                else->JSONObject().put("ack",0)
                            }.toString().toByteArray()
                            val header=if(location!=null)"HTTP/1.1 302 Found\r\nLocation: $location\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                else "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n"
                            client.outputStream.write(header.toByteArray());if(location==null)client.outputStream.write(payload);client.outputStream.flush()
                        }catch(_:Exception){ /* Failed hostname handshake or closed predecessor is expected. */ }
                    }
                })
            }catch(_:Exception){if(!socket.isClosed)throw AssertionError("local TLS accept failed")}
        }
        override fun close(){socket.close();acceptor.join(2000);workers.forEach{it.join(2000)}}
    }
    private fun context():SSLContext {
        val store=KeyStore.getInstance("PKCS12")
        javaClass.getResourceAsStream("/live-endpoint-test.p12")!!.use{store.load(it,"test-only-local-tls".toCharArray())}
        val keys=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply{init(store,"test-only-local-tls".toCharArray())}
        val trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply{init(store)}
        return SSLContext.getInstance("TLS").apply{init(keys.keyManagers,trust.trustManagers,null)}
    }
    private fun await(p:()->Boolean){val end=System.nanoTime()+10_000_000_000;while(!p()&&System.nanoTime()<end)Thread.sleep(10);assertTrue(p())}
    @Test fun realTwoTlsServersNeverReceiveTheOtherPairingAndDoNotFollowRedirects() {
        val tls=context();val previous=HttpsURLConnection.getDefaultSSLSocketFactory();HttpsURLConnection.setDefaultSSLSocketFactory(tls.socketFactory)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val file=java.io.File(RuntimeEnvironment.getApplication().noBackupFilesDir,"oal-live-launch-consent.bin");file.delete()
        try {
            Server(tls,"A".repeat(43)).use { a->Server(tls,"B".repeat(43)).use { b->
                val entries=java.util.concurrent.ConcurrentHashMap<LiveEndpoint,String>().apply{put(a.endpoint,a.credential)}
                val credentials=object:LiveCredentials {
                    override fun load():String?=error("unscoped");override fun save(credential:String)=error("unscoped");override fun clear()=error("unscoped")
                    override fun load(endpoint:LiveEndpoint)=entries[endpoint]
                    override fun save(endpoint:LiveEndpoint,credential:String){entries[endpoint]=credential}
                    override fun clear(endpoint:LiveEndpoint){entries.remove(endpoint)}
                }
                val consent=LiveConsentStore(RuntimeEnvironment.getApplication()).apply{saveEndpoint(a.endpoint);save(true)}
                val owner=LiveDiagnosticsOwner(RuntimeEnvironment.getApplication(),scope,credentials,consent,networkFactory={UnknownLiveNetwork})
                try {
                    owner.restore();await{owner.status.value.startsWith("Live")};assertTrue(a.requests.any{it.first=="/custom/oal/v2/connect"});assertTrue(b.requests.isEmpty())
                    owner.applyEndpoint(b.endpoint.base+"/");await{!owner.busy.value && owner.endpoint.value==b.endpoint};assertTrue(b.requests.isEmpty());assertFalse(consent.load())
                    owner.enable("one-two-three-four-five");await{owner.status.value.startsWith("Live")}
                    assertTrue(b.requests.any{it.first=="/custom/oal/v2/pair"});assertTrue(b.requests.none{it.second.optString("deviceCredential")==a.credential})
                    owner.revoke();await{owner.status.value.contains("owner revoked")};assertTrue(b.requests.any{it.first.endsWith("/v2/revoke")});assertTrue(a.requests.none{it.first.endsWith("/v2/revoke")});assertEquals(a.credential,entries[a.endpoint])
                    val bCount=b.requests.size;a.redirect=b.endpoint.base+"/v2/connect"
                    LiveHttps(a.endpoint).use { wire->try{wire.post("/v2/connect",JSONObject().put("deviceCredential",a.credential));fail("redirect must fail")}catch(e:LiveHttpFailure){assertEquals(302,e.status)} }
                    assertEquals(bCount,b.requests.size)
                    val mismatched=LiveEndpoint.parse(a.endpoint.base.replace("localhost","127.0.0.1"))
                    LiveHttps(mismatched).use { wire->assertTrue(runCatching{wire.post("/v2/connect",JSONObject().put("deviceCredential",a.credential))}.exceptionOrNull() is java.io.IOException) }
                }finally{owner.stop()}
            }}
        }finally{scope.cancel();file.delete();HttpsURLConnection.setDefaultSSLSocketFactory(previous)}
    }
}
