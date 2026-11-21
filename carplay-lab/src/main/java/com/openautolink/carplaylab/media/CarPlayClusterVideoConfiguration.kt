// SPDX-License-Identifier: GPL-3.0-only
package com.openautolink.carplaylab.media

import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayInsets
import com.shilapi.xcertplay.media.CarPlayClusterVideoQualification
import com.shilapi.xcertplay.media.CarPlayVideoSelection

/** Optional encoded111 consumer for a parent-owned common EGL renderer. Callbacks perform bounded
 * nonblocking admissions only. Bytes are owned snapshots; epoch belongs to this receiver, not an
 * Android display ID. No callback is decoder or physical-presentation acknowledgement.
 */
interface CarPlayClusterVideoListener {
    fun onStreamActive(streamEpoch: Long, active: Boolean)
    fun onCodecConfig(streamEpoch: Long, codecData: ByteArray)
    fun onFrame(streamEpoch: Long, naluBytes: ByteArray)
}

/** Requested iPhone cluster content, as one of its advertised `altScreenURLs`. These URLs are
 * undocumented; they come from other CarPlay receivers. MAP_WITH_CARD is a preserved storage token
 * for Apple's cluster view, not a map-plus-card, camera-follow or 3D guarantee.
 */
enum class CarPlayClusterContent(val url: String) {
    MAP("maps:/car/instrumentcluster/map"),
    INSTRUCTION_CARD("maps:/car/instrumentcluster/instructioncard"),
    MAP_WITH_CARD("maps:/car/instrumentcluster");

    companion object {
        val DEFAULT = MAP_WITH_CARD
        fun fromUrl(url: String?): CarPlayClusterContent? = entries.firstOrNull { it.url == url }
    }
}

/** Receiver-frozen encoded geometry. The caller validates vehicle/host applicability and transforms
 * its output/safe rectangles into this canvas BEFORE construction. No app, host or preference owner
 * is retained here. Exact decoder capability is not phone acceptance or simultaneous decode proof.
 */
class CarPlayClusterVideoConfiguration private constructor(
    private val qualification: CarPlayClusterVideoQualification,
) {
    val display: AirPlayDisplayConfig get() = qualification.display
    val videoSelection: CarPlayVideoSelection get() = qualification.videoSelection

    companion object {
        const val PILOT_STATUS = "Driver-display video uses decoder-qualified dimensions and automatic codec selection"

        /** All geometry is integer encoded-pixel insets, not Android display IDs or host padding.
         * Invalid/empty/uncontained geometry or a fallback to another canvas fails closed.
         */
        fun create(viewArea: AirPlayInsets, safeArea: AirPlayInsets,
            videoSelection: CarPlayVideoSelection,
            width: Int = videoSelection.width, height: Int = videoSelection.height, fps: Int = videoSelection.fps,
            content: CarPlayClusterContent = CarPlayClusterContent.DEFAULT): CarPlayClusterVideoConfiguration {
            val display = AirPlayDisplayConfig(width, height, fps = fps,
                primaryInputDevice = 0, features = 0, viewArea = viewArea, safeArea = safeArea,
                safeAreaDrawOutside = false, initialUrl = content.url)
            return CarPlayClusterVideoConfiguration(CarPlayClusterVideoQualification.qualify(display, videoSelection))
        }
    }
}
