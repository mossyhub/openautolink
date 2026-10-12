package com.openautolink.carplaylab.intake

import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.wire.Iap2Frame

data class OalCarDataCapabilities(
    val location: Boolean = false,
    val range: Boolean = false,
    val currentEnergy: Boolean = false,
    val maximumEnergy: Boolean = false,
)

/**
 * One owner per iAP2 link. All writes are synchronous under this owner's retirement lock.
 * [send] must target that exact link, complete after transport write, and be bounded by its owner.
 * It must NOT enqueue a frame for some later/latest link. Stop/close revoke before returning;
 * an already admitted synchronous write may finish before stop obtains the lock.
 * [authenticate] is called only after this link's authentication success, never identification alone.
 */
class OalIap2CarDataReporter(
    private val capabilities: OalCarDataCapabilities,
    private val elapsedMs: () -> Long,
    private val latestNmea: (Set<Int>) -> String?,
    private val latestEv: () -> OalEvSnapshot?,
    private val locationActive: (Boolean) -> Unit,
    private val send: (Iap2Frame) -> Unit,
    private val receipt: (String) -> Unit,
) : AutoCloseable {
    private var authenticated = false
    private var retired = false
    private var requested = emptySet<Int>()
    private var lastLocationAttempt: Long? = null
    private var locationInterval = 1000L
    private var evActive = false
    private var lastEvAttempt: Long? = null
    private var transmissionCount = 0L
    private val lastReceipts = mutableMapOf<Pair<Int,String>,Long>()
    private var suppressed = 0L

    @Synchronized fun authenticate() {
        if (!retired && !authenticated) { authenticated = true; log(0,"authenticated") }
    }

    @Synchronized fun handle(frame: Iap2Frame): Boolean {
        val id = frame.messageId
        if (id !in setOf(0xFFFA,0xFFFC,0xA100,0xA102)) return false
        if (!authenticated || retired) { log(id,if (retired) "retired" else "unauthenticated"); return true }
        when (id) {
            0xA102 -> { evActive = false; log(id,"stopped") }
            0xA100 -> {
                // Only empty start has a pinned source contract. Do not guess selector/interval TLVs.
                evActive = frame.payload.isEmpty() && (capabilities.range || capabilities.currentEnergy || capabilities.maximumEnergy)
                log(id,if (evActive) "started" else "unsupported_request")
                tick()
            }
            0xFFFC -> { requested = emptySet(); setLocationActive(false); log(id,"stopped") }
            0xFFFA -> {
                requested = emptySet()
                if (!capabilities.location) { log(id,"disabled"); return true }
                val parsed = try { parseLocation(frame) } catch (_: Exception) { null }
                requested = parsed.orEmpty()
                setLocationActive(requested.isNotEmpty())
                val sentenceMask = (if (1 in requested) 1 else 0) or (if (2 in requested) 2 else 0)
                log(id,if (requested.isNotEmpty()) "started" else "unsupported_request", sentenceMask)
                tick()
            }
        }
        return true
    }

    private fun parseLocation(frame: Iap2Frame): Set<Int> {
        require(frame.payload.size <= 128)
        val body = Iap2BodyReader.of(frame)
        val ids = body.list().map { it.id }
        require(ids.distinct().size == ids.size && ids.all { it in setOf(0,1,2,4,0x8001) })
        if (body.has(0)) require(body.u16(0) == 0)
        ids.filter { it in setOf(1,2,4) }.forEach { body.void(it) }
        val interval = body.optionalU32(0x8001) ?: 1000L
        require(interval in 1..60_000)
        locationInterval = interval.coerceAtLeast(1000)
        return ids.filter { it in setOf(1,2) }.toSet() // PASCD intentionally unsupported.
    }

    @Synchronized fun pollTimeout(remainingMillis: Long): Long {
        require(remainingMillis > 0)
        if (!authenticated || retired) return remainingMillis
        val now = elapsedMs()
        fun until(last: Long?, interval: Long): Long =
            if (last == null) 1 else (interval - (now - last).coerceAtLeast(0)).coerceAtLeast(1)
        var timeout = remainingMillis
        if (requested.isNotEmpty()) timeout = minOf(timeout,until(lastLocationAttempt,locationInterval))
        if (evActive) timeout = minOf(timeout,until(lastEvAttempt,30_000))
        return timeout
    }

    @Synchronized fun tick() {
        if (!authenticated || retired) return
        val now = elapsedMs()
        if (evActive && due(now,lastEvAttempt,30_000)) {
            lastEvAttempt = now
            val snapshot = try { latestEv() } catch (_: Exception) { null }
            val frame = snapshot?.let {
                OalCarDataCodec.vehicle(OalEvSnapshot(
                    it.rangeKm.takeIf { capabilities.range },
                    it.currentChargeWh.takeIf { capabilities.currentEnergy },
                    it.maxChargeWh.takeIf { capabilities.maximumEnergy },
                ))
            }
            if (frame == null) log(0xA101,"source_absent") else transmit(frame)
        }
        if (retired || requested.isEmpty() || !due(now,lastLocationAttempt,locationInterval)) return
        lastLocationAttempt = now
        val raw = try { latestNmea(requested) } catch (_: Exception) { null }
        // Defense at the wire boundary: providers cannot sneak unrequested or unbounded sentences in.
        val nmea = raw?.takeIf { it.length <= 264 }?.let {
            val lines = it.trimEnd('\r','\n').split("\r\n")
            if (lines.size !in 1..2) null else {
                val checked = OalNmeaCache { now }
                lines.forEach(checked::accept)
                checked.latest(requested)
            }
        }
        if (nmea == null) log(0xFFFB,"source_absent")
        else transmit(Iap2Messages.buildRaw(0xFFFB) { string(0,nmea) })
    }

    private fun due(now: Long, last: Long?, interval: Long) = last == null || (now >= last && now - last >= interval)

    private fun transmit(frame: Iap2Frame) {
        if (retired || !authenticated) return
        try {
            send(frame)
            transmissionCount = (transmissionCount + 1).coerceAtMost(Long.MAX_VALUE - 1)
            val presence = frame.body().asList().fold(0) { mask, parameter ->
                mask or when (parameter.id) { 0 -> 1; 3 -> 2; 11 -> 4; 22 -> 8; 23 -> 16; else -> 0 }
            }
            log(frame.messageId,"transmitted",presence)
        } catch (_: Exception) {
            close()
            log(frame.messageId,"send_failed") // No exception text; no claim of phone consumption.
        }
    }

    private fun setLocationActive(active: Boolean) {
        try { locationActive(active) } catch (_: Exception) {
            requested = emptySet(); log(0xFFFB,"source_absent")
        }
    }

    private fun log(id: Int, reason: String, fields: Int = 0) {
        val now = elapsedMs()
        val key = id to reason // Exclusively finite internal allowlists.
        val last = lastReceipts[key]
        if (last != null && now >= last && now - last < 1000) {
            suppressed = (suppressed + 1).coerceAtMost(Long.MAX_VALUE - 1); return
        }
        lastReceipts[key] = now
        val line = "carplay-car-data id=$id reason=$reason count=$transmissionCount fields=$fields suppressed=$suppressed"
        suppressed = 0
        try { receipt(line) } catch (_: Exception) { /* diagnostics never owns transport */ }
    }

    @Synchronized override fun close() {
        if (retired) return
        retired = true; authenticated = false; requested = emptySet(); evActive = false
        setLocationActive(false)
        log(0,"retired")
    }
}
