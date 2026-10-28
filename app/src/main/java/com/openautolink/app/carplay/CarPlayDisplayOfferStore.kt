package com.openautolink.app.carplay

import com.openautolink.carplaylab.media.CarPlayDisplayPreferences
import com.openautolink.carplaylab.media.CarPlayScaleReceipt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Memory-only observation of the last qualified local offer, never proof of phone rendering.
 * No display/device identity, preferences, connection actions or raw platform objects. */
class CarPlayDisplayOfferStore {
    enum class Phase { OFFERED, ENDED }
    data class Offer internal constructor(
        val phase: Phase,
        val mode: CarPlayScaleReceipt.Mode,
        val reason: CarPlayScaleReceipt.Reason,
        val referenceWidthMm: Int,
        val effectiveWidthMm: Int,
        val effectiveHeightMm: Int,
        val viewportWidthPx: Int,
        val viewportHeightPx: Int,
        val displayWidthPx: Int,
        val displayHeightPx: Int,
        val fittedWidthPx: Double?,
        val fittedHeightPx: Double?,
        val codedWidthPx: Int,
        val codedHeightPx: Int,
        val fps: Int,
        val xdpi: Double?,
        val ydpi: Double?,
        val logicalDensityDpi: Int?,
    )
    /** Identity is private process-local authority, not a global display owner. */
    class Lease internal constructor()
    private var owner: Lease? = null
    private var published = false
    private val mutableState = MutableStateFlow<Offer?>(null)
    val state: StateFlow<Offer?> = mutableState.asStateFlow()

    @Synchronized fun begin(): Lease {
        mutableState.value = mutableState.value?.copy(phase = Phase.ENDED)
        return Lease().also { owner = it; published = false }
    }

    @Synchronized fun publish(lease: Lease, display: CarPlayDisplayPreferences): Boolean {
        if (owner !== lease || published) return false
        val receipt = display.scaleReceipt // Context-matched frozen core decision, not requested settings.
        fun px(value: Int) = value.takeIf { it in 0..65535 } ?: 0
        fun mm(value: Int) = value.takeIf { it in 1..2000 } ?: 0
        fun finite(value: Double?, range: ClosedFloatingPointRange<Double>) =
            value?.takeIf { it.isFinite() && it in range }
        mutableState.value = Offer(Phase.OFFERED, receipt.mode, receipt.reason,
            mm(receipt.referenceWidthMm), mm(receipt.effectiveWidthMm), mm(receipt.effectiveHeightMm),
            px(receipt.viewportWidthPx), px(receipt.viewportHeightPx), px(receipt.displayWidthPx), px(receipt.displayHeightPx),
            finite(receipt.fittedWidthPx, 0.0..16384.0), finite(receipt.fittedHeightPx, 0.0..16384.0),
            px(display.width), px(display.height), display.fps.takeIf { it in 1..240 } ?: 0,
            finite(receipt.xdpi, 40.0..1000.0), finite(receipt.ydpi, 40.0..1000.0),
            receipt.logicalDensityDpi?.takeIf { it in 1..1000 })
        published = true
        return true
    }

    @Synchronized fun retire(lease: Lease) {
        if (owner !== lease) return
        owner = null
        mutableState.value = mutableState.value?.copy(phase = Phase.ENDED)
    }

    companion object { @JvmField val PROCESS = CarPlayDisplayOfferStore() }
}
