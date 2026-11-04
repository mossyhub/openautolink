package com.openautolink.app.transport.aasdk

import com.openautolink.app.cluster.ClusterVideoGeometry
import com.openautolink.app.cluster.ClusterDisplayProfiles
import com.openautolink.app.cluster.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class AasdkClusterVideoTest {
    @Test fun optionalOfferIsAbsentByDefault() {
        // Reflection keeps the baseline test executable before the optional API exists.
        assertNotNull("missing optional clusterOffer JNI field", AasdkSdrConfig::class.java.fields.firstOrNull { it.name == "clusterOffer" })
        assertNull(AasdkSdrConfig::class.java.getField("clusterOffer").get(AasdkSdrConfig()))
    }
}
