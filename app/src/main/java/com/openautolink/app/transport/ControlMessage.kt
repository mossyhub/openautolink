package com.openautolink.app.transport

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parsed control messages from the OAL protocol control channel (port 5288).
 * JSON lines — one JSON object per line, bidirectional.
 */
sealed class ControlMessage {

    companion object {
        /** App's protocol version — increment when adding breaking changes. */
        const val PROTOCOL_VERSION = 1
        /** Minimum bridge protocol version this app supports. */
        const val MIN_PROTOCOL_VERSION = 1
    }

    // Bridge → App
    data class Hello(
        val version: Int,
        val name: String,
        val capabilities: List<String>,
        val videoPort: Int,
        val audioPort: Int,
        val bridgeVersion: String? = null,
        val bridgeSha256: String? = null,
        val protocolVersion: Int? = null,
        val minProtocolVersion: Int? = null,
        val buildSource: String? = null,
    ) : ControlMessage()

    data class PhoneConnected(
        val phoneName: String,
        val phoneType: String
    ) : ControlMessage()

    data class PhoneDisconnected(val reason: String) : ControlMessage()

    data class AudioStart(
        val purpose: AudioPurpose,
        val sampleRate: Int,
        val channels: Int
    ) : ControlMessage()

    data class AudioStop(val purpose: AudioPurpose) : ControlMessage()

    data class MicStart(val sampleRate: Int) : ControlMessage()
    object MicStop : ControlMessage()

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

    data class MediaMetadata(
        val title: String?,
        val artist: String?,
        val album: String?,
        val durationMs: Long?,
        val positionMs: Long?,
        val playing: Boolean?,
        val albumArtBase64: String? = null
    ) : ControlMessage()

    data class ConfigEcho(val config: Map<String, String>) : ControlMessage()
    data class Error(val code: Int, val message: String) : ControlMessage()
    data class Stats(
        val videoFramesSent: Long,
        val audioFramesSent: Long,
        val uptimeSeconds: Long
    ) : ControlMessage()

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

    data class PairedPhones(
        val phones: List<PairedPhone>
    ) : ControlMessage()

    data class PairingModeStatus(
        val enabled: Boolean
    ) : ControlMessage()

    data class PairedPhone(
        val mac: String,
        val name: String,
        val connected: Boolean
    )

    data class PhoneCall(
        val state: String,
        val durationSeconds: Int,
        val callerNumber: String?,
        val callerId: String?
    )

    // App → Bridge
    data class AppHello(
        val version: Int,
        val name: String,
        val displayWidth: Int,
        val displayHeight: Int,
        val displayDpi: Int,
        val cutoutTop: Int = 0,
        val cutoutBottom: Int = 0,
        val cutoutLeft: Int = 0,
        val cutoutRight: Int = 0,
        val barTop: Int = 0,
        val barBottom: Int = 0,
        val barLeft: Int = 0,
        val barRight: Int = 0,
        val videoScalingMode: String = "crop",
        val decoderFillsSurface: Boolean = false,
    ) : ControlMessage()

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
        // EV extended (probed but may not be exposed by all HALs)
        val evChargeState: Int? = null,
        val evChargeTimeRemainingSec: Int? = null,
        val evCurrentBatteryCapacityWh: Float? = null,
        val evBatteryTempC: Float? = null,
        val evChargePercentLimit: Float? = null,
        val evChargeCurrentDrawLimitA: Float? = null,
        val evRegenBrakingLevel: Int? = null,
        val evStoppingMode: Int? = null,
        val distanceDisplayUnits: Int? = null,
        // Vehicle identity (static VHAL properties, sent once on connect)
        val carMake: String? = null,
        val carModel: String? = null,
        val carYear: String? = null,
        val fuelTypes: List<Int>? = null,          // INFO_FUEL_TYPE — e.g. [10] = ELECTRIC
        val evConnectorTypes: List<Int>? = null,   // INFO_EV_CONNECTOR_TYPE — e.g. [1,5] = J1772+COMBO_1
        // P5: IMU sensors
        val accelXe3: Int? = null,
        val accelYe3: Int? = null,
        val accelZe3: Int? = null,
        val gyroRxe3: Int? = null,
        val gyroRye3: Int? = null,
        val gyroRze3: Int? = null,
        val compassBearingE6: Int? = null,
        val compassPitchE6: Int? = null,
        val compassRollE6: Int? = null,
        // P5: GPS satellites
        val satInUse: Int? = null,
        val satInView: Int? = null,
        val satellites: List<SatelliteInfo>? = null,
        // P6: RPM
        val rpmE3: Int? = null
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

    data class ConfigUpdate(val config: Map<String, String>) : ControlMessage()
    data class RestartServices(
        val wireless: Boolean = false,
        val bluetooth: Boolean = false
    ) : ControlMessage()
    object KeyframeRequest : ControlMessage()
    object ListPairedPhones : ControlMessage()
    data class SwitchPhone(val mac: String) : ControlMessage()
    data class ForgetPhone(val mac: String) : ControlMessage()
    data class SetPairingMode(val enabled: Boolean) : ControlMessage()

    // Bridge update protocol (App → Bridge)
    data class BridgeUpdateOffer(
        val version: String,
        val size: Int,
        val sha256: String,
        val autoApply: Boolean = true
    ) : ControlMessage()

    data class BridgeUpdateData(
        val offset: Int,
        val length: Int,
        val data: String  // base64
    ) : ControlMessage()

    data class BridgeUpdateComplete(val sha256: String) : ControlMessage()

    // Bridge update protocol (Bridge → App)
    data class BridgeUpdateAccept(val dummy: Unit = Unit) : ControlMessage()
    data class BridgeUpdateReject(val reason: String) : ControlMessage()
    data class BridgeUpdateStatus(val status: String, val message: String) : ControlMessage()

    // App → Bridge: diagnostic messages
    data class AppLog(
        val ts: Long,
        val level: String,
        val tag: String,
        val msg: String
    ) : ControlMessage()

    data class AppTelemetry(
        val ts: Long,
        val video: com.openautolink.app.diagnostics.VideoTelemetry? = null,
        val audio: com.openautolink.app.diagnostics.AudioTelemetry? = null,
        val session: com.openautolink.app.diagnostics.SessionTelemetry? = null,
        val cluster: com.openautolink.app.diagnostics.ClusterTelemetry? = null
    ) : ControlMessage()
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
