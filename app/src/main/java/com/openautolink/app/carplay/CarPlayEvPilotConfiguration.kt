package com.openautolink.app.carplay

import com.openautolink.carplaylab.intake.OalEvRoutingModel
import kotlinx.serialization.Serializable
import java.math.BigDecimal

/** Non-secret calibration in the existing atomic CarPlay draft, never live VHAL energy. */
@Serializable
data class CarPlayEvPilotConfiguration(
    val enabled: Boolean = false,
    /** Engineering-only custom contracts retain their exact source binding. */
    val sourceMake: String = "",
    val sourceModel: String = "",
    val pilotCapacityWh: String = "",
    val chargingJson: String = "",
    val consumptionJson: String = "",
    val profileId: String = CUSTOM,
    /** Advanced manual scale; blank uses qualified actual usable, then source-reported nominal scale. */
    val modelCapacityKwh: String = "",
    /** Owner's battery-side average over the intended DC charging window, NOT peak/live power. */
    val averageChargeKw: String = "",
    val connectorChoice: String = "auto",
    val connectorDcKw: String = "",
    val connectorAcKw: String = "",
) {
    private fun validIdentity(value: String) = value.isNotBlank() && value == value.trim() &&
        value.toByteArray(Charsets.UTF_8).size <= 80 && value.none {it.isISOControl() || it.isSurrogate()} &&
        value.lowercase(java.util.Locale.ROOT) !in setOf("unknown","null","n/a")
    private fun capacity(): Int? = pilotCapacityWh.toIntOrNull()?.takeIf {
        it in 10_000..250_000 && it.toString() == pilotCapacityWh
    }
    fun validationError(): String? = when {
        !enabled -> null
        profileId == GM_SOURCE && kiloUnits(averageChargeKw,1,1000) == null ->
            "Enter this car's average battery-side DC charge power from 1 to 1000 kW."
        profileId == GM_SOURCE && modelCapacityKwh.isNotEmpty() && kiloUnits(modelCapacityKwh,10,250) == null ->
            "Enter model capacity from 10 to 250 kWh, or leave blank for actual usable capacity."
        profileId == SAVED_SHARING && averageChargeKw.isNotEmpty() && averageDcWatts(averageChargeKw) == null ->
            "Enter an average from 1 to 1000 kW."
        profileId == GM_SOURCE || profileId == GM_ROUTING || profileId == SAVED_SHARING -> null
        profileId != CUSTOM -> "Choose a supported EV pilot profile."
        !validIdentity(sourceMake) || !validIdentity(sourceModel) -> "Enter the exact vehicle source make and model in the EV pilot."
        capacity() == null -> "Enter a whole pilot calibration capacity from 10000 to 250000 Wh in the EV pilot."
        OalEvRoutingModel.parse(chargingJson,consumptionJson,capacity()!!) == null ->
            "Enter valid charging and consumption calibration contracts matching the pilot capacity."
        else -> null
    }
    /** Custom JSON remains an engineering API, not the ordinary GUI. */
    fun modelOrNull(): OalEvRoutingModel? {
        if (!enabled || profileId != CUSTOM || !validIdentity(sourceMake) || !validIdentity(sourceModel)) return null
        val wh=capacity() ?: return null
        return OalEvRoutingModel.parse(chargingJson,consumptionJson,wh)
    }
    fun acceptsGmSource(make: String?, model: String?): Boolean = make != null && model != null &&
        validIdentity(make) && validIdentity(model) &&
        make.lowercase(java.util.Locale.ROOT) in setOf("chevrolet","gmc","cadillac","buick")

    /**
     * Explicit flat-road / constant-average-power approximation, NOT an Equinox/fleet calibration.
     * Consumption is the OEM remaining-energy/range estimate, frozen once per exact link. The ten
     * identical positions deliberately assert no speed-bin meaning. No additional auxiliary or
     * elevation load is fabricated. Uniform 11/21 energy grids follow the sourced contract shapes;
     * the energy_w_per_h interpolation semantics remain unverified. Efficiency1 is intentional:
     * owner power is already battery-side, not a charger peak with guessed conversion losses.
     */
    fun sourceEstimate(currentWh: Long?, rangeKm: Float?, actualUsableWh: Long?, reportedModelScaleWh: Long? = null): OalEvRoutingModel? {
        if (!enabled || profileId != GM_SOURCE || validationError() != null) return null
        val power=kiloUnits(averageChargeKw,1,1000) ?: return null
        // This argument is model scale, independent of the separately qualified wire maximum.
        // Reported nominal/new capacity is never relabelled measured present usable.
        // An explicitly chosen advanced manual scale is retained when actual usable is absent.
        val scale = if (actualUsableWh != null) actualUsableWh.takeIf {it in 10_000L..250_000L}?.toInt()
            else if (modelCapacityKwh.isNotEmpty()) kiloUnits(modelCapacityKwh,10,250)
            else reportedModelScaleWh?.takeIf {it in 10_000L..250_000L}?.toInt()
        val wh=scale ?: return null
        val provenance = when {
            actualUsableWh != null -> OalEvRoutingModel.CapacityProvenance.SOURCE_USABLE
            modelCapacityKwh.isNotEmpty() -> OalEvRoutingModel.CapacityProvenance.MANUAL
            else -> OalEvRoutingModel.CapacityProvenance.SOURCE_REPORTED
        }
        return flatEstimate(currentWh, rangeKm, wh, power, provenance)
    }

    internal fun gmRoutingEstimate(currentWh: Long?, rangeKm: Float?, actualWh: Long?, reportedWh: Long, year: String?): OalEvRoutingModel? {
        if (!enabled || (profileId != GM_ROUTING && profileId != SAVED_SHARING)) return null
        val power = c234PilotPower(year) ?: return null
        val usable = actualWh?.takeIf { it in 10_000L..250_000L }
        if (actualWh != null && usable == null) return null
        val provenance = if (usable != null) OalEvRoutingModel.CapacityProvenance.SOURCE_USABLE
            else OalEvRoutingModel.CapacityProvenance.SOURCE_REPORTED
        return flatEstimate(currentWh, rangeKm, (usable ?: reportedWh).toInt(), power, provenance)
    }

    /** Saved Advanced estimate uses only qualified source capacity, never legacy calibration. */
    internal fun savedEstimate(currentWh: Long?, rangeKm: Float?, usableWh: Long?, reportedWh: Long?,
        automaticPowerWatts: Int): OalEvRoutingModel? {
        if (!enabled || profileId != SAVED_SHARING) return null
        val power = if (averageChargeKw.isEmpty()) automaticPowerWatts
            else averageDcWatts(averageChargeKw) ?: return null
        val scale = (usableWh ?: reportedWh)?.takeIf { it in 10_000L..250_000L } ?: return null
        val provenance = if (usableWh != null) OalEvRoutingModel.CapacityProvenance.SOURCE_USABLE
            else OalEvRoutingModel.CapacityProvenance.SOURCE_REPORTED
        return flatEstimate(currentWh, rangeKm, scale.toInt(), power, provenance)
    }

    private fun flatEstimate(currentWh: Long?, rangeKm: Float?, wh: Int, power: Int,
        provenance: OalEvRoutingModel.CapacityProvenance): OalEvRoutingModel? {
        val current=currentWh?.takeIf {it > 0 && it <= wh} ?: return null
        val range=rangeKm?.takeIf {it.isFinite() && it > 0f && it <= 65535f} ?: return null
        val consumption=current.toDouble()/(range.toDouble()*1000.0)
        if (!consumption.isFinite() || consumption !in 0.001..2.0) return null
        fun energies(divisions: Int)=(0..divisions).joinToString(",") {(wh.toLong()*it/divisions).toString()}
        val powers=List(11) {power}.joinToString(",")
        val values=List(10) {consumption}.joinToString(",")
        return OalEvRoutingModel.parse(
            """{"model_id":12582922,"vehicle_parameters":{"vehicle_energy_axis_wh":[${energies(10)}],"vehicle_charge_axis_w":[$powers],"energy_w_per_h":[${energies(20)}],"efficiency_factor":1}}""",
            """{"model_id":12582921,"vehicle_parameters":{"vehicle_auxiliary_power_w":0,"vehicle_consumption_values_wh_per_m":[$values],"vehicle_altitude_gain_consumption_wh_per_m":0,"vehicle_altitude_loss_consumption_wh_per_m":0}}""",wh,provenance)
    }
    override fun toString() = "CarPlayEvPilotConfiguration(enabled=$enabled)"
    companion object {
        const val CUSTOM="custom"
        const val GM_SOURCE="gm-source-estimate"
        const val GM_ROUTING="gm-routing-pilot"
        internal const val SAVED_SHARING = "saved-ev-sharing"
        private val kiloUnitsPattern = Regex("[1-9][0-9]{0,3}(\\.[0-9]{1,3})?")

        private fun kiloUnits(raw: String, minimum: Int, maximum: Int): Int? {
            if (!kiloUnitsPattern.matches(raw)) return null
            val decimal = BigDecimal(raw)
            if (decimal < BigDecimal(minimum) || decimal > BigDecimal(maximum)) return null
            return decimal.movePointRight(3).intValueExact()
        }

        internal fun averageDcWatts(raw: String): Int? = kiloUnits(raw, 1, 1000)
        internal fun connectorAcWatts(raw: String): Int? = kiloUnits(raw, 1, 50)

        // Chevrolet MY25 C234 ten-module calibration, high-input curve secant spanning
        // 16,110..71,600 Wh deficit: (71600-16110)*3600/(3220-1235), rounded. A pilot
        // assumption, not live power, charger rating, or a conversion of the OEM curve.
        private const val C234_AVERAGE_DC_W = 100_637
        internal const val DEFAULT_AVERAGE_DC_W = 89_395

        // MY24 small-pack AWD proxy from a published RS AWD 10–80% session:
        // 102 kW at the station / 1.141 input-to-pack energy ratio, rounded.
        // Not a VIN/trim detection, live charger limit, or validated taper curve.
        internal fun c234PilotPower(year: String?): Int? = when (year) {
            "2024" -> DEFAULT_AVERAGE_DC_W
            "2025" -> C234_AVERAGE_DC_W
            else -> null
        }
    }
}
