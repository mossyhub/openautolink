package com.openautolink.app.carplay

import com.openautolink.app.input.GnssForwarder
import com.openautolink.app.transport.ControlMessage

class OalCarPlayGnssSnapshotAdapter(
    private val elapsedMs: () -> Long,
    private val createSource: ((ControlMessage.Gnss) -> Unit) -> GnssForwarder,
) : AutoCloseable {
    private var cache = com.openautolink.carplaylab.intake.OalNmeaCache(elapsedMs)
    private var source: GnssForwarder? = null
    private var active = false
    private var retired = false
    private var generation = 0L

    @Synchronized fun setActive(requestedActive: Boolean) {
        if (retired || requestedActive == active) return
        generation++
        active = requestedActive
        cache = com.openautolink.carplaylab.intake.OalNmeaCache(elapsedMs)
        source?.stop()
        source = null
        if (!active) return
        val ownerGeneration = generation
        val owner = createSource { message ->
            synchronized(this) {
                if (active && !retired && ownerGeneration == generation) cache.accept(message.nmea)
            }
        }
        source = owner
        try { owner.start() } catch (failure: Exception) {
            active = false; generation++; source = null
            try { owner.stop() } catch (_: Exception) { }
            throw failure // Reporter catches without exporting text.
        }
        if (!owner.isActive) { active = false; generation++; source = null; owner.stop() }
    }

    @Synchronized fun latest(requested: Set<Int>): String? =
        if (active && !retired) cache.latest(requested) else null

    @Synchronized override fun close() {
        if (retired) return
        try { setActive(false) } finally { retired = true }
    }
}
