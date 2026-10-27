package com.openautolink.app.session

import android.app.Application
import com.openautolink.app.audio.MicCaptureManager
import com.openautolink.app.data.AppPreferences
import com.openautolink.app.transport.ControlMessage
import com.openautolink.app.transport.aasdk.AasdkSession
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = Application::class)
class AudioNextMicrophoneStreamTest {
    @Test fun savedChoiceIsReadAtEachActualCurrentOwnerStartForUsbAndWireless() = runBlocking {
        for (transport in listOf("usb", "wireless")) {
            val job = SupervisorJob()
            val prefs = AppPreferences.getInstance(RuntimeEnvironment.getApplication())
            val manager = SessionManager(CoroutineScope(Dispatchers.Unconfined + job), RuntimeEnvironment.getApplication())
            manager.externalUsbTransport = transport == "usb"
            val session = mockk<AasdkSession>(relaxed = true)
            val capture = mockk<MicCaptureManager>(relaxed = true)
            field(manager, "aasdkSession", session)
            field(manager, "_micCaptureManager", capture)
            field(manager, "micSource", "phone") // stale connection-time snapshot
            try {
                prefs.setMicSource("car")
                deliver(manager, session, ControlMessage.MicStart(16000))
                verify(exactly = 1) { capture.start(16000) }
                prefs.setMicSource("phone")
                // Saving changes desired source only: never touches a live recorder.
                verify(exactly = 0) { capture.stop() }
                deliver(manager, session, ControlMessage.MicStop)
                deliver(manager, session, ControlMessage.MicStart(24000))
                verify(exactly = 0) { capture.start(24000) }
                prefs.setMicSource("car")
                val stale = mockk<AasdkSession>(relaxed = true)
                deliver(manager, stale, ControlMessage.MicStart(48000))
                verify(exactly = 0) { capture.start(48000) }
                deliver(manager, session, ControlMessage.MicStart(48000))
                verify(exactly = 1) { capture.start(48000) }
            } finally { job.cancelAndJoin(); prefs.setMicSource("car") }
        }
    }

    @Test fun unavailablePreferenceReadWithholdsOnlyThisMicStartAndDoesNotBreakControlDelivery() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val manager = SessionManager(this, app)
        val managerScope = SessionManager::class.java.getDeclaredField("scope").apply { isAccessible = true }
            .get(manager) as CoroutineScope
        val session = mockk<AasdkSession>(relaxed = true)
        val capture = mockk<MicCaptureManager>(relaxed = true)
        val player = mockk<com.openautolink.app.audio.AudioPlayer>(relaxed = true)
        val controls = kotlinx.coroutines.flow.MutableSharedFlow<ControlMessage>()
        every { session.controlMessages } returns controls
        every { session.evTelemetryToken } returns "audio-preference-read-fixture"
        every { session.reconnectAttempt } returns kotlinx.coroutines.flow.MutableStateFlow(0)
        every { session.vehicleEnergyForecast } returns kotlinx.coroutines.flow.MutableStateFlow(null)
        every { session.videoFrames } returns kotlinx.coroutines.flow.MutableSharedFlow()
        every { session.audioFrames } returns kotlinx.coroutines.flow.MutableSharedFlow()
        every { session.negotiatedCodecType } returns kotlinx.coroutines.flow.MutableStateFlow(0)
        field(manager, "aasdkSession", session)
        field(manager, "_micCaptureManager", capture)
        field(manager, "_audioPlayer", player)
        val readAllowed = java.util.concurrent.atomic.AtomicBoolean(false)
        val readAttempted = CompletableDeferred<Unit>()
        val audioReceived = CompletableDeferred<Unit>()
        val captureReceived = CompletableDeferred<Unit>()
        every { player.startPurpose(com.openautolink.app.transport.AudioPurpose.MEDIA, 48000, 2) } answers {
            audioReceived.complete(Unit); Unit
        }
        every { capture.start(16000) } answers { captureReceived.complete(Unit); Unit }
        val failing = spyk(AppPreferences.getInstance(app))
        every { failing.micSource } returns kotlinx.coroutines.flow.flow {
            if (!readAllowed.get()) {
                readAttempted.complete(Unit)
                throw java.io.IOException("private storage path")
            }
            emit("car")
        }
        mockkObject(AppPreferences.Companion)
        every { AppPreferences.getInstance(any()) } returns failing
        try {
            // Exercise the real collector parent and child, not just its private target.
            SessionManager::class.java.getDeclaredMethod("bindSessionCollectors", AasdkSession::class.java)
                .apply { isAccessible = true }.invoke(manager, session)
            withTimeout(5000) { controls.subscriptionCount.first { it > 0 } }
            controls.emit(ControlMessage.MicStart(16000))
            withTimeout(5000) { readAttempted.await() }
            controls.emit(ControlMessage.AudioStart(com.openautolink.app.transport.AudioPurpose.MEDIA, 48000, 2))
            withTimeout(5000) { audioReceived.await() }
            verify(exactly = 0) { capture.start(any()) }
            readAllowed.set(true)
            controls.emit(ControlMessage.MicStart(16000))
            withTimeout(5000) { captureReceived.await() }
            verify(exactly = 1) { capture.start(16000) }
            val collectors = SessionManager::class.java.getDeclaredField("sessionCollectors")
                .apply { isAccessible = true }.get(manager) as Job
            org.junit.Assert.assertTrue(collectors.isActive)
            val logs = org.robolectric.shadows.ShadowLog.getLogsForTag("SessionManager").map { it.msg }
            org.junit.Assert.assertTrue(logs.any { it.contains("Assistant microphone source unavailable; MicStart withheld") })
            org.junit.Assert.assertFalse(logs.any { it.contains("private storage path") })
        } finally {
            managerScope.coroutineContext[Job]!!.cancelAndJoin()
            for (name in listOf("videoDispatcher", "audioDispatcher")) {
                (SessionManager::class.java.getDeclaredField(name).apply { isAccessible = true }
                    .get(manager) as ExecutorCoroutineDispatcher).close()
            }
            unmockkObject(AppPreferences.Companion)
        }
    }

    private fun field(owner: Any, name: String, value: Any) {
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value)
    }

    // Exercise the real collector target; support the pre-fix synchronous signature for RED.
    private suspend fun deliver(manager: SessionManager, session: AasdkSession, message: ControlMessage) = suspendCoroutine<Unit> { continuation ->
        val method = SessionManager::class.java.declaredMethods.first { it.name == "handleControlMessage" }.apply { isAccessible = true }
        val result = if (method.parameterCount == 3) method.invoke(manager, session, message, continuation)
            else method.invoke(manager, session, message)
        if (result !== COROUTINE_SUSPENDED) continuation.resume(Unit)
    }
}
