package com.openautolink.app.media

import android.app.Application
import android.media.*
import android.view.Surface
import com.openautolink.app.carplay.*
import com.openautolink.carplaylab.media.ProjectionReceiver
import com.openautolink.carplaylab.metadata.CarPlayMediaMetadata
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.media.*
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import io.mockk.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import org.robolectric.shadows.*
import java.util.concurrent.*

/** Real app Resume -> installed host -> receiver command queue -> sink/focus.
 * Only phone transport and OS focus are substituted; not physical audibility. */
@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[32,34],manifest=Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ExplicitResumeIntegrationTest {
    private fun field(o:Any,n:String)=o.javaClass.getDeclaredField(n).apply {isAccessible=true}
    @After fun cleanup() { OalMediaSessionManager.instanceOrNull()?.release();unmockkAll() }
    private fun fixture(block:(OalMediaSessionManager,ProjectionReceiver,AirPlaySession,AndroidMediaSink,ShadowAudioManager)->Unit) {
        val app=RuntimeEnvironment.getApplication()
        val prefs=mockk<com.openautolink.app.data.AppPreferences>()
        mockkObject(com.openautolink.app.data.AppPreferences.Companion)
        every {com.openautolink.app.data.AppPreferences.getInstance(any())} returns prefs
        every {prefs.clusterNavigation} returns kotlinx.coroutines.flow.flowOf(false)
        every {prefs.distanceUnits} returns kotlinx.coroutines.flow.flowOf("auto")
        val caps=MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,1280,720))
            .setIsEncoder(false).setColorFormats(intArrayOf(2130708361))
            .setProfileLevels(arrayOf(MediaCodecInfo.CodecProfileLevel().apply {profile=MediaCodecInfo.CodecProfileLevel.AVCProfileHigh;level=MediaCodecInfo.CodecProfileLevel.AVCLevel52})).build()
        ShadowMediaCodecList.addCodec(MediaCodecInfoBuilder.newBuilder().setName("media.avc").setIsEncoder(false).setCapabilities(caps).build())
        val surface=mockk<Surface>();every {surface.isValid} returns true
        val receiver=ProjectionReceiver(app,"02:00:00:00:00:01",surface,mockk<MfiAuthenticator>(relaxed=true),wired=true,onStatus={})
        val session=mockk<AirPlaySession>(relaxed=true)
        every {session.controlAuthenticated} returns true;every {session.encryptedEventReady} returns true
        every {session.sendMedia(any(),any())} returns true
        @Suppress("UNCHECKED_CAST") val sessions=field(receiver,"sessions").get(receiver) as MutableSet<AirPlaySession>
        sessions.add(session);field(receiver,"active").set(receiver,session);field(receiver,"runtimeOwner").set(receiver,session);field(receiver,"runtimeReady").set(receiver,true)
        val manager=OalMediaSessionManager.getInstance(app);manager.initialize()
        val lease=CarPlayHostMediaLease(CarPlayHostMediaMode.NORMAL);manager.beginCarPlayHostMedia(lease)
        val host=OalCarPlayMetadataHost(app,attemptContext=CarPlayAttemptContext(lease)) {receiver.mediaCommand(session,it)}
        val shadow=Shadows.shadowOf(app.getSystemService(AudioManager::class.java))
        try {
            assertTrue(host.start());host.mediaAvailability(true)
            block(manager,receiver,session,field(receiver,"sink").get(receiver) as AndroidMediaSink,shadow)
        } finally {
            // Allow successful OS focus retirement only after all denial assertions.
            shadow.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            host.close();receiver.close();assertTrue(receiver.awaitClosed(3000));manager.finishCarPlayHostMedia(lease)
            ShadowMediaCodec.clearCodecs();ShadowMediaCodecList.reset()
        }
    }
    private fun start(sink:AndroidMediaSink,purpose:String,type:Int=100):AudioStreamId {
        val id=AudioStreamId(type,purpose)
        sink.onAudioStarted(id,requireNotNull(AudioOutputSupport.negotiate(0x4000L,type,purpose)),0)
        return id
    }
    private fun volume(sink:AndroidMediaSink,id:AudioStreamId):Float {
        val track=(field(sink,"tracks").get(sink) as Map<*,*>)[id]!!
        return field(track,"requestedVolume").getFloat(track)
    }
    private fun assertApplied(sink:AndroidMediaSink,id:AudioStreamId,percent:Int) {
        val track=(field(sink,"tracks").get(sink) as Map<*,*>)[id]!!
        val format=requireNotNull(AudioOutputSupport.negotiate(0x4000L,id.type,id.audioType))
        sink.onAudioRtp(id,format,ByteArray(12).apply {this[0]=0x80.toByte()}+byteArrayOf(0x12,0x34,0x12,0x34),0)
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
        while(System.nanoTime()<deadline && (field(track,"appliedPercent").get(track)!=percent || field(track,"streamWritten").getLong(track)==0L)) Thread.sleep(2)
        assertEquals(percent,field(track,"appliedPercent").get(track))
        assertTrue("nonzero PCM reaches the AudioTrack write boundary",field(track,"streamWritten").getLong(track)>0)
    }
    private fun drain(receiver:ProjectionReceiver) {(field(receiver,"input").get(receiver) as ExecutorService).submit {}.get(3,TimeUnit.SECONDS)}
    private fun lose(request:AudioFocusRequest) {
        (AudioFocusRequest::class.java.getDeclaredMethod("getOnAudioFocusChangeListener").invoke(request) as AudioManager.OnAudioFocusChangeListener)
            .onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
    }
    @Test fun explicitResumeRequestsNewGrantAndRestoresBothReplacementMusicAndNavigation() = fixture { manager,receiver,session,sink,focus ->
        focus.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val old=start(sink,"media");val original=focus.lastAudioFocusRequest.audioFocusRequest
        lose(original)
        sink.onAudioStopped(old)
        val music=start(sink,"media");val nav=start(sink,"default",101)
        assertEquals(0f,volume(sink,music),0f);assertEquals(0f,volume(sink,nav),0f)
        val controls=field(manager,"carPlayControls").get(manager)!!
        val owner=field(controls,"owner").get(controls)!!
        repeat(5) {manager.updateCarPlay(owner,CarPlayMediaMetadata(playing=true),null,true)};drain(receiver)
        assertApplied(sink,music,0);assertApplied(sink,nav,0)
        assertSame("replacement SETUP and repeated publications do not reclaim",original,focus.lastAudioFocusRequest.audioFocusRequest)
        requireNotNull(manager.captureCarPlayAudioResume()).invoke();drain(receiver)
        assertNotSame(original,focus.lastAudioFocusRequest.audioFocusRequest)
        assertEquals(1f,volume(sink,music),0f);assertEquals(1f,volume(sink,nav),0f)
        assertApplied(sink,music,100);assertApplied(sink,nav,100)
        verify(exactly=1) {session.sendMedia(1,any())}
        assertFalse(PhonePlaybackResumeRecovery.isEnabled())
    }
    @Test fun stopAfterResumeIngressCancelsQueuedRecoveryBeforeFocusRequest() = fixture {manager,receiver,session,sink,focus ->
        focus.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val music=start(sink,"media");val original=focus.lastAudioFocusRequest.audioFocusRequest;lose(original)
        val input=field(receiver,"input").get(receiver) as ExecutorService
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        input.submit {entered.countDown();assertTrue(release.await(3,TimeUnit.SECONDS))};assertTrue(entered.await(3,TimeUnit.SECONDS))
        try {requireNotNull(manager.captureCarPlayAudioResume()).invoke();assertTrue(receiver.mediaCommand(session,2))}
        finally {release.countDown()}
        drain(receiver)
        assertSame(original,focus.lastAudioFocusRequest.audioFocusRequest)
        assertEquals(0f,volume(sink,music),0f)
        verify(exactly=0) {session.sendMedia(1,any())}
    }
    @Test fun deniedResumeCannotUnmuteMusicOrNavigationOrSendPhonePlay() = fixture {manager,receiver,session,sink,focus ->
        focus.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val music=start(sink,"media");lose(focus.lastAudioFocusRequest.audioFocusRequest)
        val nav=start(sink,"default",101)
        focus.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        requireNotNull(manager.captureCarPlayAudioResume()).invoke();drain(receiver)
        assertEquals(0f,volume(sink,music),0f);assertEquals(0f,volume(sink,nav),0f)
        verify(exactly=0) {session.sendMedia(1,any())}
    }
    @Test fun resumeDuringCallOrSiriPreservesIndependentFocusAndMediaMute() {
        for(purpose in listOf("telephony","speechrecognition")) fixture {manager,receiver,session,sink,focus ->
            focus.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            val music=start(sink,"media");lose(focus.lastAudioFocusRequest.audioFocusRequest)
            val nav=start(sink,"default",101);val communication=start(sink,purpose)
            val communicationRequest=focus.lastAudioFocusRequest.audioFocusRequest
            requireNotNull(manager.captureCarPlayAudioResume()).invoke();drain(receiver)
            assertSame("explicit resume must defer during $purpose",communicationRequest,focus.lastAudioFocusRequest.audioFocusRequest)
            assertEquals(1f,volume(sink,communication),0f)
            assertEquals(0f,volume(sink,music),0f);assertEquals(0f,volume(sink,nav),0f)
            verify(exactly=1) {session.sendMedia(1,any())}
        }
    }
}
