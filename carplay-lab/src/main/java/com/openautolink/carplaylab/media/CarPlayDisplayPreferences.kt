// SPDX-License-Identifier: GPL-3.0-only
package com.openautolink.carplaylab.media

import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig

/** Stable convenience IDs, not an exhaustive protocol size list or phone acceptance receipts. */
enum class CarPlayResolution(val id: String, val width: Int, val height: Int) {
    WVGA("800x480", 800, 480),
    QHD540("960x540", 960, 540),
    HD720("720p", 1280, 720),
    WIDE720("1920x720", 1920, 720),
    FULL_HD1080("1080p", 1920, 1080),
    PORTRAIT1080("1080x1920", 1080, 1920),
    QHD1440("1440p", 2560, 1440),
    UHD4K("4k", 3840, 2160),
    CUSTOM("custom", 0, 0);

    val label: String get() = if (this == CUSTOM) "Custom" else "$width × $height"
    /** Compatibility metadata only; never a user-facing experimental brand or phone proof. */
    @Deprecated("All profiles require exact capability and phone acceptance")
    val experimental: Boolean get() = this == QHD1440 || this == UHD4K
    companion object { val PRESETS = entries.filter { it != CUSTOM } }
}

/** Immutable next-receiver request. Exact decoder qualification and phone acceptance are separate.
 * Dimensions are positive/even for full-panel view/safe areas. No universal dimension maximum is
 * inferred: the actual decoder's exact size/rate/alignment checks qualify each candidate.
 */
data class CarPlayDisplayPreferences(
    val resolution: CarPlayResolution = CarPlayResolution.HD720,
    val fps: Int = 30,
    val widthPhysicalMm: Int = 200,
    val rightHandDrive: Boolean = false,
    val autoScaleInput: CarPlayAutoScalePolicy.Input? = null,
    val customWidth: Int = 1280,
    val customHeight: Int = 720,
    /** Conditional offer preference; AVC remains mandatory and HEVC needs its own exact receipt. */
    val preferHevc: Boolean = false,
    /** Pre-bootstrap exact receipt; downstream must consume this without re-probing. */
    val qualifiedVideoSelection: com.shilapi.xcertplay.media.CarPlayVideoSelection? = null,
    /** Already transformed once into this exact qualified canvas; never Surface padding. */
    val encodedSafeInsets: com.shilapi.xcertplay.airplay.AirPlayInsets? = null,
    /** Explicit intent: a missing Auto viewport is not a Manual request. */
    val scaleMode: CarPlayScaleReceipt.Mode = if (autoScaleInput == null)
        CarPlayScaleReceipt.Mode.MANUAL else CarPlayScaleReceipt.Mode.AUTOMATIC,
    private val frozenScale: CarPlayScaleReceipt? = null,
    /** One full-canvas next-session area only; Automatic preserves the original wire description. */
    val dockPosition: CarPlayDockPosition = CarPlayDockPosition.AUTOMATIC,
) {
    init {
        require(fps in FRAME_RATES) { "Maximum FPS must be an integer from 1 to 60" }
        require(widthPhysicalMm in PHYSICAL_WIDTHS_MM) { "Unsupported physical width" }
        require(validDimensions(width, height)) { "Dimensions must be positive even integers" }
        encodedSafeInsets?.let {
            require(listOf(it.top, it.bottom, it.left, it.right).all { edge -> edge >= 0 })
            require(it.left.toLong() + it.right < width && it.top.toLong() + it.bottom < height)
        }
    }
    val width: Int get() = if (resolution == CarPlayResolution.CUSTOM) customWidth else resolution.width
    val height: Int get() = if (resolution == CarPlayResolution.CUSTOM) customHeight else resolution.height
    val codec: String get() = "H264"
    /** A derived frozen receipt must not change preference/request equality or hash semantics. */
    private fun requestIdentity(): List<Any?> = listOf(resolution, fps, widthPhysicalMm, rightHandDrive,
        autoScaleInput, customWidth, customHeight, preferHevc, qualifiedVideoSelection, encodedSafeInsets, scaleMode)
    override fun equals(other: Any?): Boolean = this === other ||
        other is CarPlayDisplayPreferences && requestIdentity() == other.requestIdentity()
    override fun hashCode(): Int = requestIdentity().hashCode()

    /** Data-class copy may carry the old backing value; never expose it for a different context. */
    val resolvedScale: CarPlayScaleReceipt? get() = frozenScale?.takeIf {
        it.matches(width, height, widthPhysicalMm, scaleMode, autoScaleInput)
    }
    val scaleReceipt: CarPlayScaleReceipt get() = resolvedScale ?: CarPlayAutoScalePolicy.resolveReceipt(
        width, height, widthPhysicalMm, scaleMode, autoScaleInput)
    fun freezeScale(): CarPlayDisplayPreferences = copy(frozenScale = scaleReceipt)
    val physicalSize: CarPlayAutoScalePolicy.Size get() = scaleReceipt.physicalSize
    fun toAirPlayDisplay(): AirPlayDisplayConfig = physicalSize.let { size ->
        AirPlayDisplayConfig(width, height, size.widthMm, size.heightMm, fps = fps,
            safeArea = encodedSafeInsets, safeAreaDrawOutside = true,
            viewAreas = dockPosition.wireEdge?.let { edge ->
                listOf(com.shilapi.xcertplay.airplay.AirPlayViewArea(width, height, dockEdge = edge))
            })
    }
    companion object {
        /** Product ceiling, not an Apple protocol maximum or guarantee of achieved frame rate. */
        val FRAME_RATES: List<Int> = (1..60).toList()
        val COMMON_FRAME_RATES: List<Int> = listOf(30, 60)
        val QUICK_FRAME_RATES: List<Int> = listOf(30, 60, 35, 40, 45, 50, 55)
        val PHYSICAL_WIDTHS_MM: List<Int> = (100..400).toList()
        fun validDimensions(width: Int, height: Int): Boolean =
            width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0
        fun custom(width: Int, height: Int, fps: Int) = CarPlayDisplayPreferences(
            resolution = CarPlayResolution.CUSTOM, fps = fps, customWidth = width, customHeight = height)
        /** Corrupt stored values alone restore safely; interactive Save never calls this sanitizer. */
        fun fromStored(resolutionId: String?, fps: Int?, widthPhysicalMm: Int?, rightHandDrive: Boolean = false,
            customWidth: Int? = null, customHeight: Int? = null): CarPlayDisplayPreferences {
            val resolution = CarPlayResolution.entries.firstOrNull { it.id == resolutionId } ?: CarPlayResolution.HD720
            val validCustom = validDimensions(customWidth ?: 0, customHeight ?: 0)
            return CarPlayDisplayPreferences(
                if (resolution == CarPlayResolution.CUSTOM && !validCustom) CarPlayResolution.HD720 else resolution,
                fps?.takeIf { it in FRAME_RATES } ?: 30,
                widthPhysicalMm?.takeIf { it in PHYSICAL_WIDTHS_MM } ?: 200, rightHandDrive,
                customWidth = customWidth?.takeIf { validCustom } ?: 1280,
                customHeight = customHeight?.takeIf { validCustom } ?: 720)
        }
    }
}
