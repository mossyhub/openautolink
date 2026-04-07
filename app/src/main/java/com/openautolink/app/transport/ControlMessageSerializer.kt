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
                audioPort = obj["audio_port"]?.jsonPrimitive?.content?.toIntOrNull() ?: 5289,
                bridgeVersion = obj["bridge_version"]?.jsonPrimitive?.content,
                bridgeSha256 = obj["bridge_sha256"]?.jsonPrimitive?.content
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

            "nav_state_clear" -> ControlMessage.NavStateClear

            "nav_state" -> {
                val lanes = obj["lanes"]?.jsonArray?.map { laneEl ->
                    val laneObj = laneEl.jsonObject
                    val directions = laneObj["directions"]?.jsonArray?.map { dirEl ->
                        val dirObj = dirEl.jsonObject
                        ControlMessage.NavLaneDirection(
                            shape = dirObj["shape"]?.jsonPrimitive?.content ?: "unknown",
                            highlighted = dirObj["highlighted"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                        )
                    } ?: emptyList()
                    ControlMessage.NavLane(directions)
                }
                ControlMessage.NavState(
                    maneuver = obj["maneuver"]?.jsonPrimitive?.content,
                    distanceMeters = obj["distance_meters"]?.jsonPrimitive?.content?.toIntOrNull(),
                    road = obj["road"]?.jsonPrimitive?.content,
                    etaSeconds = obj["eta_seconds"]?.jsonPrimitive?.content?.toIntOrNull(),
                    navImageBase64 = obj["nav_image_base64"]?.jsonPrimitive?.content,
                    lanes = lanes,
                    cue = obj["cue"]?.jsonPrimitive?.content,
                    roundaboutExitNumber = obj["roundabout_exit_number"]?.jsonPrimitive?.content?.toIntOrNull(),
                    roundaboutExitAngle = obj["roundabout_exit_angle"]?.jsonPrimitive?.content?.toIntOrNull(),
                    displayDistance = obj["display_distance"]?.jsonPrimitive?.content,
                    displayDistanceUnit = obj["display_distance_unit"]?.jsonPrimitive?.content,
                    currentRoad = obj["current_road"]?.jsonPrimitive?.content,
                    destination = obj["destination"]?.jsonPrimitive?.content,
                    etaFormatted = obj["eta_formatted"]?.jsonPrimitive?.content,
                    timeToArrivalSeconds = obj["time_to_arrival_seconds"]?.jsonPrimitive?.content?.toLongOrNull()
                )
            }

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

            "phone_battery" -> ControlMessage.PhoneBattery(
                level = obj["level"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                timeRemainingSeconds = obj["time_remaining_s"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                critical = obj["critical"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
            )

            "voice_session" -> ControlMessage.VoiceSession(
                started = obj["status"]?.jsonPrimitive?.content == "start"
            )

            "phone_status" -> {
                val calls = obj["calls"]?.jsonArray?.map { callEl ->
                    val callObj = callEl.jsonObject
                    ControlMessage.PhoneCall(
                        state = callObj["state"]?.jsonPrimitive?.content ?: "unknown",
                        durationSeconds = callObj["duration_s"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                        callerNumber = callObj["caller_number"]?.jsonPrimitive?.content,
                        callerId = callObj["caller_id"]?.jsonPrimitive?.content
                    )
                } ?: emptyList()
                ControlMessage.PhoneStatus(
                    signalStrength = obj["signal_strength"]?.jsonPrimitive?.content?.toIntOrNull(),
                    calls = calls
                )
            }

            "paired_phones" -> {
                val phones = obj["phones"]?.jsonArray?.map { phoneEl ->
                    val phoneObj = phoneEl.jsonObject
                    ControlMessage.PairedPhone(
                        mac = phoneObj["mac"]?.jsonPrimitive?.content ?: "",
                        name = phoneObj["name"]?.jsonPrimitive?.content ?: "",
                        connected = phoneObj["connected"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                    )
                } ?: emptyList()
                ControlMessage.PairedPhones(phones = phones)
            }

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
                if (message.cutoutTop != 0 || message.cutoutBottom != 0 ||
                    message.cutoutLeft != 0 || message.cutoutRight != 0) {
                    put("cutout_top", message.cutoutTop)
                    put("cutout_bottom", message.cutoutBottom)
                    put("cutout_left", message.cutoutLeft)
                    put("cutout_right", message.cutoutRight)
                }
                if (message.barTop != 0 || message.barBottom != 0 ||
                    message.barLeft != 0 || message.barRight != 0) {
                    put("bar_top", message.barTop)
                    put("bar_bottom", message.barBottom)
                    put("bar_left", message.barLeft)
                    put("bar_right", message.barRight)
                }
            }

            is ControlMessage.Touch -> buildJsonObject {
                put("type", "touch")
                put("action", message.action)
                if (message.pointers != null) {
                    message.actionIndex?.let { put("action_index", it) }
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
                // Bridge field names and formats — must match live_session.cpp extract_* calls
                message.speedKmh?.let { put("speed_mm_s", (it / 3.6f * 1000).toInt()) }
                message.gearRaw?.let { put("gear", it) }
                (message.batteryPct ?: message.fuelLevelPct)?.let { put("fuel_level_pct", it) }
                message.rangeKm?.let { put("range_m", (it * 1000).toInt()) }
                message.lowFuel?.let { put("low_fuel", it) }
                message.parkingBrake?.let { put("parking_brake", it) }
                message.nightMode?.let { put("night_mode", it) }
                message.driving?.let { put("driving", it) }
                message.odometerKm?.let { put("odometer_km_e1", (it * 10).toInt()) }
                message.ambientTempC?.let { put("temp_e3", (it * 1000).toInt()) }
                message.headlight?.let { put("headlight", it) }
                message.hazardLights?.let { put("hazard", it) }
                // P5: IMU sensors
                message.accelXe3?.let { put("accel_x_e3", it) }
                message.accelYe3?.let { put("accel_y_e3", it) }
                message.accelZe3?.let { put("accel_z_e3", it) }
                message.gyroRxe3?.let { put("gyro_rx_e3", it) }
                message.gyroRye3?.let { put("gyro_ry_e3", it) }
                message.gyroRze3?.let { put("gyro_rz_e3", it) }
                message.compassBearingE6?.let { put("compass_bearing_e6", it) }
                message.compassPitchE6?.let { put("compass_pitch_e6", it) }
                message.compassRollE6?.let { put("compass_roll_e6", it) }
                // P5: GPS satellites
                message.satInUse?.let { put("sat_in_use", it) }
                message.satInView?.let { put("sat_in_view", it) }
                message.satellites?.let { sats ->
                    if (sats.isNotEmpty()) {
                        putJsonArray("satellites") {
                            for (s in sats) {
                                add(buildJsonObject {
                                    put("prn", s.prn)
                                    put("snr_e3", s.snrE3)
                                    put("used", s.usedInFix)
                                    put("az_e3", s.azimuthE3)
                                    put("el_e3", s.elevationE3)
                                })
                            }
                        }
                    }
                }
                // P6: RPM
                message.rpmE3?.let { put("rpm_e3", it) }
            }

            is ControlMessage.ConfigUpdate -> buildJsonObject {
                put("type", "config_update")
                message.config.forEach { (k, v) -> put(k, v) }
            }

            is ControlMessage.RestartServices -> buildJsonObject {
                put("type", "restart_services")
                put("wireless", message.wireless.toString())
                put("bluetooth", message.bluetooth.toString())
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

            is ControlMessage.ListPairedPhones -> buildJsonObject {
                put("type", "list_paired_phones")
            }

            is ControlMessage.SwitchPhone -> buildJsonObject {
                put("type", "switch_phone")
                put("mac", message.mac)
            }

            is ControlMessage.ForgetPhone -> buildJsonObject {
                put("type", "forget_phone")
                put("mac", message.mac)
            }

            is ControlMessage.AppLog -> buildJsonObject {
                put("type", "app_log")
                put("ts", message.ts)
                put("level", message.level)
                put("tag", message.tag)
                put("msg", message.msg)
            }

            is ControlMessage.AppTelemetry -> buildJsonObject {
                put("type", "app_telemetry")
                put("ts", message.ts)
                message.video?.let { v ->
                    put("video", buildJsonObject {
                        put("fps", v.fps)
                        put("decoded", v.decoded)
                        put("dropped", v.dropped)
                        put("codec", v.codec)
                        put("width", v.width)
                        put("height", v.height)
                    })
                }
                message.audio?.let { a ->
                    put("audio", buildJsonObject {
                        putJsonArray("active") { a.active.forEach { add(JsonPrimitive(it)) } }
                        put("underruns", buildJsonObject { a.underruns.forEach { (k, v) -> put(k, v) } })
                        put("frames_written", buildJsonObject { a.framesWritten.forEach { (k, v) -> put(k, v) } })
                    })
                }
                message.session?.let { s ->
                    put("session", buildJsonObject {
                        put("state", s.state)
                        put("uptime_ms", s.uptimeMs)
                    })
                }
                message.cluster?.let { c ->
                    put("cluster", buildJsonObject {
                        put("bound", c.bound)
                        put("alive", c.alive)
                        put("rebinds", c.rebinds)
                    })
                }
            }

            // Bridge→App messages shouldn't be serialized by the app, but handle gracefully
            else -> return "{}"
        }
        return obj.toString()
    }
}
