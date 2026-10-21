package com.shilapi.xcertplay.media

import android.Manifest
import android.app.Application
import android.media.*
import android.view.Surface
import com.openautolink.carplaylab.media.*
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.Iap2WirelessIdentification
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.shadows.*
import org.robolectric.util.ReflectionHelpers
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Real receiver qualification, TCP /info session, its admission snapshot, owned sink and UDP uplink.
 * Only Android capture/codec platform edges are deterministic shadows; no qualification helper mocks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32],application=Application::class,manifest=Config.NONE,
    shadows=[CarPlayProductionCallReceiverTest.ProvenRecorder::class,
        AndroidMicrophoneExperimentQualificationTest.ObservedCodec::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CarPlayProductionCallReceiverTest {
    @Implements(AudioRecord::class)
    class ProvenRecorder {
        companion object {
            @JvmStatic @Implementation fun native_get_min_buff_size(rate: Int, channels: Int, format: Int): Int =
                if (rate==48000 && channels==2) 1024 else -2
        }
        @Implementation fun native_start(event: Int, session: Int): Int = 0
        @Implementation fun native_read_in_byte_array(b: ByteArray, offset: Int, size: Int, blocking: Boolean): Int {
            if (AndroidMicrophoneExperimentQualificationTest.captureReads.isNotEmpty()) { Thread.sleep(5); return 0 }
            AndroidMicrophoneExperimentQualificationTest.captureReads.add(size)
            val pcm=ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            for (i in offset until offset+size step 2) pcm.putShort(i,(if ((i/2)%2==0) -1000 else 2000).toShort())
            return size
        }
    }
    @Test fun productionOpusUsesIndependentEncoderWithStereoOnlyCaptureAndNegotiatedClock() {
        val app=RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val device=AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_MIC).build()
        ReflectionHelpers.setField(ReflectionHelpers.getField<Any>(device,"mPort"),"mRole",1)
        shadowOf(app.getSystemService(AudioManager::class.java)).setInputDevices(listOf(device))
        val caps=MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(OpusEncoder.format(64000)).setIsEncoder(true).build()
        ShadowMediaCodecList.addCodec(MediaCodecInfoBuilder.newBuilder().setName("qualified.opus.encoder")
            .setIsEncoder(true).setCapabilities(caps).build())
        val cap=AndroidMicrophoneSupport.probe(app)
        assertTrue(0x20000000L in cap.bits("telephony"))
        assertTrue(cap.bits("default").isEmpty()); assertTrue(cap.bits("speechrecognition").isEmpty())
        val captured=java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
        ShadowMediaCodec.addEncoder("qualified.opus.encoder",ShadowMediaCodec.CodecConfig(4096,1275) { input, output ->
            val pcm=ByteArray(input.remaining());input.get(pcm);captured.add(pcm)
            output.put(byteArrayOf(0x98.toByte(),0x33))
        })
        AndroidMicrophoneExperimentQualificationTest.captureReads.clear()
        val key=ByteArray(32) { (it+1).toByte() }
        DatagramSocket(0,InetAddress.getLoopbackAddress()).use { peer ->
            peer.soTimeout=3000
            val config=MicrophoneSupport.negotiate(cap,100,"telephony",0x20000000L,peer.localPort,480,peer.localAddress,key.copyOf())!!
            val uplink=MicrophoneUplink(config,qualifiedCapability=cap)
            try {
                uplink.start()
                val packet=DatagramPacket(ByteArray(4096),4096);peer.receive(packet)
                val wire=packet.data.copyOf(packet.length)
                val body=AirPlayCrypto.chachaOpen(key,ByteArray(4)+wire.takeLast(8).toByteArray(),
                    wire.copyOfRange(12,wire.size-8),wire.copyOfRange(4,12))
                assertArrayEquals(byteArrayOf(0x98.toByte(),0x33),body)
                assertEquals(listOf(3840),AndroidMicrophoneExperimentQualificationTest.captureReads.toList())
                assertEquals(1920,captured.single().size)
                val samples=ByteBuffer.wrap(captured.single()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                while(samples.hasRemaining()) assertEquals(500,samples.get().toInt())
                shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
                val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
                while(!uplink.isRetired && System.nanoTime()<deadline) Thread.sleep(5)
                assertTrue(uplink.isRetired)
                assertArrayEquals(ByteArray(32),config.key)
                assertFalse(AndroidMicrophoneSupport.capability.available)
            } finally { uplink.close(); assertTrue(uplink.isRetired) }
        }
        MicrophoneCaptureFence.tryDiagnostic()!!.release()
    }
    @Test fun unarmedReceiverAdmitsOnlyQualifiedCallAndSendsAveragedMonoWithoutLegacyCapture() {
        val app=RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val device=AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_MIC).build()
        ReflectionHelpers.setField(ReflectionHelpers.getField<Any>(device,"mPort"),"mRole",1)
        shadowOf(app.getSystemService(AudioManager::class.java)).setInputDevices(listOf(device))
        fun addCodec(name: String, encoder: Boolean) {
            val caps=MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
                .setMediaFormat(OpusEncoder.format(64000)).setIsEncoder(encoder).build()
            ShadowMediaCodecList.addCodec(MediaCodecInfoBuilder.newBuilder().setName(name)
                .setIsEncoder(encoder).setCapabilities(caps).build())
        }
        addCodec("qualified.opus.encoder",true)
        val actualOutputs=AndroidAudioDecoderSupport.availableBits.toSet()
        assertTrue(actualOutputs.intersect(MicrophoneSupport.opusBits).isEmpty())
        CarPlayMicrophoneExperiments.disarm()
        File(app.noBackupFilesDir,"pairing-probe").deleteRecursively()
        val surface=mock(Surface::class.java);`when`(surface.isValid).thenReturn(true)
        val ledgerLines=java.util.concurrent.CopyOnWriteArrayList<String>()
        val receiver=ProjectionReceiver(app,"00:11:22:33:44:55",surface,mock(MfiAuthenticator::class.java),
            display=CarPlayDisplayPreferences(qualifiedVideoSelection=CarPlayVideoSelection(1280,720,30,"avc.exact")),onStatus=ledgerLines::add)
        fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible=true }.get(owner)
        try {
            assertNull(field(receiver,"outputAudioFormats"))
            val capability=field(receiver,"microphoneCapability") as MicrophoneCapability
            assertEquals("AUTO must discard encoded inputs absent from actual output rows",setOf(0x40L),capability.bits("telephony"))
            val sink=field(receiver,"sink") as AndroidMediaSink
            assertNull(field(receiver,"microphoneExperiment"))
            assertFalse(capability.bits("default").isNotEmpty())
            assertFalse(capability.bits("speechrecognition").isNotEmpty())
            CarPlayMicrophoneExperiments.disarm() // future owners only
            val ip=NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp }
                .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
                .first { !it.isLoopbackAddress && !it.isAnyLocalAddress && !it.isMulticastAddress && !it.isLinkLocalAddress }
            val peer=ServerSocket().use { probe ->
                probe.bind(InetSocketAddress(InetAddress.getByName("::"),0))
                Socket().use { client ->
                    client.bind(InetSocketAddress(ip,0));client.connect(InetSocketAddress(ip,probe.localPort))
                    probe.accept().use { it.inetAddress }
                }
            }
            receiver.setRuntimeIdentification(Iap2IdentificationConfig(name="test",modelIdentifier="test",manufacturer="test",
                serialNumber="test",firmwareVersion="test",hardwareVersion="test",
                wireless=Iap2WirelessIdentification("00:11:22:33:44:55","test")))
            receiver.start(peer.hostAddress!!)
            Socket().apply { bind(InetSocketAddress(ip,0));connect(InetSocketAddress(ip,receiver.port));soTimeout=5000 }.use { phone ->
                phone.getOutputStream().apply { write("GET /info RTSP/1.0\r\nCSeq: 1\r\nContent-Length: 0\r\n\r\n".toByteArray());flush() }
                val input=phone.getInputStream();val header=ByteArrayOutputStream()
                while (!header.toByteArray().takeLast(4).toByteArray().contentEquals("\r\n\r\n".toByteArray())) {
                    val b=input.read();check(b>=0);header.write(b);check(header.size()<8192)
                }
                val lines=header.toString("US-ASCII").split("\r\n")
                assertTrue(lines.first().contains("200"))
                val length=lines.first { it.startsWith("Content-Length:") }.substringAfter(':').trim().toInt()
                val body=ByteArray(length);var n=0
                while(n<length) { val count=input.read(body,n,length-n);check(count>0);n+=count }
                val info=BplistCodec.decode(body) as Map<*,*>
                val rows=(info["audioFormats"] as List<*>).map { it as Map<*,*> }
                assertEquals(AudioOutputSupport.wirelessRows(actualOutputs).map { it["audioOutputFormats"] },rows.map { it["audioOutputFormats"] })
                rows.filter { it["type"]==100 && it["audioType"]=="telephony" }.forEach {
                    assertEquals(0x40L,(it["audioInputFormats"] as Number).toLong())
                }
                val session=(field(receiver,"allSessions") as Set<*>).single() as AirPlaySession
                assertNull(field(session,"outputAudioFormats"))
                assertSame(capability,session.offeredMicrophone)
                assertEquals(setOf(0x40L),session.offeredMicrophone.bits("telephony"))
                AndroidMicrophoneExperimentQualificationTest.captureReads.clear()
                val key=ByteArray(32) { (it+1).toByte() }
                DatagramSocket(0,InetAddress.getLoopbackAddress()).use { udp ->
                    udp.soTimeout=3000
                    assertNull(MicrophoneSupport.negotiate(session.offeredMicrophone,100,"telephony",0x20000000L,
                        udp.localPort,480,udp.localAddress,key.copyOf()))
                    val config=MicrophoneSupport.negotiate(session.offeredMicrophone,100,"telephony",0x40L,
                        udp.localPort,480,udp.localAddress,key.copyOf())!!
                    sink.onMicrophoneStarted(MicrophoneStreamId(session,100,"telephony",1),config)
                    val packet=DatagramPacket(ByteArray(4096),4096);udp.receive(packet)
                    val bytes=packet.data.copyOf(packet.length)
                    val pcm=AirPlayCrypto.chachaOpen(key,ByteArray(4)+bytes.takeLast(8).toByteArray(),
                        bytes.copyOfRange(12,bytes.size-8),bytes.copyOfRange(4,12))
                    assertEquals(listOf(3840),AndroidMicrophoneExperimentQualificationTest.captureReads.toList())
                    assertEquals(960,pcm.size)
                    val samples=ByteBuffer.wrap(pcm).order(ByteOrder.BIG_ENDIAN).asShortBuffer()
                    while(samples.hasRemaining()) assertEquals(500,samples.get().toInt())
                    val policyDeadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
                    while (ledgerLines.none { "stage=microphone-owned " in it } && System.nanoTime()<policyDeadline) Thread.sleep(5)
                    assertTrue("existing passive exact-recorder policy must carry the ledger owner",ledgerLines.any {
                        "stage=microphone-owned " in it && "media stage=microphone-config scope=own " in it
                    })
                }
            }
        } finally { CarPlayMicrophoneExperiments.disarm();receiver.close();assertTrue(receiver.awaitClosed(5000)) }
        MicrophoneCaptureFence.tryDiagnostic()!!.release()
        assertTrue("raw per-channel census is pre-conversion",ledgerLines.any {
            "stage=microphone-pcm" in it && "boundary=raw phase=released" in it &&
                "observed=960 scanned=960" in it && "ch0Peak=1000 ch0Rms=1000" in it && "ch1Peak=2000 ch1Rms=2000" in it
        })
        assertTrue("converted mono census is separate from capture blocks",ledgerLines.any {
            "stage=microphone-pcm" in it && "boundary=converted phase=released" in it &&
                "observed=480 scanned=480" in it && "ch0Peak=500 ch0Rms=500" in it && "ch1Samples=0" in it
        })
        assertFalse("production profile must not produce experimental UX",ledgerLines.any { "stage=microphone-experiment " in it })
        val creates=ledgerLines.filter { "kind=probe action=create " in it }
        assertEquals("actual normal qualification must retain all five attempts",5,creates.size)
        assertEquals(listOf("MIC:24000:1","MIC:48000:1","VOICE_RECOGNITION:24000:1",
            "VOICE_RECOGNITION:48000:1","VOICE_RECOGNITION:48000:2"),creates.map {
            val source=Regex("source=([A-Z_]+)").find(it)!!.groupValues[1]
            val rate=Regex("rate=([0-9]+)").find(it)!!.groupValues[1]
            val channels=Regex("channels=([0-9]+)").find(it)!!.groupValues[1]
            "$source:$rate:$channels"
        })
    }
}
