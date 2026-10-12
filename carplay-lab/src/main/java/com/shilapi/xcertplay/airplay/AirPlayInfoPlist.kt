// SPDX-License-Identifier: GPL-3.0-only
// From DiPlay e2fd8eadf5a6b705666f292055e72aa93387533d, shared/src/main/java/
// com/shilapi/xcertplay/airplay/; upstream shilapi/xcertplay. See PROVENANCE.md.
package com.shilapi.xcertplay.airplay

/**
 * Builds the /info response the phone reads before it requests media streams.
 *
 * The declaration is complete on purpose: it must carry the display, audio formats/latencies,
 * CarPlay resource modes, and the HID input devices, otherwise the phone aborts the session.
 */
object AirPlayInfoPlist {
    const val MAIN_UUID = "b7e6c5a0-1111-4000-8000-000000000001"
    const val ALT_UUID = "b7e6c5a0-2222-4000-8000-000000000002"

    private const val STREAM_TYPE_MAIN_SCREEN = 110
    private const val STREAM_TYPE_ALT_SCREEN = 111
    private const val DISPLAY_FEATURE_KNOBS = 0x02
    private const val DISPLAY_FEATURE_HIGH_FIDELITY_TOUCH = 0x08
    private const val CARPLAY_FEATURES = 0x615653aee2L
    private const val CARPLAY_AUDIO_FEATURES = 0x10004540a00L
    private val CARPLAY_FEATURES_NO_AUDIO = CARPLAY_FEATURES and CARPLAY_AUDIO_FEATURES.inv()

    private const val RESOURCE_SCREEN = 1
    private const val RESOURCE_AUDIO = 2
    private const val TRANSFER_TAKE = 1
    private const val PRIORITY_NICE_TO_HAVE = 100
    private const val CONSTRAINT_ANYTIME = 100

    /** Discovery and /info must describe the same receiver capabilities. */
    fun features(config: AirPlayConfig): Long =
        if (config.disableAudioOutput) CARPLAY_FEATURES_NO_AUDIO else CARPLAY_FEATURES

    fun build(config: AirPlayConfig, availableAudioFormats: Set<Long> =
        if (config.lpcmOnly || config.disableAudioOutput) emptySet()
        else com.shilapi.xcertplay.media.AndroidAudioDecoderSupport.availableBits,
        microphoneCapability: com.shilapi.xcertplay.media.MicrophoneCapability =
            if (config.microphone && !config.lpcmOnly && !config.disableAudioOutput)
                com.shilapi.xcertplay.media.AndroidMicrophoneSupport.capability
            else com.shilapi.xcertplay.media.MicrophoneCapability.NONE,
        videoSelection: com.shilapi.xcertplay.media.CarPlayVideoSelection? = null): Map<String, Any?> {
        // Pairing-only closure: no buffered media or video-in-car engine.
        require(!config.mainBufferedAudio && !config.videoInCar)
        val displays = arrayListOf<Any?>(
            displayEntry(config.main, STREAM_TYPE_MAIN_SCREEN, MAIN_UUID),
        )
        config.cluster?.let { displays.add(displayEntry(it, STREAM_TYPE_ALT_SCREEN, ALT_UUID)) }

        val info = linkedMapOf<String, Any?>(
            "sourceVersion" to config.sourceVersion,
            "features" to features(config),
            "statusFlags" to 4L,
            "model" to config.model,
            "manufacturer" to config.manufacturer,
            "deviceID" to config.deviceId,
            "bluetoothIDs" to listOf(config.btMac),
            "name" to config.deviceName,
            "rightHandDrive" to config.rightHandDrive,
            "keepAliveLowPower" to false,
            "keepAliveSendStatsAsBody" to false,
            "modes" to modes(),
        )
        if (!config.disableAudioOutput) {
            val outputs = if (config.lpcmOnly) lpcmFormats(config.entertainmentSampleRate)
                else com.shilapi.xcertplay.media.AudioOutputSupport.wirelessRows(availableAudioFormats)
            val rows = com.shilapi.xcertplay.media.MicrophoneSupport.addInputOffers(outputs,
                if (config.microphone && !config.lpcmOnly) microphoneCapability
                else com.shilapi.xcertplay.media.MicrophoneCapability.NONE)
            info["audioFormats"] = rows
            // Preserve the working receiver's latency selectors, independent of codec rows.
            // These are latency declarations, not offers of additional codecs/input streams.
            info["audioLatencies"] = listOf(100 to null,100 to "default",100 to "media",
                100 to "telephony",100 to "speechRecognition",100 to "alert",
                101 to null,101 to "default",102 to "default").map { (type,kind) ->
                linkedMapOf<String,Any?>("type" to type,"inputLatencyMicros" to 0L,
                    "outputLatencyMicros" to 0L).apply { if (kind != null) put("audioType",kind) }
            }
        }
        // Preserve the extended-feature declaration shared by pinned DiPlay and independent receivers.
        // No evidence establishes a separate vocoder control endpoint requirement.
        info["extendedFeatures"] = listOf("vocoderInfo", "enhancedRequestCarUI")
        info["displays"] = displays
        info["hidDevices"] = listOf(
            AirPlayHid.touchHidDevice(config.main.widthPixels, config.main.heightPixels, MAIN_UUID),
            AirPlayHid.knobHidDevice(MAIN_UUID),
            AirPlayHid.mediaHidDevice(MAIN_UUID),
            AirPlayHid.telephonyHidDevice(MAIN_UUID),
        )
        if (config.icons.isNotEmpty()) {
            info["oemIconVisible"] = true
            info["oemIconLabel"] = config.oemLabel
            info["oemIcons"] = config.icons.map { icon ->
                linkedMapOf(
                    "imageData" to icon.data,
                    "widthPixels" to icon.widthPixels,
                    "heightPixels" to icon.heightPixels,
                    "prerendered" to true,
                )
            }
        }
        if (config.hevc && videoSelection?.matches(config) == true && videoSelection.hevcOffered) info["hevcInfo"] = emptyMap<String, Any?>()
        // With the mainBuffered session feature the iPhone requires this key; an empty dictionary is accepted.
        if (config.bufferedAudioOutputEnabled) info["mainBufferedInfo"] = emptyMap<String, Any?>()
        return info
    }

    private fun resource(resourceId: Int): Map<String, Any?> = linkedMapOf(
        "resourceID" to resourceId,
        "transferType" to TRANSFER_TAKE,
        "transferPriority" to PRIORITY_NICE_TO_HAVE,
        "takeConstraint" to CONSTRAINT_ANYTIME,
        "borrowConstraint" to CONSTRAINT_ANYTIME,
        "unborrowConstraint" to CONSTRAINT_ANYTIME,
    )

    private fun modes(): Map<String, Any?> = linkedMapOf(
        "resources" to listOf(resource(RESOURCE_SCREEN), resource(RESOURCE_AUDIO)),
        "appStates" to listOf(
            linkedMapOf("appStateID" to 2, "state" to false),
            linkedMapOf("appStateID" to 1, "speechMode" to -1),
            linkedMapOf("appStateID" to 3, "state" to false),
        ),
    )

    private fun lpcmFormats(rate: Int): List<Map<String, Any?>> {
        val pcm = 0x3fc or if (rate == 48000) 0xc000 else 0xc00
        val mono = 0x154 or if (rate == 48000) 0x4000 else 0x400
        return listOf(100 to "compatibility", 101 to "compatibility", 100 to "default",
            100 to "alert", 100 to "media", 100 to "telephony", 100 to "speechRecognition",
            101 to "default").map { (type, audioType) ->
            linkedMapOf("type" to type, "audioType" to audioType,
                "audioOutputFormats" to if (audioType in setOf("telephony", "speechRecognition")) mono else pcm)
        }
    }

    /**
     * viewAreaStatusBarEdge values, as CarPlay Simulator's StatusBarEdge (automatic, bottom, driver) and as
     * seen on a Tang with iOS 27: 1 puts the dock at the bottom, 2 on the driver's side.
     */
    const val DOCK_EDGE_BOTTOM = 1
    const val DOCK_EDGE_DRIVER_SIDE = 2
    private const val VIEW_AREA_ANIMATION_MILLIS = 300

    /**
     * updateViewArea for the main screen. Seen on a Tang with iOS 27: the iPhone acts on it only with an
     * animation duration and the adjacent areas, the arguments CarPlaySDK's ViewAreaUpdate takes.
     */
    fun viewAreaCommand(index: Int, areaCount: Int): Map<String, Any?> = linkedMapOf(
        "type" to "updateViewArea",
        "params" to linkedMapOf(
            "uuid" to MAIN_UUID,
            "viewAreaIndex" to index,
            "animationDurationMillis" to VIEW_AREA_ANIMATION_MILLIS,
            "adjacentViewAreas" to (0 until areaCount).filter { it != index },
        ),
    )

    private fun displayEntry(display: AirPlayDisplayConfig, type: Int, uuid: String): Map<String, Any?> {
        val widthPhysical = AirPlayDisplaySettings.sanitizeReportedPhysicalMm(
            display.widthPhysicalMm ?: AirPlayDisplaySettings.DEFAULT_WIDTH_PHYSICAL_MM,
        )
        val heightPhysical = AirPlayDisplaySettings.sanitizeReportedPhysicalMm(
            display.heightPhysicalMm
                ?: Math.round(
                    widthPhysical * display.heightPixels.toDouble() / display.widthPixels,
                ).toInt(),
        )
        val fps = AirPlayDisplaySettings.sanitizeFps(display.fps)

        val entry = linkedMapOf<String, Any?>(
            "uuid" to uuid,
            "type" to type,
            "maxFPS" to fps,
            "widthPixels" to display.widthPixels,
            "heightPixels" to display.heightPixels,
            "widthPhysical" to widthPhysical,
            "heightPhysical" to heightPhysical,
            "features" to (display.features ?: (DISPLAY_FEATURE_HIGH_FIDELITY_TOUCH or DISPLAY_FEATURE_KNOBS)),
            "primaryInputDevice" to display.primaryInputDevice,
        )

        // Several areas let the car move CarPlay between them (another dock edge, the head unit's split
        // screen) with updateViewArea, without reconnecting.
        val areas = display.viewAreas?.takeIf { it.isNotEmpty() }
        entry["viewAreas"] = areas?.map { areaDict(display, it) } ?: listOf(areaDict(display))
        entry["initialViewArea"] = if (areas == null) 0 else display.initialViewArea.coerceIn(0, areas.lastIndex)
        if (areas != null && areas.size > 1) entry["viewAreaTransitionControl"] = true
        if (display.initialUrl != null) entry["initialURL"] = display.initialUrl
        return entry
    }

    private fun areaDict(display: AirPlayDisplayConfig, area: AirPlayViewArea? = null): Map<String, Any?> {
        // The session SETUP response enables "viewAreas", so /info must always describe one.
        // A display without custom insets uses the full panel for both the view and safe areas; an
        // explicit area replaces the display's view insets and clips its safe area.
        val width = display.widthPixels
        val height = display.heightPixels
        val view = area?.let {
            AirPlayInsets(top = it.originY, bottom = height - it.originY - it.height,
                left = it.originX, right = width - it.originX - it.width)
        } ?: display.viewArea ?: AirPlayInsets()
        val result = linkedMapOf<String, Any?>(
            "widthPixels" to (width - view.left - view.right),
            "heightPixels" to (height - view.top - view.bottom),
            "originXPixels" to view.left,
            "originYPixels" to view.top,
        )
        area?.dockEdge?.let { result["viewAreaStatusBarEdge"] = it }
        val displaySafe = display.safeArea ?: AirPlayInsets()
        val clipped = if (area == null) displaySafe else AirPlayInsets(
            top = maxOf(displaySafe.top, view.top),
            bottom = maxOf(displaySafe.bottom, view.bottom),
            left = maxOf(displaySafe.left, view.left),
            right = maxOf(displaySafe.right, view.right),
        )
        // A valid saved mapping can lie wholly outside a smaller view area. In that case the
        // intersection is empty: use this area's bounds instead of advertising negative/zero sizes.
        val safe = if (area != null &&
            (clipped.left + clipped.right >= width || clipped.top + clipped.bottom >= height)
        ) view else clipped
        val safeArea = linkedMapOf<String, Any?>(
            "widthPixels" to (width - safe.left - safe.right),
            "heightPixels" to (height - safe.top - safe.bottom),
            "originXPixels" to safe.left,
            "originYPixels" to safe.top,
            "drawUIOutsideSafeArea" to (display.safeAreaDrawOutside ?: true),
        )
        result["safeArea"] = safeArea
        return result
    }
}
