package com.openautolink.app.drivecard

import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import com.openautolink.app.cluster.ClusterNavigationState
import com.openautolink.app.input.ProcessVehicleDataRuntime
import com.openautolink.app.secondary.SecondaryHostGeometry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

/** An admitted Activity's transparent, noninteractive drawing. Does not initialize vehicle/nav
 * runtimes, replace their consumers, acquire AndroidX navigation, or retain any background work. */
internal class DriveCardView(
    context: Context,
    private val geometry: SecondaryHostGeometry,
    private val carPlay: Boolean,
    private val current: () -> Boolean,
    private val expired: () -> Unit,
    initialLayout: DriveCardLayout? = null,
) : View(context) {
    private val model = DriveCardModel()
    private val renderer = DriveCardRenderer(DriveCardTypeface.load(context))
    var layout: DriveCardLayout = initialLayout ?: DriveCardLayout.DEFAULT
        private set
    private var unitPreference = "auto"
    private var vehicle: com.openautolink.app.transport.ControlMessage.VehicleData? = null
    internal fun isCurrentCard() = scope != null && current()
    internal fun updatePreviewLayout(value: DriveCardLayout) { layout = value; invalidate() }
    private var scope: CoroutineScope? = null
    private var state = DriveCardState()

    init {
        tag = TAG
        isClickable = false; isFocusable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
    }
    fun start() {
        if (scope != null || !current()) return
        val owned = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = owned
        owned.launch {
            ProcessVehicleDataRuntime.latestVehicleData()?.collect { value ->
                if (!current()) { expired(); return@collect }
                vehicle = value.takeIf { ProcessVehicleDataRuntime.isActive() }
                if (ProcessVehicleDataRuntime.isActive()) model.update(value,SystemClock.elapsedRealtime()) else model.clear()
                refresh()
            }
        }
        owned.launch {
            // ManeuverState merges partial updates and has no per-field receipt timestamp.
            // A road/turn update must NOT re-age cached route distance or remaining time.
            // Require an observed change of EACH energy input after the initial cached replay;
            // expire from the older component, conservatively omitting ambiguous estimates.
            var initialized = false
            var previous: com.openautolink.app.navigation.ManeuverState? = null
            var distanceAt: Long? = null
            var remainingAt: Long? = null
            var validity = ClusterNavigationState.terminalValidity.value
            // One route consumer observes terminal-only epochs too. Keeping a separate
            // clearing collector races new-route receipts and used to erase vehicle history.
            combine(ClusterNavigationState.state, ClusterNavigationState.terminalValidity) { _, _ -> Unit }.collect {
                if (!current()) { expired(); return@collect }
                val nav = ClusterNavigationState.state.value
                val now = SystemClock.elapsedRealtime()
                val nextValidity = ClusterNavigationState.terminalValidity.value
                val boundary = nextValidity != validity
                val replaced = initialized && previous != null && previous?.destination != nav?.destination
                if (boundary || replaced) {
                    model.route(null); distanceAt = null; remainingAt = null
                    initialized = false
                }
                if (initialized && nav != null) {
                    if (nav.destDistanceMeters != previous?.destDistanceMeters) distanceAt = now
                    if (nav.timeToArrivalSeconds != previous?.timeToArrivalSeconds) remainingAt = now
                }
                if (nav == null || !ClusterNavigationState.isActive) {
                    distanceAt = null; remainingAt = null
                }
                val distanceReceipt = distanceAt
                val timeReceipt = remainingAt
                model.route(nav?.takeIf { ClusterNavigationState.isActive && distanceReceipt != null && timeReceipt != null }?.let {
                    DriveCardRoute(it.destination?.takeIf(String::isNotBlank) ?: "active", it.destDistanceMeters,
                        it.timeToArrivalSeconds, minOf(requireNotNull(distanceReceipt), requireNotNull(timeReceipt)))
                })
                // A null terminal emission cannot prime fresh receipts. Otherwise an immediate
                // restore of the cached route looks like two changed inputs relative to null.
                previous = nav; initialized = nav != null; validity = nextValidity
                refresh()
            }
        }
        owned.launch {
            ClusterNavigationState.vehicleEnergyForecast.collect { model.forecast(it,carPlay); refresh() }
        }
        owned.launch {
            while (isActive) {
                delay(1000)
                if (!current()) { expired(); return@launch }
                if (!ProcessVehicleDataRuntime.isActive()) model.clear()
                refresh()
            }
        }
        owned.launch {
            com.openautolink.app.data.AppPreferences.getInstance(context).distanceUnits.collect {
                unitPreference = it; invalidate()
            }
        }
        refresh()
    }
    private fun refresh() { state = model.snapshot(SystemClock.elapsedRealtime()); invalidate() }
    fun stop() {
        DriveCardPreview.unregister(this)
        vehicle = null
        scope?.cancel(); scope = null
        model.clear(); state = DriveCardState(); invalidate()
    }
    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (scope != null && current()) renderer.draw(canvas,geometry,state,layout,
            DriveCardUnits.imperial(unitPreference,DriveCardUnits.currentVehicleUnits(
                vehicle.takeIf { ProcessVehicleDataRuntime.isActive() },SystemClock.elapsedRealtime())))
    }
    companion object { const val TAG = "oal.drivecard.admitted" }
}
