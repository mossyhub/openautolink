package com.openautolink.app.cluster

import android.view.Surface
import com.openautolink.app.secondary.VideoMapping
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong

/** Complete Annex-B AVC access unit. Bytes are snapshotted before any asynchronous delivery. */
class ClusterEncodedFrame(val streamEpoch: Long, bytes: ByteArray, val presentationTimeUs: Long, val keyframe: Boolean) {
    internal val bytes: ByteArray
    init {
        require(streamEpoch >= 0 && presentationTimeUs >= 0 && bytes.isNotEmpty() && bytes.size <= MAX_BYTES)
        this.bytes = bytes.copyOf()
    }
    companion object { const val MAX_BYTES = 2 * 1024 * 1024 }
}

/** AVC-only pilot; SPS/PPS include Annex-B start codes. Coded dimensions must match the mapping.
 * A named decoder is an exact qualification receipt, not a hint or fallback preference.
 */
class ClusterCodecConfig(val codedSize: PixelSize, sps: ByteArray, pps: ByteArray, val decoderName: String? = null) {
    internal val sps: ByteArray
    internal val pps: ByteArray
    init {
        require(decoderName == null || (decoderName.isNotBlank() && decoderName.length <= 256))
        require(sps.isNotEmpty() && pps.isNotEmpty() && sps.size <= 65536 && pps.size <= 65536)
        this.sps = sps.copyOf(); this.pps = pps.copyOf()
    }
}

data class ClusterRendererStats(
    val received: Long, val configs: Long, val keyframes: Long, val decoded: Long,
    val presented: Long, val dropped: Long, val errors: Long, val queuedPackets: Int, val queuedBytes: Int,
)

/** Exact identity receipt. A false/timeout detach is NOT permission to release the host Surface. */
class ClusterSurfaceAttachment internal constructor(
    val surface: Surface, val mapping: VideoMapping, val generation: Long,
    internal val owner: Any,
) {
    val ready = CompletableFuture<Boolean>()
}

internal data class ClusterRenderProgress(val decoded: Long, val presented: Long, val ptsUs: Long = -1)
internal interface ClusterRenderDriver {
    fun diagnostics(trace: OptionalOutputDiagnostics) = Unit
    /** Called after the worker retains the driver, so partial initialization can be retired safely. */
    fun initialize() = Unit
    fun configure(config: ClusterCodecConfig)
    fun submit(frame: ClusterEncodedFrame): Boolean
    fun poll(): ClusterRenderProgress
    fun stopDecoder()
    fun clearOutput()
    /** Direct owned-window only: release the EGL window, retaining the decoder on a pbuffer. */
    fun parkWindow(): Boolean = false
    fun resumeWindow(surface: Surface): Boolean = false
    /** True only after decoder, SurfaceTexture and EGL have stopped referencing the exact host. */
    fun detach(): Boolean
}
internal fun interface ClusterRenderDriverFactory {
    /** Construct an opaque black EGL output; never release the borrowed host Surface. */
    fun open(surface: Surface, mapping: VideoMapping): ClusterRenderDriver
}

/**
 * Independent optional-screen owner. No main decoder, reconnect, audio, Canvas or navigation dependency.
 *
 * attach/configure/offer/endStream are nonblocking admissions, not presentation acknowledgements.
 * attach.ready and detach/retire futures complete on the dedicated worker: use async continuations,
 * never blocking callbacks. Wait only off Main, with a caller-chosen finite deadline. A timeout does
 * not revoke GPU ownership; retain the exact host Surface until a true detach/retire acknowledgement.
 * Generation and stream epochs must be monotonically increasing within this renderer instance.
 * Queues, packet sizes, startup GOP and codec polling are bounded. Overflow discards the broken GOP
 * and waits for a new IDR with retained configuration; it never asks the main session to reconnect.
 */
class ClusterVideoRenderer internal constructor(private val factory: ClusterRenderDriverFactory,
    private val diagnostics: OptionalOutputDiagnostics = OptionalOutputDiagnostics()) {
    constructor() : this(ClusterRenderDriverFactory { surface, mapping -> ClusterEglVideoDriver(surface, mapping) })

    private sealed interface Command {
        data class Attach(val handle: ClusterSurfaceAttachment) : Command
        data class Detach(val handle: ClusterSurfaceAttachment, val ack: CompletableFuture<Boolean>) : Command
        data class Park(val handle: ClusterSurfaceAttachment, val ack: CompletableFuture<Boolean>) : Command
        data class Configure(val epoch: Long, val config: ClusterCodecConfig) : Command
        data class Frame(val value: ClusterEncodedFrame, val continuity: Long) : Command
        data class End(val epoch: Long) : Command
        data class Barrier(val ack: CompletableFuture<Boolean>) : Command
    }
    private val lock = Object()
    private val identity = Any()
    private val queue = ArrayDeque<Command>()
    private var queuedBytes = 0
    private var queuedFrames = 0
    private var continuity = 0L
    private var admittedEpoch = -1L
    private var admittedEnded = false
    private var retiring = false
    private val retired = CompletableFuture<Boolean>()
    private val received = AtomicLong()
    private val configs = AtomicLong()
    private val keyframes = AtomicLong()
    private val decoded = AtomicLong()
    private val presented = AtomicLong()
    private val dropped = AtomicLong()
    private val errors = AtomicLong()
    /**
     * Local output failure, observable without waiting on the worker. The host must withdraw
     * this optional output on failure: black retirement is best effort, not guaranteed by this
     * diagnostic. Only true detach/retire acknowledges both clear and GPU retirement; false
     * or timeout retains exact-Surface ownership debt. No main-session reconnect is requested.
     */
    @Volatile var lastError: String? = null
        private set
    // Everything below is confined to the worker.
    private var handle: ClusterSurfaceAttachment? = null
    private var highestGeneration = -1L
    private var driver: ClusterRenderDriver? = null
    private var parked = false
    private var epoch = -1L
    private var ended = false
    private var config: ClusterCodecConfig? = null
    private var configured = false
    private var needIdr = true
    private var lastContinuity = 0L
    private var faulted = false
    private var operation = "idle"
    private var lastPts = -1L
    private var firstEncodedEpoch = -1L
    private val pendingInput = ArrayDeque<ClusterEncodedFrame>()
    private var pendingBytes = 0
    private val startup = ArrayDeque<ClusterEncodedFrame>()
    private var startupBytes = 0
    private val worker = Thread(::run, "oal-cluster-video").apply { isDaemon = true; start() }

    fun attach(surface: Surface, mapping: VideoMapping, generation: Long): ClusterSurfaceAttachment {
        require(generation >= 0)
        val h = ClusterSurfaceAttachment(surface, mapping, generation, identity)
        if (!enqueue(Command.Attach(h))) h.ready.complete(false)
        return h
    }
    fun detach(attachment: ClusterSurfaceAttachment): CompletableFuture<Boolean> {
        if (attachment.owner !== identity) return CompletableFuture.completedFuture(false)
        val ack = CompletableFuture<Boolean>()
        if (!enqueue(Command.Detach(attachment, ack))) {
            // After retirement the global acknowledgement also covers this exact owned handle.
            if (retired.isDone) ack.complete(retired.getNow(false)) else ack.complete(false)
        }
        return ack
    }
    fun park(attachment: ClusterSurfaceAttachment): CompletableFuture<Boolean> {
        if (attachment.owner !== identity) return CompletableFuture.completedFuture(false)
        val ack = CompletableFuture<Boolean>()
        if (!enqueue(Command.Park(attachment, ack))) ack.complete(false)
        return ack
    }
    fun configure(streamEpoch: Long, config: ClusterCodecConfig): Boolean = synchronized(lock) {
        require(streamEpoch >= 0)
        if (streamEpoch < admittedEpoch || (streamEpoch == admittedEpoch && admittedEnded)) return false
        if (!enqueue(Command.Configure(streamEpoch, config))) return false
        admittedEpoch = streamEpoch; admittedEnded = false
        true
    }
    fun endStream(streamEpoch: Long): Boolean = synchronized(lock) {
        if (streamEpoch != admittedEpoch || admittedEnded || !enqueue(Command.End(streamEpoch))) return false
        admittedEnded = true
        true
    }
    fun offer(frame: ClusterEncodedFrame): Boolean {
        received.incrementAndGet()
        if (frame.keyframe) keyframes.incrementAndGet()
        synchronized(lock) {
            if (retiring || admittedEnded || frame.streamEpoch != admittedEpoch) { dropped.incrementAndGet(); return false }
            if (queuedFrames >= MAX_FRAMES || queuedBytes + frame.bytes.size > MAX_QUEUED_BYTES) {
                val iterator = queue.iterator()
                while (iterator.hasNext()) if (iterator.next() is Command.Frame) { iterator.remove(); dropped.incrementAndGet() }
                queuedFrames = 0; queuedBytes = 0; continuity++
            }
            if (queue.size >= MAX_COMMANDS) { continuity++; dropped.incrementAndGet(); return false }
            queue.addLast(Command.Frame(frame, continuity))
            queuedFrames++; queuedBytes += frame.bytes.size
            lock.notifyAll()
            return true
        }
    }
    /** Queue fence for integration/tests, not a decoded/presented fence. */
    fun barrier(): CompletableFuture<Boolean> = CompletableFuture<Boolean>().also {
        if (!enqueue(Command.Barrier(it))) it.complete(false)
    }
    /** Idempotent terminal operation, prioritized even when the data/control queue is full. */
    fun retire(): CompletableFuture<Boolean> {
        synchronized(lock) { retiring = true; lock.notifyAll() }
        return retired
    }
    fun stats(): ClusterRendererStats = synchronized(lock) {
        ClusterRendererStats(received.get(), configs.get(), keyframes.get(), decoded.get(), presented.get(),
            dropped.get(), errors.get(), queuedFrames, queuedBytes)
    }
    private fun enqueue(command: Command): Boolean = synchronized(lock) {
        if (retiring || queue.size >= MAX_COMMANDS) false
        else { queue.addLast(command); lock.notifyAll(); true }
    }
    private fun run() {
        while (true) {
            val command = synchronized(lock) {
                if (!retiring && queue.isEmpty()) lock.wait(POLL_MS)
                if (retiring) null else queue.pollFirst().also {
                    if (it is Command.Frame) { queuedFrames--; queuedBytes -= it.value.bytes.size }
                }
            }
            // Process a command already removed from the queue even if retirement raced the take;
            // otherwise its exact attachment/detach acknowledgement could be stranded forever.
            if (command == null && synchronized(lock) { retiring }) break
            try {
                if (command != null) process(command)
                operation = "poll"
                if (configured && !faulted) driver?.poll()?.let {
                    decoded.addAndGet(it.decoded); presented.addAndGet(it.presented)
                    diagnostics.progress(it.decoded, it.presented, it.ptsUs)
                    drainPending()
                }
                if (diagnostics.summaryDue()) diagnostics.summary("generation=${handle?.generation ?: highestGeneration} received=${received.get()} configs=${configs.get()} decoded=${decoded.get()} presented=${presented.get()} dropped=${dropped.get()} errors=${errors.get()} lastInputPtsUs=$lastPts configured=$configured")
            } catch (failure: Exception) {
                recordError(failure)
                faulted = true
                configured = false
                clearStartup()
                try { closeOutput() } catch (cleanup: Exception) { recordError(cleanup) }
                when (command) {
                    is Command.Attach -> command.handle.ready.complete(false)
                    is Command.Detach -> command.ack.complete(false)
                    is Command.Park -> command.ack.complete(false)
                    is Command.Barrier -> command.ack.complete(false)
                    else -> Unit
                }
            }
        }
        val success = try { closeOutput() } catch (failure: Exception) { recordError(failure); false }
        val abandoned = synchronized(lock) {
            queue.toList().also { queue.clear(); queuedBytes = 0; queuedFrames = 0 }
        }
        // CompletableFuture continuations may synchronously enter an external bridge lock.
        // Never run them under the queue monitor: bridge owners can call retire/detach there.
        for (command in abandoned) when (command) {
            is Command.Attach -> command.handle.ready.complete(false)
            is Command.Detach -> command.ack.complete(success)
            is Command.Park -> command.ack.complete(success)
            is Command.Barrier -> command.ack.complete(false)
            else -> Unit
        }
        clearStartup(); config = null
        diagnostics.summary("result=$success received=${received.get()} decoded=${decoded.get()} presented=${presented.get()} dropped=${dropped.get()} errors=${errors.get()}", terminal = true)
        retired.complete(success)
    }
    private fun process(command: Command) {
        when (command) {
            is Command.Attach -> {
                operation = "attach"
                val h = command.handle
                if (h.generation <= highestGeneration) { h.ready.complete(false); return }
                highestGeneration = h.generation
                if (parked && driver != null) {
                    require(handle?.mapping === h.mapping) { "Parked decoder mapping changed" }
                    // The new native-window borrow can begin inside resumeWindow before it
                    // throws. Install exact cleanup ownership before crossing that boundary.
                    handle = h
                    if (!driver!!.resumeWindow(h.surface)) { h.ready.complete(false); return }
                    parked = false
                    h.ready.complete(true)
                    return
                }
                val replacing = handle != null
                if (!closeOutput()) { h.ready.complete(false); return }
                if (replacing) clearStartup()
                handle = h
                faulted = false
                driver = factory.open(h.surface, h.mapping)
                driver!!.diagnostics(diagnostics)
                operation = "initialize"
                driver!!.initialize()
                configureDriver()
                h.ready.complete(true)
                diagnostics.event("renderer_ready", "generation=${h.generation} surfaceValid=${h.surface.isValid}")
                val replay = startup.toList()
                clearStartup()
                replay.forEach { deliver(it) }
            }
            is Command.Detach -> {
                if (command.handle !== handle) { command.ack.complete(true); return }
                val success = closeOutput()
                if (success) { clearStartup(); needIdr = true }
                command.ack.complete(success)
            }
            is Command.Park -> {
                if (command.handle !== handle || parked) { command.ack.complete(true); return }
                operation = "park_window"
                val success = driver?.parkWindow() == true
                if (success) parked = true
                command.ack.complete(success)
            }
            is Command.Configure -> {
                if (command.epoch < epoch || (command.epoch == epoch && ended)) return
                epoch = command.epoch; ended = false
                diagnostics.stream(epoch)
                configs.incrementAndGet()
                diagnostics.event("config_received", "coded=${command.config.codedSize} decoder=${OptionalOutputDiagnostics.codecName(command.config.decoderName)}")
                config = command.config; clearStartup(); needIdr = true
                if (!faulted) configureDriver()
            }
            is Command.End -> if (command.epoch == epoch) {
                diagnostics.event("stream_end")
                ended = true; config = null; clearStartup(); clearPending(); needIdr = true
                driver?.stopDecoder(); configured = false
                driver?.clearOutput()
            }
            is Command.Frame -> {
                lastPts = command.value.presentationTimeUs
                if (firstEncodedEpoch != command.value.streamEpoch) {
                    firstEncodedEpoch = command.value.streamEpoch
                    diagnostics.event("first_encoded", "inputEpoch=$firstEncodedEpoch ptsUs=$lastPts")
                }
                if (command.value.streamEpoch != epoch || ended || config == null || faulted) { dropped.incrementAndGet(); return }
                if (command.continuity != lastContinuity) {
                    lastContinuity = command.continuity; clearStartup(); needIdr = true
                    configureDriver()
                }
                if (driver == null) retainStartup(command.value) else deliver(command.value)
            }
            is Command.Barrier -> command.ack.complete(true)
        }
    }
    private fun configureDriver() {
        clearPending()
        val wasConfigured = configured
        configured = false; needIdr = true
        val d = driver ?: return
        if (wasConfigured) d.stopDecoder()
        val c = config ?: return
        require(c.codedSize == handle!!.mapping.codedSize) { "Cluster coded size does not match qualified mapping" }
        operation = "configure"
        d.configure(c); configured = true
        diagnostics.event("configured", "coded=${c.codedSize}")
    }
    private fun deliver(frame: ClusterEncodedFrame) {
        if (!configured || (needIdr && pendingInput.isEmpty() && !frame.keyframe)) { dropped.incrementAndGet(); return }
        if (pendingInput.size >= MAX_FRAMES || pendingBytes + frame.bytes.size > MAX_QUEUED_BYTES) {
            dropped.addAndGet(pendingInput.size.toLong())
            clearPending(); configureDriver()
            if (!frame.keyframe) { dropped.incrementAndGet(); return }
        }
        pendingInput.addLast(frame); pendingBytes += frame.bytes.size
        drainPending()
    }
    private fun drainPending() {
        for (i in 0 until MAX_FRAMES) {
            val next = pendingInput.peekFirst() ?: return
            operation = "submit"
            if (!driver!!.submit(next)) return // Retry the exact AU; EAGAIN is not decoder corruption.
            needIdr = false
            pendingInput.removeFirst(); pendingBytes -= next.bytes.size
        }
    }
    private fun clearPending() { pendingInput.clear(); pendingBytes = 0 }
    private fun retainStartup(frame: ClusterEncodedFrame) {
        if (frame.keyframe) clearStartup()
        if (startup.isEmpty() && !frame.keyframe) { dropped.incrementAndGet(); return }
        if (startup.size >= MAX_FRAMES || startupBytes + frame.bytes.size > MAX_QUEUED_BYTES) {
            dropped.addAndGet(startup.size.toLong() + 1); clearStartup(); return
        }
        startup.addLast(frame); startupBytes += frame.bytes.size
    }
    private fun clearStartup() { startup.clear(); startupBytes = 0 }
    private fun closeOutput(): Boolean {
        clearPending()
        val d = driver
        if (d != null) {
            // Never disconnect a borrowed window with the last route frame still latched.
            // Even a failed decoder stop must not prevent a best-effort black swap. A failed
            // clear leaves the exact driver/handle retained: false is retirement debt, not
            // permission for the host to release its Surface.
            try { operation = "stop_decoder"; d.stopDecoder() } finally { configured = false; operation = "clear"; d.clearOutput() }
            operation = "detach"
            if (!d.detach()) return false
        }
        driver = null; handle = null; parked = false; configured = false; needIdr = true
        return true
    }
    private fun recordError(failure: Exception) {
        errors.incrementAndGet(); lastError = OptionalOutputDiagnostics.reason(failure)
        diagnostics.failure(operation, failure)
    }
    companion object {
        const val MAX_FRAMES = 8
        const val MAX_QUEUED_BYTES = 4 * 1024 * 1024
        const val MAX_COMMANDS = 32
        private const val POLL_MS = 5L
    }
}
