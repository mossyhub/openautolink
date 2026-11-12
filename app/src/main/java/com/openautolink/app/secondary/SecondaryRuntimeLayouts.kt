package com.openautolink.app.secondary

import com.openautolink.app.cluster.PixelRect
import com.openautolink.app.cluster.PixelSize

internal object SecondaryRuntimeLayouts {
    fun target(descriptor: SecondaryDisplayDescriptor, layout: SecondaryLayoutPreference?,
        panel: PixelSize, actualSafe: PixelRect, vehicle: String, fingerprint: String,
        carPlay: Boolean = false, safeOnly: Boolean = false): SecondaryRenderTarget {
        fun rect(value: PixelRect) = "${value.left},${value.top},${value.right},${value.bottom}"
        fun report(mode: String, fallback: String, target: SecondaryRenderTarget) {
            runCatching { com.openautolink.app.diagnostics.DiagnosticLog.i("secondary",
                "OPTIONAL_LAYOUT_V1 physical=${descriptor.displayId} epoch=${descriptor.attachmentEpoch} " +
                    "protocol=${if (carPlay) "carplay" else "aa"} mode=$mode fallback=$fallback " +
                    "panel=${panel.width}x${panel.height} actualSafe=${rect(actualSafe)} " +
                    "output=${rect(target.output)} safe=${rect(target.safe)}" +
                    if (carPlay) " fitted=${rect(SecondaryVideoMapping.carPlayDestination(target.output))}" else "") }
        }
        fun automatic(reason: String): SecondaryRenderTarget {
            val safe = if (carPlay) {
                val fitted = SecondaryVideoMapping.carPlayDestination(panel.bounds)
                val intersection = actualSafe.intersect(fitted)
                if (intersection == null) {
                    runCatching { com.openautolink.app.diagnostics.DiagnosticLog.w("secondary",
                        "OPTIONAL_LAYOUT_V1 physical=${descriptor.displayId} epoch=${descriptor.attachmentEpoch} " +
                            "protocol=carplay mode=rejected reason=automatic_no_safe_video_intersection " +
                            "panel=${panel.width}x${panel.height} actualSafe=${rect(actualSafe)} " +
                            "output=${rect(panel.bounds)} fitted=${rect(fitted)}") }
                }
                requireNotNull(intersection) {
                    "Automatic CarPlay safe area does not intersect fitted video"
                }
            } else if (safeOnly && SecondaryCalibratedDefaults.supports(panel.width, panel.height)) {
                PixelRect(192, 71, 1788, 660)
            } else actualSafe
            return SecondaryRenderTarget(descriptor.id, descriptor.attachmentEpoch, descriptor.displayId,
                panel, panel.bounds, safe).also { report("automatic", reason, it) }
        }
        if (layout == null) return automatic("none")
        if (layout.scope != SecondaryLayoutScope(descriptor.id, vehicle, fingerprint,
                descriptor.width, descriptor.height, descriptor.densityDpi)) return automatic("stale_scope")
        if (safeOnly && layout.directSafeAutomatic && (carPlay || layout.aaDirectSafe == null ||
                !SecondaryCalibratedDefaults.supports(panel.width, panel.height))) return automatic("safe_reset")
        fun pixels(box: SecondaryLayoutBox): PixelRect = PixelRect(
            kotlin.math.ceil(box.left * panel.width).toInt(), kotlin.math.ceil(box.top * panel.height).toInt(),
            kotlin.math.floor(box.right * panel.width).toInt(), kotlin.math.floor(box.bottom * panel.height).toInt())
        val custom = try {
            // Legacy outer data stays durable, but never overrides an automatic direct window.
            val output = if (safeOnly) panel.bounds else pixels(layout.output)
            val aaSafe = if (!carPlay && SecondaryCalibratedDefaults.supports(panel.width, panel.height)) layout.aaDirectSafe else null
            val safe = if (safeOnly) pixels(aaSafe ?: layout.directSafe ?: layout.safe) else requireNotNull(pixels(layout.safe).intersect(actualSafe))
            SecondaryRenderTarget(descriptor.id, descriptor.attachmentEpoch, descriptor.displayId, panel, output, safe)
        } catch (_: IllegalArgumentException) {
            return automatic("custom_invalid_geometry")
        }
        // Preserve valid saved intent. An invalid custom safe rectangle triggers whole-layout
        // Automatic fallback; it is not silently cropped or written back into preferences.
        if (carPlay) {
            val fitted = try { SecondaryVideoMapping.carPlayDestination(custom.output) }
            catch (_: IllegalArgumentException) { return automatic("custom_empty_fitted_video") }
            if (!fitted.contains(custom.safe)) return automatic("custom_outside_fitted_video")
        }
        return custom.also { report("custom", "none", it) }
    }
}
