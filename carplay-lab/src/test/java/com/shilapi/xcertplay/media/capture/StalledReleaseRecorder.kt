package com.shilapi.xcertplay.media.capture

import android.media.AudioRecord
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.util.reflector.Direct
import org.robolectric.util.reflector.ForType
import org.robolectric.util.reflector.Reflector.reflector

/** Both release fixtures use one shadow map in one explicitly keyed SDK sandbox.
 * Robolectric 4.14.1 can otherwise bind a finalizer's old native no-op after a map change.
 * Each real recorder retains its own scenario: old finalizers never observe a new test's latches.
 */
@Implements(AudioRecord::class)
class StalledReleaseRecorder {
    @RealObject lateinit var record: AudioRecord
    private var scenario: Scenario? = null

    companion object {
        private val lock = Any()
        private var active: Scenario? = null
        fun install(): Scenario = synchronized(lock) {
            check(active == null) { "previous exact recorder scenario has not retired" }
            Scenario().also { active = it }
        }
        @JvmStatic @Implementation fun native_get_min_buff_size(rate: Int, channels: Int, format: Int) = 1024
    }

    class Scenario internal constructor() : AutoCloseable {
        val reading = CountDownLatch(1)
        val releasing = CountDownLatch(1)
        val allowRelease = CountDownLatch(1)
        val realReleaseCompleted = CountDownLatch(1)
        val nativeReleases = AtomicInteger()
        @Volatile var record: AudioRecord? = null
        override fun close() {
            allowRelease.countDown()
            assertTrue("exact real AudioRecord.release must complete before fixture disposal",
                realReleaseCompleted.await(3, TimeUnit.SECONDS))
            synchronized(lock) { check(active === this); active = null }
        }
    }

    @Implementation fun native_start(event: Int, session: Int) = 0
    @Implementation fun native_read_in_byte_array(bytes: ByteArray, offset: Int, size: Int, blocking: Boolean): Int {
        val owner = synchronized(lock) {
            scenario ?: checkNotNull(active).also {
                check(it.record == null) { "scenario already belongs to another exact recorder" }
                it.record = record
                scenario = it
            }
        }
        owner.reading.countDown()
        Thread.sleep(30000)
        return 0
    }

    @Implementation fun release() {
        val owner = scenario
        if (owner != null && owner.realReleaseCompleted.count != 0L) {
            owner.releasing.countDown()
            while (true) {
                try { owner.allowRelease.await(); break } catch (_: InterruptedException) { }
            }
        }
        // Exercise the actual instrumented Android API body, not a synthetic retirement callback.
        reflector(AudioRecordReflector::class.java, record).release()
        owner?.realReleaseCompleted?.countDown()
    }
    @Implementation fun native_release() { scenario?.nativeReleases?.incrementAndGet() }

    @ForType(AudioRecord::class)
    interface AudioRecordReflector { @Direct fun release() }
}
