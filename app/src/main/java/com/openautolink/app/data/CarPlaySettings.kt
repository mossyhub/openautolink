package com.openautolink.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import com.openautolink.carplaylab.media.CarPlayDockPosition

/** One atomic, non-secret next-session configuration. Never a connection/admission lease. */
@Serializable
data class CarPlaySettings(
    val enabled: Boolean = true,
    /** Independent normal CarPlay transport. Missing/invalid values are wired, never wireless opt-in. */
    val connectionMode: String = "usb",
    /** Explicit next-session ordinary CarPlay limited-UI capability; no shipping build opt-in needed. */
    val drivingRestrictionsEnabled: Boolean = false,
    /** Parked-use override only. Never changes raw VHAL gear, Android Auto, or parked video. */
    val alwaysInPark: Boolean = false,
    val phoneAddress: String = "",
    val peerAddress: String = "",
    val interfaceName: String = "ap_br_swlan0",
    val sharedWifiOverride: Boolean = false,
    val ssid: String = "",
    val receiverAddress: String = "",
    val resolution: String = "1440p",
    val fps: Int = 30,
    /** Source-backed CarPlay UI scale, independent of Android Surface size. */
    val widthPhysicalMm: Int = 200,
    /** Next-session viewport-based physical-size estimate; false retains exact manual width. */
    val autoScale: Boolean = true,
    /** AP auto uses scoped IPv6; shared Wi-Fi auto retains IPv4. */
    val networkFamily: String = "auto",
    val localAddress: String = "",
    /** Independent of Android Auto WPP; describes an existing AP, never creates one. */
    val apFrequencyMhz: Int = 5180,
    val apSecurity: String = "wpa2",
    /** Used when resolution == custom; presets remain backwards-compatible convenience IDs. */
    val widthPixels: Int = 1280,
    val heightPixels: Int = 720,
    /** Separate from Android Auto codec; HEVC is preferred, never forced. */
    val videoCodec: String = "hevc",
    /** Independent of physical-size Auto; retain the manual resolution and FPS underneath. */
    val autoResolution: Boolean = true,
    /** Standard tiers fit the measured window by default; explicit saved false stays exact. */
    val fitToWindow: Boolean = true,
    /** Fractions of the chrome-free parent axis in 1/10000 units, never AA codec pixels. */
    val safeTop: Int = 0,
    val safeBottom: Int = 0,
    val safeLeft: Int = 0,
    val safeRight: Int = 0,
    /** Memory-only partial text; invalid drafts are retained and never silently restored at Save. */
    @kotlinx.serialization.Transient val widthDraft: String? = null,
    @kotlinx.serialization.Transient val heightDraft: String? = null,
    @kotlinx.serialization.Transient val fpsDraft: String? = null,
    /** Next-connection status-bar placement, independent of physical UI size and transport. */
    val dockPosition: String = "automatic",
    @kotlinx.serialization.Transient val physicalWidthDraft: String? = null,
) {
    /** Strict path shared by UI qualification, Save and runtime; no corrupt-storage fallback. */
    fun displayRequest(): com.openautolink.carplaylab.media.CarPlayDisplayPreferences {
        val resolution = com.openautolink.carplaylab.media.CarPlayResolution.entries.first { it.id == resolution }
        return com.openautolink.carplaylab.media.CarPlayDisplayPreferences(
            resolution = resolution, fps = fps, widthPhysicalMm = widthPhysicalMm,
            customWidth = widthPixels, customHeight = heightPixels, preferHevc = videoCodec == "hevc",
            scaleMode = if (autoScale) com.openautolink.carplaylab.media.CarPlayScaleReceipt.Mode.AUTOMATIC
                else com.openautolink.carplaylab.media.CarPlayScaleReceipt.Mode.MANUAL,
            dockPosition = CarPlayDockPosition.entries.first { it.id == dockPosition })
    }
    /** Only tier-named choices are detail intent; geometry-named presets and Custom stay exact. */
    val windowDetail: Boolean get() = fitToWindow && resolution in setOf("720p", "1080p", "1440p", "4k")
    val usesWindowGeometry: Boolean get() = autoResolution || windowDetail
    val hasSafeCalibration: Boolean get() = listOf(safeTop, safeBottom, safeLeft, safeRight).any { it != 0 }
    fun qualifiedDisplay(viewport: com.openautolink.carplaylab.media.CarPlayAutoScalePolicy.Input?,
        probe: com.shilapi.xcertplay.media.VideoDecoderProbe = com.shilapi.xcertplay.media.CarPlayVideoQualification.androidProbe()
    ): com.openautolink.carplaylab.media.CarPlayDisplayPreferences {
        val requested = displayRequest().copy(autoScaleInput = if (autoScale) viewport else null)
        val video = if (usesWindowGeometry) {
            com.shilapi.xcertplay.media.CarPlayVideoQualification.selectAutomatic(
                viewport?.viewportWidthPx ?: 0, viewport?.viewportHeightPx ?: 0,
                viewport?.displayWidthPx ?: 0, viewport?.displayHeightPx ?: 0, fps, requested.preferHevc, probe,
                detailPixels = if (autoResolution) 2560L * 1440 else requested.width.toLong() * requested.height)
        } else com.shilapi.xcertplay.media.CarPlayVideoQualification.select(
            requested.width, requested.height, requested.fps, requested.preferHevc, probe)
        val safe = if (!hasSafeCalibration) null else {
            require(listOf(safeTop, safeBottom, safeLeft, safeRight).all { it in 0..4999 })
            val parent = requireNotNull(viewport) { "Safe area needs a measured parent" }
            require(parent.viewportWidthPx in 1..16384 && parent.viewportHeightPx in 1..16384) {
                "Safe area needs a measured parent"
            }
            val pw = parent.viewportWidthPx.toDouble()
            val ph = parent.viewportHeightPx.toDouble()
            // Same centered aspect-fit used by the Surface; letterbox is not a safe inset.
            val scale = minOf(pw / video.width, ph / video.height)
            val rw = video.width * scale
            val rh = video.height * scale
            val x = (pw - rw) / 2
            val y = (ph - rh) / 2
            val left = maxOf(x, pw * safeLeft / 10000)
            val top = maxOf(y, ph * safeTop / 10000)
            val right = minOf(x + rw, pw * (1 - safeRight / 10000.0))
            val bottom = minOf(y + rh, ph * (1 - safeBottom / 10000.0))
            require(right > left && bottom > top) { "Safe area has no video intersection" }
            fun pixels(value: Double) = kotlin.math.ceil(value / scale - 1e-7).toInt().coerceAtLeast(0)
            com.shilapi.xcertplay.airplay.AirPlayInsets(pixels(top - y), pixels(y + rh - bottom),
                pixels(left - x), pixels(x + rw - right))
        }
        return requested.copy(resolution = com.openautolink.carplaylab.media.CarPlayResolution.CUSTOM,
            customWidth = video.width, customHeight = video.height, fps = video.fps,
            qualifiedVideoSelection = video, encodedSafeInsets = safe).freezeScale()
    }
    fun videoError(): String? = when {
        listOf(safeTop, safeBottom, safeLeft, safeRight).any { it !in 0..4999 } ->
            "CarPlay safe edges must be below 50% of each parent axis in Display."
        videoCodec !in setOf("h264", "hevc") -> "Choose H.264 or Automatic codec in Video."
        fpsDraft != null && fpsDraft.toIntOrNull() != fps -> "Enter an integer maximum FPS in Video."
        fps !in com.openautolink.carplaylab.media.CarPlayDisplayPreferences.FRAME_RATES ->
            "Maximum FPS must be an integer from 1 to 60 in Video."
        com.openautolink.carplaylab.media.CarPlayResolution.entries.none { it.id == resolution } ->
            "Choose a resolution in Video."
        resolution == "custom" && (widthDraft != null && widthDraft.toIntOrNull() != widthPixels ||
            heightDraft != null && heightDraft.toIntOrNull() != heightPixels ||
            !com.openautolink.carplaylab.media.CarPlayDisplayPreferences.validDimensions(widthPixels, heightPixels)) ->
            "Custom width and height must be positive even integers in Video."
        else -> null
    }
    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun decode(record: String?): CarPlaySettings =
            record?.let { runCatching {
                val fields = json.parseToJsonElement(it).jsonObject
                // Serializer accepts quoted Boolean literals; safety choices require actual JSON Booleans.
                for (key in listOf("alwaysInPark", "drivingRestrictionsEnabled")) {
                    fields[key]?.let { value ->
                        require(value is JsonPrimitive && !value.isString && value.booleanOrNull != null)
                    }
                }
                // Only stored corruption falls back. Preserve the rest of the record even when this
                // optional field has a wrong JSON type; interactive drafts never enter this path.
                val dock = CarPlayDockPosition.fromStored((fields["dockPosition"] as? JsonPrimitive)
                    ?.takeIf { value -> value.isString }?.content)
                json.decodeFromJsonElement<CarPlaySettings>(JsonObject(fields.toMutableMap().apply {
                    put("dockPosition", JsonPrimitive(dock.id))
                }))
            }.getOrNull() }
                ?.let { stored ->
                    // Restore through the receiver's canonical source-backed policy. A valid stored
                    // preference is not permission to advertise it without exact device qualification.
                    val display = com.openautolink.carplaylab.media.CarPlayDisplayPreferences.fromStored(
                        stored.resolution, stored.fps, stored.widthPhysicalMm,
                        customWidth = stored.widthPixels, customHeight = stored.heightPixels,
                    )
                    val fields = json.parseToJsonElement(requireNotNull(record)).jsonObject
                    stored.copy(autoResolution = if ("autoResolution" !in fields && "resolution" in fields)
                            false else stored.autoResolution,
                        connectionMode = stored.connectionMode.takeIf { it in setOf("usb", "wireless") } ?: "usb",
                        resolution = display.resolution.id, fps = display.fps,
                        widthPhysicalMm = display.widthPhysicalMm,
                        videoCodec = stored.videoCodec.takeIf { it in setOf("h264", "hevc") } ?: "h264")
                } ?: CarPlaySettings(enabled = record == null)
        fun encode(settings: CarPlaySettings): String {
            require(settings.connectionMode in setOf("usb", "wireless"))
            return json.encodeToString(settings)
        }
    }
}
