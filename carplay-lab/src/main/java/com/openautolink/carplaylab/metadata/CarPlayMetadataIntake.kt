package com.openautolink.carplaylab.metadata

import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/** Sourced subset; null means not reported, never inferred from video/audio or app identity. */
data class CarPlayMediaMetadata(val title: String? = null, val artist: String? = null, val album: String? = null, val durationMillis: Long? = null, val elapsedMillis: Long? = null, val playing: Boolean? = null, val artworkTransferId: Int? = null)

/** DiPlay e2fd8ead: CarPlayPlaybackStatus. Omitted fields retain; present empty strings clear. */
data class CarPlayNavigationMetadata(val maneuverType: Int, val drivingSide: Int?, val distanceMeters: Int?, val road: String?, val remainingSeconds: Long?, val remainingMeters: Int?)
class CarPlayMetadataIntake(private val elapsedMs: () -> Long) {
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
            "publishable=${navigation != null} transition=$transition reason=$reason") }
    }
    private var cover: ByteArray? = null
    val artwork: ByteArray? @Synchronized get() = cover?.copyOf()
    @Synchronized fun offerArtwork(id: Int, bytes: ByteArray) {
        if (!authenticated || retired || id !in 0..255 || media?.artworkTransferId != id) return
        cover = bytes.takeIf { it.isNotEmpty() && it.size <= 2 * 1024 * 1024 }?.copyOf()
    }
    private data class Maneuver(val type: Int, val side: Int?, val road: String?)
    private val maneuvers = LinkedHashMap<Int, Maneuver>()
    private var route = false
    private var index: Int? = null
    private var distance: Int? = null
    private var road: String? = null
    private var seconds: Long? = null
    private var meters: Int? = null
    private var lastRoute: Long? = null
    var navigation: CarPlayNavigationMetadata? = null
        private set
    @Synchronized fun tick() {
        if (retired || lastRoute?.let { elapsedMs() - it >= 30000 } == true) {
            val before = navigation
            clearRoute()
            if (before != null) observe("lifecycle", "cleared", before, reason = if (retired) "revoke" else "stale")
        }
    }
    private fun clearRoute() {
        route = false; index = null; distance = null; road = null; seconds = null; meters = null
        lastRoute = null; maneuvers.clear(); navigation = null
    }
    private fun publishRoute() {
        val m = maneuvers[index]
        navigation = if (route && m != null) CarPlayNavigationMetadata(m.type,m.side,distance,m.road ?: road,seconds,meters) else null
    }
    private fun navigationFrame(frame: Iap2Frame) {
        val b = Iap2BodyReader.of(frame).also(::unique)
        if (frame.messageId == 0x5202) {
            val id = b.u16(1); val type = b.u8(3); val side = b.optionalU8(8)
            val name = text(b,4,null)
            if (maneuvers.size >= 8 && !maneuvers.containsKey(id)) maneuvers.remove(maneuvers.keys.first())
            maneuvers[id] = Maneuver(type,side,name)
        } else {
            val state = b.optionalU8(1)
            val name = text(b,3,road)
            val nextDistance = b.optionalU32(10)?.takeIf { it <= Int.MAX_VALUE }?.toInt() ?: distance
            val nextSeconds = b.optionalU64(6)?.also { require(it >= 0) } ?: seconds
            val nextMeters = b.optionalU32(7)?.takeIf { it <= Int.MAX_VALUE }?.toInt() ?: meters
            val current = b.optionalU16List(13)?.also { require(it.size <= 8) }
            if (state == 0 || state == 2) { clearRoute(); return }
            if (state != null) require(state == 1) // other nonterminal meanings not established by pinned source
            if (current != null) index = current.firstOrNull()
            if (state != null || current?.isNotEmpty() == true) route = true
            road = name; distance = nextDistance; seconds = nextSeconds; meters = nextMeters
            lastRoute = elapsedMs()
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
                playing = p?.optionalU8(0)?.let { require(it in 0..1); it == 1 } ?: old.playing)
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
    private fun unique(b: Iap2BodyReader) { require(b.list().map { it.id }.distinct().size == b.size) }
    private fun text(b: Iap2BodyReader?, id: Int, old: String?): String? {
        if (b == null || !b.has(id)) return old
        require(b.raw(id).size <= 1024)
        return b.string(id).ifEmpty { null }
    }
}
