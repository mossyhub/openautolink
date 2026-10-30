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

/** Real native callback -> installed host -> receiver command queue -> sink/focus.
 * Only phone transport and OS focus are substituted; not physical audibility. */
@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[32,34],manifest=Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ExplicitResumeIntegrationTest {
    private fun field(o:Any,n:String)=o.javaClass.getDeclaredField(n).apply {isAccessible=true}
    @After fun cleanup() { OalMediaSessionManager.instanceOrNull()?.release();unmockkAll() }
    private fun fixture(playbackEligible:()->Boolean={true},block:(OalMediaSessionManager,ProjectionReceiver,AirPlaySession,AndroidMediaSink,ShadowAudioManager)->Unit) {
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
        val link=OalCarPlayMetadataLink({android.os.SystemClock.elapsedRealtime()},{},host)
        @Suppress("UNCHECKED_CAST") val links=field(receiver,"runtimeMetadata").get(receiver) as MutableMap<AirPlaySession,com.openautolink.carplaylab.metadata.RuntimeMetadataLink>
        links[session]=link
        link.capturePlaybackObservation = capture@ {
            PhonePlaybackResumeRecovery.capture(receiver,session,
                CarPlayAttemptContext(lease,playbackAdmission=playbackEligible),manager)
        }
        val shadow=Shadows.shadowOf(app.getSystemService(AudioManager::class.java))
        try {
            link.authenticate()
            (field(link,"worker").get(link) as ExecutorService).submit {}.get(3,TimeUnit.SECONDS)
            host.mediaAvailability(true)
            block(manager,receiver,session,field(receiver,"sink").get(receiver) as AndroidMediaSink,shadow)
        } finally {
            // Allow successful OS focus retirement only after all denial assertions.
            shadow.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            links.remove(session);link.close();receiver.close();assertTrue(receiver.awaitClosed(3000));manager.finishCarPlayHostMedia(lease)
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
    private fun drain(receiver:ProjectionReceiver) {
        (field(receiver,"input").get(receiver) as ExecutorService).submit {}.get(3,TimeUnit.SECONDS)
        (field(receiver,"runtimeMetadata").get(receiver) as Map<*,*>).values.forEach { link ->
            (field(link!!,"worker").get(link) as ExecutorService).submit {}.get(3,TimeUnit.SECONDS)
        }
    }
    private fun lose(request:AudioFocusRequest) {
        (AudioFocusRequest::class.java.getDeclaredMethod("getOnAudioFocusChangeListener").invoke(request) as AudioManager.OnAudioFocusChangeListener)
            .onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
    }
    private fun callback(manager:OalMediaSessionManager):android.support.v4.media.session.MediaSessionCompat.Callback {
        val compat=field(manager,"mediaSession").get(manager)!!
        val impl=field(compat,"mImpl").get(compat)
        return impl.javaClass.methods.first {it.name=="getCallback"}.apply {isAccessible=true}.invoke(impl) as android.support.v4.media.session.MediaSessionCompat.Callback
    }
    private fun callbackCount(name:String):Int {
        val snapshot=MediaReadinessDiagnostics::class.java.getDeclaredMethod("snapshot").apply {isAccessible=true}.invoke(MediaReadinessDiagnostics) as String
        return Regex("(?m)^$name=([0-9]+)$").find(snapshot)?.groupValues?.get(1)?.toInt() ?: 0
    }
    private fun phone(receiver:ProjectionReceiver,state:Int) {
        val link=(field(receiver,"runtimeMetadata").get(receiver) as Map<*,*>).values.single() as OalCarPlayMetadataLink
        link.handle(com.shilapi.xcertplay.iap2.message.Iap2Messages.buildRaw(0x5001) { group(1) { u8(0,state) } })
    }
    @Test fun normalBackgroundPhoneEdgeRestoresReplacementMusicAndNavigationWithoutNativePlay()=fixture {manager,receiver,session,sink,focus ->
        focus.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val old=start(sink,"media");val original=focus.lastAudioFocusRequest.audioFocusRequest
        lose(original);callback(manager).onStop();drain(receiver);sink.onAudioStopped(old)
        val music=start(sink,"media");val nav=start(sink,"default",101)
        assertApplied(sink,music,0);assertApplied(sink,nav,0)
        assertSame(original,focus.lastAudioFocusRequest.audioFocusRequest)
        phone(receiver,2);phone(receiver,1);drain(receiver)
        assertNotSame("fresh authenticated phone edge must fund normal recovery without Activity resume",original,focus.lastAudioFocusRequest.audioFocusRequest)
        assertApplied(sink,music,100);assertApplied(sink,nav,100)
        assertEquals(android.support.v4.media.session.PlaybackStateCompat.STATE_PLAYING,manager.diagnosticSnapshot().submittedState)
        verify(exactly=0) {session.sendMedia(1,any())}
    }
    @Test fun phoneEdgeDuringCallOrSiriDefersExactlyOnceUntilInteractionRetires() {
        for(purpose in listOf("telephony","speechrecognition")) fixture {_,receiver,_,sink,focus ->
            focus.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            val music=start(sink,"media");lose(focus.lastAudioFocusRequest.audioFocusRequest)
            val interaction=start(sink,purpose);val request=focus.lastAudioFocusRequest.audioFocusRequest
            phone(receiver,2);phone(receiver,1);drain(receiver)
            assertSame(request,focus.lastAudioFocusRequest.audioFocusRequest)
            assertApplied(sink,music,0);assertApplied(sink,interaction,100)
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(30))
            sink.onAudioStopped(interaction);drain(receiver)
            assertNotSame("accepted edge must survive the admitted interaction, without stealing focus",request,focus.lastAudioFocusRequest.audioFocusRequest)
            assertApplied(sink,music,100)
            val recovered=focus.lastAudioFocusRequest.audioFocusRequest
            repeat(2000) {phone(receiver,1)};drain(receiver)
            assertSame(recovered,focus.lastAudioFocusRequest.audioFocusRequest)
        }
    }
    @Test fun newerLossCancelsQueuedPhoneCandidateButFreshPostLossEdgeCanRenew()=fixture {_,receiver,_,sink,focus ->
        focus.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val music=start(sink,"media");val original=focus.lastAudioFocusRequest.audioFocusRequest
        val input=field(receiver,"input").get(receiver) as ExecutorService
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        input.submit {entered.countDown();assertTrue(release.await(3,TimeUnit.SECONDS))};assertTrue(entered.await(3,TimeUnit.SECONDS))
        try {phone(receiver,2);phone(receiver,1);lose(original)} finally {release.countDown()}
        drain(receiver);assertSame("LOSS ingress must revoke queued phone intent",original,focus.lastAudioFocusRequest.audioFocusRequest)
        assertApplied(sink,music,0)
        phone(receiver,2);phone(receiver,1);drain(receiver);assertApplied(sink,music,100)
        val refreshed=focus.lastAudioFocusRequest.audioFocusRequest
        lose(original);phone(receiver,2);phone(receiver,1);drain(receiver)
        assertNotSame(refreshed,focus.lastAudioFocusRequest.audioFocusRequest)
    }
    @Test fun deniedPhoneEdgeIsNotReplenishedByPacketsSetupOrRepeatedPlaying()=fixture {manager,receiver,session,sink,focus ->
        focus.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val music=start(sink,"media");lose(focus.lastAudioFocusRequest.audioFocusRequest)
        focus.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        phone(receiver,2);phone(receiver,1);drain(receiver)
        val denied=focus.lastAudioFocusRequest.audioFocusRequest
        repeat(2000) {phone(receiver,1)};drain(receiver)
        start(sink,"media");assertApplied(sink,music,0)
        assertSame(denied,focus.lastAudioFocusRequest.audioFocusRequest)
        assertEquals(android.support.v4.media.session.PlaybackStateCompat.STATE_PAUSED,manager.diagnosticSnapshot().submittedState)
        focus.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        phone(receiver,2);phone(receiver,1);drain(receiver)
        assertNotSame(denied,focus.lastAudioFocusRequest.audioFocusRequest);assertApplied(sink,music,100)
        verify(exactly=0) {session.sendMedia(1,any())}
    }
    @Test fun phoneStampMustBeCapturedBeforeMetadataMonitorWait()=fixture {manager,receiver,_,sink,focus ->
        focus.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        start(sink,"media");lose(focus.lastAudioFocusRequest.audioFocusRequest)
        val original=focus.lastAudioFocusRequest.audioFocusRequest
        val link=(field(receiver,"runtimeMetadata").get(receiver) as Map<*,*>).values.single() as OalCarPlayMetadataLink
        phone(receiver,2)
        val capture=link.capturePlaybackObservation;val entered=CountDownLatch(1)
        link.capturePlaybackObservation={capture().also {entered.countDown()}}
        val worker=Executors.newSingleThreadExecutor()
        try {
            lateinit var future:java.util.concurrent.Future<*>
            synchronized(link) {
                future=worker.submit {phone(receiver,1)}
                assertTrue("capture must precede the first parse/presentation monitor wait",entered.await(1,TimeUnit.SECONDS))
                callback(manager).onStop()
            }
            future.get(3,TimeUnit.SECONDS);drain(receiver)
            assertSame(original,focus.lastAudioFocusRequest.audioFocusRequest)
            phone(receiver,2);phone(receiver,1);drain(receiver)
            assertNotSame(original,focus.lastAudioFocusRequest.audioFocusRequest)
        } finally {worker.shutdown();assertTrue(worker.awaitTermination(3,TimeUnit.SECONDS))}
    }
    @Test fun unavailableForegroundServiceCannotAuthorizePhoneEdge()=fixture(playbackEligible={false}) {_,receiver,_,sink,focus ->
        focus.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val music=start(sink,"media");val original=focus.lastAudioFocusRequest.audioFocusRequest;lose(original)
        phone(receiver,2);phone(receiver,1);drain(receiver)
        assertSame("current connection foreground-service eligibility is mandatory, not Activity visibility",original,focus.lastAudioFocusRequest.audioFocusRequest)
        assertApplied(sink,music,0)
    }
    @Test fun revokedForegroundServiceCancelsQueuedCandidate() {
        var eligible=true
        fixture(playbackEligible={eligible}) {_,receiver,_,sink,focus ->
            focus.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            start(sink,"media");val original=focus.lastAudioFocusRequest.audioFocusRequest;lose(original)
            val input=field(receiver,"input").get(receiver) as ExecutorService
            val entered=CountDownLatch(1);val release=CountDownLatch(1)
            input.submit {entered.countDown();assertTrue(release.await(3,TimeUnit.SECONDS))};assertTrue(entered.await(3,TimeUnit.SECONDS))
            try {phone(receiver,2);phone(receiver,1);eligible=false} finally {release.countDown()}
            drain(receiver);assertSame(original,focus.lastAudioFocusRequest.audioFocusRequest)
        }
    }
    @Implements(AudioManager::class)
    class ResumeFocusManager:ShadowAudioManager() {
        @Implementation override fun requestAudioFocus(request:android.media.AudioFocusRequest?):Int {
            val result=super.requestAudioFocus(request)
            duringRequest?.invoke(requireNotNull(request))
            return result
        }
        companion object { @JvmField var duringRequest:((android.media.AudioFocusRequest)->Unit)?=null }
    }
    @Test @Config(shadows=[ResumeFocusManager::class])
    fun selectedLossFromAIsAtomicAgainstFreshPhoneFocusB()=fixture {manager,receiver,session,sink,focus ->
        focus.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val music=start(sink,"media");val nav=start(sink,"default",101)
        val original=focus.lastAudioFocusRequest.audioFocusRequest
        val selected=CountDownLatch(1);val releaseLoss=CountDownLatch(1)
        val binder=CountDownLatch(1);val releaseBinder=CountDownLatch(1)
        val worker=Executors.newSingleThreadExecutor()
        val callbackThread=java.util.concurrent.atomic.AtomicReference<Thread>()
        field(sink,"beforePhoneResumeLossRevocation").set(sink,{
            selected.countDown();assertTrue(releaseLoss.await(3,TimeUnit.SECONDS))
        } as ()->Unit)
        try {
            val loss=worker.submit {callbackThread.set(Thread.currentThread());lose(original)}
            assertTrue(selected.await(3,TimeUnit.SECONDS))
            ResumeFocusManager.duringRequest={binder.countDown();assertTrue(releaseBinder.await(3,TimeUnit.SECONDS))}
            phone(receiver,2);phone(receiver,1);assertTrue(binder.await(3,TimeUnit.SECONDS))
            val epoch=sink.phoneResumeGeneration();val observation=sink.phoneObservationGeneration()
            releaseLoss.countDown()
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2)
            while(callbackThread.get().state!=Thread.State.BLOCKED && !loss.isDone && System.nanoTime()<deadline) Thread.yield()
            assertEquals("actual callback reaches sink monitor only after lock-free revocation",Thread.State.BLOCKED,callbackThread.get().state)
            assertEquals("retired A must not revoke B's original authority while B is in Binder",epoch,sink.phoneResumeGeneration())
            assertEquals(observation,sink.phoneObservationGeneration())
            releaseBinder.countDown();loss.get(3,TimeUnit.SECONDS);drain(receiver)
            assertApplied(sink,music,100);assertApplied(sink,nav,100)
            assertEquals(android.support.v4.media.session.PlaybackStateCompat.STATE_PLAYING,manager.diagnosticSnapshot().submittedState)
            verify(exactly=0) {session.sendMedia(1,any())}
        } finally {
            releaseLoss.countDown();releaseBinder.countDown();field(sink,"beforePhoneResumeLossRevocation").set(sink,null)
            ResumeFocusManager.duringRequest=null;worker.shutdown();assertTrue(worker.awaitTermination(3,TimeUnit.SECONDS))
        }
    }
    @Test fun selectedOldLossCannotClearFreshDeferredCallOrSiriPermit() {
        for(purpose in listOf("telephony","speechrecognition")) fixture {_,receiver,session,sink,focus ->
            focus.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            val music=start(sink,"media");val original=focus.lastAudioFocusRequest.audioFocusRequest
            val selected=CountDownLatch(1);val release=CountDownLatch(1)
            val worker=Executors.newSingleThreadExecutor()
            field(sink,"beforePhoneResumeLossRevocation").set(sink,{
                selected.countDown();assertTrue(release.await(3,TimeUnit.SECONDS))
            } as ()->Unit)
            try {
                val loss=worker.submit {lose(original)};assertTrue(selected.await(3,TimeUnit.SECONDS))
                phone(receiver,2);phone(receiver,1);drain(receiver)
                assertNotSame(original,focus.lastAudioFocusRequest.audioFocusRequest)
                val interaction=start(sink,purpose);val held=focus.lastAudioFocusRequest.audioFocusRequest
                phone(receiver,2);phone(receiver,1);drain(receiver)
                val epoch=sink.phoneResumeGeneration();val observation=sink.phoneObservationGeneration()
                release.countDown();loss.get(3,TimeUnit.SECONDS)
                assertEquals(epoch,sink.phoneResumeGeneration());assertEquals(observation,sink.phoneObservationGeneration())
                assertSame(held,focus.lastAudioFocusRequest.audioFocusRequest)
                ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(30))
                sink.onAudioStopped(interaction);drain(receiver)
                assertNotSame(held,focus.lastAudioFocusRequest.audioFocusRequest)
                assertApplied(sink,music,100)
                assertEquals("deferral consumes the original epochs, never mints call-end authority",epoch,sink.phoneResumeGeneration())
                assertEquals(observation,sink.phoneObservationGeneration())
                verify(exactly=0) {session.sendMedia(1,any())}
            } finally {release.countDown();field(sink,"beforePhoneResumeLossRevocation").set(sink,null);worker.shutdown();assertTrue(worker.awaitTermination(3,TimeUnit.SECONDS))}
        }
    }
    @Test fun stopRevokesQueuedPhoneCandidateBeforeFocusRequest()=fixture {manager,receiver,session,sink,focus ->
        focus.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val music=start(sink,"media");val original=focus.lastAudioFocusRequest.audioFocusRequest;lose(original)
        val input=field(receiver,"input").get(receiver) as ExecutorService
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        input.submit {entered.countDown();assertTrue(release.await(3,TimeUnit.SECONDS))};assertTrue(entered.await(3,TimeUnit.SECONDS))
        try {phone(receiver,2);phone(receiver,1);callback(manager).onStop()} finally {release.countDown()}
        drain(receiver);assertSame(original,focus.lastAudioFocusRequest.audioFocusRequest)
        assertApplied(sink,music,0)
        phone(receiver,2);phone(receiver,1);drain(receiver);assertApplied(sink,music,100)
        verify(exactly=0) {session.sendMedia(1,any())}
    }
    @Test @Config(shadows=[ResumeFocusManager::class])
    fun currentLossRevokesPhoneAuthorityBeforeHeldBinderReturns()=fixture {_,receiver,session,sink,focus ->
        focus.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val music=start(sink,"media");lose(focus.lastAudioFocusRequest.audioFocusRequest)
        val binder=CountDownLatch(1);val release=CountDownLatch(1)
        val request=java.util.concurrent.atomic.AtomicReference<AudioFocusRequest>()
        val worker=Executors.newSingleThreadExecutor()
        ResumeFocusManager.duringRequest={request.set(it);binder.countDown();assertTrue(release.await(3,TimeUnit.SECONDS))}
        try {
            phone(receiver,2);phone(receiver,1);assertTrue(binder.await(3,TimeUnit.SECONDS))
            val epoch=sink.phoneResumeGeneration()
            val loss=worker.submit {lose(request.get())}
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2)
            while(sink.phoneResumeGeneration()==epoch && System.nanoTime()<deadline) Thread.yield()
            assertTrue("current LOSS revokes without waiting for the sink monitor/Binder",sink.phoneResumeGeneration()>epoch)
            assertFalse(loss.isDone)
            release.countDown();loss.get(3,TimeUnit.SECONDS);drain(receiver);assertApplied(sink,music,0)
            verify(exactly=0) {session.sendMedia(1,any())}
        } finally {release.countDown();ResumeFocusManager.duringRequest=null;worker.shutdown();assertTrue(worker.awaitTermination(3,TimeUnit.SECONDS))}
    }
    @Test @Config(shadows=[ResumeFocusManager::class])
    fun stopOrLossDuringPhoneFocusBinderCannotPublishGrantOrSendPlay() {
        for(stop in listOf(true,false)) fixture {manager,receiver,session,sink,focus ->
            focus.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            val music=start(sink,"media");lose(focus.lastAudioFocusRequest.audioFocusRequest)
            val entered=CountDownLatch(1);val release=CountDownLatch(1)
            ResumeFocusManager.duringRequest={request ->
                entered.countDown();assertTrue(release.await(3,TimeUnit.SECONDS))
                if(!stop) lose(request)
            }
            try {
                phone(receiver,2);phone(receiver,1);assertTrue(entered.await(3,TimeUnit.SECONDS))
                if(stop) callback(manager).onStop()
                release.countDown();drain(receiver)
                assertApplied(sink,music,0)
                assertEquals(android.support.v4.media.session.PlaybackStateCompat.STATE_PAUSED,manager.diagnosticSnapshot().submittedState)
                verify(exactly=0) {session.sendMedia(1,any())}
                ResumeFocusManager.duringRequest=null
                phone(receiver,2);phone(receiver,1);drain(receiver);assertApplied(sink,music,100)
            } finally {release.countDown();ResumeFocusManager.duringRequest=null}
        }
    }
    @Test fun stopRevokesDeferredPhoneEdgeWithoutCancellingFutureFreshResume()=fixture {manager,receiver,_,sink,focus ->
        focus.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val music=start(sink,"media");lose(focus.lastAudioFocusRequest.audioFocusRequest)
        val call=start(sink,"telephony");val original=focus.lastAudioFocusRequest.audioFocusRequest
        phone(receiver,2);phone(receiver,1);drain(receiver)
        callback(manager).onStop();drain(receiver);sink.onAudioStopped(call);drain(receiver)
        assertSame(original,focus.lastAudioFocusRequest.audioFocusRequest);assertApplied(sink,music,0)
        phone(receiver,2);phone(receiver,1);drain(receiver);assertApplied(sink,music,100)
    }
    @Test fun replacementOwnerCannotAdoptAlreadyCapturedPhoneObservation()=fixture {_,receiver,session,sink,focus ->
        focus.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        start(sink,"media");val original=focus.lastAudioFocusRequest.audioFocusRequest;lose(original)
        val link=(field(receiver,"runtimeMetadata").get(receiver) as Map<*,*>).values.single() as OalCarPlayMetadataLink
        phone(receiver,2);val observation=link.capturePlaybackObservation()!!
        val successor=mockk<AirPlaySession>(relaxed=true)
        every {successor.controlAuthenticated} returns true;every {successor.encryptedEventReady} returns true
        field(receiver,"active").set(receiver,successor)
        try {observation(true);drain(receiver);assertSame(original,focus.lastAudioFocusRequest.audioFocusRequest)}
        finally {field(receiver,"active").set(receiver,session)}
        // A fresh admitted edge remains possible; stale work did not mint an adopted epoch.
        phone(receiver,2);phone(receiver,1);drain(receiver)
        assertNotSame(original,focus.lastAudioFocusRequest.audioFocusRequest)
    }
    @Test fun admittedCallDefersAcrossCommunicationModeUntilBothFencesEnd()=fixture {_,receiver,_,sink,focus ->
        focus.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val music=start(sink,"media");lose(focus.lastAudioFocusRequest.audioFocusRequest)
        val call=start(sink,"telephony");val original=focus.lastAudioFocusRequest.audioFocusRequest
        val audio=RuntimeEnvironment.getApplication().getSystemService(AudioManager::class.java)
        val listener=field(sink,"communicationListener").get(sink) as AudioManager.OnModeChangedListener
        try {
            audio.mode=AudioManager.MODE_IN_COMMUNICATION;listener.onModeChanged(AudioManager.MODE_IN_COMMUNICATION)
            phone(receiver,2);phone(receiver,1);drain(receiver)
            assertSame(original,focus.lastAudioFocusRequest.audioFocusRequest)
            sink.onAudioStopped(call);drain(receiver)
            assertSame("communication mode still forbids competing MEDIA focus",original,focus.lastAudioFocusRequest.audioFocusRequest)
            audio.mode=AudioManager.MODE_NORMAL;listener.onModeChanged(AudioManager.MODE_NORMAL);drain(receiver)
            assertNotSame(original,focus.lastAudioFocusRequest.audioFocusRequest);assertApplied(sink,music,100)
        } finally {audio.mode=AudioManager.MODE_NORMAL;listener.onModeChanged(AudioManager.MODE_NORMAL)}
    }
    @Test fun callbackEntryIsCountedBeforeUnknownIdAndUnreadyRejection()=fixture {manager,receiver,session,_,_ ->
        val other=callbackCount("callback_play_from_other");val missing=callbackCount("callback_play_from_null")
        val unready=callbackCount("callback_play_not_ready");val prepare=callbackCount("callback_prepare")
        callback(manager).onPlayFromMediaId("private-canary-id",null)
        callback(manager).onPlayFromMediaId(null,null)
        val controls=field(manager,"carPlayControls").get(manager)!!
        manager.setCarPlayMediaReady(field(controls,"owner").get(controls)!!,false)
        callback(manager).onPlayFromMediaId(OalMediaBrowserService.PHONE_MEDIA_ID,null)
        callback(manager).onPrepare();drain(receiver)
        assertEquals(other+1,callbackCount("callback_play_from_other"))
        assertEquals(missing+1,callbackCount("callback_play_from_null"))
        assertEquals(unready+1,callbackCount("callback_play_not_ready"))
        assertEquals(prepare+1,callbackCount("callback_prepare"))
        verify(exactly=0) {session.sendMedia(1,any())}
    }
    @Test fun nativeStopCancelsReceiverBeforePotentiallyBlockedHostPublication()=fixture {manager,receiver,_,_,_ ->
        val installed=callback(manager)
        val epoch=field(receiver,"mediaPlayEpoch").get(receiver) as java.util.concurrent.atomic.AtomicLong
        val before=epoch.get()
        val real=field(manager,"mediaSession").get(manager) as android.support.v4.media.session.MediaSessionCompat
        val platform=spyk(real)
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        every {platform.setPlaybackState(any())} answers { entered.countDown();assertTrue(release.await(3,TimeUnit.SECONDS));Unit }
        field(manager,"mediaSession").set(manager,platform)
        val worker=Executors.newSingleThreadExecutor()
        try {
            val stop=worker.submit { installed.onStop() }
            assertTrue(entered.await(3,TimeUnit.SECONDS))
            assertTrue("STOP cancellation must precede fallible/blocking state publication",epoch.get()>before)
            release.countDown();stop.get(3,TimeUnit.SECONDS);drain(receiver)
        } finally {release.countDown();worker.shutdown();assertTrue(worker.awaitTermination(3,TimeUnit.SECONDS));field(manager,"mediaSession").set(manager,real)}
    }
    @Test fun sourceAwayStopHoldsHostPausedDespitePhoneStillPlaying() = fixture { manager,receiver,session,sink,focus ->
        focus.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        start(sink,"media")
        val owner=field(field(manager,"carPlayControls").get(manager)!!,"owner").get(field(manager,"carPlayControls").get(manager)!!)!!
        manager.updateCarPlay(owner,CarPlayMediaMetadata(playing=true),null,true)
        callback(manager).onStop();drain(receiver)
        repeat(3) { manager.updateCarPlay(owner,CarPlayMediaMetadata(playing=true),null,true) }
        assertEquals(true,manager.diagnosticSnapshot().phonePlaying)
        assertEquals("GM connector must see locally stopped transport, not cached phone PLAYING",android.support.v4.media.session.PlaybackStateCompat.STATE_PAUSED,manager.diagnosticSnapshot().submittedState)
        verify(exactly=0) {session.sendMedia(1,any())}
    }
    /** Retained GM MediaConnectorService.java:21-37: playing -> no action;
     * otherwise prepare if advertised, then autoplay && ACTION_PLAY -> play.
     * Drives the actual installed production callback. OAL does not advertise PREPARE. */
    private fun connector(manager:OalMediaSessionManager,autoplay:Boolean):Boolean {
        val state=manager.diagnosticSnapshot().submittedState ?: return false
        if(state==android.support.v4.media.session.PlaybackStateCompat.STATE_PLAYING || !autoplay) return false
        callback(manager).onPlay();return true
    }
    @Test fun connectorNativePlayRequestsNewGrantAndRestoresBothReplacementMusicAndNavigation() = fixture { manager,receiver,session,sink,focus ->
        focus.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val old=start(sink,"media");val original=focus.lastAudioFocusRequest.audioFocusRequest
        lose(original)
        sink.onAudioStopped(old)
        val music=start(sink,"media");val nav=start(sink,"default",101)
        assertEquals(0f,volume(sink,music),0f);assertEquals(0f,volume(sink,nav),0f)
        val controls=field(manager,"carPlayControls").get(manager)!!
        val owner=field(controls,"owner").get(controls)!!
        repeat(5) {manager.updateCarPlay(owner,CarPlayMediaMetadata(playing=true),null,true)};drain(receiver)
        assertEquals("permanent LOSS must publish renderer truth even without STOP",android.support.v4.media.session.PlaybackStateCompat.STATE_PAUSED,manager.diagnosticSnapshot().submittedState)
        assertApplied(sink,music,0);assertApplied(sink,nav,0)
        assertSame("replacement SETUP and repeated publications do not reclaim",original,focus.lastAudioFocusRequest.audioFocusRequest)
        assertFalse(connector(manager,false));drain(receiver)
        assertSame(original,focus.lastAudioFocusRequest.audioFocusRequest)
        assertTrue(connector(manager,true));drain(receiver)
        assertNotSame(original,focus.lastAudioFocusRequest.audioFocusRequest)
        assertEquals(1f,volume(sink,music),0f);assertEquals(1f,volume(sink,nav),0f)
        assertApplied(sink,music,100);assertApplied(sink,nav,100)
        assertEquals(android.support.v4.media.session.PlaybackStateCompat.STATE_PLAYING,manager.diagnosticSnapshot().submittedState)
        assertFalse("playing connector must not replay",connector(manager,true))
        verify(exactly=1) {session.sendMedia(1,any())}
        assertTrue(PhonePlaybackResumeRecovery.isEnabled())
    }
    @Test fun stopAfterResumeIngressCancelsQueuedRecoveryBeforeFocusRequest() = fixture {manager,receiver,session,sink,focus ->
        focus.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val music=start(sink,"media");val original=focus.lastAudioFocusRequest.audioFocusRequest;lose(original)
        val input=field(receiver,"input").get(receiver) as ExecutorService
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        input.submit {entered.countDown();assertTrue(release.await(3,TimeUnit.SECONDS))};assertTrue(entered.await(3,TimeUnit.SECONDS))
        try {callback(manager).onPlay();assertTrue(receiver.mediaCommand(session,2))}
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
        callback(manager).onPlay();drain(receiver)
        assertEquals(0f,volume(sink,music),0f);assertEquals(0f,volume(sink,nav),0f)
        verify(exactly=0) {session.sendMedia(1,any())}
    }
    @Test fun resumeDuringCallOrSiriPreservesIndependentFocusAndMediaMute() {
        for(purpose in listOf("telephony","speechrecognition")) fixture {manager,receiver,session,sink,focus ->
            focus.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            val music=start(sink,"media");lose(focus.lastAudioFocusRequest.audioFocusRequest)
            val nav=start(sink,"default",101);val communication=start(sink,purpose)
            val communicationRequest=focus.lastAudioFocusRequest.audioFocusRequest
            callback(manager).onPlay();drain(receiver)
            assertSame("explicit resume must defer during $purpose",communicationRequest,focus.lastAudioFocusRequest.audioFocusRequest)
            assertEquals(1f,volume(sink,communication),0f)
            assertEquals(0f,volume(sink,music),0f);assertEquals(0f,volume(sink,nav),0f)
            verify(exactly=1) {session.sendMedia(1,any())}
        }
    }
}
