package com.openautolink.carplaylab.metadata

import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/** Sourced subset; null means not reported, never inferred from video/audio or app identity. */
data class CarPlayMediaMetadata(val title: String? = null, val artist: String? = null, val album: String? = null, val durationMillis: Long? = null, val elapsedMillis: Long? = null, val playing: Boolean? = null, val artworkTransferId: Int? = null, val playbackStatus: Int? = null)

/** Display pair sourced together from iAP2, independent of optional raw meters. */
data class CarPlayDisplayDistance(val value: String, val unit: String)

/** Raw accessory-navigation vocabulary, not Android Auto enums. Geometry keeps signed degrees. */
data class CarPlayNavigationMetadata(val maneuverType: Int, val drivingSide: Int?, val distanceMeters: Int?, val road: String?, val remainingSeconds: Long?, val remainingMeters: Int?, val junctionType: Int? = null, val junctionExitAngleDegrees: Int? = null, val junctionAnglesDegrees: List<Int>? = null, val displayDistance: CarPlayDisplayDistance? = null, val remainingDisplayDistance: CarPlayDisplayDistance? = null)
/** Route identity is independent of whether the selected instruction has arrived. */
data class CarPlayRouteMetadata(val epoch: Long, val instruction: CarPlayNavigationMetadata?)

class CarPlayMetadataIntake(private val elapsedMs: () -> Long) {
    private var routeEpoch = 0L
    val routeMetadata: CarPlayRouteMetadata? @Synchronized get() =
        if (route) CarPlayRouteMetadata(routeEpoch, navigation) else null
    /** Numeric finite receipts only; callback failure cannot change parsing/publication. */
    var onDiagnostic: (String) -> Unit = {}
    private var parsedRoutes = 0L
    private var failedRoutes = 0L
    private var parsedManeuvers = 0L
    private var failedManeuvers = 0L
    private var lastReceiptMs: Long? = null
    private fun observe(kind: String, result: String, before: CarPlayNavigationMetadata?,
        state: String = "unknown", current: String = "absent", reason: String = "none") {
        val transition = when {
            before == null && navigation != null -> "start"
            before != null && navigation == null -> "clear"
            before != navigation -> "change"
            else -> "none"
        }
        val now = elapsedMs()
        val parsed = if (kind == "maneuver") parsedManeuvers else parsedRoutes
        val failed = if (kind == "maneuver") failedManeuvers else failedRoutes
        val firstResult = if (result == "parsed") parsed == 1L else failed == 1L
        if (transition == "none" && !firstResult && lastReceiptMs?.let { now - it < 5000 } == true) return
        lastReceiptMs = now
        runCatching { onDiagnostic("carplay-nav kind=$kind parsed=$parsed failed=$failed result=$result " +
            "state=$state current=$current cached=${index != null && maneuvers.containsKey(index)} " +
            "publishable=${navigation != null} transition=$transition reason=$reason")
            // Same receipt gate; opaque local route epoch, never maneuver IDs/roads.
            onDiagnostic("carplay-nav-cache epoch=$routeEpoch size=${maneuvers.size} " +
                "selected=${selectedManeuver != null} inserts=$cacheInserts replacements=$cacheReplacements " +
                "evictions=$cacheEvictions eviction=${if (cacheEvictions == 0L) "none" else "oldest"}") }
    }
    private var cover: ByteArray? = null
    val artwork: ByteArray? @Synchronized get() = cover?.copyOf()
    @Synchronized fun offerArtwork(id: Int, bytes: ByteArray) {
        if (!authenticated || retired || id !in 0..255 || media?.artworkTransferId != id) return
        cover = bytes.takeIf { it.isNotEmpty() && it.size <= 2 * 1024 * 1024 }?.copyOf()
    }
    private data class Maneuver(val type: Int, val side: Int?, val road: String?, val junction: Int?, val exitAngle: Int?, val angles: List<Int>?)
    private val maneuvers = LinkedHashMap<Int, Maneuver>()
    // Representative 24A446 sender advances its definition window before the
    // current-ID delta and does not resend existing keys. TLV0's 42 is a
    // component ID, not a prefetch count; IDs remain opaque (no modulo guesses).
    // Keep only the displayed definition outside that eight-entry FIFO window.
    private var selectedManeuver: Maneuver? = null
    private var cacheInserts = 0L
    private var cacheReplacements = 0L
    private var cacheEvictions = 0L
    private var route = false
    // Local ownership expiry is not a sender dictionary reset. Retained exact
    // definitions grant no ownership until one fresh ACTIVE/current admission.
    private var requiresActiveSelection = false
    private var index: Int? = null
    private data class Estimate<T>(val value: T, val receivedMs: Long)
    private var distance: Estimate<Int>? = null
    private var displayDistance: Estimate<CarPlayDisplayDistance>? = null
    private var remainingDisplayDistance: Estimate<CarPlayDisplayDistance>? = null
    private var road: Estimate<String>? = null
    private var seconds: Estimate<Long>? = null
    private var meters: Estimate<Int>? = null
    private var lastRoute: Long? = null
    var navigation: CarPlayNavigationMetadata? = null
        private set
    private companion object {
        // Local estimate freshness, not an Apple instruction/selection timeout.
        const val ESTIMATE_STALE_MS = 30_000L
        // Local finite ownership grace, not an Apple protocol timeout.
        const val ROUTE_EXPIRES_MS = 120_000L
    }
    @Synchronized fun tick() {
        val now = elapsedMs()
        val age = lastRoute?.let { now - it } ?: return
        val before = navigation
        if (retired || age >= ROUTE_EXPIRES_MS) {
            clearRoute(preserveDefinitions = !retired)
            observe("lifecycle", "cleared", before, reason = if (retired) "revoke" else "expired")
        } else {
            // A selected maneuver is a route-scoped definition, not a periodic
            // heartbeat. Age dynamic values independently; an omitted estimate
            // must not be renewed by another field or by an explicit current ID.
            distance = distance?.takeIf { now - it.receivedMs < ESTIMATE_STALE_MS }
            displayDistance = displayDistance?.takeIf { now - it.receivedMs < ESTIMATE_STALE_MS }
            remainingDisplayDistance = remainingDisplayDistance?.takeIf { now - it.receivedMs < ESTIMATE_STALE_MS }
            road = road?.takeIf { now - it.receivedMs < ESTIMATE_STALE_MS }
            seconds = seconds?.takeIf { now - it.receivedMs < ESTIMATE_STALE_MS }
            meters = meters?.takeIf { now - it.receivedMs < ESTIMATE_STALE_MS }
            publishRoute()
        }
    }
    private fun clearEstimates() {
        distance = null; displayDistance = null; remainingDisplayDistance = null; road = null; seconds = null; meters = null
    }
    private fun clearRoute(preserveDefinitions: Boolean = false) {
        route = false; index = null; clearEstimates()
        lastRoute = null; selectedManeuver = null; navigation = null
        requiresActiveSelection = preserveDefinitions
        if (!preserveDefinitions) {
            maneuvers.clear()
            cacheInserts = 0; cacheReplacements = 0; cacheEvictions = 0
        }
    }
    private fun publishRoute() {
        val m = selectedManeuver
        navigation = if (route && m != null && lastRoute?.let { elapsedMs() - it < ROUTE_EXPIRES_MS } == true) CarPlayNavigationMetadata(m.type,m.side,distance?.value,m.road ?: road?.value,seconds?.value,meters?.value,m.junction,m.exitAngle,m.angles,displayDistance?.value,remainingDisplayDistance?.value) else null
    }
    private fun navigationFrame(frame: Iap2Frame) {
        val b = Iap2BodyReader.of(frame)
        // accessoryd 24A446 @0x100177a28/@0x100177bf8: repeated U16 TLVs,
        // not duplicate scalars. Route current-list (13) is one packed U16 array.
        val repeated = if (frame.messageId == 0x5202) setOf(0, 10) else setOf(0)
        require(b.list().filterNot { it.id in repeated }.map { it.id }.let { it.distinct().size == it.size })
        repeated.forEach { id -> b.all(id).forEach { require(it.payload.size == 2) } }
        if (frame.messageId == 0x5202) {
            val id = b.u16(1); val type = b.u8(3); val side = b.optionalU8(8)
            val name = text(b,4,null)
            val junction = b.optionalU8(9)
            val exitAngle = b.optionalI16(11)
            val angles = b.all(10).takeIf { it.isNotEmpty() }?.map {
                val raw = it.payload // exact two-byte width checked above
                (((raw[0].toInt() and 255) shl 8) or (raw[1].toInt() and 255)).toShort().toInt()
            }?.let { java.util.Collections.unmodifiableList(it) }
            // Enforce the prior route deadline only after every field is validated.
            tick()
            if (maneuvers.containsKey(id)) cacheReplacements++ else cacheInserts++
            if (maneuvers.size >= 8 && !maneuvers.containsKey(id)) {
                maneuvers.remove(maneuvers.keys.first())
                cacheEvictions++
            }
            val definition = Maneuver(type,side,name,junction,exitAngle,angles)
            maneuvers[id] = definition
            if (id == index) selectedManeuver = definition
        } else {
            val state = b.optionalU8(1)
            val name = text(b,3,null)
            val nextDistance = b.optionalU32(10)?.also { require(it <= Int.MAX_VALUE) }?.toInt()
            val nextDisplayDistance = displayDistance(b, 11, 12)
            val nextRemainingDisplayDistance = displayDistance(b, 8, 9)
            val nextSeconds = b.optionalU64(6)?.also { require(it >= 0) }
            val nextMeters = b.optionalU32(7)?.also { require(it <= Int.MAX_VALUE) }?.toInt()
            val current = b.optionalU16List(13)?.also { require(it.size <= 8) }
            if (state != null) require(state in 0..6) // accessoryd @0x10000443c; unknown stays rejected
            // A recv may cross a deadline after the receiver's last tick. Resolve
            // omitted-field inheritance against the post-expiry state, not old data.
            tick()
            if (state == 0 || state == 2) { clearRoute(); return }
            if (requiresActiveSelection) {
                if (state != 1 || current.isNullOrEmpty()) return
                requiresActiveSelection = false
            }
            // accessoryd24A446 @0x100002c64-0x100002c8c omits TLV13
            // for an absent dictionary key; a present empty array is a distinct
            // empty TLV (_iAP2MsgAddCFU16ArrayParam @0x1000040d0).
            // Pinned DiPlay e2fd8ead BydHudRouteState:121-162 retains activeIndex
            // on omission. Never replace that protocol delta with a 30s ID TTL.
            if (current != null) {
                val selected = current.firstOrNull()
                if (selected != index || selected == null) {
                    clearEstimates()
                    selectedManeuver = maneuvers[selected]
                }
                index = selected
            }
            if (state != null || current?.isNotEmpty() == true) {
                if (!route) routeEpoch++
                route = true
            }
            val now = elapsedMs()
            if (b.has(3)) road = name?.let { Estimate(it, now) }
            if (nextDistance != null) distance = Estimate(nextDistance, now)
            // Display is a separate rounded estimate. Raw-only deltas do not refresh
            // its age or erase it; partial/invalid display updates clear the pair.
            if (b.has(11) || b.has(12))
                displayDistance = nextDisplayDistance?.let { Estimate(it, now) }
            if (b.has(8) || b.has(9))
                remainingDisplayDistance = nextRemainingDisplayDistance?.let { Estimate(it, now) }
            if (nextSeconds != null) seconds = Estimate(nextSeconds, now)
            if (nextMeters != null) meters = Estimate(nextMeters, now)
            if (route) lastRoute = now
        }
        publishRoute()
    }
    var media: CarPlayMediaMetadata? = null
        private set
    private var authenticated = false
    private var retired = false
    @Synchronized fun authenticate() { if (!retired) authenticated = true }
    @Synchronized fun handle(frame: Iap2Frame): Boolean {
        if (frame.messageId !in setOf(0x5001,0x5201,0x5202)) return false
        if (frame.messageId != 0x5001) {
            val before = navigation
            val kind = if (frame.messageId == 0x5201) "route" else "maneuver"
            val admitted = authenticated && !retired && frame.payload.size <= 16384
            val success = admitted && runCatching { navigationFrame(frame) }.isSuccess
            if (kind == "route") { if (success) parsedRoutes++ else failedRoutes++ }
            else { if (success) parsedManeuvers++ else failedManeuvers++ }
            // Diagnostic reads happen only after the unchanged parser accepted the frame.
            val body = if (success && kind == "route") runCatching { Iap2BodyReader.of(frame) }.getOrNull() else null
            val state = when (body?.optionalU8(1)) { 0 -> "stopped"; 1 -> "active"; 2 -> "arrived"; else -> "unknown" }
            val list = body?.optionalU16List(13)
            val current = when { list == null -> "absent"; list.isEmpty() -> "empty"; else -> "nonempty" }
            val reason = when {
                !success -> "none"
                state == "stopped" -> "stop"
                state == "arrived" -> "arrived"
                current == "empty" -> "empty"
                route && navigation == null -> "cache_miss"
                else -> "none"
            }
            observe(kind, if (success) "parsed" else if (admitted) "failed" else "rejected", before, state, current, reason)
            return true
        }
        if (!authenticated || retired || frame.payload.size > 16384) return true
        val next = runCatching {
            val b = Iap2BodyReader.of(frame)
            unique(b)
            val m = b.optionalGroup(0)?.also(::unique)
            val p = b.optionalGroup(1)?.also(::unique)
            val old = media ?: CarPlayMediaMetadata()
            old.copy(title = text(m,1,old.title), artist = text(m,12,old.artist), album = text(m,6,old.album),
                durationMillis = m?.optionalU32(4) ?: old.durationMillis,
                elapsedMillis = p?.optionalU32(1) ?: old.elapsedMillis,
                artworkTransferId = m?.optionalU8(26) ?: old.artworkTransferId,
                playbackStatus = p?.optionalU8(0) ?: old.playbackStatus,
                playing = if (p?.has(0) == true) when (p.u8(0)) {
                    0, 2 -> false // stopped / paused, not a Boolean wire field
                    1 -> true
                    else -> null // seeking or unknown cannot be represented by this Boolean DTO
                } else old.playing)
        }.getOrNull() ?: return true
        val trackChanged = media != null && (media?.title != next.title || media?.artist != next.artist || media?.album != next.album)
        val hasArtReference = runCatching { Iap2BodyReader.of(frame).optionalGroup(0)?.has(26) == true }.getOrDefault(false)
        val retained = if (trackChanged && !hasArtReference) next.copy(artworkTransferId = null) else next
        if (trackChanged || media?.artworkTransferId != retained.artworkTransferId) cover = null
        media = retained
        return true
    }
    @Synchronized fun revoke() {
        val before = navigation
        retired = true; authenticated = false; media = null; cover = null; clearRoute()
        if (before != null) observe("lifecycle", "cleared", before, reason = "revoke")
    }
    private fun displayDistance(b: Iap2BodyReader, valueId: Int, unitId: Int): CarPlayDisplayDistance? {
        val value = text(b, valueId, null)
        // accessoryd 24A446 sender: TLV11 UTF8 string / TLV12 U8. Its converter
        // preserves 0..4. CarPlay CPDistanceUnitsFromNSUnitLength confirms these
        // are NOT AndroidX/AA enums: km=0, mi=1, m=2, yd=3, ft=4.
        val unit = when (b.optionalU8(unitId)) {
            0 -> "kilometers"
            1 -> "miles"
            2 -> "meters"
            3 -> "yards"
            4 -> "feet"
            else -> null
        }
        if (value?.toDoubleOrNull()?.let { it.isFinite() && it >= 0 } != true || unit == null) return null
        return CarPlayDisplayDistance(value, unit)
    }
    private fun unique(b: Iap2BodyReader) { require(b.list().map { it.id }.distinct().size == b.size) }
    private fun text(b: Iap2BodyReader?, id: Int, old: String?): String? {
        if (b == null || !b.has(id)) return old
        require(b.raw(id).size <= 1024)
        return b.string(id).ifEmpty { null }
    }
}
