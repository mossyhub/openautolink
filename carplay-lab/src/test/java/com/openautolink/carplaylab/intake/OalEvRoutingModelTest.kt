package com.openautolink.carplaylab.intake

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = android.app.Application::class)
class OalEvRoutingModelTest {
    @Test fun explicitOwnerCalibrationIsValidatedAndCanonicalizedWithoutInventingCapacity() {
        val model = OalEvRoutingModel.parse(charging, consumption, 60000)!!
        assertEquals(60000, model.pilotCapacityWh)
        assertEquals(12582922, org.json.JSONObject(model.chargingJson).getInt("model_id"))
        assertEquals(12582921, org.json.JSONObject(model.consumptionJson).getInt("model_id"))
        assertFalse(model.chargingJson.contains('\n'))
        assertNull(OalEvRoutingModel.parse(charging, consumption, 85000))
        assertNull(OalEvRoutingModel.parse(charging.replace("12582922", "12582921"), consumption, 60000))
        assertNull(OalEvRoutingModel.parse(charging, consumption.replace("0.2", "-0.2"), 60000))
    }

    @Test fun malformedOutOfBoundsAndUnknownPayloadsCannotBecomeModels() {
        val badCharging=listOf(
            "{}", charging+"junk", charging.replace("12582922","\"12582922\""),
            charging.replace("12582922","12582922.0"),
            charging.replace("0,6000,12000","0,0,12000"),
            charging.replace("0.95","1.01"), charging.replace("100000","1000001"),
            charging.replace("60000]","60001]"), charging.replace("{\"model_id\"","{\"extra\":true,\"model_id\""),
            " ".repeat(4097),
        )
        badCharging.forEach {assertNull(OalEvRoutingModel.parse(it,consumption,60000))}
        val badConsumption=listOf(
            consumption.replace("400","-1"), consumption.replace("0.2","\"0.2\""),
            consumption.replace("6.4","-6.4"), consumption.replace("4.3","101"),
            consumption.replace("0.2,0.2,0.2,0.2,0.2,0.2,0.2,0.2,0.2,0.2","0.2"),
        )
        badConsumption.forEach {assertNull(OalEvRoutingModel.parse(charging,it,60000))}
        for (capacity in listOf(0,9999,250001,Int.MAX_VALUE)) assertNull(OalEvRoutingModel.parse(charging,consumption,capacity))
    }

    companion object {
        // Constructed calibration for software contracts only, not a physical vehicle profile.
        val charging = """{"model_id":12582922,"vehicle_parameters":{"vehicle_energy_axis_wh":[0,6000,12000,18000,24000,30000,36000,42000,48000,54000,60000],"vehicle_charge_axis_w":[100000,100000,100000,100000,90000,80000,70000,60000,40000,20000,10000],"energy_w_per_h":[0,3000,6000,9000,12000,15000,18000,21000,24000,27000,30000,33000,36000,39000,42000,45000,48000,51000,54000,57000,60000],"efficiency_factor":0.95}}"""
        val consumption = """{"model_id":12582921,"vehicle_parameters":{"vehicle_auxiliary_power_w":400,"vehicle_consumption_values_wh_per_m":[0.2,0.2,0.2,0.2,0.2,0.2,0.2,0.2,0.2,0.2],"vehicle_altitude_gain_consumption_wh_per_m":6.4,"vehicle_altitude_loss_consumption_wh_per_m":4.3}}"""
    }
}
