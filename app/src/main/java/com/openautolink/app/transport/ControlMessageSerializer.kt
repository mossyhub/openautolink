package com.openautolink.app.transport

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Serializes/deserializes OAL control channel JSON lines.
 */
object ControlMessageSerializer {

    private val json = Json { ignoreUnknownKeys = true }

    fun deserialize(line: String): ControlMessage? {
        val obj = try {
            json.parseToJsonElement(line).jsonObject
        } catch (_: Exception) {
            return null
        }

        val type = obj["type"]?.jsonPrimitive?.content ?: return null

        return when (type) {
            "hello" -> ControlMessage.Hello(
                version = obj["version"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1,
                name = obj["name"]?.jsonPrimitive?.content ?: "",
                capabilities = obj["capabilities"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
                videoPort = obj["video_port"]?.jsonPrimitive?.content?.toIntOrNull() ?: 5290,
                audioPort = obj["audio_port"]?.jsonPrimitive?.content?.toIntOrNull() ?: 5289
            )

            "phone_connected" -> ControlMessage.PhoneConnected(
                phoneName = obj["phone_name"]?.jsonPrimitive?.content ?: "",
                phoneType = obj["phone_type"]?.jsonPrimitive?.content ?: ""
            )

            "phone_disconnected" -> ControlMessage.PhoneDisconnected(
                reason = obj["reason"]?.jsonPrimitive?.content ?: ""
            )

            "audio_start" -> {
                val purpose = AudioPurpose.fromWire(
                    obj["purpose"]?.jsonPrimitive?.content ?: ""
                ) ?: return null
                ControlMessage.AudioStart(
                    purpose = purpose,
                    sampleRate = obj["sample_rate"]?.jsonPrimitive?.content?.toIntOrNull() ?: 48000,
                    channels = obj["channels"]?.jsonPrimitive?.content?.toIntOrNull() ?: 2
                )
            }

            "audio_stop" -> {
                val purpose = AudioPurpose.fromWire(
                    obj["purpose"]?.jsonPrimitive?.content ?: ""
                ) ?: return null
                ControlMessage.AudioStop(purpose)
            }

            "mic_start" -> ControlMessage.MicStart(
                sampleRate = obj["sample_rate"]?.jsonPrimitive?.content?.toIntOrNull() ?: 16000
            )

            "mic_stop" -> ControlMessage.MicStop

            "nav_state" -> ControlMessage.NavState(
                maneuver = obj["maneuver"]?.jsonPrimitive?.content,
                distanceMeters = obj["distance_meters"]?.jsonPrimitive?.content?.toIntOrNull(),
                road = obj["road"]?.jsonPrimitive?.content,
                etaSeconds = obj["eta_seconds"]?.jsonPrimitive?.content?.toIntOrNull(),
                navImageBase64 = obj["nav_image_base64"]?.jsonPrimitive?.content
            )

            "media_metadata" -> ControlMessage.MediaMetadata(
                title = obj["title"]?.jsonPrimitive?.content,
                artist = obj["artist"]?.jsonPrimitive?.content,
                album = obj["album"]?.jsonPrimitive?.content,
                durationMs = obj["duration_ms"]?.jsonPrimitive?.content?.toLongOrNull(),
                positionMs = obj["position_ms"]?.jsonPrimitive?.content?.toLongOrNull(),
                playing = obj["playing"]?.jsonPrimitive?.content?.toBooleanStrictOrNull(),
                albumArtBase64 = obj["album_art_base64"]?.jsonPrimitive?.content
            )

            "config_echo" -> {
                val config = mutableMapOf<String, String>()
                obj.forEach { (key, value) ->
                    if (key != "type") {
                        config[key] = value.jsonPrimitive.content
                    }
                }
                ControlMessage.ConfigEcho(config)
            }

            "error" -> ControlMessage.Error(
                code = obj["code"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                message = obj["message"]?.jsonPrimitive?.content ?: ""
            )

            "stats" -> ControlMessage.Stats(
                videoFramesSent = obj["video_frames_sent"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
                audioFramesSent = obj["audio_frames_sent"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
                uptimeSeconds = obj["uptime_seconds"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0
            )

            else -> null
        }
    }

    fun serialize(message: ControlMessage): String {
        val obj = when (message) {
            is ControlMessage.AppHello -> buildJsonObject {
                put("type", "hello")
                put("version", message.version)
                put("name", message.name)
                put("display_width", message.displayWidth)
                put("display_height", message.displayHeight)
                put("display_dpi", message.displayDpi)
            }

            is ControlMessage.Touch -> buildJsonObject {
                put("type", "touch")
                put("action", message.action)
                if (message.pointers != null) {
                    putJsonArray("pointers") {
                        for (p in message.pointers) {
                            add(buildJsonObject {
                                put("id", p.id)
                                put("x", p.x)
                                put("y", p.y)
                            })
                        }
                    }
                } else {
                    message.x?.let { put("x", it) }
                    message.y?.let { put("y", it) }
                    message.pointerId?.let { put("pointer_id", it) }
                }
            }

            is ControlMessage.Gnss -> buildJsonObject {
                put("type", "gnss")
                put("nmea", message.nmea)
            }

            is ControlMessage.VehicleData -> buildJsonObject {
                put("type", "vehicle_data")
                message.speedKmh?.let { put("speed_kmh", it) }
                message.gear?.let { put("gear", it) }
                message.batteryPct?.let { put("battery_pct", it) }
                message.turnSignal?.let { put("turn_signal", it) }
                message.parkingBrake?.let { put("parking_brake", it) }
                message.nightMode?.let { put("night_mode", it) }
                message.fuelLevelPct?.let { put("fuel_level_pct", it) }
                message.rangeKm?.let { put("range_km", it) }
                message.lowFuel?.let { put("low_fuel", it) }
                message.odometerKm?.let { put("odometer_km", it) }
                message.ambientTempC?.let { put("ambient_temp_c", it) }
                message.steeringAngleDeg?.let { put("steering_angle_deg", it) }
                message.headlight?.let { put("headlight", it) }
                message.hazardLights?.let { put("hazard_lights", it) }
            }

            is ControlMessage.ConfigUpdate -> buildJsonObject {
                put("type", "config_update")
                message.config.forEach { (k, v) -> put(k, v) }
            }

            is ControlMessage.Button -> buildJsonObject {
                put("type", "button")
                put("keycode", message.keycode)
                put("down", message.down)
                put("metastate", message.metastate)
                put("longpress", message.longpress)
            }

            is ControlMessage.KeyframeRequest -> buildJsonObject {
                put("type", "keyframe_request")
            }

            // Bridge→App messages shouldn't be serialized by the app, but handle gracefully
            else -> return "{}"
        }
        return obj.toString()
    }
}
