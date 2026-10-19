package com.openautolink.carplaylab.metadata

import com.shilapi.xcertplay.iap2.message.Iap2Messages
import org.junit.Assert.*
import org.junit.Test

class CarPlayManeuverWindowTest {
    private fun CarPlayMetadataIntake.define(id: Int, type: Int = 2) {
        handle(Iap2Messages.buildRaw(0x5202) { u16(0, 42); u16(1, id); u8(3, type) })
    }
    private fun CarPlayMetadataIntake.select(id: Int) {
        handle(Iap2Messages.buildRaw(0x5201) { u8(1, 1); u16List(13, listOf(id)) })
    }

    @Test fun enteringDefinitionBeforeCurrentDeltaKeepsNextUnresentInstruction() {
        val intake = CarPlayMetadataIntake { 0L }.apply { authenticate() }
        for (id in 0..7) intake.define(id, if (id == 0) 11 else 2)
        intake.select(0)
        assertEquals(11, intake.navigation?.maneuverType)
        intake.define(8)
        assertEquals("old display survives window advance", 11, intake.navigation?.maneuverType)
        intake.select(1)
        assertEquals("sender does not resend definitions still in its window", 2, intake.navigation?.maneuverType)
        for (current in 2..32) {
            intake.define(current + 7)
            intake.select(current)
            assertEquals("successive sender windows retain the next selection", 2, intake.navigation?.maneuverType)
        }
    }
}
