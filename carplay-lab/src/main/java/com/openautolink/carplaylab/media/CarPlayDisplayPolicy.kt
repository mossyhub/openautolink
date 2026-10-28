// SPDX-License-Identifier: GPL-3.0-only
package com.openautolink.carplaylab.media


/** Returns an exact AVC decoder for this size/rate, or null; capability evidence, not render proof. */
fun interface AvcDecoderProbe {
    fun decoderFor(display: CarPlayDisplayPreferences): String?
}

data class CarPlayDisplaySelection(
    val display: CarPlayDisplayPreferences,
    val decoderName: String,
    val usedFallback: Boolean,
)

data class CarPlayRenderRect(val left: Float, val top: Float, val width: Float, val height: Float)

object CarPlayDisplayPolicy {
    /** Consume the same immutable receipt selected before bootstrap. No second decoder query. */
    fun videoSelection(display: CarPlayDisplayPreferences,
        probe: com.shilapi.xcertplay.media.VideoDecoderProbe? = null): com.shilapi.xcertplay.media.CarPlayVideoSelection {
        display.qualifiedVideoSelection?.let { receipt ->
            require(receipt.width == display.width && receipt.height == display.height && receipt.fps == display.fps)
            require(display.preferHevc || !receipt.hevcOffered)
            return receipt
        }
        return com.shilapi.xcertplay.media.CarPlayVideoQualification.select(
            display.width, display.height, display.fps, display.preferHevc,
            probe ?: com.shilapi.xcertplay.media.CarPlayVideoQualification.androidProbe())
    }

    /** Numeric allowlist from the ACTUAL qualified offer; coded dimensions are not viewport pixels. */
    fun scaleDiagnostic(display: CarPlayDisplayPreferences): String {
        val receipt = display.scaleReceipt
        val size = receipt.physicalSize
        fun pixels(value: Int?) = value?.takeIf { it in 1..16384 } ?: 0
        fun dpi(value: Double?) = value?.takeIf { it.isFinite() && it in 40.0..1000.0 } ?: 0.0
        val basis = when (size.basis) {
            CarPlayAutoScalePolicy.Basis.MANUAL -> "manual"
            CarPlayAutoScalePolicy.Basis.HARDWARE_ESTIMATE -> "hardware_estimate"
            CarPlayAutoScalePolicy.Basis.CALIBRATION_NEEDED -> "calibration_needed"
        }
        return "BENCH_DIAG displayScale mode=${if (receipt.mode == CarPlayScaleReceipt.Mode.MANUAL) "manual" else "auto"} " +
            "basis=$basis viewport=${pixels(receipt.viewportWidthPx)}x${pixels(receipt.viewportHeightPx)} " +
            "display=${pixels(receipt.displayWidthPx)}x${pixels(receipt.displayHeightPx)} " +
            "xdpi=${dpi(receipt.xdpi)} ydpi=${dpi(receipt.ydpi)} " +
            "physical=${size.widthMm}x${size.heightMm} coded=${display.width}x${display.height} maxFPS=${display.fps}"
    }

    /** Versioned finite numeric export. Never interpolate a raw receipt/platform object. */
    fun scaleDiagnosticV2(display: CarPlayDisplayPreferences): String {
        val r = display.scaleReceipt
        fun pixels(v: Int) = v.takeIf { it in 0..65535 } ?: 0
        fun mm(v: Int) = v.takeIf { it in 1..2000 } ?: 0
        fun finite(v: Double?, max: Double, min: Double = 0.0): String =
            v?.takeIf { it.isFinite() && it in min..max }?.let {
                java.math.BigDecimal.valueOf(it).setScale(6, java.math.RoundingMode.HALF_UP)
                    .stripTrailingZeros().toPlainString()
            } ?: "0.0"
        return "BENCH_DIAG displayScaleV2 mode=${if (r.mode == CarPlayScaleReceipt.Mode.MANUAL) "manual" else "auto"} " +
            "reason=${r.reason.name.lowercase(java.util.Locale.ROOT)} reference=${mm(r.referenceWidthMm)} " +
            "physical=${mm(r.effectiveWidthMm)}x${mm(r.effectiveHeightMm)} " +
            "viewport=${pixels(r.viewportWidthPx)}x${pixels(r.viewportHeightPx)} " +
            "display=${pixels(r.displayWidthPx)}x${pixels(r.displayHeightPx)} " +
            "fitted=${finite(r.fittedWidthPx, 16384.0)}x${finite(r.fittedHeightPx, 16384.0)} " +
            "coded=${pixels(display.width)}x${pixels(display.height)} fps=${display.fps.takeIf { it in 1..240 } ?: 0} " +
            "xdpi=${finite(r.xdpi, 1000.0, 40.0)} ydpi=${finite(r.ydpi, 1000.0, 40.0)} " +
            "logical=${r.logicalDensityDpi?.takeIf { it in 1..1000 } ?: 0}"
    }

    /** Centered aspect-fit rectangle. UI must actually lay out video here before using this mapping. */
    fun fitRect(display: CarPlayDisplayPreferences, viewportWidth: Int, viewportHeight: Int): CarPlayRenderRect? {
        if (viewportWidth <= 0 || viewportHeight <= 0) return null
        val scale = minOf(viewportWidth.toFloat() / display.width, viewportHeight.toFloat() / display.height)
        val width = display.width * scale
        val height = display.height * scale
        return CarPlayRenderRect((viewportWidth - width) / 2, (viewportHeight - height) / 2, width, height)
    }

    /** Numeric allowlist only: never emit a raw MediaFormat, SPS or controller identifiers.
     * Decoder crop is inclusive. Negotiated output must NOT replace the advertised HID space.
     */
    fun outputDiagnostic(display: CarPlayDisplayPreferences, width: Int, height: Int,
        left: Int = 0, top: Int = 0, right: Int = width - 1, bottom: Int = height - 1): String? {
        if (width !in 1..8192 || height !in 1..8192 || left !in 0 until width || top !in 0 until height ||
            right !in left until width || bottom !in top until height) return null
        return "BENCH_DIAG videoOutput codec=H264 advertised=${display.width}x${display.height} " +
            "coded=${width}x${height} visible=${right - left + 1}x${bottom - top + 1}"
    }

    fun availableDisplays(
        preferences: CarPlayDisplayPreferences = CarPlayDisplayPreferences(),
        probe: AvcDecoderProbe = androidProbe(),
    ): List<CarPlayDisplayPreferences> = (CarPlayResolution.PRESETS +
        if (preferences.resolution == CarPlayResolution.CUSTOM) listOf(CarPlayResolution.CUSTOM) else emptyList()).flatMap { resolution ->
        CarPlayDisplayPreferences.FRAME_RATES.map { fps -> preferences.copy(resolution = resolution, fps = fps) }
    }.filter { decoder(probe, it) != null }

    fun isQualified(display: CarPlayDisplayPreferences, probe: AvcDecoderProbe = androidProbe()): Boolean =
        decoder(probe, display) != null

    fun select(
        requested: CarPlayDisplayPreferences,
        probe: AvcDecoderProbe = androidProbe(),
    ): CarPlayDisplaySelection {
        decoder(probe, requested)?.let { return CarPlayDisplaySelection(requested.freezeScale(), it, false) }
        // Only the exact baseline is a safe fallback; never silently upgrade size or rate.
        // Retain this qualification's decoder receipt rather than probing a second time.
        val fallback = requested.copy(resolution = CarPlayResolution.HD720, fps = 30)
        val decoderName = decoder(probe, fallback)
            ?: throw IllegalStateException("No supported baseline AVC display")
        return CarPlayDisplaySelection(fallback.freezeScale(), decoderName, true)
    }

    private fun decoder(probe: AvcDecoderProbe, display: CarPlayDisplayPreferences): String? {
        // Our HID descriptor/report packs each advertised axis as U16. This implementation bound
        // prevents truncation; it is NOT a recovered universal CarPlay encoder maximum.
        if (display.width > 0xffff || display.height > 0xffff) return null
        return runCatching { probe.decoderFor(display)?.takeIf { it.isNotBlank() } }.getOrNull()
    }

    /** Snapshot regular codecs; fail closed on malformed/missing platform capability data.
     * Match size AND rate and the exact AVC format. Use this exact name in the sink, never the
     * platform's potentially different createDecoderByType default. Enumeration isn't rendering.
     */
    fun androidProbe(): AvcDecoderProbe {
        val probe = com.shilapi.xcertplay.media.CarPlayVideoQualification.androidProbe()
        return AvcDecoderProbe { display ->
            probe.decoderFor(com.shilapi.xcertplay.media.VideoDecoderRequest(
                com.shilapi.xcertplay.airplay.VideoCodec.H264, display.width, display.height, display.fps))
        }
    }
}
