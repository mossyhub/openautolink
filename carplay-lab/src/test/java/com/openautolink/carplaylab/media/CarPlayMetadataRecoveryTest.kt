package com.openautolink.carplaylab.media

import android.media.AudioManager
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AudioStreamId
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.AudioOutputSupport
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.MediaCodecInfoBuilder
import org.robolectric.shadows.ShadowMediaCodecList
import java.util.concurrent.*

/** Real receiver/input worker/sink; synthetic admitted session, platform focus shadowed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32,34],manifest=Config.NONE)
class CarPlayMetadataRecoveryTest {
    private fun field(o:Any,n:String)=o.javaClass.getDeclaredField(n).apply { isAccessible=true }
    private class Fixture:AutoCloseable {
        val app=RuntimeEnvironment.getApplication()
        val receiver:ProjectionReceiver
        val session=mock(AirPlaySession::class.java)
        var enabled=true
        var epoch=1L
        val input:ExecutorService
        val sink:AndroidMediaSink
        val focus=shadowOf(app.getSystemService(AudioManager::class.java))
        init {
            val caps=MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
                .setMediaFormat(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,1280,720))
                .setIsEncoder(false).setColorFormats(intArrayOf(2130708361))
                .setProfileLevels(arrayOf(MediaCodecInfo.CodecProfileLevel().apply { profile=MediaCodecInfo.CodecProfileLevel.AVCProfileHigh;level=MediaCodecInfo.CodecProfileLevel.AVCLevel52 })).build()
            ShadowMediaCodecList.addCodec(MediaCodecInfoBuilder.newBuilder().setName("media.avc").setIsEncoder(false).setCapabilities(caps).build())
            val surface=mock(Surface::class.java); `when`(surface.isValid).thenReturn(true)
            receiver=ProjectionReceiver(app,"02:00:00:00:00:01",surface,mock(MfiAuthenticator::class.java),wired=true,onStatus={})
            `when`(session.controlAuthenticated).thenReturn(true); `when`(session.encryptedEventReady).thenReturn(true)
            fun f(n:String)=receiver.javaClass.getDeclaredField(n).apply { isAccessible=true }
            @Suppress("UNCHECKED_CAST") val sessions=f("sessions").get(receiver) as MutableSet<AirPlaySession>
            sessions.add(session);f("active").set(receiver,session);f("runtimeOwner").set(receiver,session);f("runtimeReady").set(receiver,true)
            input=f("input").get(receiver) as ExecutorService;sink=f("sink").get(receiver) as AndroidMediaSink
            focus.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        }
        fun start(kind:String="media"):AudioStreamId = AudioStreamId(100,kind).also { sink.onAudioStarted(it,requireNotNull(AudioOutputSupport.negotiate(0x4000L,100,kind)),0) }
        fun capture():Any? {
            val method=receiver.javaClass.methods.firstOrNull { it.name=="captureMediaRecoveryStamp" }
            assertNotNull("metadata recovery must capture command/experiment authority before parse",method)
            val capturedEpoch=epoch
            return method!!.invoke(receiver,session,epoch,{ enabled && epoch==capturedEpoch })
        }
        fun offer(state:Boolean?, stamp:Any?=capture()) {
            if(stamp==null) return
            val method=receiver.javaClass.methods.first { it.name=="offerPhonePlaybackObservation" }
            method.invoke(receiver,session,state,stamp)
        }
        fun drain() { input.submit {}.get(3,TimeUnit.SECONDS) }
        override fun close() { receiver.close();assertTrue(receiver.awaitClosed(3000)) }
    }
    private fun lose(f:Fixture) {
        val request=f.focus.lastAudioFocusRequest.audioFocusRequest
        (android.media.AudioFocusRequest::class.java.getDeclaredMethod("getOnAudioFocusChangeListener").invoke(request) as AudioManager.OnAudioFocusChangeListener).onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
    }
    private fun volume(f:Fixture,id:AudioStreamId):Float {
        val track=(field(f.sink,"tracks").get(f.sink) as Map<*,*>)[id]!!
        return field(track,"requestedVolume").getFloat(track)
    }
    @Test fun pausedBaselineSurvivesMediaAndAlternateRetirementButNotInteraction()=Fixture().use { f ->
        val music=f.start();lose(f);val original=f.focus.lastAudioFocusRequest
        f.receiver.mediaCommand(f.session,2);f.drain()
        f.offer(false)
        f.sink.onAudioStopped(music)
        val nav=AudioStreamId(101,"default")
        f.sink.onAudioStarted(nav,requireNotNull(AudioOutputSupport.negotiate(0x4000L,101,"default")),0)
        f.sink.onAudioStopped(nav)
        val replacement=f.start()
        f.offer(true);f.drain()
        assertNotSame("a paused observation is not a stale permission",original,f.focus.lastAudioFocusRequest)
        assertEquals(1f,volume(f,replacement),0f)
        lose(f);val refreshed=f.focus.lastAudioFocusRequest
        f.offer(false);val call=f.start("telephony");f.sink.onAudioStopped(call)
        val callRequest=f.focus.lastAudioFocusRequest
        f.offer(true);f.drain();assertSame(callRequest,f.focus.lastAudioFocusRequest)
        assertNotSame(refreshed,callRequest)
    }
    @Test fun freshEngineSetupPreservesPostStopPhonePermitUntilAuthenticatedMediaStart()=Fixture().use { f ->
        val shared=ByteArray(32) {7};val peer=java.net.InetAddress.getLoopbackAddress()
        `when`(f.session.sharedSecret).thenReturn(shared);`when`(f.session.remoteAddress).thenReturn(peer)
        val received=LinkedBlockingQueue<AudioStreamId>()
        val engine=com.shilapi.xcertplay.airplay.CarPlayMediaEngine(object:com.shilapi.xcertplay.airplay.MediaSink by f.sink {
            override fun onAudioRtp(id:AudioStreamId,format:com.shilapi.xcertplay.airplay.AudioFormat,rtp:ByteArray,sample:Int) {
                f.sink.onAudioRtp(id,format,rtp,sample);received.add(id)
            }
        })
        fun start(connection:Long):AudioStreamId {
            val accepted=requireNotNull(engine.onAudio(f.session,100,mapOf("audioType" to "media","audioFormat" to 0x4000L,"streamConnectionID" to connection)))
            val key=com.shilapi.xcertplay.airplay.AirPlayCrypto.hkdfSha512(shared,"DataStream-Salt$connection".toByteArray(),"DataStream-Output-Encryption-Key".toByteArray(),32)
            val wire=com.shilapi.xcertplay.airplay.MicrophonePacketizer.sealPacket(key,com.shilapi.xcertplay.airplay.MicrophoneCounters(),ByteArray(960),480)
            java.net.DatagramSocket().use {it.send(java.net.DatagramPacket(wire,wire.size,peer,(accepted["dataPort"] as Number).toInt()))}
            return requireNotNull(received.poll(3,TimeUnit.SECONDS))
        }
        try {
            start(10);lose(f);val original=f.focus.lastAudioFocusRequest
            f.receiver.mediaCommand(f.session,2);f.drain()
            engine.onSessionClosed(f.session)
            f.offer(false);f.offer(true);f.drain()
            val music=start(11)
            assertNotSame("empty-slot SETUP must not cancel the next-stream permit",original,f.focus.lastAudioFocusRequest)
            val nav=AudioStreamId(101,"default");f.sink.onAudioStarted(nav,requireNotNull(AudioOutputSupport.negotiate(0x4000L,101,"default")),0)
            assertEquals(1f,volume(f,music),0f);assertEquals(1f,volume(f,nav),0f)
            verify(f.session,never()).sendMedia(org.mockito.ArgumentMatchers.eq(1),(org.mockito.ArgumentMatchers.any<() -> Boolean>() ?: {true}))
        } finally {engine.onSessionClosed(f.session)}
        Unit
    }
    @Test fun queuedSourceEdgeCannotRefreshAfterNewerPauseButPauseStillDispatches()=Fixture().use { f ->
        f.start();val original=f.focus.lastAudioFocusRequest
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        try {
            f.input.execute { entered.countDown();release.await() };assertTrue(entered.await(3,TimeUnit.SECONDS))
            f.offer(false);f.offer(true);assertTrue(f.receiver.mediaCommand(f.session,2))
            release.countDown();f.drain()
            verify(f.session).sendMedia(org.mockito.ArgumentMatchers.eq(2),(org.mockito.ArgumentMatchers.any<() -> Boolean>() ?: { true }))
            assertSame("older metadata cannot mint fresh command authority",original,f.focus.lastAudioFocusRequest)
        } finally { release.countDown() }
    }
    @Test fun oneKnownPausePlayRefreshesOnlyMediaAndSendsNoHid()=Fixture().use { f ->
        f.start();val original=f.focus.lastAudioFocusRequest
        f.offer(false);f.offer(true);f.drain()
        assertNotSame(original,f.focus.lastAudioFocusRequest)
        val refreshed=f.focus.lastAudioFocusRequest
        f.offer(true);f.drain();assertSame(refreshed,f.focus.lastAudioFocusRequest)
        verify(f.session,never()).sendMedia(anyInt(),(org.mockito.ArgumentMatchers.any<() -> Boolean>() ?: { true })); Unit
    }
    @Test fun stampCapturedBeforePauseCannotBeRelabelledWhenParseFinishes()=Fixture().use { f ->
        f.start();val original=f.focus.lastAudioFocusRequest
        f.offer(false);val beforeParse=f.capture()
        assertTrue(f.receiver.mediaCommand(f.session,2));f.offer(true,beforeParse);f.drain()
        verify(f.session).sendMedia(org.mockito.ArgumentMatchers.eq(2),(org.mockito.ArgumentMatchers.any<() -> Boolean>() ?: { true }));assertSame(original,f.focus.lastAudioFocusRequest)
    }
    @Test fun unknownAndOffOnResetKnownEdgeWithoutReplayingCurrentPlaying()=Fixture().use { f ->
        f.start();val original=f.focus.lastAudioFocusRequest
        f.offer(false);f.offer(null);f.offer(true);f.drain();assertSame(original,f.focus.lastAudioFocusRequest)
        f.offer(false);f.epoch++;f.offer(true);f.drain();assertSame(original,f.focus.lastAudioFocusRequest)
        f.enabled=false;f.offer(false);f.offer(true);f.drain();assertSame(original,f.focus.lastAudioFocusRequest)
    }
    @Test fun pauseWhileAdmissionPredicateIsBlockedCannotMintANewerRecoveryStamp()=Fixture().use { f ->
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val worker=Executors.newSingleThreadExecutor()
        try {
            val capture=worker.submit<Any?> {
                f.receiver.captureMediaRecoveryStamp(f.session,1) { entered.countDown();release.await();true }
            }
            assertTrue(entered.await(3,TimeUnit.SECONDS))
            assertTrue(f.receiver.mediaCommand(f.session,2));release.countDown()
            assertNull("capture must retain ingress epoch before foreign admission work",capture.get(3,TimeUnit.SECONDS))
        } finally {release.countDown();worker.shutdownNow()}
    }
    @Test fun communicationBusyAtIngressCannotBeSavedForCallEnd()=Fixture().use { f ->
        f.start();f.offer(false)
        val manager=f.app.getSystemService(AudioManager::class.java)
        manager.mode=AudioManager.MODE_IN_COMMUNICATION
        assertNull("known communication mode is not media recovery authority",f.capture())
    }
    @Test fun expiredEdgeAndLaterFalseClearPendingWithoutRetry()=Fixture().use { f ->
        f.start();val original=f.focus.lastAudioFocusRequest
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        try {
            f.input.execute { entered.countDown();release.await() };assertTrue(entered.await(3,TimeUnit.SECONDS))
            f.offer(false);f.offer(true)
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(5001))
            release.countDown();f.drain();assertSame(original,f.focus.lastAudioFocusRequest)
            f.offer(true);f.drain();assertSame(original,f.focus.lastAudioFocusRequest)
            f.offer(false);f.offer(true);f.offer(false);f.drain()
        } finally { release.countDown() }
    }
    @Test fun interactionDiscardsRatherThanSavingRecoveryUntilCallEnd()=Fixture().use { f ->
        f.start();val call=f.start("telephony");val original=f.focus.lastAudioFocusRequest
        f.offer(false);f.offer(true);f.drain();f.sink.onAudioStopped(call);f.drain()
        assertSame(original,f.focus.lastAudioFocusRequest);verify(f.session,never()).sendMedia(anyInt(),(org.mockito.ArgumentMatchers.any<() -> Boolean>() ?: { true })); Unit
    }
}
