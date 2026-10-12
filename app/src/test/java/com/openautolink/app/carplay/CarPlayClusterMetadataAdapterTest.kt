package com.openautolink.app.carplay

import com.openautolink.carplaylab.metadata.CarPlayNavigationMetadata
import com.openautolink.app.navigation.ManeuverType
import org.junit.Assert.*
import org.junit.Test

class CarPlayClusterMetadataAdapterTest {
    @Test fun sameWireManeuverWorksWithoutAnAppleMapsPackageFilter() {
        val result = CarPlayClusterMetadataAdapter.maneuver(CarPlayNavigationMetadata(2,0,350,"Road",120,1500))
        assertEquals(ManeuverType.TURN_RIGHT,result?.type)
        assertEquals(350,result?.distanceMeters)
        assertEquals(120L,result?.timeToArrivalSeconds)
        assertNull(result?.navImageBase64)
    }
    @Test fun unknownDistanceOrTimingDoesNotSynthesizeClusterEstimates() {
        val value = CarPlayClusterMetadataAdapter.maneuver(CarPlayNavigationMetadata(2,0,null,"Road",null,null))
        assertNotNull(value)
        assertNull(value?.distanceMeters)
        assertNull(value?.etaSeconds)
    }
}
