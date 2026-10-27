package com.shilapi.xcertplay.media

import android.Manifest
import android.app.Application
import android.media.*
import android.view.Surface
import com.shilapi.xcertplay.airplay.*
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.shadows.*
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32], application=Application::class, manifest=Config.NONE,
    shadows=[AndroidMicrophoneExperimentQualificationTest.StereoOnlyRecorder::class,
        AndroidMicrophoneExperimentQualificationTest.ObservedCodec::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class AndroidMicrophoneExperimentQualificationTest {
    @Implements(AudioRecord::class)
    class StereoOnlyRecorder {
        companion object {
            @JvmStatic @Implementation fun native_get_min_buff_size(rate: Int, channels: Int, format: Int): Int =
                if (rate == 24000 && channels == 2) 1024 else -2
        }
        @Implementation fun native_start(event: Int, session: Int): Int = 0
        @Implementation fun native_read_in_byte_array(b: ByteArray, offset: Int, size: Int, blocking: Boolean): Int {
            if (captureReads.isNotEmpty()) { Thread.sleep(5); return 0 }
            captureReads.add(size)
            val pcm=ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            for (i in offset until offset+size step 2) pcm.putShort(i,(if ((i/2)%2==0) -1000 else 2000).toShort())
            return size
        }
    }
    @Implements(MediaCodec::class)
    class ObservedCodec : ShadowMediaCodec() {
        @Implementation override fun native_configure(keys: Array<String>, values: Array<Any>, surface: Surface?, crypto: MediaCrypto?, flags: Int) {
            configured.add(keys.zip(values).toMap())
            super.native_configure(keys, values, surface, crypto, flags)
        }
        @Implementation override fun native_start() { starts++; super.native_start() }
        @Implementation fun native_release() { releases++ }
    }
    companion object {
        val configured = mutableListOf<Map<String, Any>>()
        var starts = 0
        var releases = 0
        val captureReads = CopyOnWriteArrayList<Int>()
        val encoderPcm = CopyOnWriteArrayList<ByteArray>()
    }
    @Test fun actualOpusQualificationIsIndependentOfAllLegacyMonoRecorders() {
        val app=RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val device=AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_MIC).build()
        ReflectionHelpers.setField(ReflectionHelpers.getField<Any>(device,"mPort"),"mRole",1)
        shadowOf(app.getSystemService(AudioManager::class.java)).setInputDevices(listOf(device))
        val caps=MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(OpusEncoder.format(64000)).setIsEncoder(true).build()
        ShadowMediaCodecList.addCodec(MediaCodecInfoBuilder.newBuilder().setName("qualified.opus.encoder")
            .setIsEncoder(true).setCapabilities(caps).build())
        configured.clear();starts=0;releases=0
        assertFalse("All legacy mono shapes fail",AndroidMicrophoneSupport.probe(app).available)
        assertNull(AndroidMicrophoneSupport.encoderName(0x20000000L))
        val profile=CarPlayMicrophoneExperimentProfile(source=MicrophoneSource.VOICE_RECOGNITION,
            rate=CarPlayMicrophoneCaptureRate.HZ_24000,channels=CarPlayMicrophoneChannels.TWO,
            selection=CarPlayMicrophoneChannelSelection.SECOND,codec=CarPlayMicrophoneCodec.OPUS)
        val cap=AndroidMicrophoneSupport.experimentCapability(app,profile)
        assertTrue("Exact stereo capture plus actual Opus encoder must qualify",0x20000000L in cap.bits("telephony"))
        assertEquals(2,starts)
        assertEquals(starts,releases)
        configured.forEach { format ->
            assertEquals(48000,format[MediaFormat.KEY_SAMPLE_RATE])
            assertEquals(1,format[MediaFormat.KEY_CHANNEL_COUNT])
            assertEquals(1920,format[MediaFormat.KEY_MAX_INPUT_SIZE])
        }
        assertFalse("Independent encoder evidence must not enable default mono",AndroidMicrophoneSupport.capability.available)
        assertNull(AndroidMicrophoneSupport.encoderName(0x20000000L))
        captureReads.clear();encoderPcm.clear()
        ShadowMediaCodec.addEncoder("qualified.opus.encoder",ShadowMediaCodec.CodecConfig(4096,1275) { input, output ->
            val bytes=ByteArray(input.remaining());input.get(bytes);encoderPcm.add(bytes)
            output.put(byteArrayOf(0x98.toByte(),0x33)) // deterministic 20ms Opus fixture, not a hardware codec
        })
        val key=ByteArray(32) { (it+1).toByte() }
        DatagramSocket(0,InetAddress.getLoopbackAddress()).use { peer ->
            peer.soTimeout=3000
            val config=MicrophoneSupport.negotiate(cap,100,"telephony",0x20000000L,peer.localPort,480,peer.localAddress,key.copyOf())!!
            val uplink=MicrophoneUplink(config,experiment=profile,qualifiedCapability=cap,captureAdmission=MicrophoneCaptureAdmission {true})
            try {
                uplink.start()
                val packet=DatagramPacket(ByteArray(4096),4096);peer.receive(packet)
                val bytes=packet.data.copyOf(packet.length)
                val body=AirPlayCrypto.chachaOpen(key,ByteArray(4)+bytes.takeLast(8).toByteArray(),
                    bytes.copyOfRange(12,bytes.size-8),bytes.copyOfRange(4,12))
                assertArrayEquals(byteArrayOf(0x98.toByte(),0x33),body)
                assertEquals(listOf(1920),captureReads.toList())
                assertEquals(1920,encoderPcm.single().size)
                val samples=ByteBuffer.wrap(encoderPcm.single()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                while (samples.hasRemaining()) assertEquals(2000,samples.get().toInt())
            } finally { uplink.close();assertTrue(uplink.isRetired) }
        }
        MicrophoneCaptureFence.tryDiagnostic()!!.release()
    }
}
