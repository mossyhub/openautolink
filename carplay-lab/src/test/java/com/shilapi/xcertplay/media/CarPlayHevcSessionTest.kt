package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.*
import com.openautolink.carplaylab.network.CarPlaySocketBinding
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import org.junit.Assert.*
import org.junit.Test

/** Exercise actual session SETUP and /info serializers with real ancillary loopback sockets.
 * Reflect only dispatch (PairVerify is independently tested); not a physical authentication receipt. */
class CarPlayHevcSessionTest {
    @Test fun actualSessionInfoSetupAndRetirementShareTheExactReceipt() {
        val local=InetAddress.getByName("::1")
        val config=AirPlayConfig("test","test","test","950.7.1",AirPlayDisplayConfig(1280,720,fps=30),hevc=true,lpcmOnly=true)
        val receipt=CarPlayVideoSelection(1280,720,30,"avc.exact","hevc.exact")
        for (selection in listOf(receipt,receipt.copy(fps=31),receipt.copy(hevcDecoderName=null),null)) {
          for (proposed in listOf(listOf("hevc"),emptyList())) {
            ServerSocket(0,1,local).use { listener ->
                Socket(local,listener.localPort).use { phone ->
                    listener.accept().use { control ->
                        val session=AirPlaySession(control,config,AirPlayIdentity.generate(),PairingStore(),null,
                            object: AirPlaySessionListener {},object: AirPlayMediaHandler {},
                            socketBinding=CarPlaySocketBinding(local,local),videoSelection=selection)
                        session.use {
                            val qualified=selection==receipt
                            val post=AirPlaySession::class.java.getDeclaredMethod("handle",RtspMessage.Request::class.java).apply { isAccessible=true }
                            val infoResponse=post.invoke(session,RtspMessage.Request("POST","/info","RTSP/1.0",emptyMap(),ByteArray(0))) as RtspMessage.Response
                            assertEquals(qualified,(BplistCodec.decode(infoResponse.body) as Map<*,*>).containsKey("hevcInfo"))
                            assertTrue(session.acceptsVideoCodec(VideoCodec.H264))
                            assertFalse(session.acceptsVideoCodec(VideoCodec.H265))
                            val setup=AirPlaySession::class.java.getDeclaredMethod("handleSetup",RtspMessage.Request::class.java).apply { isAccessible=true }
                            run {
                                val reply=setup.invoke(session,RtspMessage.Request("SETUP","/stream","RTSP/1.0",emptyMap(),
                                    BplistCodec.encode(mapOf("features" to proposed)))) as RtspMessage.Response
                                assertEquals(200,reply.status ?: 200)
                                val enabled=(BplistCodec.decode(reply.body) as Map<*,*>)["enabledFeatures"] as List<*>
                                assertEquals(qualified,"hevc" in enabled)
                                assertEquals(qualified,session.acceptsVideoCodec(VideoCodec.H265))
                                assertTrue(session.acceptsVideoCodec(VideoCodec.H264))
                            }
                        }
                        assertFalse(session.acceptsVideoCodec(VideoCodec.H264))
                        assertFalse(session.acceptsVideoCodec(VideoCodec.H265))
                    }
                }
            }
          }
        }
    }
}
