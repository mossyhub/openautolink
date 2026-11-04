package com.openautolink.app.ui.settings

import org.junit.Assert.*
import org.junit.Test

class InstrumentClusterSettingsTest {
    @Test fun instrumentClusterIsPrimaryDestinationBetweenDisplayAndVideo() {
        val type = Class.forName("com.openautolink.app.ui.settings.SettingsTab")
        val titles = type.enumConstants.map { type.getMethod("getTitle").invoke(it) }
        assertEquals(listOf("Display", "Secondary displays", "Video"), titles.drop(1).take(3))
    }
}
