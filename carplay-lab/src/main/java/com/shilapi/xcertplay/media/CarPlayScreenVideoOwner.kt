// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.media

import android.media.MediaCodec
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.shilapi.xcertplay.airplay.ScreenCodec
import com.shilapi.xcertplay.airplay.VideoCodec
import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Opaque exact binding: a predecessor cannot detach a successor reusing the same Surface. */
class CarPlayClusterSurfaceLease internal constructor(
    internal val owner: Any,
    internal val receipt: VideoPresentation.Receipt,
)

/** VIDEO ONLY. No Context, AudioManager, focus, AudioTrack, recorder or session owner. */
internal class CarPlayScreenVideoOwner(
    private val selection: CarPlayVideoSelection,
    private val codecFactory: (String) -> MediaCodec,
    private val onStatus: (String) -> Unit,
) : Closeable {
    private val closed = AtomicBoolean()
    private val retired = AtomicBoolean()
    private val presentation = VideoPresentation(null)
    private val generation = AtomicLong()
    private val latest = AtomicReference<Config?>()
    private class Config(val bytes: ByteArray, val receipt: Long, val recovery: (() -> Unit)?, val diagnostic: ((String)->Unit)?)
    private sealed class Job {
        class Frame(val bytes: ByteArray, val presentation: VideoPresentation.Receipt, val config: Config?) : Job()
        class Reset(val presentation: VideoPresentation.Receipt) : Job()
    }
    private val queue = ArrayBlockingQueue<Job>(24)
    @Volatile private var recovery: (() -> Unit)? = null
    @Volatile private var diagnostic: ((String)->Unit)? = null
    @Volatile internal var beforePoll: (() -> Unit)? = null
    private var accepted = 0L
    private var duplicates = 0L
    private var announced = VideoCodec.H264
    private val thread = Thread(::run, "lab-video-111").apply { isDaemon = true; start() }
    val isRetired: Boolean get() = retired.get() && !thread.isAlive
    private fun report(stage: String) { runCatching { onStatus("media stage=cluster-video $stage") } }
    private fun due(count: Long) = count <= 4 || count % 32 == 0L
    fun attach(surface: Surface): CarPlayClusterSurfaceLease? = presentation.attachLease(surface)?.let { CarPlayClusterSurfaceLease(this,it) }
    fun detach(lease: CarPlayClusterSurfaceLease) { if (lease.owner === this) presentation.detachLease(lease.receipt) }
    fun setRecovery(handler: () -> Unit) { recovery = handler }
    fun setDiagnostic(handler: (String)->Unit) { diagnostic = handler }
    @Synchronized fun codec(codec: VideoCodec) {
        require(codec == VideoCodec.H264 && selection.decoderName(codec) != null) { "Secondary video requires qualified AVC" }
        if (!closed.get()) announced = codec
    }
    @Synchronized fun config(bytes: ByteArray) {
        if (closed.get()) return
        val (codec,data) = ScreenCodec.detectConfig(bytes)
        require(codec == announced && codec == VideoCodec.H264)
        val old = latest.get()
        if (old?.bytes?.contentEquals(data) == true) {
            if (due(++duplicates)) report("action=config-duplicate receipt=${old.receipt} count=$duplicates")
            return
        }
        latest.set(Config(data.copyOf(),++accepted,recovery,diagnostic)); queue.clear()
        if (due(accepted)) report("action=config-accepted receipt=$accepted count=$accepted")
    }
    fun frame(bytes: ByteArray) {
        if (closed.get()) return
        val target = presentation.snapshot()
        if (target.closed || target.surface == null) return
        if (!queue.offer(Job.Frame(bytes,target,latest.get()))) {
            queue.clear(); queue.offer(Job.Reset(target)); report("action=queue-overflow")
        }
    }
    /** Clear canonical config on replacement as well as teardown; never reuse an old stream's CSD. */
    @Synchronized fun streamChanged() {
        latest.set(null); announced = VideoCodec.H264; queue.clear(); queue.offer(Job.Reset(presentation.snapshot()))
    }
    private fun run() {
        var codec: MediaCodec? = null
        var config: Config? = null
        var anchored = false
        var lastRecovery = 0L
        var observed = presentation.snapshot()
        fun visible() = !closed.get() && presentation.isCurrent(observed) &&
            observed.surface?.isValid == true && latest.get() === config
        fun release() {
            generation.incrementAndGet()
            runCatching { codec?.stop() }; runCatching { codec?.release() }; codec = null; anchored = false
        }
        fun recover(force: Boolean = false) {
            anchored = false
            val handler = config?.recovery ?: return
            if (!visible()) return
            val now = System.nanoTime()
            if (force || now-lastRecovery > 1_000_000_000L) {
                lastRecovery = now; runCatching { handler?.invoke() }
            }
        }
        fun configure(job: Config) {
            release()
            val format = CarPlayVideoQualification.mediaFormat(VideoCodec.H264,job.bytes,selection)
            val target = observed
            if (!visible()) return
            val candidate = codecFactory(checkNotNull(selection.decoderName(VideoCodec.H264)))
            val epoch = generation.get()
            val first = AtomicBoolean()
            try {
                val caps = candidate.codecInfo.getCapabilitiesForType(CarPlayVideoQualification.mime(VideoCodec.H264))
                require(CarPlayVideoQualification.supports(caps,format,selection.fps))
                if (!visible()) { candidate.release(); return }
                candidate.configure(format,target.surface,null,0)
                candidate.setOnFrameRenderedListener({ _,_,_ ->
                    if (!closed.get() && presentation.isCurrent(target) && latest.get() === job && generation.get() == epoch &&
                        first.compareAndSet(false,true)) {
                        runCatching { job.diagnostic?.invoke("first frame rendered") }
                        report("action=rendered generation=${target.generation} codecGeneration=$epoch")
                    }
                },Handler(Looper.getMainLooper()))
                if (!visible()) { candidate.release(); return }
                candidate.start(); codec = candidate
                if (!visible()) { release(); return }
                report("action=configured receipt=${job.receipt}")
            } catch (failure: Exception) { runCatching { candidate.release() }; throw failure }
        }
        fun drain(current: MediaCodec) {
            val info = MediaCodec.BufferInfo()
            repeat(32) {
                if (!visible()) return
                val index = current.dequeueOutputBuffer(info,0)
                if (index >= 0) current.releaseOutputBuffer(index,visible() && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0)
                else if (index != MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) return
            }
        }
        try {
            while (!closed.get()) {
                try {
                    val desired = presentation.snapshot()
                    if (desired.surface != null && !desired.surface.isValid) {
                        if (presentation.invalidate(desired)) report("action=surface-lost")
                        continue
                    }
                    val next = latest.get(); val changed = next !== config; config = next
                    if (desired !== observed) {
                        observed = desired; release()
                        if (desired.surface != null) { config?.let(::configure); recover(true) }
                    } else if (changed) {
                        // A stream retirement with no config must also release the exact codec.
                        if (config == null) release()
                        else if (desired.surface != null) { config?.let(::configure); recover() }
                    }
                    if (observed.surface == null) { queue.clear(); TimeUnit.MILLISECONDS.sleep(10); continue }
                    beforePoll?.invoke()
                    when (val job = queue.poll(10,TimeUnit.MILLISECONDS)) {
                        is Job.Reset -> if (job.presentation === observed) { release(); recover() }
                        is Job.Frame -> {
                            if (job.presentation === observed && job.config === latest.get() && job.config !== config) {
                                config = job.config; config?.let(::configure); recover()
                            }
                            if (job.presentation !== observed || job.config !== config || !visible()) continue
                            val bytes = MediaCodecSupport.toAnnexB(job.bytes)
                            if (!anchored && !MediaCodecSupport.isRandomAccess(bytes,VideoCodec.H264)) { recover(); continue }
                            if (codec == null) config?.let(::configure)
                            val current = codec ?: continue
                            drain(current)
                            var index = current.dequeueInputBuffer(0)
                            val deadline = System.nanoTime()+100_000_000L
                            while (index < 0 && visible() && System.nanoTime() < deadline) {
                                drain(current); index = current.dequeueInputBuffer(5000)
                            }
                            if (!visible()) continue
                            if (index < 0) { release(); recover(); continue }
                            val input = checkNotNull(current.getInputBuffer(index)); input.clear()
                            if (bytes.isEmpty() || bytes.size > input.remaining()) { release(); recover(); continue }
                            input.put(bytes); current.queueInputBuffer(index,0,bytes.size,System.nanoTime()/1000,0); anchored = true
                        }
                        null -> Unit
                    }
                    codec?.let(::drain)
                } catch (_: InterruptedException) { throw InterruptedException() }
                catch (_: Exception) { release(); report("action=decoder-failed"); recover() }
            }
        } catch (_: InterruptedException) { /* retirement */ }
        finally { release(); retired.set(true); report("action=retired") }
    }
    fun revoke() { closed.set(true); presentation.close(); queue.clear(); thread.interrupt() }
    override fun close() {
        revoke()
        if (Thread.currentThread() !== thread) try { thread.join(500) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        recovery = null; diagnostic = null
    }
}
