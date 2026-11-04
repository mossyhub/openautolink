package com.openautolink.app.cluster

import android.content.Context
import android.view.Surface
import com.openautolink.app.secondary.VideoMapping
import com.openautolink.app.secondary.SecondaryRenderTarget
import com.openautolink.app.video.NalParser
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Host acknowledgement, packet admission and renderer progress are deliberately separate. */
data class ClusterProjectionStatus(
    val hostAttached: Boolean = false,
    val received: Long = 0, val configurations: Long = 0, val keyframes: Long = 0,
    val decoded: Long = 0, val presented: Long = 0, val dropped: Long = 0,
    val error: String? = null,
)

/** Exact source identity; wire epochs from different transports must never be compared. */
class ClusterProjectionStream internal constructor(
    internal val owner: Any, val epoch: Long, internal val isCurrent: () -> Boolean,
)

/**
 * One optional output per admitted projection. No center-video, audio or recovery dependency.
 * All methods are nonblocking; retain this bridge and the exact lease after false retirement.
 * The common typed configure/frame interface is also usable by an AVC CarPlay adapter.
 */
class ClusterProjectionOutput internal constructor(
    val mapping: VideoMapping,
    factory: ClusterRenderDriverFactory,
    val avcDecoderName: String? = null,
) : ClusterHostOutputBridge {
    constructor(mapping: VideoMapping, avcDecoderName: String? = null) : this(mapping,
        ClusterRenderDriverFactory { surface, geometry -> ClusterEglVideoDriver(surface, geometry,
            retainDecoderWhileHidden = geometry is com.openautolink.app.secondary.RetainedWindowMapping) }, avcDecoderName)
    constructor(mapping: ClusterVideoMapping, avcDecoderName: String? = null) : this(mapping as VideoMapping, avcDecoderName)
    internal constructor(mapping: ClusterVideoMapping, factory: ClusterRenderDriverFactory,
        avcDecoderName: String? = null) : this(mapping as VideoMapping, factory, avcDecoderName)

    private val lock = Any()
    private val diagnostics = OptionalOutputDiagnostics()
    private var lease: ClusterOutputLease? = null
    private var retired = false
    private var generation = 0L
    private var hostRevision = 0L
    private var streamEpoch = 0L
    private var attachment: ClusterSurfaceAttachment? = null
    private var stream: ClusterProjectionStream? = null
    private var configuration: ClusterCodecConfig? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var admittedConfig = false
    private data class StatusRevision(val revision: Long, val value: ClusterProjectionStatus)
    // Authoritative snapshot is reserved with ownership; observable CAS never holds that lock.
    // Versioning prevents a delayed poll/attach publisher from resurrecting an already-retired host.
    private var statusSnapshot = StatusRevision(0, ClusterProjectionStatus())
    private val state = MutableStateFlow(statusSnapshot)
    @OptIn(InternalCoroutinesApi::class)
    val status: StateFlow<ClusterProjectionStatus> = object : StateFlow<ClusterProjectionStatus> {
        override val value: ClusterProjectionStatus get() = state.value.value
        override val replayCache: List<ClusterProjectionStatus> get() = listOf(value)
        override suspend fun collect(collector: FlowCollector<ClusterProjectionStatus>): Nothing {
            state.map { it.value }.distinctUntilChanged().collect(collector)
            error("StateFlow collection completed")
        }
    }
    private fun updateStatus(transform: (ClusterProjectionStatus) -> ClusterProjectionStatus) {
        val next = transform(statusSnapshot.value)
        if (next != statusSnapshot.value) statusSnapshot = StatusRevision(statusSnapshot.revision + 1, next)
    }
    private fun publishStatus() {
        val snapshot = synchronized(lock) { statusSnapshot }
        while (true) {
            val previous = state.value
            if (previous.revision >= snapshot.revision || state.compareAndSet(previous, snapshot)) return
        }
    }
    private inline fun <T> mutate(action: () -> T): T =
        try { synchronized(lock) { action() } } finally { publishStatus() }

    // Recheck exact source authority AFTER the renderer's bounded queue and before codec calls.
    // In particular a revoked transport selection cannot replay startup packets on late attach.
    private val renderer = ClusterVideoRenderer(ClusterRenderDriverFactory { surface, geometry ->
        val driver = factory.open(surface, geometry)
        object : ClusterRenderDriver {
            override fun diagnostics(trace: OptionalOutputDiagnostics) = driver.diagnostics(trace)
            private var revoked = false
            private var driverConfig: ClusterCodecConfig? = null
            private var driverStream: ClusterProjectionStream? = null
            private fun configuredCurrent(): Boolean = driverStream != null &&
                currentStream() === driverStream && driverConfig === configuration
            private fun revokeDecoder() {
                // Clear even if stop fails; neither failure acknowledges borrowed-host retirement.
                driverConfig = null; driverStream = null
                try { driver.stopDecoder() } finally { driver.clearOutput() }
                revoked = true
            }
            private fun <T> guarded(action: () -> T): T = try { action() }
                catch (failure: Exception) { fail(failure); throw failure }
            override fun initialize() = guarded { driver.initialize() }
            override fun configure(config: ClusterCodecConfig) = guarded {
                val source = synchronized(lock) { currentStream()?.takeIf { configuration === config } }
                if (source != null) {
                    driver.configure(config)
                    driverConfig = config; driverStream = source; revoked = false
                    // configure can block while a new selection/configuration revokes this one.
                    if (!synchronized(lock) { configuredCurrent() }) revokeDecoder()
                }
            }
            override fun submit(frame: ClusterEncodedFrame): Boolean = guarded {
                val valid = synchronized(lock) { configuredCurrent() && driverStream?.epoch == frame.streamEpoch }
                if (valid) driver.submit(frame) else true // consume stale queued AU, never decode it
            }
            override fun poll(): ClusterRenderProgress = guarded {
                val valid = synchronized(lock) { configuredCurrent() }
                if (!valid && !revoked) revokeDecoder()
                (if (valid) driver.poll() else ClusterRenderProgress(0, 0)).also { progress ->
                    mutate {
                        updateStatus { old -> old.copy(decoded = old.decoded + progress.decoded,
                            presented = old.presented + progress.presented) }
                    }
                }
            }
            override fun stopDecoder() = guarded { driverConfig = null; driverStream = null; driver.stopDecoder() }
            override fun clearOutput() = guarded { driver.clearOutput() }
            override fun parkWindow() = guarded { driver.parkWindow() }
            override fun resumeWindow(surface: Surface) = guarded {
                if (!synchronized(lock) { configuredCurrent() } && !revoked) revokeDecoder()
                driver.resumeWindow(surface)
            }
            override fun detach() = guarded { driver.detach() }
        }
    }, diagnostics)

    fun bindLease(exact: ClusterOutputLease): Boolean = synchronized(lock) {
        if (retired || lease != null) false else { lease = exact; diagnostics.bind(exact); true }
    }
    private fun owns(exact: ClusterOutputLease) = !retired && lease == exact
    private fun currentStream(): ClusterProjectionStream? = stream?.takeIf { !retired && it.isCurrent() }
    private fun current(exact: ClusterOutputLease, source: ClusterProjectionStream) =
        owns(exact) && stream === source && source.isCurrent()

    fun beginStream(exact: ClusterOutputLease, owner: Any, isCurrent: () -> Boolean): ClusterProjectionStream? = synchronized(lock) {
        if (!owns(exact) || !isCurrent()) return@synchronized null
        stream?.takeIf { it.owner === owner }?.let { return@synchronized it }
        stream?.let { renderer.endStream(it.epoch) }
        configuration = null; sps = null; pps = null; admittedConfig = false
        ClusterProjectionStream(owner, ++streamEpoch, isCurrent).also { stream = it }
    }
    fun configure(exact: ClusterOutputLease, source: ClusterProjectionStream, config: ClusterCodecConfig): Boolean =
        mutate { configureLocked(exact, source, config) }
    private fun configureLocked(exact: ClusterOutputLease, source: ClusterProjectionStream, config: ClusterCodecConfig): Boolean {
        if (!current(exact, source) || config.codedSize != mapping.codedSize ||
            (avcDecoderName != null && config.decoderName != null && config.decoderName != avcDecoderName)) return false
        val selectedConfig = if (avcDecoderName != null && config.decoderName == null)
            ClusterCodecConfig(config.codedSize, config.sps, config.pps, avcDecoderName) else config
        val previous = configuration
        configuration = selectedConfig
        val admitted = renderer.configure(source.epoch, selectedConfig)
        if (!admitted) configuration = previous
        admittedConfig = admitted
        if (admitted) updateStatus { it.copy(configurations = it.configurations + 1) }
        return admitted
    }
    fun frame(exact: ClusterOutputLease, source: ClusterProjectionStream, frame: ClusterEncodedFrame): Boolean =
        mutate { frameLocked(exact, source, frame) }
    private fun frameLocked(exact: ClusterOutputLease, source: ClusterProjectionStream, frame: ClusterEncodedFrame): Boolean {
        if (!current(exact, source) || !admittedConfig || frame.streamEpoch != source.epoch) return false
        val admitted = renderer.offer(frame)
        updateStatus { old -> old.copy(received = old.received + 1, keyframes = old.keyframes + if (frame.keyframe) 1 else 0,
            dropped = old.dropped + if (admitted) 0 else 1, error = renderer.lastError ?: old.error) }
        return admitted
    }
    /** AA supplies complete Annex-B AUs. Never infer IDR solely from transport flags. */
    fun annexB(exact: ClusterOutputLease, source: ClusterProjectionStream, bytes: ByteArray, timestampUs: Long): Boolean =
        mutate { annexBLocked(exact, source, bytes, timestampUs) }
    private fun annexBLocked(exact: ClusterOutputLease, source: ClusterProjectionStream, bytes: ByteArray, timestampUs: Long): Boolean {
        if (!current(exact, source) || bytes.isEmpty() || bytes.size > ClusterEncodedFrame.MAX_BYTES || timestampUs < 0 ||
            NalParser.findStartCode(bytes) != 0) return false
        var position = 0
        var hasSlice = false
        var idr = false
        var changed = false
        var units = 0
        while (position < bytes.size) {
            if (++units > 1024) return false
            val payload = position + NalParser.startCodeLength(bytes, position)
            if (payload >= bytes.size) return false
            val next = NalParser.findStartCode(bytes, payload + 1).let { if (it < 0) bytes.size else it }
            when (NalParser.h264NalType(bytes[payload])) {
                NalParser.H264_NAL_SPS -> {
                    if (next - position > 65536) return false
                    val value = bytes.copyOfRange(position, next)
                    if (sps?.contentEquals(value) != true) { sps = value; changed = true }
                }
                NalParser.H264_NAL_PPS -> {
                    if (next - position > 65536) return false
                    val value = bytes.copyOfRange(position, next)
                    if (pps?.contentEquals(value) != true) { pps = value; changed = true }
                }
                NalParser.H264_NAL_IDR -> { hasSlice = true; idr = true }
                NalParser.H264_NAL_SLICE -> hasSlice = true
            }
            position = next
        }
        if ((changed || !admittedConfig) && sps != null && pps != null) {
            if (!configureLocked(exact, source, ClusterCodecConfig(mapping.codedSize, sps!!, pps!!,
                    avcDecoderName ?: configuration?.decoderName))) return false
        }
        return if (!hasSlice) admittedConfig else frameLocked(exact, source, ClusterEncodedFrame(source.epoch, bytes, timestampUs, idr))
    }
    fun endStream(exact: ClusterOutputLease, source: ClusterProjectionStream): Boolean = synchronized(lock) {
        if (!owns(exact) || stream !== source) return@synchronized false
        stream = null; configuration = null; sps = null; pps = null; admittedConfig = false
        renderer.endStream(source.epoch)
    }
    /** Direct hosts must echo the frozen identity/attachment epoch, not just a reusable display ID.
     * visible = measured output canvas; stable = measured safe-drawing intersection.
     * The per-instance lease remains an ownership token, never a physical role receipt.
     */
    fun attach(lease: ClusterOutputLease, surface: Surface, target: SecondaryRenderTarget): CompletableFuture<Boolean> {
        if (target != mapping.target) return done(false)
        return attach(lease, surface, ClusterHostGeometry(target.displayId, target.panel, target.output, target.safe))
    }
    fun updateBounds(lease: ClusterOutputLease, surface: Surface, target: SecondaryRenderTarget): CompletableFuture<Boolean> {
        if (target != mapping.target) {
            // Geometry/epoch drift withdraws the old image, never retargets a frozen offer.
            return detach(lease, surface).thenApply { false }
        }
        return updateBounds(lease, surface, ClusterHostGeometry(target.displayId, target.panel, target.output, target.safe))
    }
    override fun attach(lease: ClusterOutputLease, surface: Surface, geometry: ClusterHostGeometry): CompletableFuture<Boolean> {
        val (handle, request) = synchronized(lock) {
            if (!owns(lease) || attachment != null || !matches(geometry)) {
                diagnostics.event("attach_rejected", "reason=${when {
                    !owns(lease) -> "stale_lease"
                    attachment != null -> "attachment_pending"
                    else -> "frozen_geometry_mismatch"
                }} revision=$hostRevision", true)
                return done(false)
            }
            val handle = renderer.attach(surface, mapping, ++generation)
            attachment = handle
            handle to ++hostRevision
        }
        // ready may already be complete. Subscribing beneath lock would run inline observers there.
        return handle.ready.handle { success, failure ->
            mutate {
                val accepted = failure == null && success == true && owns(lease) &&
                    attachment === handle && hostRevision == request
                if (hostRevision == request) updateStatus { old ->
                    old.copy(hostAttached = accepted, error = renderer.lastError ?: old.error)
                }
                diagnostics.event("attach_ack", "accepted=$accepted request=$request revision=$hostRevision generation=${handle.generation} reason=${when {
                    failure != null -> "ready_exception"
                    success != true -> "renderer_not_ready"
                    !owns(lease) -> "stale_lease"
                    attachment !== handle -> "superseded_attachment"
                    hostRevision != request -> "superseded_revision"
                    else -> "none"
                }}", !accepted)
                accepted
            }
        }
    }
    override fun updateBounds(lease: ClusterOutputLease, surface: Surface, geometry: ClusterHostGeometry): CompletableFuture<Boolean> {
        synchronized(lock) {
            if (!owns(lease) || attachment?.surface !== surface) {
                diagnostics.event("update_rejected", "revision=$hostRevision reason=${if (!owns(lease)) "stale_lease" else "surface_mismatch"}", true)
                return done(false)
            }
            // Same borrowed Surface and exact frozen output/safe: a component-area expansion
            // needs only a host receipt acknowledgement, not black-clear/decoder/GOP recreation.
            // Pending/failed attach and every changed essential canvas still take real cleanup.
            if (surface.isValid && statusSnapshot.value.hostAttached &&
                mapping.gmReceipt?.host?.let { it != geometry } == true && matches(geometry)) return done(true)
        }
        // Always retire the exact old generation BEFORE reattaching; false keeps borrowed debt.
        return detach(lease, surface).thenCompose { success -> if (success) attach(lease, surface, geometry) else done(false) }
    }
    override fun detach(lease: ClusterOutputLease, surface: Surface): CompletableFuture<Boolean> = detachWindow(lease, surface, false)
    /** Does not revoke the negotiated stream or discard its prediction chain. */
    fun park(lease: ClusterOutputLease, surface: Surface): CompletableFuture<Boolean> = detachWindow(lease, surface, true)
    private fun detachWindow(lease: ClusterOutputLease, surface: Surface, retainDecoder: Boolean): CompletableFuture<Boolean> {
        val old = mutate {
            if (this.lease != lease) return done(false)
            val exact = attachment ?: return done(true)
            if (exact.surface !== surface) return done(false)
            ++hostRevision
            updateStatus { it.copy(hostAttached = false) }
            exact
        }
        return (if (retainDecoder) renderer.park(old) else renderer.detach(old)).handle { success, failure ->
            synchronized(lock) {
                if (failure == null && success == true && attachment === old) attachment = null
                failure == null && success == true
            }
        }
    }
    fun retire(exact: ClusterOutputLease): CompletableFuture<Boolean> {
        mutate {
            if (lease != exact) return done(false)
            retired = true; stream = null; configuration = null; ++hostRevision
            updateStatus { it.copy(hostAttached = false) }
        }
        return renderer.retire() // exact handle retained until true, including failed retirement debt
    }
    private fun matches(host: ClusterHostGeometry): Boolean {
        val display = mapping.gmReceipt ?: return host.displayId == mapping.target.displayId &&
            host.surfaceSize == mapping.target.panel && host.visible == mapping.target.output &&
            host.stable == mapping.target.safe
        val actual = ClusterDisplayProfiles.qualify(display.profile.vehicle,
            listOf(ClusterDisplayBinding(display.profile.role, display.displayId)), host,
            validatedLayout = display.validatedLayout,
            frozenAutomatic = display.takeIf { it.validatedLayout == null }) ?: return false
        return actual.effectiveOutput == display.effectiveOutput && actual.safe == display.safe
    }
    private fun fail(failure: Exception) = mutate {
        updateStatus { it.copy(hostAttached = false, error = OptionalOutputDiagnostics.reason(failure)) }
    }
    private fun done(value: Boolean) = CompletableFuture.completedFuture(value)

    companion object {
        /** Retained TestA initial host qualification, NOT an assertion of a live attached host.
         * AndroidX revalidates the actual physical display/bounds before acknowledging attach. */
        fun qualifiedInitialDisplay(context: Context): QualifiedClusterDisplay? {
            val display = ClusterDriverDisplayReceipt.QUALIFIED_GM.resolveCandidate(context) ?: return null
            val profile = ClusterDisplayProfiles.AEGEAN_ORANGE_239
            // Retain the proven TestA host seed and durable layout scope. Profile firmware
            // records the source of the default geometry; no runtime firmware field is required.
            val host = ClusterHostGeometry(display.displayId, profile.panel,
                PixelRect(0, 135, 2000, 810), PixelRect(0, 135, 2000, 810))
            return ClusterDisplayProfiles.qualify(profile.vehicle,
                listOf(ClusterDisplayBinding(ClusterVehicleRole.DRIVER_CLUSTER, display.displayId)), host)
        }
    }
}
