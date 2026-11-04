package com.openautolink.app.carplay

import android.content.Context
import com.openautolink.app.cluster.*
import com.openautolink.carplaylab.media.CarPlayClusterVideoConfiguration
import com.openautolink.carplaylab.media.CarPlayClusterVideoListener
import com.shilapi.xcertplay.airplay.AirPlayInsets
import com.shilapi.xcertplay.media.CarPlayVideoQualification
import com.shilapi.xcertplay.media.MediaCodecSupport
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Software receipts only. Even presented frames do not establish physical visibility. */
data class CarPlayClusterStatus(
    val offerQualified: Boolean = false,
    val phoneStreamActive: Boolean = false,
    val pilotStatus: String? = null,
    val output: ClusterProjectionStatus = ClusterProjectionStatus(),
    val layoutSelectionReason: ClusterLayoutFallbackReason? = null,
)

/** One attempt's optional map output; never owns center-video, audio or a lab decoder.
 * Receiver callbacks are bounded queue admission. Host/renderer retirement stays asynchronous.
 */
class OalCarPlayClusterOutput internal constructor(
    val selection: ClusterOutputSelection,
    val configuration: CarPlayClusterVideoConfiguration?,
    private val manager: ClusterManager?,
    private val bridge: ClusterProjectionOutput?,
    private val lease: ClusterOutputLease?,
    private val clockUs: () -> Long = { System.nanoTime() / 1000 },
    private val initialError: String? = null,
    val layoutSelectionReason: ClusterLayoutFallbackReason? = null,
    private val prepared: com.openautolink.app.secondary.PreparedCarPlaySecondaryOutput? = null,
) : CarPlayClusterVideoListener {
    private val preparedListener = prepared?.listener
    private val lock = Any()
    @Volatile private var revoked = false
    @Volatile private var wireEpoch = Long.MIN_VALUE
    private var stream: ClusterProjectionStream? = null
    private var lastPts = -1L
    private var retirement: CompletableFuture<Boolean>? = null
    private var localError: String? = initialError

    fun snapshot(): CarPlayClusterStatus = prepared?.snapshot() ?: synchronized(lock) {
        CarPlayClusterStatus(configuration != null, !revoked && stream != null,
            configuration?.let { CarPlayClusterVideoConfiguration.PILOT_STATUS },
            (bridge?.status?.value ?: ClusterProjectionStatus()).let { it.copy(error = localError ?: it.error) },
            layoutSelectionReason)
    }

    override fun onStreamActive(streamEpoch: Long, active: Boolean) {
        preparedListener?.let { it.onStreamActive(streamEpoch, active); return }
        legacyStreamActive(streamEpoch, active)
    }
    private fun legacyStreamActive(streamEpoch: Long, active: Boolean) = synchronized(lock) {
        if (revoked || bridge == null || lease == null || configuration == null) return@synchronized
        if (!active) {
            if (streamEpoch != wireEpoch) return@synchronized
            stream?.let { bridge.endStream(lease, it) }; stream = null
            return@synchronized
        }
        // A retained callback from an old wire epoch must never replace a successor.
        if (streamEpoch <= wireEpoch) return@synchronized
        wireEpoch = streamEpoch
        val identity = Any()
        stream = bridge.beginStream(lease, identity) {
            !revoked && wireEpoch == streamEpoch
        }
    }

    override fun onCodecConfig(streamEpoch: Long, codecData: ByteArray) {
        preparedListener?.let { it.onCodecConfig(streamEpoch, codecData); return }
        legacyCodecConfig(streamEpoch, codecData)
    }
    private fun legacyCodecConfig(streamEpoch: Long, codecData: ByteArray) = synchronized(lock) {
        val current = stream ?: return@synchronized
        if (revoked || wireEpoch != streamEpoch || codecData.size !in 7..65536) return@synchronized
        try {
            val (sps, pps) = MediaCodecSupport.avcParameterSets(codecData)
            if (sps.isEmpty() || pps.isEmpty()) { localError = "Cluster AVC configuration rejected"; return@synchronized }
            bridge!!.configure(lease!!, current, ClusterCodecConfig(bridge.mapping.codedSize,
                START + sps, START + pps, decoderName = configuration!!.videoSelection.avcDecoderName))
        } catch (_: Exception) { localError = "Cluster AVC configuration rejected" }
    }

    override fun onFrame(streamEpoch: Long, naluBytes: ByteArray) {
        preparedListener?.let { it.onFrame(streamEpoch, naluBytes); return }
        legacyFrame(streamEpoch, naluBytes)
    }
    private fun legacyFrame(streamEpoch: Long, naluBytes: ByteArray) = synchronized(lock) {
        val current = stream ?: return@synchronized
        if (revoked || wireEpoch != streamEpoch || naluBytes.isEmpty() || naluBytes.size > ClusterEncodedFrame.MAX_BYTES) return@synchronized
        try {
            // Screen transport delivers complete Annex-B AUs. Do not invent packet timestamps.
            // Local monotonic PTS is solely the decoder's ordering clock, not a phone-time receipt.
            val pts = maxOf(clockUs().coerceAtLeast(0), lastPts + 1)
            lastPts = pts
            bridge!!.annexB(lease!!, current, naluBytes, pts)
        } catch (_: Exception) { localError = "Cluster frame rejected" }
    }

    /** Immediate source revocation; no wait and no effect on primary/audio. */
    fun revoke() { prepared?.revoke(); legacyRevoke() }
    private fun legacyRevoke() = synchronized(lock) {
        if (!revoked) {
            revoked = true
            stream?.let { bridge?.endStream(lease!!, it) }; stream = null
        }
    }

    /** Exact false/failed retirement is retained globally: successors may run primary WITHOUT map. */
    fun retire(): CompletableFuture<Boolean> = prepared?.retire("CarPlay optional output retired") ?: synchronized(lock) {
        revoke()
        retirement?.let { return@synchronized it }
        if (manager == null || bridge == null || lease == null) return@synchronized CompletableFuture.completedFuture(true)
        val result = CompletableFuture<Boolean>()
        retirement = result
        manager.endMapOutput(lease, selection, "CarPlay optional output retired").thenCompose { host ->
            if (host) bridge.retire(lease) else CompletableFuture.completedFuture(false)
        }.whenComplete { acknowledged, failure ->
            val success = failure == null && acknowledged == true
            if (success) {
                manager.release()
                synchronized(admissionLock) { if (owner === this) owner = null }
            } else synchronized(lock) { localError = "Cluster retirement unacknowledged; new map output withheld" }
            result.complete(success)
        }
        result
    }

    /** Runtime close is already off Main; a deadline never proves GPU detach or clears debt. */
    fun awaitRetirement() {
        try { retire().get(10, TimeUnit.SECONDS) } catch (_: Exception) {
            synchronized(lock) { localError = "Cluster retirement pending; new map output withheld" }
        }
    }

    companion object {
        private val START = byteArrayOf(0, 0, 0, 1)
        private val epochs = AtomicLong()
        private val admissionLock = Any()
        private var owner: OalCarPlayClusterOutput? = null

        internal fun fromPrepared(prepared: com.openautolink.app.secondary.PreparedCarPlaySecondaryOutput): OalCarPlayClusterOutput =
            OalCarPlayClusterOutput(prepared.legacySelection, prepared.configuration, null, null, null, prepared = prepared)

        internal fun hasOutstandingMapOutput(): Boolean = synchronized(admissionLock) { owner != null } ||
            com.openautolink.app.secondary.SecondaryOutputRuntime.hasOutstandingHostedMapOutput()

        fun prepare(context: Context, selection: ClusterOutputSelection): OalCarPlayClusterOutput = synchronized(admissionLock) {
            var layoutReason: ClusterLayoutFallbackReason? = null
            fun withheld(error: String? = null) = OalCarPlayClusterOutput(selection, null, null, null, null,
                initialError = error, layoutSelectionReason = layoutReason)
            if (!ClusterOutputPolicy.shouldOfferMapVideo(selection, ClusterProjectionProtocol.CARPLAY)) return@synchronized withheld()
            if (owner != null) return@synchronized withheld("Previous cluster output has not retired")
            val automatic = runCatching { ClusterProjectionOutput.qualifiedInitialDisplay(context) }.getOrNull()
                ?: return@synchronized withheld("Cluster driver display/profile unavailable")
            var manager: ClusterManager? = null
            var bridge: ClusterProjectionOutput? = null
            var lease: ClusterOutputLease? = null
            try {
                // The runtime factory is an IO worker. Keep synchronous construction there;
                // the resolver performs the durable scoped read on IO, never on Main.
                val resolution = kotlinx.coroutines.runBlocking {
                    ClusterSavedLayoutResolver.resolve(automatic,
                        com.openautolink.app.data.AppPreferences.getInstance(context)::getClusterLayout)
                }
                val display = resolution.display
                layoutReason = resolution.fallbackReason
                com.openautolink.app.diagnostics.DiagnosticLog.i("cluster",
                    "CarPlay layout selection: ${layoutReason?.name ?: "CUSTOM"}")
                val mapping = ClusterVideoGeometry.carPlay(display)
                val insets = mapping.phoneSafeInsets
                val video = CarPlayVideoQualification.select(1280, 480, 30, preferHevc = false)
                val config = CarPlayClusterVideoConfiguration.create(AirPlayInsets(0, 0, 0, 0),
                    AirPlayInsets(insets.top, insets.bottom, insets.left, insets.right), video)
                bridge = ClusterProjectionOutput(mapping, avcDecoderName = config.videoSelection.avcDecoderName)
                manager = ClusterManager(context)
                lease = manager.beginMapOutput(epochs.incrementAndGet(), selection, ClusterProjectionProtocol.CARPLAY, display, bridge)
                if (lease == null) {
                    val unused = ClusterOutputLease(0, 0)
                    bridge.bindLease(unused); bridge.retire(unused); manager.release()
                    return@synchronized withheld("Cluster host output not admitted")
                }
                check(bridge.bindLease(lease))
                OalCarPlayClusterOutput(selection, config, manager, bridge, lease,
                    layoutSelectionReason = layoutReason).also { owner = it }
            } catch (_: Exception) {
                if (manager != null && bridge != null && lease != null) {
                    // Retain every partially installed owner, including its false detach debt.
                    OalCarPlayClusterOutput(selection, null, manager, bridge, lease).also { owner = it; it.retire() }
                } else {
                    bridge?.let { val unused = ClusterOutputLease(0, 0); it.bindLease(unused); it.retire(unused) }
                    manager?.release()
                }
                withheld("Optional cluster output unavailable; main CarPlay continues")
            }
        }
    }
}
