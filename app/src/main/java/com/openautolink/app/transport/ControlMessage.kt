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

    // Bridge → App
    data class Hello(
        val version: Int,
        val name: String,
        val capabilities: List<String>,
        val videoPort: Int,
        val audioPort: Int
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
        val navImageBase64: String? = null
    ) : ControlMessage()

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

    // App → Bridge
    data class AppHello(
        val version: Int,
        val name: String,
        val displayWidth: Int,
        val displayHeight: Int,
        val displayDpi: Int
    ) : ControlMessage()

    data class Touch(
        val action: Int,
        val x: Float?,
        val y: Float?,
        val pointerId: Int?,
        val pointers: List<Pointer>?
    ) : ControlMessage()

    data class Pointer(val id: Int, val x: Float, val y: Float)

    data class Gnss(val nmea: String) : ControlMessage()

    data class VehicleData(
        val speedKmh: Float? = null,
        val gear: String? = null,
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
        val hazardLights: Boolean? = null
    ) : ControlMessage()

    data class Button(
        val keycode: Int,
        val down: Boolean,
        val metastate: Int = 0,
        val longpress: Boolean = false
    ) : ControlMessage()

    data class ConfigUpdate(val config: Map<String, String>) : ControlMessage()
    object KeyframeRequest : ControlMessage()
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
