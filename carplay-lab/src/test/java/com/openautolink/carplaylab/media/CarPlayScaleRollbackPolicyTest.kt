package com.openautolink.carplaylab.media

import com.shilapi.xcertplay.airplay.AirPlayDisplaySettings
import org.junit.Assert.*
import org.junit.Test

class CarPlayScaleRollbackPolicyTest {
    @Test fun restoredProductRangeAndPersistedExtendedValuesUse550Policy() {
        assertEquals((100..400).toList(), CarPlayDisplayPreferences.PHYSICAL_WIDTHS_MM)
        assertEquals(400, AirPlayDisplaySettings.MAX_WIDTH_PHYSICAL_MM)
        for (width in listOf(401, 422, 1178, 2000)) {
            val restored = CarPlayDisplayPreferences.fromStored("custom", 60, width,
                customWidth = 3076, customHeight = 1196)
            assertEquals(200, restored.widthPhysicalMm)
            assertEquals(200, restored.physicalSize.widthMm)
            assertEquals(78, restored.physicalSize.heightMm)
        }
    }
}
