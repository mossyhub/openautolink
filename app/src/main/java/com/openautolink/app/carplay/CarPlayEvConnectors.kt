package com.openautolink.app.carplay

/** Connector declarations only. No influence on energy telemetry or the routing model. */
internal object CarPlayEvConnectors {
    private const val CCS1 = 1 shl 0
    private const val J1772 = 1 shl 2
    private const val FALLBACK_MASK = CCS1 or J1772
    private const val FALLBACK_DC_WATTS = 150_000
    private const val FALLBACK_AC_WATTS = 11_500
    enum class Choice(val id: String, val label: String, val mask: Int?) {
        AUTO("auto", "Automatic", null),
        NORTH_AMERICA("ccs1-j1772", "CCS1 + J1772", FALLBACK_MASK),
        CCS1_ONLY("ccs1", "CCS1 only", CCS1),
        J1772_ONLY("j1772", "J1772 only", J1772),
        EUROPE("ccs2-type2", "CCS2 + Type 2", (1 shl 1) or (1 shl 4));

        companion object {
            fun fromId(id: String): Choice? = entries.firstOrNull { it.id == id }
        }
    }

    data class Offer(val mask: Int, val dcWatts: Int?, val acWatts: Int?)

    fun resolve(reportedMask: Int, settings: CarPlayEvPilotConfiguration): Offer {
        // Keep a usable report as-is, even when it lists only one plug. Zero means none translated.
        val mask = Choice.fromId(settings.connectorChoice)?.mask ?: reportedMask.takeIf { it != 0 } ?: FALLBACK_MASK
        val dc = if ((mask and CCS1) != 0) CarPlayEvPilotConfiguration.averageDcWatts(settings.connectorDcKw)
            ?: FALLBACK_DC_WATTS else null
        val ac = if ((mask and J1772) != 0) CarPlayEvPilotConfiguration.connectorAcWatts(settings.connectorAcKw)
            ?: FALLBACK_AC_WATTS else null
        return Offer(mask, dc, ac)
    }
}
