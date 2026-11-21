package com.openautolink.app.ui.settings

import com.openautolink.carplaylab.media.CarPlayClusterContent
import org.junit.Assert.*
import org.junit.Test

class CarPlayClusterContentLabels589Test {
    @Test fun bareUrlHasAnHonestLabelWithoutChangingSavedTokensOrDefault() {
        assertEquals(listOf("MAP", "INSTRUCTION_CARD", "MAP_WITH_CARD"), CarPlayClusterContent.entries.map { it.name })
        assertEquals(CarPlayClusterContent.MAP_WITH_CARD, CarPlayClusterContent.DEFAULT)
        assertEquals("maps:/car/instrumentcluster", CarPlayClusterContent.MAP_WITH_CARD.url)
        assertEquals("Apple's cluster view", carPlayContentLabel(CarPlayClusterContent.MAP_WITH_CARD))
        assertTrue(carPlayContentDescription(CarPlayClusterContent.MAP_WITH_CARD).contains("not guaranteed"))
        assertEquals("Map only", carPlayContentLabel(CarPlayClusterContent.MAP))
        assertEquals("Turn card only", carPlayContentLabel(CarPlayClusterContent.INSTRUCTION_CARD))
    }
}
