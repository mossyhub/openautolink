package com.shilapi.xcertplay.media

import com.openautolink.carplaylab.media.CarPlayClusterVideoConfiguration
import com.shilapi.xcertplay.airplay.*
import org.junit.Assert.*
import org.junit.Test

class CarPlayClusterHevcQualificationTest {
    @Test fun highResolutionAlternateKeepsExactDimensionsAndGlobalHevcIntersection() {
        val cluster = CarPlayVideoSelection(2000,810,30,"cluster.avc","cluster.hevc")
        val config = CarPlayClusterVideoConfiguration.create(AirPlayInsets(),AirPlayInsets(10,10,120,120),
            cluster,2000,810,30)
        assertEquals(2000,config.display.widthPixels)
        assertEquals(810,config.display.heightPixels)
        // Distinct names still need an explicit platform resource receipt (added next slice).
        val bound = CarPlayVideoSelection(1920,1080,30,"main.avc","main.hevc").withCluster(config.display,cluster)
        assertNotNull(bound.clusterQualification)
    }
}
