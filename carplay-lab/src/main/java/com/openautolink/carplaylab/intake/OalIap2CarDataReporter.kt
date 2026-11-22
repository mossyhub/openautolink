package com.openautolink.carplaylab.intake

import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.wire.Iap2Frame

data class OalCarDataCapabilities(
    val location: Boolean = false,
    val range: Boolean = false,
    val currentEnergy: Boolean = false,
    val maximumEnergy: Boolean = false,
    /** Frozen, source-qualified display label; not an accessory or energy-model identifier. */
    val vehicleDisplayName: String? = null,
    /** Bit per source-reported iAP2 connector ordinal, frozen for the link. */
    val chargingConnectorMask: Int = 0,
    val outsideTemperature: Boolean = false,
    /** Stable per-link pilot configuration; source admission is rechecked on every snapshot. */
    val evRoutingModel: OalEvRoutingModel? = null,
    val maximumRange: Boolean = false,
    val electricRangeWarning: Boolean = false,
    /** Rated connector limits, not instantaneous power or charging-model arguments. */
    val maximumDcChargePowerWatts: Int? = null,
    val maximumAcChargePowerWatts: Int? = null,
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
    // Normal OAL runtimes use the dedicated, memory-only sink. Keep the standalone observer
    // usable for library callers; only the dedicated sink acknowledges whole-group retention.
    private val evReceipt: (OalEvWireDiagnostics.Receipt) -> Boolean = { batch -> batch.lines.forEach(receipt); true },
) : AutoCloseable {
    private var authenticated = false
    private var retired = false
    private var requested = emptySet<Int>()
    private val lastLocationAttempts = mutableMapOf<Int,Long>()
    private var locationIntervals = emptyMap<Int,Long>()
    private var evActive = false
    private var evGroups = emptySet<Int>()
    private var lastEvAttempt: Long? = null
    private var transmissionCount = 0L
    private var lastEvModelReceiptMs: Long? = null
    private val lastReceipts = mutableMapOf<Pair<Int,String>,Long>()
    private var suppressed = 0L

    init {
        runCatching { receipt("carplay-car-data-offer location=${capabilities.location} range=${capabilities.range} current=${capabilities.currentEnergy} maximum=${capabilities.maximumEnergy}" +
            if (capabilities.outsideTemperature) " temperature=true" else "") }
    }

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
                evGroups = try { parseEv(frame) } catch (failure: ContractRejection) {
                    requestReceipt(frame,failure.code,failure.parameter,failure.length); emptySet()
                }
                evActive = evGroups.isNotEmpty()
                if (evActive) requestReceipt(frame,"accepted",groups=evMask())
                log(id,if (evActive) "started" else "unsupported_request",evMask())
                tick()
            }
            0xFFFC -> { requested = emptySet(); setLocationActive(false); log(id,"stopped") }
            0xFFFA -> {
                requested = emptySet()
                locationIntervals = emptyMap()
                if (!capabilities.location) {
                    setLocationActive(false); requestReceipt(frame,"disabled"); log(id,"disabled"); return true
                }
                val parsed = try { parseLocation(frame) } catch (failure: ContractRejection) {
                    requestReceipt(frame,failure.code,failure.parameter,failure.length); null
                }
                locationIntervals = parsed.orEmpty()
                requested = locationIntervals.keys
                val sentenceMask = (if (1 in requested) 1 else 0) or (if (2 in requested) 2 else 0)
                if (requested.isNotEmpty()) requestReceipt(frame,"accepted",groups=sentenceMask)
                setLocationActive(requested.isNotEmpty())
                log(id,if (locationIntervals.isNotEmpty()) "started" else "unsupported_request", sentenceMask)
                tick()
            }
        }
        return true
    }

    private fun parseLocation(frame: Iap2Frame): Map<Int,Long> {
        val body = checkedBody(frame,mapOf(0 to 4,1 to 0,2 to 0,4 to 0,0x8001 to 4,0x8002 to 4))
        val ids = body.list().map { it.id }
        val global = body.optionalU32(0)
        if (global != null && global !in 1..60_000) throw ContractRejection("interval_bounds",0,4)
        listOf(1,2).forEach { selector ->
            if (body.has(selector or 0x8000)) {
                if (!body.has(selector)) throw ContractRejection("orphan_interval",selector or 0x8000,4)
                if (body.u32(selector or 0x8000) !in 1..60_000) throw ContractRejection("interval_bounds",selector or 0x8000,4)
            }
        }
        // 250ms is the bounded local scheduling floor, not a protocol width or component ID.
        val intervals = ids.filter { it in setOf(1,2) }.associateWith {
            maxOf(global ?: 0L, body.optionalU32(it or 0x8000) ?: global ?: 1000L, 250L)
        } // PASCD intentionally unsupported.
        if (intervals.isEmpty()) throw ContractRejection("no_supported_groups")
        return intervals
    }

    private fun parseEv(frame: Iap2Frame): Set<Int> {
        // MaxRangeInfo is advertised as capability 30 and may be echoed empty by the
        // phone. Tolerate that known element without selecting a new status group.
        val widths = mapOf(3 to 0,4 to 0,11 to 0,21 to 0,30 to 0)
        val body = checkedBody(frame, if (capabilities.range && capabilities.electricRangeWarning) widths + (15 to 0) else widths)
        val ids = body.list().map { it.id }
        val supported = buildSet {
            if (capabilities.range) {
                add(3); add(11)
                if (capabilities.electricRangeWarning) add(15)
            }
            if (capabilities.outsideTemperature) add(4)
            if (capabilities.currentEnergy || capabilities.maximumEnergy) add(21)
        }
        // Retain the prior empty-start compatibility; nonempty starts select status groups,
        // not update scalars (e.g. group 21 authorizes current/max update IDs 22/23).
        val selected = if (ids.isEmpty()) supported else ids.toSet().intersect(supported)
        if (selected.isEmpty()) throw ContractRejection("no_supported_groups")
        return selected
    }

    private class ContractRejection(val code: String,val parameter: Int=0,val length: Int=0) : RuntimeException()

    private fun checkedBody(frame: Iap2Frame,widths: Map<Int,Int>): Iap2BodyReader {
        if (frame.payload.size > 128) throw ContractRejection("oversized")
        val body = try { Iap2BodyReader.of(frame) } catch (_: Exception) { throw ContractRejection("malformed") }
        val seen = mutableSetOf<Int>()
        body.list().forEach {
            if (!seen.add(it.id)) throw ContractRejection("duplicate",it.id,it.payload.size)
            val width = widths[it.id] ?: throw ContractRejection("unsupported_id",it.id,it.payload.size)
            if (width != it.payload.size) throw ContractRejection("width",it.id,it.payload.size)
        }
        return body
    }

    private fun evMask() = (if (3 in evGroups) 1 else 0) or (if (11 in evGroups) 2 else 0) or
        (if (21 in evGroups) 4 else 0) or (if (4 in evGroups) 8 else 0)

    private fun requestReceipt(frame: Iap2Frame,result: String,parameter: Int=0,length: Int=0,groups: Int=0) {
        val key = frame.messageId to "request_$result"
        val now = elapsedMs()
        val last = lastReceipts[key]
        if (last != null && now >= last && now-last < 1000) return
        lastReceipts[key] = now
        // Numeric IDs/widths only; never interval values from rejected payloads or free-text errors.
        val gga=if(frame.messageId==0xFFFA) locationIntervals[1] ?: 0 else 0
        val rmc=if(frame.messageId==0xFFFA) locationIntervals[2] ?: 0 else 0
        val line="carplay-car-data-request id=${frame.messageId} bytes=${frame.payload.size.coerceAtMost(65535)} result=$result parameter=${parameter.coerceIn(0,65535)} length=${length.coerceIn(0,129)} groups=$groups ggaMs=$gga rmcMs=$rmc"
        runCatching { receipt(line) }
    }

    @Synchronized fun pollTimeout(remainingMillis: Long): Long {
        require(remainingMillis > 0)
        if (!authenticated || retired) return remainingMillis
        val now = elapsedMs()
        fun until(last: Long?, interval: Long): Long =
            if (last == null) 1 else (interval - (now - last).coerceAtLeast(0)).coerceAtLeast(1)
        var timeout = remainingMillis
        if (requested.isNotEmpty()) locationIntervals.forEach { (selector,interval) ->
            timeout = minOf(timeout,until(lastLocationAttempts[selector],interval))
        }
        if (evActive) timeout = minOf(timeout,until(lastEvAttempt,30_000))
        return timeout
    }

    @Synchronized fun tick() {
        if (!authenticated || retired) return
        val now = elapsedMs()
        if (evActive && due(now,lastEvAttempt,30_000)) {
            lastEvAttempt = now
            val snapshot = try { latestEv() } catch (_: Exception) { null }
            // The ordinary phone start selects electric range (11), not its warning separately.
            val electricWarning = capabilities.range && capabilities.electricRangeWarning && (11 in evGroups || 15 in evGroups)
            val frame = snapshot?.let {
                OalCarDataCodec.vehicle(OalEvSnapshot(
                    it.rangeKm.takeIf { capabilities.range && (3 in evGroups || 11 in evGroups || electricWarning) },
                    it.currentChargeWh.takeIf { capabilities.currentEnergy && 21 in evGroups },
                    it.maxChargeWh.takeIf { capabilities.maximumEnergy && 21 in evGroups },
                    it.outsideTemperatureC.takeIf { capabilities.outsideTemperature && 4 in evGroups },
                    it.routingModel?.takeIf { model ->
                        capabilities.currentEnergy && 21 in evGroups && model === capabilities.evRoutingModel
                    },
                    minimumChargeWh = it.minimumChargeWh.takeIf { capabilities.maximumEnergy && 21 in evGroups },
                    chargePercentMilli = it.chargePercentMilli.takeIf { capabilities.currentEnergy && capabilities.maximumEnergy && 21 in evGroups },
                    charging = it.charging.takeIf { capabilities.currentEnergy && 21 in evGroups },
                    electricRangeWarning = it.electricRangeWarning.takeIf { electricWarning },
                    fullRangeKm = it.fullRangeKm.takeIf { capabilities.maximumRange && capabilities.range && capabilities.currentEnergy && capabilities.maximumEnergy && 21 in evGroups && (3 in evGroups || 11 in evGroups) },
                ))
            }?.let { encoded ->
                Iap2Messages.buildRaw(0xA101) {
                    Iap2BodyReader.of(encoded).list().filter {
                        when (it.id) { 3,4,11 -> it.id in evGroups; 15 -> electricWarning; 21,22,23,24,25,26,27,30 -> 21 in evGroups; else -> false }
                    }.forEach { raw(it.id,it.payload) }
                }
            }
            if (frame == null || (electricWarning && frame.payload.isEmpty())) log(0xA101,"source_absent") else transmit(frame)
        }
        if (retired || requested.isEmpty()) return
        val dueSentences = locationIntervals.filter { (selector,interval) -> due(now,lastLocationAttempts[selector],interval) }.keys
        if (dueSentences.isEmpty()) return
        dueSentences.forEach { lastLocationAttempts[it] = now }
        val raw = try { latestNmea(dueSentences) } catch (_: Exception) { null }
        // Defense at the wire boundary: providers cannot sneak unrequested or unbounded sentences in.
        val nmea = raw?.takeIf { it.length <= 264 }?.let {
            val lines = it.trimEnd('\r','\n').split("\r\n")
            if (lines.size !in 1..2) null else {
                val checked = OalNmeaCache { now }
                lines.forEach(checked::accept)
                checked.latest(dueSentences)
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
                mask or when (parameter.id) { 0 -> 1; 3 -> 2; 11 -> 4; 22 -> 8; 23 -> 16; 4 -> 32; 26 -> 64; 27 -> 128; 21 -> 256; 24 -> 512; 25 -> 1024; 30 -> 2048; else -> 0 }
            }
            log(frame.messageId,"transmitted",presence)
            // A diagnostic failure must never be reported as a transport failure or close the link.
            if (frame.messageId == 0xA101) runCatching { logEvValues(frame) }
        } catch (_: Exception) {
            close()
            log(frame.messageId,"send_failed") // No exception text; no claim of phone consumption.
        }
    }

    private fun logEvValues(frame: Iap2Frame) {
        val body = Iap2BodyReader.of(frame)
        val status = OalEvWireDiagnostics.status(body, transmissionCount) ?: return
        val now = elapsedMs()
        val previous = lastEvModelReceiptMs
        // Never throttle a rejected model batch. Retry only on the next ordinary, completed
        // model-bearing write; this decision does not schedule or authorize any protocol send.
        if (body.has(26) && body.has(27) && (previous == null || now < previous || now - previous >= 300_000)) {
            val retained = runCatching {
                val values = OalEvWireDiagnostics.model(body, transmissionCount, 26) +
                    OalEvWireDiagnostics.model(body, transmissionCount, 27)
                values.size == 8 && evReceipt(OalEvWireDiagnostics.Receipt(
                    OalEvWireDiagnostics.Kind.WRITTEN_MODEL, listOf(status) + values))
            }.getOrDefault(false)
            if (retained) {
                lastEvModelReceiptMs = now
                return
            }
        }
        evReceipt(OalEvWireDiagnostics.Receipt(OalEvWireDiagnostics.Kind.WRITTEN_STATUS, listOf(status)))
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
