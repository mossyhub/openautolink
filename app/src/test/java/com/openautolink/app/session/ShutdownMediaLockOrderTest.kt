package com.openautolink.app.session

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.support.v4.media.session.MediaSessionCompat
import com.openautolink.app.carplay.MetadataHostOwnership
import com.openautolink.app.input.ImuForwarder
import com.openautolink.app.media.GmMediaControlPolicy
import com.openautolink.app.media.OalMediaSessionManager
import com.openautolink.app.transport.ControlMessage
import com.openautolink.app.transport.aasdk.AasdkSession
import io.mockk.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [32, 34], manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
class ShutdownMediaLockOrderTest {
    @Test fun pauseCallbackReturnsWhileRealDisconnectHoldsSessionOwnership() {
        val f = Fixture()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val callbackReturned = CountDownLatch(1)
        val callbackBlocked = AtomicBoolean(false)
        val abortFixture = AtomicBoolean(false)
        val error = AtomicReference<Throwable?>()
        val imu = mockk<ImuForwarder>(relaxed = true)
        every { imu.stop() } answers {
            assertTrue(Thread.holdsLock(f.sessionLock))
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "fixture IMU boundary was not released" }
            // RED must unwind rather than allowing a permanent two-monitor deadlock.
            if (abortFixture.get()) throw FixtureUnwind()
        }
        field(f.manager, "_imuForwarder", imu)
        val disconnect = Thread {
            try { runBlocking { deliver(f.manager, f.session, ControlMessage.PhoneDisconnected("ignition_off")) } }
            catch (e: Throwable) { error.set(e) }
        }.apply { isDaemon = true; name = "held-real-disconnect" }
        val callbackThread = Thread.currentThread()
        val observer = Thread {
            if (!callbackReturned.await(2, TimeUnit.SECONDS)) {
                callbackBlocked.set(callbackThread.state == Thread.State.BLOCKED)
                abortFixture.set(true)
            }
            release.countDown()
        }.apply { isDaemon = true; name = "bounded-lock-order-observer" }
        try {
            disconnect.start()
            assertTrue("real disconnect never reached held IMU boundary", entered.await(5, TimeUnit.SECONDS))
            observer.start()
            // The real framework-installed callback enters MetadataHostOwnership.legacy.
            f.callback.onPause()
            callbackReturned.countDown()
            observer.join(5_000)
            disconnect.join(5_000)
            assertFalse("disconnect worker did not retire", disconnect.isAlive)
            assertFalse("PAUSE waited for sessionStateLock while holding metadata ownership; the disconnect's next clear needs that monitor", callbackBlocked.get())
            assertNull("disconnect must complete without the RED-only unwind", error.get())
            f.drainRoutes()
            var heartbeat = false
            Handler(Looper.getMainLooper()).post { heartbeat = true }
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("Main must continue dispatching after the overlapping callbacks", heartbeat)
        } finally {
            abortFixture.set(true)
            release.countDown()
            disconnect.join(5_000)
            if (observer.isAlive) observer.join(5_000)
            f.close()
        }
    }

    @Test fun callbackCapturesOldOwnerWithoutBlockingAndQueuedCommandCannotReachReplacement() {
        val f = Fixture()
        val returned = CountDownLatch(1)
        val replacement = mockk<AasdkSession>(relaxed = true)
        every { replacement.currentNativeSessionGeneration() } returns 202L
        every { replacement.sendKeyPress(any(), any()) } returns true
        val callback = Thread { f.callback.onPause(); returned.countDown() }.apply { isDaemon = true }
        var returnedBeforeReplacement = false
        try {
            synchronized(f.sessionLock) {
                callback.start()
                returnedBeforeReplacement = returned.await(2, TimeUnit.SECONDS)
                field(f.manager, "aasdkSession", replacement)
                field(f.manager, "lifecycleGeneration", 2L)
            }
            callback.join(5_000)
            assertFalse("media callback stayed blocked after ownership release", callback.isAlive)
            f.drainRoutes()
            assertTrue("command intake must not acquire sessionStateLock under metadata ownership", returnedBeforeReplacement)
            verify(exactly = 0) { f.session.sendKeyPress(any(), any()) }
            verify(exactly = 0) { replacement.sendKeyPress(any(), any()) }
        } finally { callback.join(5_000); f.close() }
    }

    @Test fun currentOwnerPauseStillQueuesExactlyOneGenerationQualifiedCommand() {
        val f = Fixture()
        try {
            f.callback.onPause()
            f.drainRoutes()
            verify(exactly = 1) { f.session.sendKeyPress(GmMediaControlPolicy.keyCodeFor(GmMediaControlPolicy.Command.PAUSE), 101L) }
        } finally { f.close() }
    }

    private class FixtureUnwind : RuntimeException()

    private class Fixture {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val routeScope = CoroutineScope(SupervisorJob() + dispatcher)
        val app = RuntimeEnvironment.getApplication()
        val manager = SessionManager(routeScope, app)
        val session = mockk<AasdkSession>(relaxed = true)
        val media: OalMediaSessionManager
        val callback: MediaSessionCompat.Callback
        val sessionLock = field(manager, "sessionStateLock")!!
        init {
            val originalScope = field(manager, "scope") as CoroutineScope
            originalScope.cancel()
            field(manager, "scope", routeScope)
            val slot = slot<MediaSessionCompat.Callback>()
            mockkConstructor(MediaSessionCompat::class)
            every { anyConstructed<MediaSessionCompat>().setCallback(capture(slot), any<Handler>()) } just Runs
            every { anyConstructed<MediaSessionCompat>().setPlaybackState(any()) } just Runs
            every { anyConstructed<MediaSessionCompat>().setMetadata(any()) } just Runs
            every { anyConstructed<MediaSessionCompat>().setActive(any()) } just Runs
            media = OalMediaSessionManager::class.java.getDeclaredConstructor(Context::class.java)
                .apply { isAccessible = true }.newInstance(app)
            media.initialize()
            callback = slot.captured
            every { session.currentNativeSessionGeneration() } returns 101L
            every { session.sendKeyPress(any(), any()) } returns true
            field(manager, "aasdkSession", session)
            field(manager, "lifecycleGeneration", 1L)
            field(manager, "_mediaSessionManager", media)
            SessionManager::class.java.getDeclaredMethod("installGmMediaControls")
                .apply { isAccessible = true }.invoke(manager)
        }
        fun drainRoutes() = runBlocking { withContext(dispatcher) {} }
        fun close() {
            runBlocking { routeScope.coroutineContext[Job]!!.cancelAndJoin() }
            dispatcher.close()
            for (name in listOf("videoDispatcher", "audioDispatcher")) {
                (field(manager, name) as ExecutorCoroutineDispatcher).close()
            }
            media.release()
            unmockkAll()
        }
    }

    companion object {
        private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name)
            .apply { isAccessible = true }.get(owner)
        private fun field(owner: Any, name: String, value: Any) = owner.javaClass.getDeclaredField(name)
            .apply { isAccessible = true }.set(owner, value)
        private suspend fun deliver(manager: SessionManager, session: AasdkSession, message: ControlMessage) =
            suspendCoroutine<Unit> { continuation ->
                val method = SessionManager::class.java.declaredMethods.first { it.name == "handleControlMessage" }
                    .apply { isAccessible = true }
                val result = method.invoke(manager, session, message, continuation)
                if (result !== COROUTINE_SUSPENDED) continuation.resume(Unit)
            }
    }
}
