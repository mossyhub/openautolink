package com.openautolink.app.carplay

import com.openautolink.app.data.CarPlaySettings
import org.junit.Assert.*
import org.junit.Test

class CarPlayRestrictionPolicyTest {
    @Test fun savedCapabilityMakesUiReachableWithoutChangingShippingBuildDefault() {
        val off=CarPlayRestrictionPolicy.from(CarPlaySettings(),false)
        assertFalse(off.enabled);assertFalse(off.alwaysInPark)
        val overrideOnly=CarPlayRestrictionPolicy.from(CarPlaySettings(alwaysInPark=true),false)
        assertTrue(overrideOnly.enabled);assertTrue(overrideOnly.alwaysInPark)
        val real=CarPlayRestrictionPolicy.from(CarPlaySettings(drivingRestrictionsEnabled=true),false)
        assertTrue(real.enabled);assertFalse(real.alwaysInPark)
        val user=CarPlayRestrictionPolicy.from(CarPlaySettings(drivingRestrictionsEnabled=true,alwaysInPark=true),false)
        assertTrue(user.enabled);assertTrue(user.alwaysInPark)
        val bench=CarPlayRestrictionPolicy.from(CarPlaySettings(alwaysInPark=true),true)
        assertTrue(bench.enabled);assertTrue(bench.alwaysInPark)
    }
}
