package com.openautolink.app.carplay

import android.content.Context
import com.openautolink.app.cluster.ClusterNavigationState
import com.openautolink.app.media.OalMediaSessionManager
import com.openautolink.carplaylab.metadata.CarPlayMediaMetadata
import com.openautolink.carplaylab.metadata.CarPlayNavigationMetadata
import io.mockk.*
import org.junit.Assert.*
import org.junit.Test

class OalCarPlayMetadataHostTest {
    @Test fun publicationReachesExistingClusterAndProcessManagerAndStaleCloseDoesNotEraseAA() {
        val host = OalCarPlayMetadataHost(mockk<Context>())
        val manager = mockk<OalMediaSessionManager>(relaxed = true)
        val owner = requireNotNull(host.javaClass.getDeclaredField("owner").apply { isAccessible = true }.get(host))
        host.javaClass.getDeclaredField("mediaManager").apply { isAccessible = true }.set(host, manager)
        assertTrue(MetadataHostOwnership.process.claim(owner))
        try {
            val media = CarPlayMediaMetadata(title = "Song")
            host.publish(media, CarPlayNavigationMetadata(2,0,200,"Road",100,1000), null)
            assertEquals(200, ClusterNavigationState.state.value?.distanceMeters)
            assertNull(ClusterNavigationState.state.value?.etaSeconds)
            verify { manager.updateCarPlay(owner, media, null, true) }
            host.publish(media, null, null)
            assertNull(ClusterNavigationState.state.value)
            host.close()
            val aa = com.openautolink.app.navigation.ManeuverState(com.openautolink.app.navigation.ManeuverType.STRAIGHT, 100,"","AA",5)
            ClusterNavigationState.update(aa)
            host.close()
            assertEquals(aa, ClusterNavigationState.state.value)
        } finally { host.close(); ClusterNavigationState.clear(); unmockkAll() }
    }
}
