package com.openautolink.app.data

import com.openautolink.app.cluster.ClusterOutputSelection
import com.openautolink.app.secondary.*
import org.junit.Assert.*
import org.junit.Test

class DriveCardPreferenceTest {
    private val legacy = ClusterOutputPreferences(ClusterOutputSelection(), null)
    private fun raw(extra: String = "") = """{"version":1,"revision":1,"outputs":{"driver":{"enabled":true,"aaRole":null,"androidAuto":false,"carPlay":false,"layout":null$extra}}}"""

    @Test fun explicitDriveCardOptInSurvivesColdSerialization() {
        val input = raw(",\"driveCardEnabled\":true")
        val saved = SecondaryDisplayPreferences.read(input, legacy)
        assertNull("Drive Card is valid explicit per-output intent", saved.error)
        val cold = SecondaryDisplayPreferences.read(SecondaryDisplayPreferences.encode(saved), legacy)
        assertEquals(input, SecondaryDisplayPreferences.encode(cold))
    }

    @Test fun absentCardLeavesLegacyRecordByteIdentical() {
        val input = raw()
        assertEquals(input, SecondaryDisplayPreferences.encode(SecondaryDisplayPreferences.read(input, legacy)))
    }

    @Test fun customCardLayoutRoundTripsWithoutChangingDirectionsOrOptIn() {
        val id=SecondaryOutputId("driver")
        val card=com.openautolink.app.drivecard.DriveCardLayout.DEFAULT.move(-.02,-.01)
        val input=SecondaryPreferencesSnapshot(2,mapOf(id to SecondaryOutputPreference(driveCardLayout=card)),legacy)
        val cold=SecondaryDisplayPreferences.read(SecondaryDisplayPreferences.encode(input),legacy)
        assertNull(cold.error);assertEquals(card,cold.outputs.getValue(id).driveCardLayout)
        assertFalse(cold.outputs.getValue(id).driveCardEnabled);assertNull(cold.outputs.getValue(id).layout)
        assertEquals(legacy,cold.legacyCluster)
    }

    @Test fun malformedLayoutFailsClosedWithoutInventingDefaultCustomRecord() {
        for(value in listOf("true","{}","{\"left\":0,\"top\":0,\"right\":1,\"bottom\":1,\"slant\":0.2}"))
            assertNotNull(SecondaryDisplayPreferences.read(raw(",\"driveCardLayout\":$value"),legacy).error)
        val absent=SecondaryDisplayPreferences.read(raw(),legacy)
        assertNull(absent.outputs.getValue(SecondaryOutputId("driver")).driveCardLayout)
    }

    @Test fun malformedCardDoesNotAuthorizeAnyOutput() {
        for (value in listOf("null", "1", "\"true\"", "{}")) {
            assertNotNull(SecondaryDisplayPreferences.read(raw(",\"driveCardEnabled\":$value"), legacy).error)
        }
    }
}
