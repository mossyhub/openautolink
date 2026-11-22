package com.openautolink.app.carplay

import com.openautolink.carplaylab.intake.OalEvWireDiagnostics
import com.openautolink.carplaylab.intake.RuntimeCarDataLink
import com.shilapi.xcertplay.iap2.wire.Iap2Frame

internal class OalCarPlayIntakeLink(
    gpsQualified: Boolean,
    vehicle: OalCarPlayVehicleSnapshotAdapter,
    gnss: OalCarPlayGnssSnapshotAdapter,
    elapsedMs: () -> Long,
    appearance: CarPlayAppearanceBridge,
    sendNight: (Boolean) -> Boolean,
    send: (Iap2Frame) -> Unit,
    private val receipt: (String) -> Unit,
    frozenCapabilities: com.openautolink.carplaylab.intake.OalCarDataCapabilities? = null,
    restrictionPolicy: CarPlayRestrictionPolicy = CarPlayRestrictionPolicy.from(com.openautolink.app.data.CarPlaySettings()),
    private val restriction: CarPlayDrivingRestrictionBridge? = if (restrictionPolicy.enabled)
        CarPlayDrivingRestrictionBridge(com.openautolink.app.input.ProcessVehicleDataRuntime.latestVehicleData(),
            kotlinx.coroutines.Dispatchers.IO, elapsedMs, 15_000L, restrictionPolicy.alwaysInPark) else null,
    sendLimitedUI: (Boolean, () -> Boolean) -> Boolean = { _, _ -> false },
    private val evCapture: CarPlayEvLogCapture.Link = CarPlayEvLogCapture.Link(),
) : RuntimeCarDataLink {
    private val capabilities = frozenCapabilities ?: vehicle.capabilities(gpsQualified, receipt)
    private val gps = gnss
    private val appearanceBridge = appearance
    private val ready = kotlinx.coroutines.flow.MutableStateFlow(false)
    private val retired = java.util.concurrent.atomic.AtomicBoolean()
    private val evOfferReported = java.util.concurrent.atomic.AtomicBoolean()
    @Volatile private var authenticated = false
    private var appearanceTransmissions = 0L // appearance IO collector only
    private val appearanceOwner = kotlinx.coroutines.runBlocking {
        appearance.replace(ready) { night ->
            if (retired.get() || !authenticated || !ready.value) false
            else {
                val delivered = sendNight(night)
                if (delivered && appearanceTransmissions < Long.MAX_VALUE) appearanceTransmissions++
                runCatching { receipt("carplay-appearance reason=${if (delivered) "transmitted" else "send_failed"} count=$appearanceTransmissions") }
                delivered
            }
        }
    }
    private val restrictionOwner = kotlinx.coroutines.runBlocking {
        restriction?.replace(ready, { limited ->
            !retired.get() && authenticated && ready.value && sendLimitedUI(limited) {
                !retired.get() && authenticated && ready.value && restriction?.permitsUnlock() == true
            }
        }, receipt)
    }
    private val reporter = com.openautolink.carplaylab.intake.OalIap2CarDataReporter(
        capabilities, elapsedMs, gnss::latest, { vehicle.snapshot(receipt) }, gnss::setActive,
        { frame -> check(!retired.get()); send(frame) }, receipt, evReceipt = evCapture::record,
    )
    override fun identification(base: Iap2Frame): Iap2Frame {
        val frame = com.openautolink.carplaylab.intake.OalCarDataIdentification.apply(base, capabilities)
        if (!retired.get() && evOfferReported.compareAndSet(false, true)) runCatching {
            OalEvWireDiagnostics.preparedOffer(frame)?.let(evCapture::record)
        }
        return frame
    }
    override fun authenticate() {
        if (retired.get()) return
        reporter.authenticate()
        authenticated = true
    }
    override fun handle(frame: Iap2Frame) = if (retired.get()) frame.messageId in setOf(0xFFFA,0xFFFC,0xA100,0xA102)
        else reporter.handle(frame)
    override fun tick(encryptedEventReady: Boolean) {
        if (retired.get()) return
        ready.value = encryptedEventReady && authenticated && !retired.get()
        reporter.tick()
    }
    override fun pollTimeout(maximumMs: Long) = reporter.pollTimeout(maximumMs)
    override fun revoke() {
        retired.set(true); authenticated = false; ready.value = false
        restrictionOwner?.let { restriction?.revoke(it) }
    }
    /** Called on lifecycle/control IO, never an AirPlay listener or event-write callback. */
    override fun close() {
        revoke()
        var interrupted = Thread.interrupted()
        try {
            while (true) {
                try {
                    kotlinx.coroutines.runBlocking {
                        restrictionOwner?.let { restriction?.retire(it) }
                        restriction?.close()
                        appearanceBridge.retire(appearanceOwner)
                        appearanceBridge.close()
                    }
                    break
                } catch (_: InterruptedException) {
                    // Cancellation is not retirement acknowledgement; an admitted blocking IO write
                    // still owns the gate. Retry the wait and restore caller interruption afterward.
                    interrupted = true
                    Thread.interrupted()
                }
            }
        } finally {
            try { try { reporter.close() } finally { gps.close() } }
            finally { if (interrupted) Thread.currentThread().interrupt() }
        }
    }
}
