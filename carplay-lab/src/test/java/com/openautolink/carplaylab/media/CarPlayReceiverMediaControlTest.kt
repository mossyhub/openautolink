package com.openautolink.carplaylab.media

import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.MediaCodecInfoBuilder
import org.robolectric.shadows.ShadowMediaCodecList
import java.util.concurrent.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32],manifest=Config.NONE)
class CarPlayReceiverMediaControlTest {
    private fun field(target: Any,name: String)=target.javaClass.getDeclaredField(name).apply { isAccessible=true }
    private fun receiver(onStatus: (String) -> Unit = {}): ProjectionReceiver {
        val caps=MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,1280,720))
            .setIsEncoder(false).setColorFormats(intArrayOf(2130708361))
            .setProfileLevels(arrayOf(MediaCodecInfo.CodecProfileLevel().apply { profile=MediaCodecInfo.CodecProfileLevel.AVCProfileHigh;level=MediaCodecInfo.CodecProfileLevel.AVCLevel52 })).build()
        ShadowMediaCodecList.addCodec(MediaCodecInfoBuilder.newBuilder().setName("media.avc").setIsEncoder(false).setCapabilities(caps).build())
        val surface=mock(Surface::class.java); `when`(surface.isValid).thenReturn(true)
        val app=RuntimeEnvironment.getApplication(); java.io.File(app.noBackupFilesDir,"pairing-probe").deleteRecursively()
        return ProjectionReceiver(app,"02:00:00:00:00:01",surface,mock(MfiAuthenticator::class.java),wired=true,onStatus=onStatus)
    }
    private fun admit(receiver: ProjectionReceiver,session: AirPlaySession) {
        `when`(session.controlAuthenticated).thenReturn(true); `when`(session.encryptedEventReady).thenReturn(true)
        (field(receiver,"sessions").get(receiver) as MutableSet<AirPlaySession>).add(session)
        field(receiver,"active").set(receiver,session);field(receiver,"runtimeOwner").set(receiver,session);field(receiver,"runtimeReady").set(receiver,true)
    }
    private fun command(receiver: ProjectionReceiver,session: AirPlaySession,index: Int): Boolean {
        val method=receiver.javaClass.methods.find { it.name=="mediaCommand" }
        assertNotNull("authenticated exact-session media transport must be exposed to host",method)
        return method!!.invoke(receiver,session,index) as Boolean
    }
    @Test fun commandUsesBoundedInputWorkerAndExactAuthenticatedSession() {
        val receiver=receiver();val session=mock(AirPlaySession::class.java);val sent=CountDownLatch(1)
        admit(receiver,session)
        `when`(session.sendMedia(org.mockito.ArgumentMatchers.eq(2),(org.mockito.ArgumentMatchers.any<() -> Boolean>() ?: { true }))).thenAnswer { assertEquals("lab-media-input",Thread.currentThread().name);sent.countDown();true }
        try {
            assertTrue(command(receiver,session,2));assertTrue(sent.await(3,TimeUnit.SECONDS))
            assertFalse(command(receiver,mock(AirPlaySession::class.java),2))
            assertFalse(command(receiver,session,7))
        } finally { receiver.close();assertTrue(receiver.awaitClosed(3000)) }
    }
    @Test fun userPlayTransportWaitsForFocusGrantInsteadOfOverridingCallDenial() {
        val receiver=receiver();val session=mock(AirPlaySession::class.java);admit(receiver,session)
        val manager=RuntimeEnvironment.getApplication().getSystemService(android.media.AudioManager::class.java)
        val input=field(receiver,"input").get(receiver) as ExecutorService
        try {
            org.robolectric.Shadows.shadowOf(manager).setNextFocusRequestResponse(android.media.AudioManager.AUDIOFOCUS_REQUEST_FAILED)
            assertTrue(command(receiver,session,1));input.submit {}.get(3,TimeUnit.SECONDS)
            verify(session,never()).sendMedia(org.mockito.ArgumentMatchers.eq(1),(org.mockito.ArgumentMatchers.any<() -> Boolean>() ?: { true }))
            org.robolectric.Shadows.shadowOf(manager).setNextFocusRequestResponse(android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            assertTrue(command(receiver,session,1));input.submit {}.get(3,TimeUnit.SECONDS)
            verify(session).sendMedia(org.mockito.ArgumentMatchers.eq(1),(org.mockito.ArgumentMatchers.any<() -> Boolean>() ?: { true }))
        } finally { receiver.close();assertTrue(receiver.awaitClosed(3000)) }
    }
    @Test fun liveAssistantPlayForwardsTransportWithoutClaimingMediaGrant() {
        val lines=CopyOnWriteArrayList<String>(); val receiver=receiver { lines.add(it) }
        val session=mock(AirPlaySession::class.java);admit(receiver,session)
        val sink=field(receiver,"sink").get(receiver) as com.shilapi.xcertplay.media.AndroidMediaSink
        val input=field(receiver,"input").get(receiver) as ExecutorService
        val statuses=field(receiver,"statuses").get(receiver) as ExecutorService
        val manager=RuntimeEnvironment.getApplication().getSystemService(android.media.AudioManager::class.java)
        val shadow=org.robolectric.Shadows.shadowOf(manager)
        try {
            shadow.setNextFocusRequestResponse(android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            sink.onAudioStarted(com.shilapi.xcertplay.airplay.AudioStreamId(100,"speechrecognition"),
                requireNotNull(com.shilapi.xcertplay.media.AudioOutputSupport.negotiate(0x4000L,100,"speechrecognition")),0)
            val assistant=shadow.lastAudioFocusRequest
            shadow.setNextFocusRequestResponse(android.media.AudioManager.AUDIOFOCUS_REQUEST_FAILED)
            `when`(session.sendMedia(org.mockito.ArgumentMatchers.eq(1),(org.mockito.ArgumentMatchers.any<() -> Boolean>() ?: { true }))).thenReturn(true)
            assertTrue(command(receiver,session,1));input.submit {}.get(3,TimeUnit.SECONDS)
            statuses.submit {}.get(3,TimeUnit.SECONDS)
            verify(session).sendMedia(org.mockito.ArgumentMatchers.eq(1),(org.mockito.ArgumentMatchers.any<() -> Boolean>() ?: { true }))
            assertSame("no conflicting media request",assistant,shadow.lastAudioFocusRequest)
            assertTrue(lines.contains("media stage=media-play-disposition result=deferred transport=permitted output=withheld"))
            assertFalse(lines.contains("media stage=media-focus-recovery reason=user-play result=permitted"))
        } finally {
            shadow.setNextFocusRequestResponse(android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            receiver.close();assertTrue(receiver.awaitClosed(3000))
        }
    }
    @Test fun pendingPlayCannotSurviveStopReplacementOrRevocation() {
        listOf("stop","replacement","revoke").forEach { cancellation ->
            val receiver=receiver();val session=mock(AirPlaySession::class.java);admit(receiver,session)
            val sink=field(receiver,"sink").get(receiver) as com.shilapi.xcertplay.media.AndroidMediaSink
            val input=field(receiver,"input").get(receiver) as ExecutorService
            val shadow=org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication().getSystemService(android.media.AudioManager::class.java))
            fun start(kind: String): com.shilapi.xcertplay.airplay.AudioStreamId {
                val id=com.shilapi.xcertplay.airplay.AudioStreamId(100,kind)
                sink.onAudioStarted(id,requireNotNull(com.shilapi.xcertplay.media.AudioOutputSupport.negotiate(0x4000L,100,kind)),0)
                return id
            }
            try {
                shadow.setNextFocusRequestResponse(android.media.AudioManager.AUDIOFOCUS_REQUEST_FAILED)
                sink.onAudioStopped(start("media"))
                shadow.setNextFocusRequestResponse(android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                val assistant=start("speechrecognition")
                val last=shadow.lastAudioFocusRequest
                assertTrue(command(receiver,session,1));input.submit {}.get(3,TimeUnit.SECONDS)
                verify(session).sendMedia(org.mockito.ArgumentMatchers.eq(1),(org.mockito.ArgumentMatchers.any<() -> Boolean>() ?: { true }))
                when(cancellation) {
                    "stop" -> { assertTrue(command(receiver,session,2));input.submit {}.get(3,TimeUnit.SECONDS);verify(session).sendMedia(org.mockito.ArgumentMatchers.eq(2),(org.mockito.ArgumentMatchers.any<() -> Boolean>() ?: { true })) }
                    "replacement" -> admit(receiver,mock(AirPlaySession::class.java))
                    "revoke" -> receiver.revokePendingAdmission()
                }
                sink.onAudioStopped(assistant)
                start("media")
                assertSame("$cancellation must cancel old-session pending permission",last,shadow.lastAudioFocusRequest)
            } finally { receiver.close();assertTrue(receiver.awaitClosed(3000)) }
        }
    }
    @Test fun stopIngressCancelsPendingAndEarlierQueuedPlayBeforeWorkerDrain() {
        val receiver=receiver();val session=mock(AirPlaySession::class.java);admit(receiver,session)
        val sink=field(receiver,"sink").get(receiver) as com.shilapi.xcertplay.media.AndroidMediaSink
        val input=field(receiver,"input").get(receiver) as ExecutorService
        val shadow=org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication().getSystemService(android.media.AudioManager::class.java))
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        fun start(kind: String): com.shilapi.xcertplay.airplay.AudioStreamId {
            val id=com.shilapi.xcertplay.airplay.AudioStreamId(100,kind)
            sink.onAudioStarted(id,requireNotNull(com.shilapi.xcertplay.media.AudioOutputSupport.negotiate(0x4000L,100,kind)),0)
            return id
        }
        try {
            shadow.setNextFocusRequestResponse(android.media.AudioManager.AUDIOFOCUS_REQUEST_FAILED)
            sink.onAudioStopped(start("media"))
            shadow.setNextFocusRequestResponse(android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            val assistant=start("speechrecognition");val last=shadow.lastAudioFocusRequest
            assertTrue(command(receiver,session,1));input.submit {}.get(3,TimeUnit.SECONDS)
            input.execute { entered.countDown();release.await() };assertTrue(entered.await(3,TimeUnit.SECONDS))
            assertTrue(command(receiver,session,1))
            assertTrue(command(receiver,session,2))
            sink.onAudioStopped(assistant);start("media")
            assertSame("STOP must cancel before its HID worker can drain",last,shadow.lastAudioFocusRequest)
            release.countDown();input.submit {}.get(3,TimeUnit.SECONDS)
            verify(session,times(1)).sendMedia(org.mockito.ArgumentMatchers.eq(1),(org.mockito.ArgumentMatchers.any<() -> Boolean>() ?: { true }))
            verify(session).sendMedia(org.mockito.ArgumentMatchers.eq(2),(org.mockito.ArgumentMatchers.any<() -> Boolean>() ?: { true }))
            assertSame("earlier queued PLAY cannot rearm intent after STOP",last,shadow.lastAudioFocusRequest)
        } finally { release.countDown();receiver.close();assertTrue(receiver.awaitClosed(3000)) }
    }
    @Test fun queuedCommandCannotReachReplacedOwner() {
        val receiver=receiver();val session=mock(AirPlaySession::class.java);admit(receiver,session)
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val input=field(receiver,"input").get(receiver) as ExecutorService
        input.execute { entered.countDown();release.await() }
        try {
            assertTrue(entered.await(3,TimeUnit.SECONDS));assertTrue(command(receiver,session,2))
            admit(receiver,mock(AirPlaySession::class.java));release.countDown()
            input.submit {}.get(3,TimeUnit.SECONDS)
            verify(session,never()).sendMedia(anyInt(),(org.mockito.ArgumentMatchers.any<() -> Boolean>() ?: { true }))
        } finally { release.countDown();receiver.close();assertTrue(receiver.awaitClosed(3000)) }
    }
    @Test fun diagnosticsAreFiniteBoundedDedupedNonblockingAndExactOwnerFenced() {
        val lines=CopyOnWriteArrayList<String>(); val receiver=receiver { lines.add(it) }
        val session=mock(AirPlaySession::class.java); admit(receiver,session)
        val statuses=field(receiver,"statuses").get(receiver) as ThreadPoolExecutor
        val release=CountDownLatch(1); val entered=CountDownLatch(1)
        val receipt="media stage=media-command-ingress command=STOP controller=car-service sequence=1 elapsedMs=10"
        try {
            val method=receiver.javaClass.methods.find { it.name=="audioDiagnostic" && it.parameterCount==2 }
            assertNotNull("exact-session bounded diagnostic ingress seam is missing",method)
            fun offer(message: String)=method!!.invoke(receiver,session,message) as Boolean
            statuses.submit {}.get(3,TimeUnit.SECONDS)
            statuses.execute { entered.countDown();release.await() }
            assertTrue(entered.await(3,TimeUnit.SECONDS))
            assertTrue(offer(receipt))
            assertFalse("diagnostic dedupe cannot amplify STOP storms",offer(receipt))
            assertFalse(offer(receipt+" package=private"))
            assertFalse(offer(receipt.replace("car-service","private")))
            admit(receiver,mock(AirPlaySession::class.java))
            release.countDown(); statuses.submit {}.get(3,TimeUnit.SECONDS)
            assertFalse("retired diagnostic must not adopt replacement owner",lines.contains(receipt))
            assertFalse(offer(receipt))
            admit(receiver,session)
            val next=receipt.replace("STOP","PLAY")
            assertTrue(offer(next)); statuses.submit {}.get(3,TimeUnit.SECONDS)
            assertTrue(lines.contains(next))
            val bt="carplay-bt event=hfp-voice state=started previous=unknown scope=device owner=unknown"
            assertTrue(offer(bt)); statuses.submit {}.get(3,TimeUnit.SECONDS)
            assertTrue(lines.contains(bt))
            val blocked=CountDownLatch(1); val unblock=CountDownLatch(1); val drained=CountDownLatch(1)
            try {
                statuses.execute { blocked.countDown();unblock.await() }
                assertTrue(blocked.await(3,TimeUnit.SECONDS))
                repeat(64) { index -> statuses.execute { if (index==63) drained.countDown() } }
                val overflow=receipt.replace("STOP","PREVIOUS")
                assertFalse("status rejection is truthful and cannot call consumer synchronously",offer(overflow))
                assertFalse(lines.contains(overflow))
                unblock.countDown(); assertTrue(drained.await(3,TimeUnit.SECONDS))
                assertTrue("rejected diagnostic must be retryable after queue drain",offer(overflow))
                statuses.submit {}.get(3,TimeUnit.SECONDS)
                assertTrue(lines.contains(overflow))
                assertTrue(lines.contains("media stage=status-queue drops=1"))
            } finally { unblock.countDown() }
        } finally { release.countDown();receiver.close();assertTrue(receiver.awaitClosed(3000)) }
    }
    @Test fun distinctRapidStopsSurviveStatusExportUntilFiniteRateCap() {
        val lines=CopyOnWriteArrayList<String>(); val receiver=receiver { lines.add(it) }
        val session=mock(AirPlaySession::class.java); admit(receiver,session)
        val statuses=field(receiver,"statuses").get(receiver) as ExecutorService
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val stops=(1..32).map { "media stage=media-command-ingress command=STOP controller=car-service sequence=$it elapsedMs=10" }
        try {
            statuses.submit {}.get(3,TimeUnit.SECONDS)
            statuses.execute { entered.countDown();release.await() }; assertTrue(entered.await(3,TimeUnit.SECONDS))
            val accepted=stops.map { receiver.audioDiagnostic(session,it) }
            assertEquals("each distinct native callback provenance must be admitted",List(32) { true },accepted)
            assertFalse("one owner cannot flood beyond finite per-second budget",receiver.audioDiagnostic(session,stops.last().replace("sequence=32","sequence=33")))
            release.countDown();statuses.submit {}.get(3,TimeUnit.SECONDS)
            assertEquals("both rapid STOPs and all remaining admitted originals must be exported",stops,lines.filter { it.contains("media-command-ingress") })
        } finally { release.countDown();receiver.close();assertTrue(receiver.awaitClosed(3000)) }
    }
    @Test fun inputQueueOutcomesAndUserPlayRecoveryReasonAreExportedWithoutChangingControl() {
        val lines=CopyOnWriteArrayList<String>(); val receiver=receiver { lines.add(it) }
        val session=mock(AirPlaySession::class.java); admit(receiver,session)
        val input=field(receiver,"input").get(receiver) as ThreadPoolExecutor
        val statuses=field(receiver,"statuses").get(receiver) as ExecutorService
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        try {
            input.execute { entered.countDown();release.await() }; assertTrue(entered.await(3,TimeUnit.SECONDS))
            val drained=CountDownLatch(1)
            repeat(64) { index -> input.execute { if (index==63) drained.countDown() } }
            assertFalse(receiver.mediaCommand(session,2))
            statuses.submit {}.get(3,TimeUnit.SECONDS)
            assertTrue("full queue must explicitly defer rather than claim delivery",lines.contains("media stage=media-command-queue index=2 outcome=deferred reason=queue-full"))
            release.countDown();assertTrue(drained.await(3,TimeUnit.SECONDS));input.submit {}.get(3,TimeUnit.SECONDS)
            org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication().getSystemService(android.media.AudioManager::class.java))
                .setNextFocusRequestResponse(android.media.AudioManager.AUDIOFOCUS_REQUEST_FAILED)
            assertTrue(receiver.mediaCommand(session,1));input.submit {}.get(3,TimeUnit.SECONDS)
            statuses.submit {}.get(3,TimeUnit.SECONDS)
            assertTrue(lines.contains("media stage=media-command-queue index=1 outcome=accepted reason=admitted"))
            assertTrue(lines.contains("media stage=media-focus-recovery reason=user-play result=denied"))
            verify(session,never()).sendMedia(org.mockito.ArgumentMatchers.eq(1),(org.mockito.ArgumentMatchers.any<() -> Boolean>() ?: { true }))
            assertFalse(receiver.mediaCommand(session,9));statuses.submit {}.get(3,TimeUnit.SECONDS)
            assertTrue(lines.contains("media stage=media-command-queue index=0 outcome=declined reason=invalid-index"))
            val listener=field(receiver,"listener").get(receiver) as com.shilapi.xcertplay.airplay.AirPlaySessionListener
            listener.onCommand(session,"disableBluetooth",emptyMap());statuses.submit {}.get(3,TimeUnit.SECONDS)
            assertTrue(lines.contains("BENCH_DIAG bluetoothHandoff requested=true policy=observe-only handling=unsupported-native-profile-handover"))
        } finally { release.countDown();receiver.close();assertTrue(receiver.awaitClosed(3000)) }
    }
    @Test fun queuedCommandCannotReachRevokedOwner() {
        val receiver=receiver();val session=mock(AirPlaySession::class.java);admit(receiver,session)
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val input=field(receiver,"input").get(receiver) as ExecutorService
        input.execute { entered.countDown();release.await() }
        try {
            assertTrue(entered.await(3,TimeUnit.SECONDS));assertTrue(command(receiver,session,2))
            receiver.revokePendingAdmission();release.countDown()
            input.submit {}.get(3,TimeUnit.SECONDS)
            verify(session,never()).sendMedia(anyInt(),(org.mockito.ArgumentMatchers.any<() -> Boolean>() ?: { true }))
            assertFalse(command(receiver,session,2))
        } finally { release.countDown();receiver.close();assertTrue(receiver.awaitClosed(3000)) }
    }
}
