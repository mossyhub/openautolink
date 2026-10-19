package com.shilapi.xcertplay.media

import android.Manifest
import android.content.Context
import android.media.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32],manifest=Config.NONE)
class MicrophoneDiagnosticAndroidTest {
    private fun device(address: String, id: Int = 41): AudioDeviceInfo {
        val d=mock(AudioDeviceInfo::class.java)
        `when`(d.isSource).thenReturn(true);`when`(d.address).thenReturn(address);`when`(d.id).thenReturn(id)
        `when`(d.sampleRates).thenReturn(intArrayOf());`when`(d.channelMasks).thenReturn(intArrayOf())
        `when`(d.channelIndexMasks).thenReturn(intArrayOf())
        `when`(d.productName).thenReturn("PRIVATE_PRODUCT")
        return d
    }
    private fun platform(record: AudioRecord = mock(AudioRecord::class.java)): MicrophoneDiagnosticPlatform {
        val type=runCatching { Class.forName("com.shilapi.xcertplay.media.MicrophoneDiagnosticAndroid") }.getOrNull()
        assertNotNull("Android public adapter must exist",type)
        val factory: (MicrophoneDiagnosticShape)->AudioRecord = { record }
        return type!!.getDeclaredConstructor(Context::class.java,kotlin.jvm.functions.Function1::class.java)
            .newInstance(RuntimeEnvironment.getApplication(),factory) as MicrophoneDiagnosticPlatform
    }
    @Test fun productionFactoryUsesEachRequestedSupportedSource() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val platform = MicrophoneDiagnosticAndroid(app)
        for ((source, rate, channels) in listOf(listOf(6, 24000, 1), listOf(7, 48000, 2), listOf(1, 16000, 2))) {
            val requested = MicrophoneDiagnosticShape(source, rate, channels, if (channels == 2) 12 else 16, 0)
            val opened = runCatching { platform.open(requested) }
            assertTrue("production factory must accept requested supported source $source", opened.isSuccess)
            val record = opened.getOrThrow()
            try {
                record.start()
                assertEquals("real AudioRecord.Builder source, not an injected factory", requested, record.observe().actual)
            } finally { runCatching { record.stop() }; record.release() }
        }
        assertTrue("other sources remain unsupported", runCatching {
            platform.open(MicrophoneDiagnosticShape(0, 24000, 1, 16, 0))
        }.exceptionOrNull() is IllegalArgumentException)
    }
    @Test fun inventoryUsesExactAddressBoundsAndEmptyArraysMeanUnconstrainedWithoutPrivateOutput() {
        val app=RuntimeEnvironment.getApplication();val manager=shadowOf(app.getSystemService(AudioManager::class.java))
        val exact=device("Call_In_Mic");val near=device("Call_In_Mic ",42)
        manager.setInputDevices(listOf(exact,near))
        val p=platform();val result=p.inventory()
        assertEquals(MicrophoneDiagnosticMatch.UNIQUE,result.match)
        assertEquals(2,result.inputs.size)
        assertTrue(result.inputs.all { it.ratesUnconstrained && it.masksUnconstrained && it.indexMasksUnconstrained })
        assertFalse(result.toString().contains("Call_In_Mic"));assertFalse(result.toString().contains("PRIVATE_PRODUCT"))
        verify(exact,never()).productName
        manager.setInputDevices(listOf(exact,device("Call_In_Mic",43)))
        assertEquals(MicrophoneDiagnosticMatch.MULTIPLE,p.inventory().match)
        manager.setInputDevices(List(17) { near })
        assertEquals(MicrophoneDiagnosticMatch.TRUNCATED,p.inventory().match)
        assertEquals(16,p.inventory().inputs.size)
        manager.setInputDevices(listOf(near));assertEquals(MicrophoneDiagnosticMatch.ABSENT,p.inventory().match)
        `when`(near.sampleRates).thenReturn(intArrayOf(24000,48000,12345))
        `when`(near.channelMasks).thenReturn(intArrayOf(16,12,12345))
        val caps=p.inventory().inputs.single()
        assertEquals(listOf(24000,48000),caps.sampleRates);assertEquals(listOf(16,12),caps.channelMasks)
        assertTrue(caps.truncated)
    }
    @Test fun ownRecorderClientAndDeviceShapesAreDistinctAndSetterIsNotRouteProof() {
        val app=RuntimeEnvironment.getApplication();shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val named=device("Call_In_Mic");val other=device("OTHER_PRIVATE",42)
        shadowOf(app.getSystemService(AudioManager::class.java)).setInputDevices(listOf(named,other))
        val record=mock(AudioRecord::class.java);val config=mock(AudioRecordingConfiguration::class.java)
        val mono=AudioFormat.Builder().setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
        val stereo=AudioFormat.Builder().setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
        `when`(record.format).thenReturn(mono);`when`(record.audioSource).thenReturn(6)
        `when`(record.activeRecordingConfiguration).thenReturn(config);`when`(record.routedDevice).thenReturn(other)
        `when`(config.clientFormat).thenReturn(mono);`when`(config.format).thenReturn(stereo)
        `when`(config.clientAudioSource).thenReturn(6);`when`(config.audioSource).thenReturn(1)
        `when`(config.isClientSilenced).thenReturn(false);`when`(record.setPreferredDevice(named)).thenReturn(true)
        val p=platform(record);assertEquals(MicrophoneDiagnosticMatch.UNIQUE,p.inventory().match)
        val owner=p.open(MicrophoneDiagnosticShape(6,24000,1,16,0))
        try {
            assertTrue(owner.preferNamedInput())
            val o=owner.observe()
            assertEquals(24000,o.actual.sampleRate);assertEquals(1,o.client!!.channelCount)
            assertEquals(48000,o.device!!.sampleRate);assertEquals(2,o.device.channelCount);assertEquals(1,o.device.source)
            assertEquals(false,o.routeMatchesNamedInput);assertEquals(false,o.silenced)
            assertEquals(MicrophoneDiagnosticReason.NONE,p.policy())
            app.getSystemService(AudioManager::class.java).mode=AudioManager.MODE_IN_CALL
            assertEquals(MicrophoneDiagnosticReason.MODE,p.policy())
            app.getSystemService(AudioManager::class.java).mode=AudioManager.MODE_NORMAL
            app.getSystemService(AudioManager::class.java).isMicrophoneMute=true
            assertEquals(MicrophoneDiagnosticReason.MUTED,p.policy())
            shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
            assertEquals(MicrophoneDiagnosticReason.PERMISSION,p.policy())
        } finally { owner.release() }
        verify(record).release()
    }
    @Test fun productionFactoryCreatesRecognitionMonoAndRejectsUnknownOwnPolicyAfterGrace() {
        val app=RuntimeEnvironment.getApplication();shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val reads=java.util.concurrent.atomic.AtomicInteger()
        org.robolectric.shadows.ShadowAudioRecord.setSource(object : org.robolectric.shadows.ShadowAudioRecord.AudioRecordSource {
            override fun readInShortArray(b: ShortArray,o: Int,n: Int,blocking: Boolean): Int {
                assertFalse("diagnostic read must be nonblocking",blocking)
                reads.incrementAndGet();b.fill(23,o,o+n);return n
            }
        })
        val done=java.util.concurrent.CountDownLatch(1)
        val owner=MicrophoneDiagnostic(app,{true}) { if(it.phase==MicrophoneDiagnosticPhase.COMPLETE) done.countDown() }
        try {
            assertTrue(owner.start(MicrophoneDiagnosticComparison.CHANNELS))
            assertTrue(done.await(3,java.util.concurrent.TimeUnit.SECONDS))
            val result=owner.state.result!!
            assertEquals(MicrophoneDiagnosticReason.POLICY_UNKNOWN,result.reason)
            assertEquals(1,result.arms.size)
            assertEquals(MicrophoneDiagnosticShape(6,24000,1,16,0),result.arms.single().actual)
            assertTrue(reads.get()>0)
            assertEquals("unknown-policy PCM is not accepted as signal evidence",0L,result.arms.single().statistics.frames)
            MicrophoneCaptureFence.tryNormal()!!.release()
        } finally { owner.cancel();org.robolectric.shadows.ShadowAudioRecord.clearSource() }
    }
    @Test fun publicContextConstructorIsIdleAndPassiveInspectionCompletesWithoutCapture() {
        val type=MicrophoneDiagnostic::class.java
        val constructor=type.constructors.firstOrNull { it.parameterTypes.contentEquals(arrayOf(Context::class.java,
            kotlin.jvm.functions.Function0::class.java,kotlin.jvm.functions.Function1::class.java)) }
        assertNotNull("production app constructor must be available",constructor)
        val done=java.util.concurrent.CountDownLatch(1)
        val owner=constructor!!.newInstance(RuntimeEnvironment.getApplication(),{true},
            { s: MicrophoneDiagnosticState -> if(s.phase==MicrophoneDiagnosticPhase.COMPLETE) done.countDown() }) as MicrophoneDiagnostic
        assertEquals(MicrophoneDiagnosticPhase.IDLE,owner.state.phase)
        assertTrue(owner.inspect());assertTrue(done.await(2,java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(owner.state.result!!.arms.isEmpty())
    }
}
