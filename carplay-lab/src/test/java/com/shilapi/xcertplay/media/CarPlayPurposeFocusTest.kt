package com.shilapi.xcertplay.media

import android.content.Context
import android.content.ContextWrapper
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.view.Surface
import com.openautolink.carplaylab.media.CarPlayDisplayPreferences
import com.openautolink.carplaylab.media.CarPlayDisplaySelection
import com.shilapi.xcertplay.airplay.AudioStreamId
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Real production sink/track owners and listeners; only the OS focus boundary is mocked. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32,34], manifest=Config.NONE)
class CarPlayPurposeFocusTest {
    private class Fixture : AutoCloseable {
        val status = java.util.concurrent.CopyOnWriteArrayList<String>()
        val requests = mutableListOf<AudioFocusRequest>()
        val abandoned = mutableListOf<AudioFocusRequest>()
        var response = AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        var duringRequest: (() -> Unit)? = null
        var abandonResponse = AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        val manager = mock(AudioManager::class.java)
        val sink: AndroidMediaSink
        init {
            `when`(manager.requestAudioFocus(any(AudioFocusRequest::class.java))).thenAnswer {
                requests += it.getArgument<AudioFocusRequest>(0); duringRequest?.invoke(); response
            }
            `when`(manager.abandonAudioFocusRequest(any(AudioFocusRequest::class.java))).thenAnswer {
                abandoned += it.getArgument<AudioFocusRequest>(0); abandonResponse
            }
            val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
                override fun getApplicationContext(): Context = this
                override fun getSystemService(name: String): Any? =
                    if (name == Context.AUDIO_SERVICE) manager else super.getSystemService(name)
            }
            val surface = mock(Surface::class.java)
            `when`(surface.isValid).thenReturn(true)
            sink = AndroidMediaSink(context, surface, onStatus={ status += it },
                display=CarPlayDisplaySelection(CarPlayDisplayPreferences(), "avc.exact", false))
        }
        fun start(purpose: String, type: Int = 100): AudioStreamId {
            val id = AudioStreamId(type,purpose)
            sink.onAudioStarted(id,requireNotNull(AudioOutputSupport.negotiate(0x4000L,type,purpose)),0)
            return id
        }
        fun volume(id: AudioStreamId): Float {
            val tracks = AndroidMediaSink::class.java.getDeclaredField("tracks").apply { isAccessible=true }.get(sink) as Map<*,*>
            val track = requireNotNull(tracks[id])
            return track.javaClass.getDeclaredField("requestedVolume").apply { isAccessible=true }.getFloat(track)
        }
        fun change(request: AudioFocusRequest, value: Int) {
            val listener = AudioFocusRequest::class.java.getDeclaredMethod("getOnAudioFocusChangeListener")
                .invoke(request) as AudioManager.OnAudioFocusChangeListener
            listener.onAudioFocusChange(value)
        }
        override fun close() { sink.close(); assertTrue(sink.awaitRetired(3000)) }
    }

    @Test fun explicitPlayRefreshesAnOtherwiseStalePositiveMediaGrantOnce() = Fixture().use { f ->
        val media=f.start("media")
        val old=f.requests.single()
        assertTrue(f.sink.requestFocusForUserPlay())
        assertEquals("OEM Play must refresh even an old logical positive grant",2,f.requests.size)
        assertSame(old,f.abandoned.single())
        f.change(old,AudioManager.AUDIOFOCUS_GAIN)
        f.response=AudioManager.AUDIOFOCUS_REQUEST_FAILED
        assertFalse(f.sink.requestFocusForUserPlay())
        assertEquals(3,f.requests.size)
        assertEquals(0f,f.volume(media),0f)
        f.sink.onAudioRtp(media,requireNotNull(AudioOutputSupport.negotiate(0x4000L,100,"media")),byteArrayOf(),0)
        assertEquals("packets do not retry",3,f.requests.size)
    }

    @Test fun sameRequestLossDuringPlatformCallCannotBeOverwrittenByLateGrant() = Fixture().use { f ->
        f.duringRequest={ f.change(f.requests.last(),AudioManager.AUDIOFOCUS_LOSS) }
        val id=f.start("media")
        assertEquals("a synchronous loss belongs to this request, not the previous grant",0f,f.volume(id),0f)
    }

    @Test fun deniedMediaDoesNotPoisonCallOrGetClearedByItsGrant() = Fixture().use { f ->
        f.response = AudioManager.AUDIOFOCUS_REQUEST_FAILED
        val media = f.start("media")
        assertEquals(0f,f.volume(media),0f)
        f.response = AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        val call = f.start("telephony")
        assertEquals("a different logical purpose gets its own bounded request",2,f.requests.size)
        assertEquals(AudioAttributes.USAGE_VOICE_COMMUNICATION,f.requests.last().audioAttributes.usage)
        assertEquals(1f,f.volume(call),0f)
        assertEquals("call permission cannot authorize denied media",0f,f.volume(media),0f)
        f.sink.onAudioStopped(call)
        assertEquals(0f,f.volume(media),0f)
        f.sink.onAudioStopped(media)
        val restarted = f.start("media")
        assertEquals(2,f.requests.size)
        assertEquals(0f,f.volume(restarted),0f)
        assertTrue(f.sink.requestFocusForUserPlay())
        assertEquals(3,f.requests.size)
        assertEquals(1f,f.volume(restarted),0f)
    }

    @Test fun ringtoneHasIndependentTransientSonificationFocus() = Fixture().use { f ->
        val media = f.start("media")
        val mediaRequest = f.requests.single()
        f.change(mediaRequest,AudioManager.AUDIOFOCUS_LOSS)
        val ring = f.start("alert")
        assertEquals(2,f.requests.size)
        val request = f.requests.last()
        assertEquals(AudioAttributes.USAGE_NOTIFICATION_RINGTONE,request.audioAttributes.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_SONIFICATION,request.audioAttributes.contentType)
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,request.focusGain)
        assertEquals(1f,f.volume(ring),0f)
        assertEquals(0f,f.volume(media),0f)
        f.response = AudioManager.AUDIOFOCUS_REQUEST_FAILED
        val call = f.start("telephony")
        assertEquals(0f,f.volume(call),0f)
        assertEquals(1f,f.volume(ring),0f)
    }

    @Test fun siriHasIndependentTransientSpeechFocus() = Fixture().use { f ->
        f.response = AudioManager.AUDIOFOCUS_REQUEST_FAILED
        f.start("media")
        f.response = AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        val siri = f.start("speechrecognition")
        assertEquals(2,f.requests.size)
        val request = f.requests.last()
        assertEquals(AudioAttributes.USAGE_ASSISTANT,request.audioAttributes.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_SPEECH,request.audioAttributes.contentType)
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,request.focusGain)
        assertEquals(1f,f.volume(siri),0f)
        f.change(request,AudioManager.AUDIOFOCUS_LOSS)
        assertFalse("live assistant defers media permission",f.sink.requestFocusForUserPlay())
        assertEquals("PLAY recovers assistant through a separate real assistant grant",1f,f.volume(siri),0f)
        assertEquals(AudioAttributes.USAGE_ASSISTANT,f.requests.last().audioAttributes.usage)
        assertEquals(3,f.requests.size)
    }

    @Test fun alternateNeverRequestsOrRetainsPrimaryFocus() {
        listOf(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
            .forEach { loss -> Fixture().use { f ->
                val alt = f.start("default",101)
                assertTrue("native/reference alternate channel must not steal focus",f.requests.isEmpty())
                val media = f.start("media")
                val request = f.requests.single()
                f.change(request,loss)
                assertEquals(if (loss == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) 0f else 0.2f,f.volume(alt),0f)
                f.sink.onAudioStopped(media)
                assertEquals(listOf(request),f.abandoned)
                assertEquals("retired MEDIA transient state must not strand alternate",1f,f.volume(alt),0f)
                f.change(request,AudioManager.AUDIOFOCUS_LOSS)
                f.change(request,AudioManager.AUDIOFOCUS_GAIN)
                assertEquals("retired listener cannot mutate alternate",1f,f.volume(alt),0f)
                f.sink.onAudioStopped(alt)
                assertEquals(1,f.abandoned.size)
                val restarted = f.start("compatibility",101)
                assertEquals(1f,f.volume(restarted),0f)
                assertEquals(1,f.requests.size)
            } }
    }

    @Test fun revokedDuringUserPlayCannotPublishLateGrant() = Fixture().use { f ->
        f.duringRequest = { f.sink.revokePendingWork() }
        assertFalse(f.sink.requestFocusForUserPlay())
        assertEquals(f.requests,f.abandoned)
        assertFalse(f.sink.requestFocusForUserPlay())
        assertEquals(1,f.requests.size)
    }

    @Test fun revokedDuringStreamRequestCannotCreateTrack() = Fixture().use { f ->
        f.duringRequest = { f.sink.revokePendingWork() }
        f.start("telephony")
        val tracks = AndroidMediaSink::class.java.getDeclaredField("tracks").apply { isAccessible=true }.get(f.sink) as Map<*,*>
        assertTrue("late Binder return cannot start retired output",tracks.isEmpty())
        assertEquals(f.requests,f.abandoned)
    }

    @Test fun platformRequestFailureCannotBecomeImplicitGrantOnRestart() = Fixture().use { f ->
        f.duringRequest = { throw IllegalStateException("platform unavailable") }
        val attempt = runCatching { f.start("telephony") }
        assertTrue("OS focus failure must fail closed, not escape with a half-owned request",attempt.isSuccess)
        val call = attempt.getOrThrow()
        assertEquals(0f,f.volume(call),0f)
        f.duringRequest = null
        f.sink.onAudioStopped(call)
        val restarted = f.start("telephony")
        assertEquals(1,f.requests.size)
        assertEquals(0f,f.volume(restarted),0f)
    }

    @Test fun failedAbandonRemainsExactRetirementDebt() = Fixture().use { f ->
        val call = f.start("telephony")
        val request = f.requests.single()
        f.abandonResponse = AudioManager.AUDIOFOCUS_REQUEST_FAILED
        try {
            f.sink.onAudioStopped(call)
            f.sink.close()
            assertFalse("unacknowledged platform focus must block sink retirement",f.sink.awaitRetired(100))
            assertTrue(f.abandoned.all { it === request })
        } finally {
            f.abandonResponse = AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        val attempts = f.abandoned.size
        f.sink.close()
        assertTrue(f.sink.awaitRetired(3000))
        assertEquals(attempts + 1,f.abandoned.size)
        f.sink.close()
        assertEquals(attempts + 1,f.abandoned.size)
    }

    @Test fun retiredOutputCannotLeaveAnOrphanedFocusMembership() = Fixture().use { f ->
        val media = f.start("media")
        val mediaRequest = f.requests.single()
        val tracks = AndroidMediaSink::class.java.getDeclaredField("tracks").apply { isAccessible=true }.get(f.sink) as Map<*,*>
        (tracks[media] as java.io.Closeable).close()
        f.start("telephony")
        assertEquals("pruning a retired output must release its exact focus too",listOf(mediaRequest),f.abandoned)
        val memberships = AndroidMediaSink::class.java.getDeclaredField("trackPurposes").apply { isAccessible=true }.get(f.sink) as Map<*,*>
        assertFalse(memberships.containsKey(media))
    }

    @Test fun purposeReceiptsBindActualRequestsAndRetirementByRevision() = Fixture().use { f ->
        val expected = mutableListOf<String>()
        listOf("media" to "media", "telephony" to "call", "alert" to "ring", "speechrecognition" to "assistant")
            .forEachIndexed { index, (kind,purpose) ->
                val id = f.start(kind)
                val r = f.requests.last()
                val suffix = "usage=${r.audioAttributes.usage} content=${r.audioAttributes.contentType} gain=${r.focusGain} revision=${index+1}"
                expected += "media stage=audio-focus-owner purpose=$purpose action=request $suffix"
                f.sink.onAudioStopped(id)
                expected += "media stage=audio-focus-owner purpose=$purpose action=abandon $suffix"
            }
        assertEquals(expected,f.status.filter { "stage=audio-focus-owner " in it })
    }

    @Test fun callTransferRestoresMediaOnlyOnItsOwnRealGain() = Fixture().use { f ->
        val media = f.start("media")
        val mediaRequest = f.requests.single()
        val call = f.start("telephony")
        val callRequest = f.requests.last()
        f.change(mediaRequest,AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        f.change(callRequest,AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(0f,f.volume(media),0f)
        assertEquals(1f,f.volume(call),0f)
        f.sink.onAudioStopped(call)
        assertEquals(listOf(callRequest),f.abandoned)
        assertEquals("ending a call is not an OS media grant",0f,f.volume(media),0f)
        f.change(mediaRequest,AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(1f,f.volume(media),0f)
        assertEquals("restore uses the retained request, not automatic reclaim",2,f.requests.size)
    }

    @Test fun eachPurposeHonorsDenialAcrossReplacementAndPacketStorm() {
        listOf("media","telephony","alert","speechrecognition").forEach { purpose -> Fixture().use { f ->
            f.response = AudioManager.AUDIOFOCUS_REQUEST_FAILED
            val id = f.start(purpose)
            val request = f.requests.single()
            f.response = AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            repeat(12) {
                f.sink.onAudioStopped(id)
                f.start(purpose)
                val format = requireNotNull(AudioOutputSupport.negotiate(0x4000L,100,purpose))
                repeat(20) { f.sink.onAudioRtp(id,format,ByteArray(16).apply { this[0]=0x80.toByte() },0) }
                f.change(request,AudioManager.AUDIOFOCUS_GAIN)
                assertEquals(0f,f.volume(id),0f)
            }
            assertEquals("no purpose may retry denial automatically",1,f.requests.size)
        } }
    }

    @Test fun eachPurposeHonorsPermanentLossUntilAuthorizedRecovery() {
        listOf("media","telephony","alert","speechrecognition").forEach { purpose -> Fixture().use { f ->
            val id = f.start(purpose)
            val request = f.requests.single()
            f.change(request,AudioManager.AUDIOFOCUS_LOSS)
            f.change(request,AudioManager.AUDIOFOCUS_GAIN)
            f.sink.onAudioStopped(id)
            f.start(purpose)
            assertEquals(1,f.requests.size)
            assertEquals(0f,f.volume(id),0f)
            assertEquals(purpose == "media",f.sink.requestFocusForUserPlay())
            assertEquals("explicit PLAY must obtain this purpose's own grant",1f,f.volume(id),0f)
            assertEquals(request.audioAttributes,f.requests.last().audioAttributes)
            assertEquals(request.focusGain,f.requests.last().focusGain)
            f.change(request,AudioManager.AUDIOFOCUS_LOSS)
            assertEquals(1f,f.volume(id),0f)
        } }
    }

    @Test fun userPlayRetriesEveryActiveBlockedPurposeWithIndependentResults() = Fixture().use { f ->
        val kinds = listOf("media","telephony","alert","speechrecognition")
        f.response = AudioManager.AUDIOFOCUS_REQUEST_FAILED
        val ids = kinds.map { f.start(it) }
        val old = f.requests.toList()
        f.duringRequest = {
            val usage = f.requests.last().audioAttributes.usage
            f.response = if (usage == AudioAttributes.USAGE_VOICE_COMMUNICATION)
                AudioManager.AUDIOFOCUS_REQUEST_FAILED else AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        assertFalse("live interactions defer media, not their separate recovery",f.sink.requestFocusForUserPlay())
        assertEquals("one actual request per blocked nonmedia purpose",7,f.requests.size)
        old.drop(1).zip(f.requests.drop(4)).forEach { (before,after) ->
            assertNotSame(before,after)
            assertEquals(before.audioAttributes,after.audioAttributes)
            assertEquals(before.focusGain,after.focusGain)
        }
        assertEquals("nonmedia grants cannot authorize media",0f,f.volume(ids[0]),0f)
        assertEquals("media, ring and assistant grants cannot authorize denied call",0f,f.volume(ids[1]),0f)
        assertEquals(1f,f.volume(ids[2]),0f)
        assertEquals(1f,f.volume(ids[3]),0f)
        old.forEach { f.change(it,AudioManager.AUDIOFOCUS_GAIN) }
        assertEquals(0f,f.volume(ids[1]),0f)
        repeat(4) { f.sink.onAudioStopped(ids[1]); f.start("telephony") }
        assertEquals("PLAY authorization was consumed by the failed call attempt",7,f.requests.size)
        f.duringRequest = null
        f.response = AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        assertFalse(f.sink.requestFocusForUserPlay())
        assertEquals(8,f.requests.size)
        assertEquals(1f,f.volume(ids[1]),0f)
    }

    @Test fun userPlayAuthorizesOneLaterRequestForEachInactiveBlockedPurpose() {
        listOf(false,true).forEach { permanent -> Fixture().use { f ->
            val kinds = listOf("telephony","alert","speechrecognition")
            f.response = if (permanent) AudioManager.AUDIOFOCUS_REQUEST_GRANTED else AudioManager.AUDIOFOCUS_REQUEST_FAILED
            kinds.forEach { kind ->
                val id = f.start(kind)
                if (permanent) f.change(f.requests.last(),AudioManager.AUDIOFOCUS_LOSS)
                f.sink.onAudioStopped(id)
            }
            val old = f.requests.toList()
            f.response = AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            assertTrue(f.sink.requestFocusForUserPlay())
            repeat(3) { assertTrue(f.sink.requestFocusForUserPlay()) }
            assertEquals("four explicit MEDIA refreshes; inactive nonmedia still gets only coalesced authorization",7,f.requests.size)
            // Deny these authorized attempts: each must consume only its own one-shot permission.
            f.response = AudioManager.AUDIOFOCUS_REQUEST_FAILED
            kinds.forEachIndexed { index,kind ->
                val id = f.start(kind)
                assertEquals("explicit PLAY permits the next real purpose request",8+index,f.requests.size)
                assertEquals(old[index].audioAttributes,f.requests.last().audioAttributes)
                assertEquals(old[index].focusGain,f.requests.last().focusGain)
                assertEquals(0f,f.volume(id),0f)
                f.change(old[index],AudioManager.AUDIOFOCUS_GAIN)
                repeat(4) { f.sink.onAudioStopped(id); f.start(kind) }
                assertEquals("no renewed authorization on SETUP",8+index,f.requests.size)
                assertEquals(0f,f.volume(id),0f)
                f.sink.onAudioStopped(id)
            }
            f.response = AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            assertTrue(f.sink.requestFocusForUserPlay())
            assertEquals(11,f.requests.size)
            kinds.forEach { kind -> assertEquals(1f,f.volume(f.start(kind)),0f) }
            assertEquals(14,f.requests.size)
        } }
    }

    @Test fun alternateRetainsPermanentAndDeniedMediaRestrictionsAfterRetirement() {
        listOf(false,true).forEach { permanent -> Fixture().use { f ->
            val alt = f.start("default",101)
            f.response = if (permanent) AudioManager.AUDIOFOCUS_REQUEST_GRANTED else AudioManager.AUDIOFOCUS_REQUEST_FAILED
            val media = f.start("media")
            val request = f.requests.single()
            if (permanent) f.change(request,AudioManager.AUDIOFOCUS_LOSS)
            f.sink.onAudioStopped(media)
            f.change(request,AudioManager.AUDIOFOCUS_GAIN)
            assertEquals(0f,f.volume(alt),0f)
            f.sink.onAudioStopped(alt)
            assertEquals(0f,f.volume(f.start("compatibility",101)),0f)
            assertEquals(1,f.requests.size)
        } }
    }

    @Test fun deniedMediaPlayDoesNotPreventSeparateActiveCallRecovery() = Fixture().use { f ->
        val media = f.start("media")
        val call = f.start("telephony")
        f.requests.toList().forEach { f.change(it,AudioManager.AUDIOFOCUS_LOSS) }
        f.duringRequest = {
            f.response = if (f.requests.last().audioAttributes.usage == AudioAttributes.USAGE_MEDIA)
                AudioManager.AUDIOFOCUS_REQUEST_FAILED else AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        assertFalse("PLAY return is not a combined-purpose permission",f.sink.requestFocusForUserPlay())
        assertEquals(3,f.requests.size)
        assertEquals(0f,f.volume(media),0f)
        assertEquals(1f,f.volume(call),0f)
    }

    @Test fun userPlayPreservesLiveNonmediaTransientAndDuckListeners() = Fixture().use { f ->
        val call = f.start("telephony")
        val callRequest = f.requests.last()
        val siri = f.start("speechrecognition")
        val siriRequest = f.requests.last()
        f.change(callRequest,AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        f.change(siriRequest,AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertFalse(f.sink.requestFocusForUserPlay())
        assertEquals("transient and duck memberships still defer MEDIA",2,f.requests.size)
        assertTrue(f.abandoned.isEmpty())
        assertEquals(0f,f.volume(call),0f)
        assertEquals(0.2f,f.volume(siri),0f)
        f.change(callRequest,AudioManager.AUDIOFOCUS_GAIN)
        f.change(siriRequest,AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(1f,f.volume(call),0f)
        assertEquals(1f,f.volume(siri),0f)
    }

    @Test fun deferredRecoveryCannotOverlapPendingNonmediaAbandonment() = Fixture().use { f ->
        val call = f.start("telephony")
        val old = f.requests.single()
        f.change(old,AudioManager.AUDIOFOCUS_LOSS)
        f.abandonResponse = AudioManager.AUDIOFOCUS_REQUEST_FAILED
        try {
            f.sink.onAudioStopped(call)
            assertFalse(f.sink.requestFocusForUserPlay())
            assertEquals(1,f.requests.size)
            f.start("telephony")
            assertEquals("authorized attempt cannot overlap exact retirement debt",1,f.requests.size)
            assertEquals(0f,f.volume(call),0f)
            f.change(old,AudioManager.AUDIOFOCUS_GAIN)
            assertEquals(0f,f.volume(call),0f)
            val abandonAttempts = f.abandoned.size
            repeat(4) { f.start("telephony") }
            assertEquals("failed recovery is not retried by SETUP",abandonAttempts,f.abandoned.size)
        } finally { f.abandonResponse = AudioManager.AUDIOFOCUS_REQUEST_GRANTED }
        assertFalse(f.sink.requestFocusForUserPlay())
        assertEquals(2,f.requests.size)
        assertEquals(AudioAttributes.USAGE_VOICE_COMMUNICATION,f.requests.last().audioAttributes.usage)
        assertEquals(1f,f.volume(call),0f)
    }

    @Test fun revokedDuringNonmediaRecoveryCannotRequestRemainingPurposes() = Fixture().use { f ->
        f.response = AudioManager.AUDIOFOCUS_REQUEST_FAILED
        f.start("telephony")
        f.start("alert")
        f.response = AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        f.duringRequest = {
            if (f.requests.last().audioAttributes.usage == AudioAttributes.USAGE_VOICE_COMMUNICATION)
                f.sink.revokePendingWork()
        }
        assertFalse(f.sink.requestFocusForUserPlay())
        assertEquals(3,f.requests.size) // two denials, revoked call grant; no MEDIA or ring retry
        assertEquals(1,f.requests.count { it.audioAttributes.usage == AudioAttributes.USAGE_NOTIFICATION_RINGTONE })
        assertTrue(f.abandoned.contains(f.requests.last()))
    }

    @Test fun alternateDoesNotLiftPendingRetirementRestriction() = Fixture().use { f ->
        val alt = f.start("default",101)
        val media = f.start("media")
        val old = f.requests.single()
        f.change(old,AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        f.abandonResponse = AudioManager.AUDIOFOCUS_REQUEST_FAILED
        try {
            f.sink.onAudioStopped(media)
            assertEquals(0f,f.volume(alt),0f)
            f.change(old,AudioManager.AUDIOFOCUS_GAIN)
            assertEquals(0f,f.volume(alt),0f)
            assertEquals(0f,f.volume(f.start("compatibility",101)),0f)
            assertEquals(1,f.requests.size)
        } finally { f.abandonResponse = AudioManager.AUDIOFOCUS_REQUEST_GRANTED }
    }

    @Test fun lastExactPrimaryMemberAloneRetiresItsRequest() = Fixture().use { f ->
        val media = f.start("media")
        val request = f.requests.single()
        val default = f.start("default")
        assertEquals(1,f.requests.size)
        f.sink.onAudioStopped(AudioStreamId(100,"alert"))
        f.sink.onAudioStopped(media)
        assertTrue(f.abandoned.isEmpty())
        f.change(request,AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertEquals(0.2f,f.volume(default),0f)
        f.sink.onAudioStopped(default)
        assertEquals(listOf(request),f.abandoned)
        f.sink.onAudioStopped(default)
        f.sink.close()
        assertEquals(listOf(request),f.abandoned)
    }

    @Test fun retiredCallListenerCannotMutateReplacementOrOtherPurpose() = Fixture().use { f ->
        val media = f.start("media")
        val call = f.start("telephony")
        val old = f.requests.last()
        f.sink.onAudioStopped(call)
        f.start("telephony")
        val current = f.requests.last()
        f.change(old,AudioManager.AUDIOFOCUS_LOSS)
        assertEquals(1f,f.volume(call),0f)
        assertEquals(1f,f.volume(media),0f)
        f.change(current,AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        f.change(old,AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(0f,f.volume(call),0f)
        assertEquals(1f,f.volume(media),0f)
        f.change(current,AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(1f,f.volume(call),0f)
    }

    @Test fun pendingAbandonCannotAdmitSuccessorOrLateGain() = Fixture().use { f ->
        val media = f.start("media")
        val request = f.requests.single()
        f.abandonResponse = AudioManager.AUDIOFOCUS_REQUEST_FAILED
        try {
            f.sink.onAudioStopped(media)
            f.start("media")
            assertEquals(1,f.requests.size)
            f.change(request,AudioManager.AUDIOFOCUS_GAIN)
            assertEquals(0f,f.volume(media),0f)
            assertFalse(f.sink.requestFocusForUserPlay())
            assertEquals(1,f.requests.size)
            assertTrue(f.status.any { "purpose=media action=pending " in it })
        } finally { f.abandonResponse = AudioManager.AUDIOFOCUS_REQUEST_GRANTED }
        assertTrue(f.sink.requestFocusForUserPlay())
        assertEquals(2,f.requests.size)
        assertEquals(1f,f.volume(media),0f)
    }

    @Test fun unexpectedDelayedResponseIsNotAnImmediateOrLateGrant() = Fixture().use { f ->
        f.response = AudioManager.AUDIOFOCUS_REQUEST_DELAYED
        val call = f.start("telephony")
        f.change(f.requests.single(),AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(0f,f.volume(call),0f)
        f.start("telephony")
        assertEquals(1,f.requests.size)
    }

    @Test fun telephonyRequestsTransientVoiceCommunicationNotMusic() = Fixture().use { f ->
        val call = f.start("telephony")
        assertEquals(1,f.requests.size)
        val request = f.requests.single()
        assertEquals(AudioAttributes.USAGE_VOICE_COMMUNICATION,request.audioAttributes.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_SPEECH,request.audioAttributes.contentType)
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,request.focusGain)
        assertEquals(1f,f.volume(call),0f)
    }
}
