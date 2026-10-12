package com.openautolink.carplaylab.media

import com.shilapi.xcertplay.airplay.AirPlayConfig

/** AirPlay protocol compatibility version, NOT the OAL application version. */
internal const val AIRPLAY_SOURCE_VERSION = "950.7.1"

internal fun benchReceiverConfig(btMac: String, port: Int,
    microphoneCapability: com.shilapi.xcertplay.media.MicrophoneCapability =
        com.shilapi.xcertplay.media.MicrophoneCapability.NONE,
    display: CarPlayDisplayPreferences = CarPlayDisplayPreferences()) = AirPlayConfig(
    deviceName = "OAL CarPlay Lab", deviceId = btMac, btMac = btMac,
    sourceVersion = AIRPLAY_SOURCE_VERSION, main = display.toAirPlayDisplay(), port = port,
    rightHandDrive = display.rightHandDrive,
    hevc = false, microphone = microphoneCapability.available, cluster = null, videoInCar = false,
    mainBufferedAudio = false, lpcmOnly = false, manufacturer = "OpenAutoLink", model = "OAL-auth-bench",
)
