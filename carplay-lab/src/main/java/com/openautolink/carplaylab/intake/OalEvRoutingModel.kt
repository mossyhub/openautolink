package com.openautolink.carplaylab.intake

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Explicit owner calibration, NOT a sourced usable-energy receipt or a fleet-validated model.
 * Shape/IDs: ocbm-xda-upstream/CONTRACTS.md (charging 12582922/26, consumption 12582921/27).
 * Vector interpolation/index semantics remain unverified. No built-in vehicle/nominal fallback.
 * Identity equality intentionally binds snapshots to the exact frozen calibration instance.
 */
class OalEvRoutingModel private constructor(
    val pilotCapacityWh: Int,
    val chargingJson: String,
    val consumptionJson: String,
    val capacityProvenance: CapacityProvenance,
) {
    enum class CapacityProvenance { MANUAL, SOURCE_USABLE, SOURCE_REPORTED }

    /** A reported calibration axis is frozen, not the current source-qualified maximum. */
    fun acceptsCurrentCharge(currentWh: Long, sourceMaximumWh: Long?): Boolean {
        if (currentWh < 0 || (sourceMaximumWh != null && currentWh > sourceMaximumWh)) return false
        return currentWh <= pilotCapacityWh ||
            (capacityProvenance == CapacityProvenance.SOURCE_REPORTED && sourceMaximumWh != null)
    }

    override fun toString() = "OalEvRoutingModel(ownerCalibration)"

    companion object {
        const val MAX_JSON_BYTES = 4096
        fun parse(charging: String, consumption: String, pilotCapacityWh: Int,
            capacityProvenance: CapacityProvenance = CapacityProvenance.MANUAL): OalEvRoutingModel? = try {
            require(pilotCapacityWh in 10_000..250_000)
            fun contract(raw: String, id: Int, keys: Set<String>): JSONObject {
                require(raw.length in 2..MAX_JSON_BYTES && raw.toByteArray(Charsets.UTF_8).size <= MAX_JSON_BYTES)
                val tokener = JSONTokener(raw)
                val root = tokener.nextValue() as? JSONObject ?: error("shape")
                require(tokener.nextClean() == '\u0000')
                require(root.keys().asSequence().toSet() == setOf("model_id", "vehicle_parameters"))
                val modelId = root.get("model_id")
                require((modelId is Int || modelId is Long) && (modelId as Number).toLong() == id.toLong())
                val parameters = root.getJSONObject("vehicle_parameters")
                require(parameters.keys().asSequence().toSet() == keys)
                return parameters
            }
            fun number(value: Any, min: Double, max: Double): Double {
                require(value is Number)
                val n = value.toDouble()
                require(n.isFinite() && n in min..max)
                return n
            }
            fun vector(p: JSONObject, key: String, size: Int, min: Double, max: Double, increasing: Boolean = false): JSONArray {
                val a = p.getJSONArray(key)
                require(a.length() == size)
                val values = (0 until size).map { number(a.get(it), min, max) }
                if (increasing) require(values.zipWithNext().all { (a,b) -> b > a })
                return JSONArray(values)
            }
            val c = contract(charging, 12582922, setOf("vehicle_energy_axis_wh", "vehicle_charge_axis_w", "energy_w_per_h", "efficiency_factor"))
            val energy = vector(c,"vehicle_energy_axis_wh",11,0.0,pilotCapacityWh.toDouble(),true)
            require(energy.getDouble(0) == 0.0 && energy.getDouble(10) == pilotCapacityWh.toDouble())
            val steps = vector(c,"energy_w_per_h",21,0.0,pilotCapacityWh.toDouble(),true)
            require(steps.getDouble(0) == 0.0)
            val chargingParameters = JSONObject()
                .put("vehicle_energy_axis_wh",energy)
                .put("vehicle_charge_axis_w",vector(c,"vehicle_charge_axis_w",11,1.0,1_000_000.0))
                .put("energy_w_per_h",steps)
                .put("efficiency_factor",number(c.get("efficiency_factor"),0.1,1.0))
            val p = contract(consumption,12582921,setOf("vehicle_auxiliary_power_w", "vehicle_consumption_values_wh_per_m", "vehicle_altitude_gain_consumption_wh_per_m", "vehicle_altitude_loss_consumption_wh_per_m"))
            val consumptionParameters = JSONObject()
                .put("vehicle_auxiliary_power_w",number(p.get("vehicle_auxiliary_power_w"),0.0,20_000.0))
                .put("vehicle_consumption_values_wh_per_m",vector(p,"vehicle_consumption_values_wh_per_m",10,0.001,2.0))
                .put("vehicle_altitude_gain_consumption_wh_per_m",number(p.get("vehicle_altitude_gain_consumption_wh_per_m"),0.0,100.0))
                .put("vehicle_altitude_loss_consumption_wh_per_m",number(p.get("vehicle_altitude_loss_consumption_wh_per_m"),0.0,100.0))
            fun encode(id: Int, p: JSONObject) = JSONObject().put("model_id",id).put("vehicle_parameters",p).toString()
            OalEvRoutingModel(pilotCapacityWh,encode(12582922,chargingParameters),encode(12582921,consumptionParameters),capacityProvenance)
        } catch (_: Exception) { null } // Never log a supplied profile or exception text.
    }
}
