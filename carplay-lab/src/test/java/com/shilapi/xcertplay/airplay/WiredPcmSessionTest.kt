package com.shilapi.xcertplay.airplay

import android.Manifest
import android.media.*
import com.shilapi.xcertplay.media.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioRecord
import java.net.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real PairVerify -> /info plist -> SETUP -> authenticated downlink -> capture -> sealed UDP. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32], manifest=Config.NONE)
class WiredPcmSessionTest {
    private fun qualify() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val device = mock(AudioDeviceInfo::class.java)
        `when`(device.isSource).thenReturn(true); `when`(device.type).thenReturn(AudioDeviceInfo.TYPE_BUILTIN_MIC)
        shadowOf(app.getSystemService(AudioManager::class.java)).setInputDevices(listOf(device))
        AndroidMicrophoneSupport.probe(app)
    }
    private fun authenticate(session: AirPlaySession, store: PairingStore) {
        val controller = AirPlayCrypto.ed25519Generate(); val id = "synthetic-pcm-controller".toByteArray()
        store.save(id.toString(Charsets.UTF_8),controller.publicKey)
        val ephemeral = AirPlayCrypto.x25519Generate()
        val m2 = Tlv8Codec.decode(session.pairVerify.handle(tlv(6 to byteArrayOf(1),3 to ephemeral.publicKey)))
        val remote = m2.getValue(3)
        val shared = AirPlayCrypto.x25519Shared(ephemeral.privateKey,remote)
        val key = AirPlayCrypto.hkdfSha512(shared,"Pair-Verify-Encrypt-Salt".toByteArray(),"Pair-Verify-Encrypt-Info".toByteArray())
        val sig = AirPlayCrypto.ed25519Sign(controller.privateKey,ephemeral.publicKey+id+remote)
        session.pairVerify.handle(tlv(6 to byteArrayOf(3),5 to AirPlayCrypto.chachaSeal(key,
            AirPlayCrypto.nonceLabel("PV-Msg03"),tlv(1 to id,10 to sig))))
        assertTrue(session.finishControlAuthentication())
    }
    private fun tlv(vararg p: Pair<Int,ByteArray>) = Tlv8Codec.encode(p.map { Tlv8Item(it.first,it.second) })
    private fun request(session: AirPlaySession, method: String, path: String, body: Map<String,Any?>? = null): RtspMessage.Response {
        val handle = session.javaClass.getDeclaredMethod("handle",RtspMessage.Request::class.java).apply { isAccessible=true }
        return handle.invoke(session,RtspMessage.Request(method,path,"RTSP/1.0",emptyMap(),
            body?.let { BplistCodec.encode(it) } ?: ByteArray(0))) as RtspMessage.Response
    }
    private fun stream(kind: String, port: Any? = 6000, spf: Any? = 480): Map<String,Any?> = mapOf(
        "type" to 100,"audioType" to kind,"audioFormat" to 0x40L,"spf" to spf,
        "streamConnectionID" to 7654L,"dataPort" to port)
    @Test fun productionSetupStartsEachInputOnlyAfterAuthenticatedDownlinkAndSealsCapture() {
        qualify()
        val pcm = ByteArray(960) { if (it%2==0) 0x34 else 0x12 }
        val peer=InetAddress.getLoopbackAddress()
        ServerSocket(0,1,peer).use { server -> Socket(peer,server.localPort).use { phone -> server.accept().use { control ->
            DatagramSocket(0,peer).use { uplinkPort ->
                uplinkPort.soTimeout=2000
                var capture: MicrophoneUplink?=null
                var started=CountDownLatch(1)
                val sink=object : MediaSink {
                    override fun supportsMicrophone(f: AudioFormat) = MicrophoneSupport.bits(f) in AndroidMicrophoneSupport.capability.bits(f.audioType)
                    override fun onMicrophoneStarted(id: MicrophoneStreamId,cfg: MicrophoneConfig) {
                        capture=MicrophoneUplink(cfg,captureAdmission=MicrophoneCaptureAdmission {true}).also { it.start() }; started.countDown()
                    }
                    override fun onMicrophoneStopped(id: MicrophoneStreamId) { capture?.close() }
                }
                val engine=CarPlayMediaEngine(sink,microphoneEnabled=true)
                val store=PairingStore()
                val session=AirPlaySession(control,AirPlayConfig("test","id","mac","950.7.1",
                    AirPlayDisplayConfig(800,480),microphone=true),AirPlayIdentity.generate(),store,null,
                    object : AirPlaySessionListener {},engine)
                try {
                    assertEquals(403,request(session,"SETUP","/",mapOf("streams" to listOf(stream("telephony")))).status)
                    authenticate(session,store)
                    val info=BplistCodec.decode(request(session,"GET","/info").body) as Map<*,*>
                    for (kind in listOf("default","telephony","speechRecognition")) {
                        started=CountDownLatch(1)
                        ShadowAudioRecord.setSourceProvider { recorder ->
                            assertEquals(MediaRecorder.AudioSource.VOICE_RECOGNITION,recorder.audioSource)
                            assertEquals(16000,recorder.sampleRate)
                            assertEquals(1,recorder.channelCount)
                            object : ShadowAudioRecord.AudioRecordSource {
                                override fun readInByteArray(b: ByteArray,o: Int,n: Int,blocking: Boolean): Int {
                                    val count=minOf(n,pcm.size); pcm.copyInto(b,o,0,count); return count
                                }
                            }
                        }
                        val row=(info["audioFormats"] as List<*>).map { it as Map<*,*> }.single { it["audioType"]==kind && (it["type"] as Number).toInt()==100 }
                        assertEquals(0x40L,(row["audioInputFormats"] as Number).toLong())
                        val reply=request(session,"SETUP","/",mapOf("streams" to listOf(stream(kind,uplinkPort.localPort))))
                        val accepted=((BplistCodec.decode(reply.body) as Map<*,*>)["streams"] as List<*>).single() as Map<*,*>
                        assertEquals(1L,started.count)
                        val outKey=AirPlayCrypto.hkdfSha512(session.sharedSecret!!,"DataStream-Salt7654".toByteArray(),
                            "DataStream-Output-Encryption-Key".toByteArray(),32)
                        val wire=MicrophonePacketizer.sealPacket(outKey,MicrophoneCounters(),pcm,480)
                        val corrupt=wire.copyOf().also { it[12]=(it[12].toInt() xor 1).toByte() }
                        DatagramSocket().use { send ->
                            send.send(DatagramPacket(corrupt,corrupt.size,peer,(accepted["dataPort"] as Number).toInt()))
                            assertFalse("malformed downlink started recording",started.await(100,TimeUnit.MILLISECONDS))
                            val invalid = listOf(0x00,0xa0,0x90,0x81).map { header ->
                                "RTP byte0=$header" to wire.copyOf().also { it[0]=header.toByte() }
                            } + listOf(
                                "empty PCM" to (wire.copyOf(12) + AirPlayCrypto.chachaSeal(outKey,ByteArray(12),
                                    ByteArray(0),wire.copyOfRange(4,12)) + ByteArray(8)),
                                "unaligned PCM" to MicrophonePacketizer.sealPacket(outKey,MicrophoneCounters(),byteArrayOf(1),480))
                            for ((reason, malformed) in invalid) {
                                // These packets authenticate: RTP byte0 is outside timestamp/SSRC AAD.
                                assertNotNull(AirPlayCrypto.chachaOpen(outKey,ByteArray(4)+malformed.takeLast(8).toByteArray(),
                                    malformed.copyOfRange(12,malformed.size-8),malformed.copyOfRange(4,12)))
                                send.send(DatagramPacket(malformed,malformed.size,peer,(accepted["dataPort"] as Number).toInt()))
                                assertFalse("authenticated invalid $reason started recording",started.await(100,TimeUnit.MILLISECONDS))
                            }
                            send.send(DatagramPacket(wire,wire.size,peer,(accepted["dataPort"] as Number).toInt()))
                        }
                        assertTrue(started.await(2,TimeUnit.SECONDS))
                        val packet=DatagramPacket(ByteArray(4096),4096); uplinkPort.receive(packet)
                        val bytes=packet.data.copyOf(packet.length)
                        val inKey=MicrophonePacketizer.inputKey(session.sharedSecret!!,7654L)!!
                        assertArrayEquals(ByteArray(960) { if (it%2==0) 0x12 else 0x34 },AirPlayCrypto.chachaOpen(inKey,
                            ByteArray(4)+bytes.takeLast(8).toByteArray(),bytes.copyOfRange(12,bytes.size-8),bytes.copyOfRange(4,12)))
                        engine.onTeardown(session,100)
                        assertTrue(capture!!.isRetired)
                        // Drain earlier packets before the next exact stream generation.
                        uplinkPort.soTimeout=30
                        while (runCatching { uplinkPort.receive(packet) }.isSuccess) {}
                        uplinkPort.soTimeout=2000
                    }
                    session.close()
                    assertNull(engine.onAudio(session,100,stream("default")))
                } finally { session.close(); capture?.close(); ShadowAudioRecord.clearSource() }
            }
        } } }
    }
    @Test fun presentNonStringAudioTypeCannotAdmitDefaultMicrophone() {
        qualify()
        val peer=InetAddress.getLoopbackAddress()
        ServerSocket(0,1,peer).use { server -> Socket(peer,server.localPort).use { phone -> server.accept().use { control ->
            val started=java.util.concurrent.atomic.AtomicInteger()
            val engine=CarPlayMediaEngine(object : MediaSink {
                override fun supportsMicrophone(f: AudioFormat)=true
                override fun onMicrophoneStarted(id: MicrophoneStreamId,cfg: MicrophoneConfig) { started.incrementAndGet() }
            },true)
            val store=PairingStore()
            val session=AirPlaySession(control,AirPlayConfig("test","id","mac","950.7.1",AirPlayDisplayConfig(800,480),microphone=true),
                AirPlayIdentity.generate(),store,null,object : AirPlaySessionListener {},engine)
            try {
                authenticate(session,store)
                for (bad in listOf(7L,true,64.5,byteArrayOf(1),listOf("default"),mapOf("kind" to "default"))) {
                    val reply=request(session,"SETUP","/",mapOf("streams" to listOf(stream("default")+mapOf("audioType" to bad))))
                    val accepted=(BplistCodec.decode(reply.body) as Map<*,*>)["streams"] as List<*>
                    assertTrue("non-string audioType admitted: $bad",accepted.isEmpty())
                    assertFalse(engine.owns(session))
                    assertNull(engine.onFeedback(session))
                    assertEquals(0,started.get())
                }
                // The production bplist writer cannot encode null; exercise that map boundary directly.
                assertNull(engine.onAudio(session,100,stream("default")+mapOf("audioType" to null)))
                assertFalse(engine.owns(session))
                assertNull(engine.onFeedback(session))
                assertEquals(0,started.get())
                // Only absence retains the legacy default, not a present null or wrong type.
                val reply=request(session,"SETUP","/",mapOf("streams" to listOf(stream("default")-"audioType")))
                assertEquals(1,((BplistCodec.decode(reply.body) as Map<*,*>)["streams"] as List<*>).size)
                assertTrue(engine.owns(session))
                assertEquals(0,started.get())
            } finally { session.close() }
        } } }
    }
    @Test fun malformedInputSetupCannotBeAcknowledgedAsSupportedOutputOnly() {
        qualify()
        val peer=InetAddress.getLoopbackAddress()
        ServerSocket(0,1,peer).use { server -> Socket(peer,server.localPort).use { phone -> server.accept().use { control ->
            val engine=CarPlayMediaEngine(object : MediaSink {
                override fun supportsMicrophone(f: AudioFormat)=true
            },true)
            val store=PairingStore()
            val session=AirPlaySession(control,AirPlayConfig("test","id","mac","950.7.1",AirPlayDisplayConfig(800,480),microphone=true),
                AirPlayIdentity.generate(),store,null,object : AirPlaySessionListener {},engine)
            try {
                authenticate(session,store)
                for (bad in listOf(stream("default",0),stream("default",6000.5),stream("default",6000,480.5),
                    stream("default",6000,960),stream("default")+mapOf("streamConnectionID" to "7654"),
                    stream("default")+mapOf("audioFormat" to 0x80L),
                    stream("default")+mapOf("audioFormat" to 64.5))) {
                    assertNull("malformed input accepted: $bad",engine.onAudio(session,100,bad))
                }
            } finally { session.close() }
        } } }
    }
}
