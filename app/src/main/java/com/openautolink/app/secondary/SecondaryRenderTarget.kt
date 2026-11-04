package com.openautolink.app.secondary

import com.openautolink.app.cluster.PixelRect
import com.openautolink.app.cluster.PixelSize
import com.openautolink.app.cluster.QualifiedClusterDisplay

/** Frozen measured surface-local geometry. Identity/epoch are attachment receipts, not physical roles. */
data class SecondaryRenderTarget(
    val id: SecondaryOutputId,
    val attachmentEpoch: Long,
    val displayId: Int,
    val panel: PixelSize,
    val output: PixelRect,
    val safe: PixelRect,
) {
    init {
        require(id.value.isNotBlank() && id.value.length <= 512)
        require(attachmentEpoch >= 0 && displayId >= 0)
        require(panel.bounds.contains(output) && output.contains(safe))
    }

    companion object {
        /** Preserve the exact qualified host/layout; never infer or manufacture a GM profile. */
        fun fromQualifiedCluster(profile: QualifiedClusterDisplay, id: SecondaryOutputId,
            epoch: Long): SecondaryRenderTarget = SecondaryRenderTarget(id, epoch, profile.displayId,
                profile.host.surfaceSize, profile.effectiveOutput, profile.safe)
    }
}
