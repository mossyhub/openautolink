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
        const val PILOT_STATUS = "Map video pilot uses H.264; your saved video choice is unchanged"

        /** All geometry is integer encoded-pixel insets, not Android display IDs or host padding.
         * Invalid/empty/uncontained geometry or a fallback to another canvas fails closed.
         */
        fun create(viewArea: AirPlayInsets, safeArea: AirPlayInsets,
            videoSelection: CarPlayVideoSelection,
            width: Int = 1280, height: Int = 480, fps: Int = 30): CarPlayClusterVideoConfiguration {
            val display = AirPlayDisplayConfig(width, height, fps = fps,
                primaryInputDevice = 0, features = 0, viewArea = viewArea, safeArea = safeArea,
                safeAreaDrawOutside = false, initialUrl = "maps:/car/instrumentcluster/map")
            return CarPlayClusterVideoConfiguration(CarPlayClusterVideoQualification.qualify(display, videoSelection))
        }
    }
}
