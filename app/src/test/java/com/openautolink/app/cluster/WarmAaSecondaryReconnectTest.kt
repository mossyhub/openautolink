package com.openautolink.app.cluster

import android.app.Application
import android.view.Display
import com.openautolink.app.audio.AudioPlayer
import com.openautolink.app.data.AppPreferences
import com.openautolink.app.drivecard.DriveCardView
import com.openautolink.app.secondary.SecondaryInventorySnapshot
import com.openautolink.app.secondary.SecondaryHostRegistry
import com.openautolink.app.secondary.SecondaryReason
import com.openautolink.app.secondary.SecondaryRoute
import com.openautolink.app.session.SessionManager
import com.openautolink.app.transport.aasdk.*
import com.openautolink.app.video.VideoDecoder
import com.shilapi.xcertplay.media.MicrophoneCaptureFence
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = Application::class, shadows = [
    ClusterDirectVideoLifecycleTest.Admission::class, ClusterDirectVideoLifecycleTest.TextureBoundary::class,
    WarmAaSecondaryReconnectTest.NativeBoundary::class,
], instrumentedPackages = ["com.openautolink.app.transport.aasdk"])
@ConscryptMode(ConscryptMode.Mode.OFF)
class WarmAaSecondaryReconnectTest {
    @Test fun returnedDisplayRequalifiesBeforeSameParentNativeStart() = journey(returnOn = true)
    @Test fun unresolvedRetirementOmitsOptionalOfferWithoutRejectingPrimary() = journey(returnOn = true, holdAck = true)
    @Test fun offDisplayOmitsOptionalOfferWithoutRejectingPrimary() = journey(returnOn = false)
    @Test fun validPreparedOutputIsReusedWithoutRelaunch() = journey(returnOn = false, invalidate = false)
    @Test fun currentOwnerWithHeldParkingIsNotOffered() = nativeJourney(Parking.HELD)
    @Test fun currentOwnerWithFailedParkingIsNotOffered() = nativeJourney(Parking.FAILED)
    @Test fun positivelyParkedOwnerIsReusedWithoutRelaunch() = nativeJourney(Parking.PARKED)
    @Test fun revokedDuringOldNativeStopDoesNotReachNewStart() = nativeJourney(revokeDuringCreate = true)
    @Test fun revokedAfterCommitRetiresExactNewNativeGeneration() = nativeJourney(revokeDuringStart = true)

    private enum class Parking { NONE, HELD, FAILED, PARKED }

    @Implements(value = AasdkNative::class, isInAndroidSdk = false)
    class NativeBoundary {
        companion object {
            @Volatile var create: () -> Long = { error("Unexpected native create") }
            @Volatile var start: (AasdkSessionCallback, AasdkSdrConfig) -> Unit = { _, _ -> error("Unexpected native start") }
            @Volatile var retire: (Int, Long) -> Unit = { _, _ -> }
            @JvmStatic @Implementation fun __staticInitializer__() = Unit
            @JvmStatic @Implementation fun nativeCreateSession(): Long = create()
            @JvmStatic @Implementation fun nativeStartSession(pipe: AasdkTransportPipe, callback: AasdkSessionCallback, sdr: AasdkSdrConfig) =
                start(callback, sdr)
            @JvmStatic @Implementation fun nativeRetireSecondaryVideo(channel: Int, generation: Long) = retire(channel, generation)
        }
    }

    private fun nativeJourney(parking: Parking = Parking.NONE, revokeDuringCreate: Boolean = false, revokeDuringStart: Boolean = false) {
        val f = DirectSessionRuntimeFixture()
        val claim = requireNotNull(MicrophoneCaptureFence.tryProjectionClaim())
        val session = AasdkSession(f.scope, f.context).apply { transportMode = AppPreferences.DIRECT_TRANSPORT_WPP }
        val prepare = SessionManager::class.java.getDeclaredMethod("prepareNativeSessionStart", AasdkSession::class.java)
            .apply { isAccessible = true }
        val connect = AasdkSession::class.java.getDeclaredMethod("handleConnection", Socket::class.java)
            .apply { isAccessible = true }
        val selected = CompletableFuture<AasdkSdrConfig>()
        val release = CompletableFuture<Unit>()
        val started = CompletableFuture<Pair<AasdkSessionCallback, AasdkSdrConfig>>()
        val retired = java.util.concurrent.CopyOnWriteArrayList<Pair<Int, Long>>()
        val mainVideo = mockk<VideoDecoder>(relaxed = true)
        val mainAudio = mockk<AudioPlayer>(relaxed = true)
        try {
            f.field("projectionCaptureClaim").set(f.manager, claim)
            f.field("aasdkSession").set(f.manager, session)
            f.field("_videoDecoder").set(f.manager, mainVideo)
            f.field("_audioPlayer").set(f.manager, mainAudio)
            f.freeze(ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO))
            val initial = f.scope.async { f.manager.prepareAndroidAutoSecondaryOutputs(f.context) }
            f.pump { initial.isCompleted }
            val original = runBlocking { initial.await() }.offers.single()
            val exact = requireNotNull(f.owner())
            session.sdrConfig = AasdkSdrConfig(videoWidth = 1280, videoDpi = 213, secondaryOffers = arrayOf(original))
            val policy = f.preferences.projectionConnectionPolicy
            policy.observe(false)
            AasdkSession::class.java.getDeclaredField("wirelessTicket").apply { isAccessible = true }
                .set(session, requireNotNull(policy.wirelessTicket()))
            session.onNativeSessionStarting = { prepare.invoke(f.manager, session) as Boolean }

            if (parking != Parking.NONE) {
                f.parkResult = CompletableFuture()
                val activity = f.physical.single().get()
                val ack = SecondaryHostRegistry.suspend(activity, activity.intent.getStringExtra(SecondaryHostRegistry.TOKEN_EXTRA))
                f.pump { f.parkEntered.count == 0L }
                assertFalse(ack.isDone)
                if (parking != Parking.HELD) {
                    f.parkResult!!.complete(parking == Parking.PARKED)
                    assertEquals(parking == Parking.PARKED, f.await(ack))
                }
                assertSame(exact, f.owner())
                assertTrue("Parking must leave the descriptor and exact owner current", exact.isCurrent())
            }
            NativeBoundary.create = {
                if (revokeDuringCreate) {
                    selected.complete(session.sdrConfig)
                    release.get(10, TimeUnit.SECONDS)
                }
                72L
            }
            NativeBoundary.start = { callback, sdr ->
                started.complete(callback to sdr)
                if (revokeDuringStart) release.get(10, TimeUnit.SECONDS)
            }
            NativeBoundary.retire = { channel, generation -> retired += channel to generation }
            val connection = f.scope.async {
                connect.invoke(session, object : Socket() {
                    override fun setSoTimeout(timeout: Int) = Unit
                    override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
                    override fun getOutputStream() = ByteArrayOutputStream()
                })
            }
            if (revokeDuringCreate || revokeDuringStart) {
                val before = if (revokeDuringCreate) f.await(selected) else f.await(started).second
                assertSame("Exact offer selected before revocation", original, before.secondaryOffers.single())
                shadowOf(f.display).setState(Display.STATE_OFF)
                f.inventory.value = SecondaryInventorySnapshot(2, listOf(f.descriptor.copy(attachmentEpoch = 2,
                    route = SecondaryRoute.INVENTORY_ONLY, reason = SecondaryReason.DISCONNECTED)))
                f.pump { !exact.isCurrent() && f.owner() == null }
                assertFalse("Native boundary must still be held", connection.isCompleted)
                assertTrue("Native retirement must not race incomplete startup", retired.isEmpty())
                release.complete(Unit)
            }
            f.pump { connection.isCompleted }
            runBlocking { connection.await() }
            assertTrue("Primary must reach native start", started.isDone)
            val (callback, sdr) = started.join()
            assertEquals(1280, sdr.videoWidth)
            assertEquals(213, sdr.videoDpi)
            assertSame(mainVideo, f.manager.videoDecoder)
            assertSame(mainAudio, f.field("_audioPlayer").get(f.manager))
            assertEquals(1, f.initialized.get())
            assertEquals(1, f.physical.size)
            if (parking == Parking.PARKED || revokeDuringStart) assertSame(original, sdr.secondaryOffers.single())
            else assertTrue("An unqualified optional channel must not reach JNI", sdr.secondaryOffers.isEmpty())
            callback.onSecondaryVideoState(original.channelId, 1, 3, 0, 1, true)
            assertEquals(parking == Parking.PARKED, session.secondaryVideoSelections.value.containsKey(original.channelId))
            assertTrue("JNI startup return isn't the native handler readiness receipt", retired.isEmpty())
            if (revokeDuringStart) {
                callback.onSessionStarted()
                assertEquals(listOf(original.channelId to 72L), retired.toList())
            }
        } finally {
            release.complete(Unit)
            f.parkResult?.complete(true)
            try { f.close() } finally {
                claim.release()
                NativeBoundary.create = { error("Unexpected native create") }
                NativeBoundary.start = { _, _ -> error("Unexpected native start") }
                NativeBoundary.retire = { _, _ -> }
            }
        }
    }

    @Test fun initialOfferLostDuringWakeCanBePreparedBeforeNativeStart() {
        val f = DirectSessionRuntimeFixture()
        val claim = requireNotNull(MicrophoneCaptureFence.tryProjectionClaim())
        val session = AasdkSession(f.scope, f.context).apply { transportMode = AppPreferences.DIRECT_TRANSPORT_WPP }
        val prepare = SessionManager::class.java.getDeclaredMethod("prepareNativeSessionStart", AasdkSession::class.java)
            .apply { isAccessible = true }
        try {
            f.field("projectionCaptureClaim").set(f.manager, claim)
            f.field("aasdkSession").set(f.manager, session)
            val video = mockk<VideoDecoder>(relaxed = true)
            val audio = mockk<AudioPlayer>(relaxed = true)
            f.field("_videoDecoder").set(f.manager, video)
            f.field("_audioPlayer").set(f.manager, audio)
            val saved = f.freeze(ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO))
            runBlocking {
                f.preferences.updateSecondaryDisplayPreferences(saved.revision,
                    mapOf(f.descriptor.id to saved.outputs.getValue(f.descriptor.id).copy(driveCardEnabled = true)))
            }
            // Wake published a qualified descriptor, but repeated callbacks replaced its epoch
            // before the platform started the Activity. Hold platform launches until prep ends.
            f.attachHosts = false
            val initial = f.scope.async { f.manager.prepareAndroidAutoSecondaryOutputs(f.context) }
            f.pump { f.owner() != null }
            f.inventory.value = SecondaryInventorySnapshot(2, listOf(f.descriptor.copy(attachmentEpoch = 2)))
            f.pump { initial.isCompleted }
            assertTrue("Wake preparation must not have advertised the revoked host", runBlocking { initial.await() }.offers.isEmpty())
            f.attachHosts = true
            f.pump { f.owner() == null }
            session.sdrConfig = AasdkSdrConfig(videoWidth = 1280, videoDpi = 213, panelWidth = 2914)
            val start = f.scope.async { prepare.invoke(f.manager, session) as Boolean }
            f.pump { start.isCompleted }
            assertTrue("Primary startup must be preserved", runBlocking { start.await() })
            assertSame(video, f.manager.videoDecoder)
            assertSame(audio, f.field("_audioPlayer").get(f.manager))
            assertEquals(1280, session.sdrConfig.videoWidth)
            assertEquals("The settled exact display must be offered before discovery", 1, session.sdrConfig.secondaryOffers.size)
            assertEquals(2L, f.owner()!!.descriptor.attachmentEpoch)
            val card = f.physical.last().get().window.decorView.findViewWithTag<DriveCardView>(DriveCardView.TAG)
            assertTrue("The replacement Activity must own the Drive Card", card?.isCurrentCard() == true)
        } finally {
            f.attachHosts = true
            try { f.close() } finally { claim.release() }
        }
    }

    private fun journey(returnOn: Boolean, holdAck: Boolean = false, invalidate: Boolean = true) {
        val f = DirectSessionRuntimeFixture()
        val retirement = CompletableFuture<Boolean>()
        val claim = requireNotNull(MicrophoneCaptureFence.tryProjectionClaim())
        val video = mockk<VideoDecoder>(relaxed = true)
        val audio = mockk<AudioPlayer>(relaxed = true)
        val session = AasdkSession(f.scope, f.context).apply { transportMode = AppPreferences.DIRECT_TRANSPORT_WPP }
        val prepare = SessionManager::class.java.getDeclaredMethod("prepareNativeSessionStart", AasdkSession::class.java)
            .apply { isAccessible = true }
        val callback = AasdkSession::class.java.getDeclaredMethod("telemetryCallback").apply { isAccessible = true }
        try {
            f.field("projectionCaptureClaim").set(f.manager, claim)
            f.field("aasdkSession").set(f.manager, session)
            f.field("_videoDecoder").set(f.manager, video)
            f.field("_audioPlayer").set(f.manager, audio)
            val saved = f.freeze(ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO))
            runBlocking {
                f.preferences.updateSecondaryDisplayPreferences(saved.revision,
                    mapOf(f.descriptor.id to saved.outputs.getValue(f.descriptor.id).copy(driveCardEnabled = true)))
            }
            val initial = f.scope.async { f.manager.prepareAndroidAutoSecondaryOutputs(f.context) }
            f.pump { initial.isCompleted }
            val original = runBlocking { initial.await() }.offers.single()
            val oldOwner = requireNotNull(f.owner())
            val oldMapping = f.mapping()
            fun card() = f.physical.last().get().window.decorView.findViewWithTag<DriveCardView>(DriveCardView.TAG)
            val oldCard = requireNotNull(card())
            assertTrue(oldCard.isCurrentCard())
            session.sdrConfig = AasdkSdrConfig(videoWidth = 1280, videoDpi = 213, panelWidth = 2914,
                secondaryOffers = arrayOf(original))
            if (holdAck) f.controlRetirement(retirement)
            if (invalidate) {
                shadowOf(f.display).setState(Display.STATE_OFF)
                f.inventory.value = SecondaryInventorySnapshot(2, listOf(f.descriptor.copy(attachmentEpoch = 2,
                    route = SecondaryRoute.INVENTORY_ONLY, reason = SecondaryReason.DISCONNECTED)))
                f.pump { f.detached.get() == 1 && (if (holdAck) f.retireCalls.get() == 1 else f.owner() == null) }
                if (returnOn) {
                    shadowOf(f.display).setState(Display.STATE_ON)
                    f.inventory.value = SecondaryInventorySnapshot(3, listOf(f.descriptor.copy(attachmentEpoch = 3)))
                }
            }
            // Same production hook called by WPP under connectionStartLock; JNI and phone are not run.
            val start = f.scope.async { prepare.invoke(f.manager, session) as Boolean }
            f.pump { start.isCompleted }
            assertTrue("Primary native admission must survive optional failure", runBlocking { start.await() })
            assertSame(session, f.field("aasdkSession").get(f.manager))
            assertSame(video, f.manager.videoDecoder)
            assertSame(audio, f.field("_audioPlayer").get(f.manager))
            val sdr = session.sdrConfig
            assertEquals(1280, sdr.videoWidth)
            assertEquals(213, sdr.videoDpi)
            assertEquals(2914, sdr.panelWidth)
            if (!invalidate || (returnOn && !holdAck)) {
                val offer = sdr.secondaryOffers.single()
                if (invalidate) {
                    assertNotSame("Native start must not reuse the retired offer", original, offer)
                    assertNotSame(oldOwner, f.owner())
                    assertEquals(2, f.initialized.get())
                    assertEquals(3L, f.owner()!!.descriptor.attachmentEpoch)
                    assertFalse(oldCard.isCurrentCard())
                    assertNotSame(oldCard, card())
                } else {
                    assertSame(original, offer)
                    assertSame(oldOwner, f.owner())
                    assertEquals(1, f.initialized.get())
                }
                assertTrue("Only the admitted Activity owns a current Drive Card", card()?.isCurrentCard() == true)
                val mapping = f.mapping()
                assertEquals(oldMapping.codedSize, mapping.codedSize)
                assertEquals(oldMapping.codecCrop, mapping.codecCrop)
                assertEquals(oldMapping.destinationPixels, mapping.destinationPixels)
                assertEquals(oldMapping.phoneSafeInsets, mapping.phoneSafeInsets)
                assertEquals(oldMapping.stableContentInsets, mapping.stableContentInsets)
                assertEquals(original.density, offer.density)
                val incoming = callback.invoke(session) as AasdkSessionCallback
                incoming.onSecondaryVideoState(offer.channelId, 1, 3, 0, 1, true)
                f.pump { f.runtime.statuses.value[f.descriptor.id]?.let { it.hostAttached && it.phoneSelected } == true }
            } else {
                assertTrue("An unqualified optional channel must not reach JNI", sdr.secondaryOffers.isEmpty())
                assertEquals(1, f.initialized.get())
                assertFalse(oldCard.isCurrentCard())
                if (holdAck) assertSame("Pending debt remains owned", oldOwner, f.owner())
            }
        } finally {
            retirement.complete(true)
            try { f.close() } finally { claim.release() }
        }
    }
}
