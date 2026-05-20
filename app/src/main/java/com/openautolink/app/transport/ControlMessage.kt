package com.openautolink.app.transport

/**
 * Control messages exchanged between session components.
 * Used for inter-island communication within the app.
 */
sealed class ControlMessage {

    // Session lifecycle
    data class PhoneConnected(
        val phoneName: String,
        val phoneType: String
    ) : ControlMessage()

    data class PhoneDisconnected(val reason: String) : ControlMessage()

    // Audio control
    data class AudioStart(
        val purpose: AudioPurpose,
        val sampleRate: Int,
        val channels: Int
    ) : ControlMessage()

    data class AudioStop(val purpose: AudioPurpose) : ControlMessage()

    data class MicStart(val sampleRate: Int) : ControlMessage()
    object MicStop : ControlMessage()

    // Navigation
    data class NavState(
        val maneuver: String?,
        val distanceMeters: Int?,
        val road: String?,
        val etaSeconds: Int?,
        val navImageBase64: String? = null,
        val lanes: List<NavLane>? = null,
        val cue: String? = null,
        val roundaboutExitNumber: Int? = null,
        val roundaboutExitAngle: Int? = null,
        val displayDistance: String? = null,
        val displayDistanceUnit: String? = null,
        val currentRoad: String? = null,
        val destination: String? = null,
        val etaFormatted: String? = null,
        val timeToArrivalSeconds: Long? = null,
        val destDistanceMeters: Int? = null,
        val destDistanceDisplay: String? = null,
        val destDistanceUnit: String? = null
    ) : ControlMessage()

    object NavStateClear : ControlMessage()

    data class NavLane(val directions: List<NavLaneDirection>)
    data class NavLaneDirection(val shape: String, val highlighted: Boolean)

    // Media
    data class MediaMetadata(
        val title: String?,
        val artist: String?,
        val album: String?,
        val durationMs: Long?,
        val positionMs: Long?,
        val playing: Boolean?,
        val albumArtBase64: String? = null
    ) : ControlMessage()

    /** Playback state only — does NOT carry metadata fields to avoid wiping real metadata. */
    data class MediaPlaybackState(
        val playing: Boolean,
        val positionMs: Long
    ) : ControlMessage()

    // Error
    data class Error(val code: Int, val message: String) : ControlMessage()

    // Phone state
    data class PhoneBattery(
        val level: Int,
        val timeRemainingSeconds: Int,
        val critical: Boolean
    ) : ControlMessage()

    data class VoiceSession(
        val started: Boolean
    ) : ControlMessage()

    data class PhoneStatus(
        val signalStrength: Int?,
        val calls: List<PhoneCall>
    ) : ControlMessage()

    data class PhoneCall(
        val state: String,
        val durationSeconds: Int,
        val callerNumber: String?,
        val callerId: String?
    )

    // Input
    data class Touch(
        val action: Int,
        val x: Float?,
        val y: Float?,
        val pointerId: Int?,
        val pointers: List<Pointer>?,
        val actionIndex: Int? = null
    ) : ControlMessage()

    data class Pointer(val id: Int, val x: Float, val y: Float)

    data class Gnss(val nmea: String) : ControlMessage()

    data class VehicleData(
        val speedKmh: Float? = null,
        val gear: String? = null,
        val gearRaw: Int? = null,
        val batteryPct: Int? = null,
        val turnSignal: String? = null,
        val parkingBrake: Boolean? = null,
        val nightMode: Boolean? = null,
        val fuelLevelPct: Int? = null,
        val rangeKm: Float? = null,
        val lowFuel: Boolean? = null,
        val odometerKm: Float? = null,
        val ambientTempC: Float? = null,
        val steeringAngleDeg: Float? = null,
        val headlight: Int? = null,
        val hazardLights: Boolean? = null,
        // EV-specific
        val chargePortOpen: Boolean? = null,
        val chargePortConnected: Boolean? = null,
        val ignitionState: Int? = null,
        val evChargeRateW: Float? = null,
        val evBatteryLevelWh: Float? = null,
        val evBatteryCapacityWh: Float? = null,
        val driving: Boolean? = null,
        // EV extended
        val evChargeState: Int? = null,
        val evChargeTimeRemainingSec: Int? = null,
        val evCurrentBatteryCapacityWh: Float? = null,
        val evBatteryTempC: Float? = null,
        val evChargePercentLimit: Float? = null,
        val evChargeCurrentDrawLimitA: Float? = null,
        val evRegenBrakingLevel: Int? = null,
        val evStoppingMode: Int? = null,
        val distanceDisplayUnits: Int? = null,
        // Vehicle identity
        val carMake: String? = null,
        val carModel: String? = null,
        val carYear: String? = null,
        val fuelTypes: List<Int>? = null,
        val evConnectorTypes: List<Int>? = null,
        // IMU sensors
        val accelXe3: Int? = null,
        val accelYe3: Int? = null,
        val accelZe3: Int? = null,
        val gyroRxe3: Int? = null,
        val gyroRye3: Int? = null,
        val gyroRze3: Int? = null,
        val compassBearingE6: Int? = null,
        val compassPitchE6: Int? = null,
        val compassRollE6: Int? = null,
        // GPS satellites
        val satInUse: Int? = null,
        val satInView: Int? = null,
        val satellites: List<SatelliteInfo>? = null,
        // RPM
        val rpmE3: Int? = null,
        // Round-6 — extra VHAL reads (CAR_MILEAGE, CAR_TIRES, CAR_DYNAMICS_STATE)
        val tirePressuresKpa: List<Float>? = null,
        val absActive: Boolean? = null,
        val tractionControlActive: Boolean? = null,
        // From com.gm.vehicleinfo.HistoryProvider (Finding F.2) — latest sample.
        // Null when provider returns nothing (patch / SecurityException / empty).
        val evMotorPowerW: Float? = null,
        val evMotorTorqueNm: Float? = null,
    ) : ControlMessage()

    data class SatelliteInfo(
        val prn: Int,
        val snrE3: Int,
        val usedInFix: Boolean,
        val azimuthE3: Int,
        val elevationE3: Int
    )

    data class Button(
        val keycode: Int,
        val down: Boolean,
        val metastate: Int = 0,
        val longpress: Boolean = false
    ) : ControlMessage()

    object KeyframeRequest : ControlMessage()
}

/**
 * Transport connection state.
 */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,        // AA handshake complete
    PHONE_CONNECTED,  // Phone AA session active, channels opening
    STREAMING         // Video/audio flowing
}

enum class AudioPurpose {
    MEDIA, NAVIGATION, ASSISTANT, PHONE_CALL, ALERT;

    fun toWire(): String = when (this) {
        MEDIA -> "media"
        NAVIGATION -> "navigation"
        ASSISTANT -> "assistant"
        PHONE_CALL -> "phone_call"
        ALERT -> "alert"
    }

    companion object {
        fun fromWire(value: String): AudioPurpose? = when (value) {
            "media" -> MEDIA
            "navigation" -> NAVIGATION
            "assistant" -> ASSISTANT
            "phone_call" -> PHONE_CALL
            "alert" -> ALERT
            else -> null
        }
    }
}
