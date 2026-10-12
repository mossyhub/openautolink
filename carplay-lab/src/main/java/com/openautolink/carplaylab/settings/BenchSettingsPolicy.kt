package com.openautolink.carplaylab.settings

/** Bench-only settings. No Android Auto keys, protocol activation or decoder claims. */
enum class BenchDisplayProfile(val width: Int, val height: Int, val fps: Int) {
    HD_720(1280, 720, 30), FULL_HD_1080(1920, 1080, 30)
}

data class BenchSettings(
    val carPlayEnabled: Boolean = false,
    val display: BenchDisplayProfile = BenchDisplayProfile.HD_720,
    val selectedPhoneAddress: String = "",
    val peerIp: String = "",
    val ssid: String = "",
    val receiverAddress: String = "",
)

object BenchSettingsPolicy {
    fun write(settings: BenchSettings): Map<String, Any> = mapOf(
        "carplay_enabled" to settings.carPlayEnabled,
        "carplay_display_width" to settings.display.width,
        "carplay_display_height" to settings.display.height,
        "carplay_display_fps" to settings.display.fps,
        "carplay_selected_phone" to settings.selectedPhoneAddress,
        "carplay_peer_ip" to settings.peerIp,
        "carplay_ssid" to settings.ssid,
        "receiver_bt_address" to settings.receiverAddress,
    )

    fun canStart(carPlayEnabled: Boolean, busy: Boolean): Boolean = carPlayEnabled && !busy
    fun canRequestMicrophone(carPlayEnabled: Boolean, busy: Boolean): Boolean = carPlayEnabled && !busy
    fun selectedPhoneIndex(savedAddress: String, addresses: List<String>): Int =
        if (savedAddress.isEmpty()) -1 else addresses.indexOf(savedAddress)

    // Escape is a shell invariant, not a preference or session capability.
    fun refreshKeepsSettingsEscape(enabled: Boolean, attemptActive: Boolean): Boolean = true

    fun read(values: Map<String, Any?>): BenchSettings = BenchSettings(
        carPlayEnabled = values["carplay_enabled"] == true,
        display = BenchDisplayProfile.entries.firstOrNull {
            values["carplay_display_width"] == it.width &&
                values["carplay_display_height"] == it.height &&
                values["carplay_display_fps"] == it.fps
        } ?: BenchDisplayProfile.HD_720,
        selectedPhoneAddress = values["carplay_selected_phone"] as? String ?: "",
        peerIp = values["carplay_peer_ip"] as? String ?: "",
        ssid = values["carplay_ssid"] as? String ?: "",
        receiverAddress = values["receiver_bt_address"] as? String ?: "",
    )
}
