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
    shadows=[AndroidMicrophoneExperimentQualificationTest.StereoOnlyRecorder::class,
        AndroidMicrophoneExperimentQualificationTest.ObservedCodec::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CarPlayMicrophoneExperimentReceiverSuccessTest {
    @Test fun receiverFreezesActualOutputAndAutoInputOffersThroughSessionAndConvertedUplink() {
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
        assertFalse(AndroidMicrophoneSupport.probe(app).available)
        val profile=CarPlayMicrophoneExperimentProfile(source=MicrophoneSource.VOICE_RECOGNITION,
            rate=CarPlayMicrophoneCaptureRate.HZ_24000,channels=CarPlayMicrophoneChannels.TWO,
            selection=CarPlayMicrophoneChannelSelection.SECOND,codec=CarPlayMicrophoneCodec.AUTO)
        assertTrue("Actual capture and encoder qualify before output-row intersection",
            0x20000000L in AndroidMicrophoneSupport.experimentCapability(app,profile).bits("telephony"))
        File(app.noBackupFilesDir,"pairing-probe").deleteRecursively()
        CarPlayMicrophoneExperiments.arm(profile)
        val surface=mock(Surface::class.java);`when`(surface.isValid).thenReturn(true)
        val ledgerLines=java.util.concurrent.CopyOnWriteArrayList<String>()
        val receiver=ProjectionReceiver(app,"00:11:22:33:44:55",surface,mock(MfiAuthenticator::class.java),
            display=CarPlayDisplayPreferences(qualifiedVideoSelection=CarPlayVideoSelection(1280,720,30,"avc.exact")),captureAdmission={true},onStatus=ledgerLines::add)
        fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible=true }.get(owner)
        try {
            assertEquals(actualOutputs,field(receiver,"outputAudioFormats"))
            val capability=field(receiver,"microphoneCapability") as MicrophoneCapability
            assertEquals("AUTO must discard encoded inputs absent from actual output rows",setOf(0x40L),capability.bits("telephony"))
            val sink=field(receiver,"sink") as AndroidMediaSink
            assertSame(profile,field(receiver,"microphoneExperiment"))
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
                assertEquals(actualOutputs,field(session,"outputAudioFormats"))
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
                    assertEquals(listOf(1920),AndroidMicrophoneExperimentQualificationTest.captureReads.toList())
                    assertEquals(960,pcm.size)
                    val samples=ByteBuffer.wrap(pcm).order(ByteOrder.BIG_ENDIAN).asShortBuffer()
                    while(samples.hasRemaining()) assertEquals(2000,samples.get().toInt())
                }
            }
        } finally { CarPlayMicrophoneExperiments.disarm();receiver.close();assertTrue(receiver.awaitClosed(5000)) }
        MicrophoneCaptureFence.tryDiagnostic()!!.release()
        val creates=ledgerLines.filter { "kind=probe action=create " in it }
        assertEquals("manual path must attest nine real attempts",9,creates.size)
        assertTrue(ledgerLines.any { "action=encoder-cache result=hit" in it })
        assertTrue(ledgerLines.any { "action=decoder-cache result=hit" in it })
        creates.zipWithNext().forEach { (before,after) ->
            val owner=Regex("owner=([0-9]+)").find(before)!!.groupValues[1]
            assertTrue(ledgerLines.indexOfFirst { "owner=$owner " in it && "action=released " in it } < ledgerLines.indexOf(after))
        }
    }
}
