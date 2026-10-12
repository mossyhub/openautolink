package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.body.Iap2BodyBuilder
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import kotlin.math.min
import kotlin.math.roundToLong

/** One reading of an electric vehicle's range and charge, as CarPlay's VehicleStatusUpdate carries it. */
data class VehicleStatusSnapshot(
    val rangeKm: Int,
    val rangeWarning: Boolean,
    val batteryPercent: Double,
    val currentChargeWh: Long?,
    val maxChargeWh: Long?,
    val maxRangeKm: Int,
    val charging: Boolean = false,
)

/** Supplies the vehicle's status for CarPlay; null while no reading is available. */
fun interface VehicleStatusProvider {
    fun snapshot(): VehicleStatusSnapshot?
}

/** The charging inlets the car declares, as iAP2 SupportedChargingConnectors values. */
enum class EvChargingConnectors(internal vararg val wireValues: Int) {
    CCS2_TYPE2(1, 4), // CCS2 (DC) and Mennekes / Type 2 (AC), as on European BYD models
    GB_T(5, 6), // GB/T DC and AC, as on Chinese-market models
    CCS1_J1772(0, 2), // CCS1 (DC) and J1772 (AC)
}

/**
 * The identification to send: an electric vehicle is declared only while [provider] has a battery
 * reading, so the iPhone is never told about an EV that DiPlay cannot report (adb off or not
 * approved, or a car without these properties). Reads the provider's cached value; never blocks.
 */
fun Iap2IdentificationConfig.withVehicleStatusFrom(provider: VehicleStatusProvider?): Iap2IdentificationConfig =
    if (vehicleStatusEnabled && provider?.snapshot() == null) copy(vehicleStatusEnabled = false) else this

/**
 * iAP2 vehicle plane for an electric car: identification declares the vehicle (engine type,
 * charging connectors) and which status fields it reports; the phone then subscribes with
 * StartVehicleStatusUpdates (0xA100) and the accessory answers with VehicleStatusUpdate (0xA101).
 * Parameter ids follow Apple's iAP2 message catalog.
 */
object Iap2VehicleStatus {
    const val START_VEHICLE_STATUS_UPDATES = 0xA100
    const val VEHICLE_STATUS_UPDATE = 0xA101
    const val STOP_VEHICLE_STATUS_UPDATES = 0xA102
    const val UPDATE_INTERVAL_MILLIS = 30_000L

    private const val ENGINE_TYPE_ELECTRIC = 2

    /** IdentificationInformation params 20 (VehicleInformation) and 21 (VehicleStatus). */
    fun Iap2BodyBuilder.electricVehicleComponents(name: String, connectors: EvChargingConnectors) {
        group(20) {
            u16(0, 3) // Identifier
            string(1, name) // Name
            u8(2, ENGINE_TYPE_ELECTRIC) // EngineType
            string(6, name) // DisplayName
            string(8, name) // MapsDisplayName
            for (connector in connectors.wireValues) u8(11, connector) // SupportedChargingConnectors
        }
        group(21) {
            u16(0, 4) // Identifier
            string(1, name) // Name
            void(3) // Range
            void(6) // RangeWarning
            void(11) // RangeElectric
            void(15) // RangeWarningElectric
            void(21) // ElectricChargeInfo
            void(30) // MaxRangeInfo
        }
    }

    fun update(status: VehicleStatusSnapshot): Iap2Frame = Iap2Messages.buildRaw(VEHICLE_STATUS_UPDATE) {
        u16(3, status.rangeKm.coerceIn(0, 0xffff)) // Range, km
        bool(6, status.rangeWarning) // RangeWarning
        u16(11, status.rangeKm.coerceIn(0, 0xffff)) // RangeElectric, km
        bool(15, status.rangeWarning) // RangeWarningElectric
        // Unknown energy is omitted, never represented as an empty battery or a zero capacity.
        if (status.currentChargeWh != null || status.maxChargeWh != null) u32(21, 0L) // MinBatteryCharge, Wh
        status.currentChargeWh?.let { u32(22, it.coerceAtLeast(0)) } // CurrentBatteryCharge, Wh
        status.maxChargeWh?.let { u32(23, it.coerceAtLeast(0)) } // MaxBatteryCharge, Wh
        u32(24, (status.batteryPercent.coerceIn(0.0, 100.0) * 1000).roundToLong()) // DisplayedBatteryPercentage, % x 1000
        bool(25, status.charging) // isCharging
        u16(30, status.maxRangeKm.coerceIn(0, 0xffff)) // MaxRangeElectric, km
    }
}

/** Serves the phone's vehicle-status subscription inside an iAP2 control loop. */
internal class Iap2VehicleStatusReporter(
    private val provider: VehicleStatusProvider?,
    private val onProgress: (String) -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private var active = false
    private var lastSentNanos = 0L

    /** Handles 0xA100/0xA102; returns false for any other message. */
    fun handle(frame: Iap2Frame, send: (Iap2Frame) -> Unit): Boolean = when (frame.messageId) {
        Iap2VehicleStatus.START_VEHICLE_STATUS_UPDATES -> {
            active = provider != null
            onProgress("iap2 rx=0xa100 start-vehicle-status provider=${provider != null}")
            if (active) sendNow(send)
            true
        }
        Iap2VehicleStatus.STOP_VEHICLE_STATUS_UPDATES -> {
            active = false
            onProgress("iap2 rx=0xa102 stop-vehicle-status")
            true
        }
        else -> false
    }

    /** Sends the periodic update when it is due. */
    fun tick(send: (Iap2Frame) -> Unit) {
        if (active && nanoTime() - lastSentNanos >= Iap2VehicleStatus.UPDATE_INTERVAL_MILLIS * 1_000_000) sendNow(send)
    }

    fun pollTimeout(remainingMillis: Long): Long =
        if (active) min(remainingMillis, Iap2VehicleStatus.UPDATE_INTERVAL_MILLIS) else remainingMillis

    private fun sendNow(send: (Iap2Frame) -> Unit) {
        val status = provider?.snapshot() ?: return
        send(Iap2VehicleStatus.update(status))
        lastSentNanos = nanoTime()
        onProgress("iap2 tx=0xa101 vehicle-status range=${status.rangeKm}km battery=${status.batteryPercent}% " +
            "warning=${status.rangeWarning}")
    }
}
