package com.openautolink.app.cluster

import com.openautolink.app.data.ClusterLayoutRead
import com.openautolink.app.data.ClusterLayoutScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A fallback always retains the exact qualified Automatic receipt, with an observable reason. */
enum class ClusterLayoutFallbackReason {
    NO_CUSTOM_LAYOUT, INVALID_RECORD, SCOPE_MISMATCH, CANVAS_CHANGED,
    HOST_REJECTED, GEOMETRY_REJECTED, READ_FAILED,
}

class ClusterLayoutResolution internal constructor(
    val display: QualifiedClusterDisplay,
    val fallbackReason: ClusterLayoutFallbackReason?,
)

object ClusterSavedLayoutResolver {
    /** Pass `preferences::getClusterLayout`; no Context or main-thread storage access is required. */
    suspend fun resolve(
        qualifiedAutomatic: QualifiedClusterDisplay,
        readLayout: suspend (ClusterLayoutScope, PixelRect) -> ClusterLayoutRead,
    ): ClusterLayoutResolution {
        require(qualifiedAutomatic.validatedLayout == null) { "Expected qualified Automatic display" }
        val profile = qualifiedAutomatic.profile
        val scope = ClusterLayoutScope(profile.vehicle, profile.id, profile.role, profile.panel)
        val canvas = requireNotNull(qualifiedAutomatic.host.visible.intersect(qualifiedAutomatic.host.stable))
        val receipt = try {
            withContext(Dispatchers.IO) {
                val legacy = readLayout(scope, canvas)
                val background = qualifiedAutomatic.host.backgroundCanvas(profile)
                if (legacy.unavailableReason == com.openautolink.app.data.ClusterLayoutUnavailableReason.CANVAS_CHANGED &&
                    background != null && background != canvas) readLayout(scope, background) else legacy
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return ClusterLayoutResolution(qualifiedAutomatic, ClusterLayoutFallbackReason.READ_FAILED)
        }
        return resolveReceipt(qualifiedAutomatic, receipt)
    }

    /**
     * Consumes immutable persisted intent, not an OEM mask or a new vehicle qualification.
     * The caller must start from a fresh Automatic receipt. Canvas is the observed visible/stable
     * intersection, not the clipped OEM output. Never clip a custom layout to a changed host.
     */
    fun resolveReceipt(qualifiedAutomatic: QualifiedClusterDisplay, receipt: ClusterLayoutRead): ClusterLayoutResolution {
        require(qualifiedAutomatic.validatedLayout == null) { "Expected qualified Automatic display" }
        fun fallback(reason: ClusterLayoutFallbackReason) = ClusterLayoutResolution(qualifiedAutomatic, reason)
        val saved = receipt.custom ?: return fallback(receipt.unavailableReason?.let {
            ClusterLayoutFallbackReason.valueOf(it.name)
        } ?: ClusterLayoutFallbackReason.INVALID_RECORD)
        if (receipt.unavailableReason != null) return fallback(ClusterLayoutFallbackReason.INVALID_RECORD)
        val profile = qualifiedAutomatic.profile
        val scope = ClusterLayoutScope(profile.vehicle, profile.id, profile.role, profile.panel)
        if (saved.scope != scope) return fallback(ClusterLayoutFallbackReason.SCOPE_MISMATCH)
        val host = qualifiedAutomatic.host
        if (saved.canvas != host.essentialCanvas && saved.canvas != host.backgroundCanvas(profile))
            return fallback(ClusterLayoutFallbackReason.CANVAS_CHANGED)
        val layout = try {
            ClusterValidatedLayout(profile, host, saved.canvas, saved.output, saved.safe)
        } catch (_: IllegalArgumentException) {
            return fallback(ClusterLayoutFallbackReason.HOST_REJECTED)
        }
        val display = QualifiedClusterDisplay(profile, host, saved.output, layout)
        try {
            // Neither protocol may put essential content in letterbox bars or an empty inward fit.
            ClusterVideoGeometry.androidAuto(display)
            ClusterVideoGeometry.carPlay(display)
        } catch (_: IllegalArgumentException) {
            return fallback(ClusterLayoutFallbackReason.GEOMETRY_REJECTED)
        }
        return ClusterLayoutResolution(display, null)
    }
}
