package com.openautolink.app.carplay

import android.content.Context
import com.openautolink.app.cluster.ClusterNavigationState
import com.openautolink.app.cluster.toOptionalDistance
import com.openautolink.carplaylab.metadata.CarPlayMetadataIntake
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test

/** Combined production intake/host boundary; not an Android host or vehicle proof. */
class CarPlayExpiryHostIntegrationTest {
    @Test fun expiryReacquiresExactDefinitionButNeverInventsDistanceOrCrossesRetiredIntake() {
        var now = 0L
        val intake = CarPlayMetadataIntake { now }.apply { authenticate() }
        val host = OalCarPlayMetadataHost(mockk<Context>())
        val owner = requireNotNull(host.javaClass.getDeclaredField("owner").apply { isAccessible = true }.get(host))
        assertTrue(MetadataHostOwnership.process.claim(owner))
        fun publish() = host.publishRoute(intake.media, intake.routeMetadata, intake.artwork)
        fun active() = Iap2Messages.buildRaw(0x5201) { u8(1,1); u16List(13,listOf(65535)) }
        try {
            intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1,65535); u8(3,2); string(4,"Exact definition") })
            intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,1); u16List(13,listOf(65535)); u32(10,350) })
            publish()
            val original = ClusterNavigationState.recoveryDemand.value
            assertNotNull(original)
            assertEquals(350, ClusterNavigationState.state.value?.distanceMeters)
            now = 120_000; intake.tick(); publish()
            assertNull(ClusterNavigationState.state.value)
            assertFalse(ClusterNavigationState.isActive)
            intake.handle(Iap2Messages.buildRaw(0x5201) { u16List(13,listOf(65535)); u32(10,100) })
            publish()
            assertNull("current-only must not readmit expired route", ClusterNavigationState.state.value)
            intake.handle(active()); publish()
            assertNotNull("exact bounded definition survives ownership expiry", intake.navigation)
            assertNotEquals(original, ClusterNavigationState.recoveryDemand.value)
            val mapped = requireNotNull(ClusterNavigationState.state.value)
            assertNull(mapped.distanceMeters)
            assertNull(mapped.timeToArrivalSeconds)
            assertNull("same required-distance guard used before host Trip publication", toOptionalDistance(mapped.distanceMeters, ClusterNavigationState.distanceUnits, mapped.displayDistance, mapped.displayDistanceUnit))
            intake.revoke(); intake.authenticate(); intake.handle(active()); publish()
            assertNull(intake.navigation)
            assertNull(ClusterNavigationState.state.value)
            val successor = CarPlayMetadataIntake { now }.apply { authenticate(); handle(active()) }
            assertNull("new authenticated intake cannot inherit retained definition", successor.navigation)
            assertNotNull(successor.routeMetadata)
        } finally { host.close(); ClusterNavigationState.clear() }
    }
}
