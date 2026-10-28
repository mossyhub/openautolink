// SPDX-License-Identifier: GPL-3.0-only
package com.openautolink.carplaylab.media

import com.shilapi.xcertplay.airplay.AirPlayInfoPlist

/** Next-connection status-bar intent; driver side uses the existing rightHandDrive declaration. */
enum class CarPlayDockPosition(val id: String, val wireEdge: Int?) {
    AUTOMATIC("automatic", null),
    BOTTOM("bottom", AirPlayInfoPlist.DOCK_EDGE_BOTTOM),
    DRIVER_SIDE("driver_side", AirPlayInfoPlist.DOCK_EDGE_DRIVER_SIDE);

    companion object {
        /** Corrupt storage alone restores Automatic; interactive edits must be validated strictly. */
        fun fromStored(id: String?): CarPlayDockPosition = entries.firstOrNull { it.id == id } ?: AUTOMATIC
    }
}
