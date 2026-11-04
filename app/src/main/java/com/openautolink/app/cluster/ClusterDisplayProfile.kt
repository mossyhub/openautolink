package com.openautolink.app.cluster

/** Bounded integer pixel edges; right and bottom are exclusive. No mutable Android Rect. */
data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    init {
        require(left >= 0 && top >= 0 && right <= MAX_EDGE && bottom <= MAX_EDGE)
        require(right > left && bottom > top) { "Empty or inverted rectangle" }
    }
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    fun contains(other: PixelRect): Boolean = left <= other.left && top <= other.top &&
        right >= other.right && bottom >= other.bottom
    fun intersect(other: PixelRect): PixelRect? {
        val l = maxOf(left, other.left)
        val t = maxOf(top, other.top)
        val r = minOf(right, other.right)
        val b = minOf(bottom, other.bottom)
        return if (l < r && t < b) PixelRect(l, t, r, b) else null
    }
    companion object { const val MAX_EDGE = 32768 }
}

data class PixelSize(val width: Int, val height: Int) {
    init { require(width in 1..PixelRect.MAX_EDGE && height in 1..PixelRect.MAX_EDGE) }
    val bounds: PixelRect get() = PixelRect(0, 0, width, height)
}

enum class ClusterVehicleRole { DRIVER_CLUSTER, CENTER }

data class ClusterVehicleIdentity(val product: String, val sdk: Int, val buildFamily: String, val buildRevision: Int)

/** An explicitly resolved vehicle role, NOT a protocol channel/type or SessionInfo type. */
data class ClusterDisplayBinding(val role: ClusterVehicleRole, val displayId: Int) {
    init { require(displayId >= 0) }
}

data class ClusterHostGeometry(
    val displayId: Int,
    val surfaceSize: PixelSize,
    val visible: PixelRect,
    val stable: PixelRect,
) {
    /** Persistent route/UI must remain inside BOTH actual host areas. */
    val essentialCanvas: PixelRect? get() = visible.intersect(stable)
    /** Sourced map background is not persistent route/UI or OEM mask authority. */
    fun backgroundCanvas(profile: ClusterDisplayProfile): PixelRect? = visible.intersect(profile.output)
    init {
        require(displayId >= 0)
        require(surfaceSize.bounds.contains(visible) && surfaceSize.bounds.contains(stable))
    }
}

/** Source-backed defaults, not dynamic calibration or an OEM instrument-mask contour. */
data class ClusterDisplayProfile(
    val id: String,
    val vehicle: ClusterVehicleIdentity,
    val role: ClusterVehicleRole,
    val panel: PixelSize,
    val output: PixelRect,
    val safe: PixelRect,
    val source: String,
) {
    init {
        require(id.isNotBlank() && source.isNotBlank())
        require(panel.bounds.contains(output) && output.contains(safe))
    }
}

/** Explicit policy distinguishes retained stable-only saved intent from a sourced background layout. */
enum class ClusterLayoutCanvasPolicy { LEGACY_STABLE, VISIBLE_PROJECTION }

/** Immutable, host-bound user calibration; never relabels the sourced OEM profile. */
class ClusterValidatedLayout internal constructor(
    val sourceProfile: ClusterDisplayProfile,
    val host: ClusterHostGeometry,
    val canvas: PixelRect,
    val output: PixelRect,
    val safe: PixelRect,
    val canvasPolicy: ClusterLayoutCanvasPolicy = if (canvas == host.essentialCanvas)
        ClusterLayoutCanvasPolicy.LEGACY_STABLE else ClusterLayoutCanvasPolicy.VISIBLE_PROJECTION,
    val essentialCanvas: PixelRect = requireNotNull(host.essentialCanvas),
) {
    init {
        require(sourceProfile == ClusterDisplayProfiles.AEGEAN_ORANGE_239)
        require(sourceProfile.role == ClusterVehicleRole.DRIVER_CLUSTER)
        require(host.surfaceSize == sourceProfile.panel)
        require(essentialCanvas == host.essentialCanvas && essentialCanvas.contains(safe))
        when (canvasPolicy) {
            ClusterLayoutCanvasPolicy.LEGACY_STABLE -> require(canvas == essentialCanvas)
            ClusterLayoutCanvasPolicy.VISIBLE_PROJECTION -> require(host.backgroundCanvas(sourceProfile)?.contains(canvas) == true)
        }
        require(canvas.contains(output) && output.contains(safe))
    }
}

/** Constructed only after identity, role, actual host and safe-containment checks. */
class QualifiedClusterDisplay internal constructor(
    val profile: ClusterDisplayProfile,
    val host: ClusterHostGeometry,
    val effectiveOutput: PixelRect,
    val validatedLayout: ClusterValidatedLayout? = null,
    /** Explicit frozen Automatic policy; never masquerades as a saved Custom layout. */
    val frozenAutomaticBackground: Boolean = false,
) {
    init {
        require(host.surfaceSize == profile.panel)
        require(host.essentialCanvas?.contains(safe) == true)
        if (validatedLayout == null) {
            if (frozenAutomaticBackground) require(host.backgroundCanvas(profile)?.contains(effectiveOutput) == true)
            else require(effectiveOutput == host.backgroundCanvas(profile))
        } else {
            require(validatedLayout.sourceProfile == profile && validatedLayout.host == host)
            require(effectiveOutput == validatedLayout.output)
        }
        require(effectiveOutput.contains(safe))
    }
    val displayId: Int get() = host.displayId
    val safe: PixelRect get() = validatedLayout?.safe ?: profile.safe
}

object ClusterDisplayProfiles {
    val AEGEAN_ORANGE_239 = ClusterDisplayProfile(
        id = "aegean_orange-sdk32-VCUSR5-163-239",
        vehicle = ClusterVehicleIdentity("aegean_orange", 32, "VCUSR5-163", 239),
        role = ClusterVehicleRole.DRIVER_CLUSTER,
        panel = PixelSize(2000, 810),
        output = PixelRect(98, 65, 1904, 745),
        safe = PixelRect(622, 165, 1374, 669),
        source = "Qualified Blazer VCUSR5-163/239 OEM projection and essential-safe bounds; " +
            "oal-cluster-video/PLAN.md section 4 and evidence.md (TestA hosted bounds are separate)",
    )

    /** A sourced vehicle geometry default, not an exact-firmware eligibility allowlist. */
    fun qualify(
        vehicle: ClusterVehicleIdentity,
        bindings: List<ClusterDisplayBinding>,
        host: ClusterHostGeometry,
        validatedLayout: ClusterValidatedLayout? = null,
        frozenAutomatic: QualifiedClusterDisplay? = null,
    ): QualifiedClusterDisplay? {
        val profile = AEGEAN_ORANGE_239
        if (vehicle.product != profile.vehicle.product || host.surfaceSize != profile.panel) return null
        val binding = bindings.filter { it.role == profile.role }.singleOrNull() ?: return null
        if (binding.displayId != host.displayId || bindings.count { it.displayId == binding.displayId } != 1) return null
        val output = host.backgroundCanvas(profile) ?: return null
        if (!output.contains(profile.safe) || host.essentialCanvas?.contains(profile.safe) != true) return null
        if (frozenAutomatic != null) {
            if (validatedLayout != null || frozenAutomatic.validatedLayout != null ||
                frozenAutomatic.profile != profile || frozenAutomatic.displayId != host.displayId ||
                frozenAutomatic.host.surfaceSize != host.surfaceSize ||
                frozenAutomatic.host.essentialCanvas != host.essentialCanvas ||
                !output.contains(frozenAutomatic.effectiveOutput)) return null
            // A wider visible callback is not authority to change a negotiated phone crop.
            // Keep the exact mapping until an explicitly prepared successor has a fresh host.
            return QualifiedClusterDisplay(profile, host, frozenAutomatic.effectiveOutput,
                frozenAutomaticBackground = true)
        }
        if (validatedLayout != null) {
            // Component bounds can expand without changing the authorized drawing canvas.
            // Keep the frozen custom output/safe geometry; never clip it or fall back here.
            if (validatedLayout.sourceProfile != profile || validatedLayout.host.displayId != host.displayId ||
                validatedLayout.host.surfaceSize != host.surfaceSize ||
                validatedLayout.essentialCanvas != host.essentialCanvas ||
                when (validatedLayout.canvasPolicy) {
                    ClusterLayoutCanvasPolicy.LEGACY_STABLE -> validatedLayout.canvas != host.essentialCanvas
                    ClusterLayoutCanvasPolicy.VISIBLE_PROJECTION -> !output.contains(validatedLayout.canvas)
                }) return null
            val actualLayout = if (validatedLayout.host == host) validatedLayout else ClusterValidatedLayout(
                profile, host, validatedLayout.canvas, validatedLayout.output, validatedLayout.safe,
                validatedLayout.canvasPolicy, validatedLayout.essentialCanvas)
            return QualifiedClusterDisplay(profile, host, actualLayout.output, actualLayout)
        }
        return QualifiedClusterDisplay(profile, host, output)
    }
}
