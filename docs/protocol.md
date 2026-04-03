# OAL Wire Protocol — OpenAutoLink App ↔ Bridge

## Overview

Three independent TCP connections between the car app and the bridge. Each connection carries one type of data. The OAL protocol is phone-protocol-agnostic — the same framing carries Android Auto and CarPlay streams. The car app does not know which phone protocol is active.

```
App ──TCP:5288──▶ Bridge    (control: bidirectional JSON lines)
App ◀─TCP:5290── Bridge     (video: bridge → app, binary frames)
App ◀─TCP:5289──▶ Bridge    (audio: bidirectional binary frames)
```

## Connection Lifecycle

1. App connects to bridge control port (5288)
2. Bridge sends `hello` with capabilities
3. App sends `hello` back with its capabilities
4. App opens video (5290) and audio (5289) connections
5. Bridge sends `phone_connected` when phone session starts (AA or CarPlay)
6. Media streams begin on video/audio channels
7. `phone_disconnected` when phone leaves — app returns to waiting state
8. App can disconnect at any time; bridge handles cleanup

## Control Channel (TCP 5288)

Bidirectional newline-delimited JSON. Each message is a single JSON object followed by `\n`.

### Bridge → App

```jsonl
{"type":"hello","version":1,"name":"OpenAutoLink","capabilities":["h264","h265","vp9"],"video_port":5290,"audio_port":5289}
{"type":"phone_connected","phone_name":"Pixel 10","phone_type":"android"}
{"type":"phone_connected","phone_name":"iPhone","phone_type":"iphone"}
{"type":"phone_disconnected","reason":"user_disconnect"}
{"type":"audio_start","purpose":"media","sample_rate":48000,"channels":2}
{"type":"audio_stop","purpose":"media"}
{"type":"mic_start","sample_rate":16000}
{"type":"mic_stop"}
{"type":"nav_state","maneuver":"turn_right","distance_meters":150,"road":"Main St","eta_seconds":420}
{"type":"nav_state","maneuver":"turn_right","distance_meters":150,"road":"Main St","eta_seconds":420,"nav_image_base64":"iVBORw0KGgo..."}
{"type":"nav_state","maneuver":"turn_right","distance_meters":150,"road":"Main St","eta_seconds":420,"cue":"Turn right onto Main St","lanes":[{"directions":[{"shape":"straight","highlighted":false},{"shape":"normal_right","highlighted":true}]}],"current_road":"Highway 101","destination":"123 Elm St","eta_formatted":"2:45 PM","time_to_arrival_seconds":1800}
{"type":"media_metadata","title":"Song Name","artist":"Artist","album":"Album","duration_ms":240000,"position_ms":60000,"playing":true}
{"type":"media_metadata","title":"Song Name","artist":"Artist","album":"Album","duration_ms":240000,"position_ms":60000,"playing":true,"album_art_base64":"iVBORw0KGgo..."}
{"type":"config_echo","video_codec":"h264","video_width":1920,"video_height":1080,"video_fps":60,"aa_resolution":"1080p"}
{"type":"error","code":100,"message":"Phone connection lost"}
{"type":"stats","video_frames_sent":1200,"audio_frames_sent":3400,"uptime_seconds":120}
{"type":"phone_battery","level":85,"time_remaining_s":14400,"critical":false}
{"type":"voice_session","status":"start"}
{"type":"voice_session","status":"end"}
{"type":"phone_status","signal_strength":3,"calls":[{"state":"in_call","duration_s":45,"caller_number":"+15550123","caller_id":"Mom"}]}
{"type":"phone_status","signal_strength":4,"calls":[]}
{"type":"carplay_pin","pin":"4829"}
```

### App → Bridge

```jsonl
{"type":"hello","version":1,"name":"OpenAutoLink App","display_width":2628,"display_height":800,"display_dpi":160}
{"type":"touch","action":0,"x":500,"y":300,"pointer_id":0}
{"type":"touch","action":2,"pointers":[{"id":0,"x":100,"y":200},{"id":1,"x":300,"y":400}]}
{"type":"button","keycode":87,"down":true,"metastate":0,"longpress":false}
{"type":"gnss","nmea":"$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*6A"}
{"type":"vehicle_data","speed_kmh":65.0,"gear":"D","battery_pct":72,"turn_signal":"left","parking_brake":false,"night_mode":false}
{"type":"vehicle_data","accel_x_e3":123,"accel_y_e3":-9810,"accel_z_e3":45,"gyro_rx_e3":10,"gyro_ry_e3":-5,"gyro_rz_e3":2,"compass_bearing_e6":127500000,"sat_in_use":8,"sat_in_view":12}
{"type":"vehicle_data","rpm_e3":2500000}
{"type":"config_update","video_codec":"h265","video_fps":30}
{"type":"keyframe_request"}
```

### Bridge → App: `phone_battery`

Phone battery status forwarded from the AA session's `BatteryStatusNotification`.

| Field | Type | Description |
|-------|------|-------------|
| `level` | int | Battery percentage (0–100) |
| `time_remaining_s` | int | Estimated seconds of battery remaining |
| `critical` | bool | True if phone reports critical battery |

### Bridge → App: `voice_session`

Google Assistant voice session status from the AA session's `VoiceSessionNotification`.

| Field | Type | Description |
|-------|------|-------------|
| `status` | string | `"start"` or `"end"` |

### Bridge → App: `phone_status`

Phone signal strength and active call state from the AA `PhoneStatusService` channel.

| Field | Type | Description |
|-------|------|-------------|
| `signal_strength` | int | Signal bars (0–4), -1 if unknown |
| `calls` | array | Active calls (empty array = no calls) |
| `calls[].state` | string | `"in_call"`, `"unknown"` |
| `calls[].duration_s` | int | Call duration in seconds |
| `calls[].caller_number` | string? | Phone number (optional) |
| `calls[].caller_id` | string? | Contact name (optional) |

### Bridge → App: `carplay_pin`

Sent when an iPhone is pairing with the bridge for the first time via HomeKit pair-setup. The app must display this PIN so the user can confirm it on their iPhone.

| Field | Type | Description |
|-------|------|-------------|
| `pin` | string | 4-digit PIN to display (e.g. `"4829"`) |

### App → Bridge: `vehicle_data`

Batched vehicle sensor data from AAOS VHAL properties and device sensors.

| Field | Type | Description |
|-------|------|-------------|
| `speed_kmh` | float? | Vehicle speed in km/h |
| `gear` | string? | `"P"`, `"R"`, `"N"`, `"D"`, `"1"`–`"4"` |
| `battery_pct` | int? | EV battery level (Wh raw value) |
| `turn_signal` | string? | `"none"`, `"left"`, `"right"` |
| `parking_brake` | bool? | Parking brake engaged |
| `night_mode` | bool? | AAOS night mode active |
| `fuel_level_pct` | int? | Fuel level percentage (ICE vehicles) |
| `range_km` | float? | Remaining range in km |
| `low_fuel` | bool? | Low fuel warning |
| `odometer_km` | float? | Odometer reading in km |
| `ambient_temp_c` | float? | Outside temperature °C |
| `steering_angle_deg` | float? | Steering angle (if available) |
| `headlight` | int? | Headlight state enum |
| `hazard_lights` | bool? | Hazard lights active |
| `accel_x_e3` | int? | Accelerometer X (m/s² × 1000) |
| `accel_y_e3` | int? | Accelerometer Y (m/s² × 1000) |
| `accel_z_e3` | int? | Accelerometer Z (m/s² × 1000) |
| `gyro_rx_e3` | int? | Gyroscope rotation X (rad/s × 1000) |
| `gyro_ry_e3` | int? | Gyroscope rotation Y (rad/s × 1000) |
| `gyro_rz_e3` | int? | Gyroscope rotation Z (rad/s × 1000) |
| `compass_bearing_e6` | int? | Compass bearing (degrees × 1,000,000) |
| `compass_pitch_e6` | int? | Pitch (degrees × 1,000,000) |
| `compass_roll_e6` | int? | Roll (degrees × 1,000,000) |
| `sat_in_use` | int? | GPS satellites used in fix |
| `sat_in_view` | int? | GPS satellites visible |
| `rpm_e3` | int? | Engine RPM × 1000 (ICE only, null on EV) |

IMU fields (`accel_*`, `gyro_*`, `compass_*`) are sent by the app's `ImuForwarder` at ~10 Hz as a separate `vehicle_data` message (not batched with VHAL data). VHAL fields are sent by `VehicleDataForwarder` at ≤2 Hz. All fields are optional — null/absent means unavailable.

### Touch Action Codes
| Code | Meaning |
|------|---------|
| 0 | ACTION_DOWN (finger touches screen) |
| 1 | ACTION_UP (finger lifts) |
| 2 | ACTION_MOVE (finger moves) |
| 3 | ACTION_CANCEL |
| 5 | ACTION_POINTER_DOWN (additional finger) |
| 6 | ACTION_POINTER_UP (additional finger lifts) |

Single-touch: `x`, `y`, `pointer_id` fields.
Multi-touch: `pointers` array with `id`, `x`, `y` per pointer.

### Button (Key Event)

Steering wheel and media button presses. Keycodes use Android/AA numeric values (identical numbering).

| Field | Type | Description |
|-------|------|-------------|
| `keycode` | int | AA keycode (e.g. 87=MEDIA_NEXT, 84=SEARCH/voice) |
| `down` | bool | `true` = key pressed, `false` = key released |
| `metastate` | int | Modifier flags (0 = none) |
| `longpress` | bool | `true` if long-press repeat |

Common keycodes:
| Code | Key |
|------|-----|
| 84 | SEARCH (voice assistant trigger) |
| 85 | MEDIA_PLAY_PAUSE |
| 86 | MEDIA_STOP |
| 87 | MEDIA_NEXT |
| 88 | MEDIA_PREVIOUS |
| 89 | MEDIA_REWIND |
| 90 | MEDIA_FAST_FORWARD |
| 126 | MEDIA_PLAY |
| 127 | MEDIA_PAUSE |

Volume keys (VOLUME_UP=24, VOLUME_DOWN=25) are handled locally by the app via AudioManager and are NOT forwarded to the bridge.

### Audio Purpose Values
| Purpose | Description |
|---------|-------------|
| `media` | Music, podcasts |
| `navigation` | Turn-by-turn prompts |
| `assistant` | Voice assistant (Google Assistant) |
| `phone_call` | Active phone call |
| `alert` | Incoming call ring, system alerts |

## Video Channel (TCP 5290)

Bridge → App only. Binary frames with a fixed 16-byte header.

### Frame Format
```
Offset  Size  Type    Field
0       4     u32le   payload_length (bytes of codec data following header)
4       2     u16le   width (pixels)
6       2     u16le   height (pixels)
8       4     u32le   pts_ms (presentation timestamp, milliseconds)
12      2     u16le   flags (bitfield)
14      2     u16le   reserved (0x0000)
--- header end (16 bytes) ---
16      N     bytes   codec payload (raw H.264/H.265/VP9)
```

### Flags Bitfield
| Bit | Meaning |
|-----|---------|
| 0 | Keyframe (IDR) |
| 1 | Codec config (SPS/PPS/VPS — must be fed to MediaFormat, not decoded) |
| 2 | End of stream |

### Frame Ordering Rules
1. First frame after connection MUST have flag bit 1 (codec config)
2. Next frame MUST have flag bit 0 (keyframe/IDR)
3. Subsequent frames may be non-IDR (P-frames, B-frames)
4. After `keyframe_request`: bridge sends a new codec config + IDR pair

## Audio Channel (TCP 5289)

Bidirectional. Same header format for both directions.

### Frame Format
```
Offset  Size  Type    Field
0       1     u8      direction (0 = bridge→app playback, 1 = app→bridge mic)
1       1     u8      purpose (0=media, 1=nav, 2=assistant, 3=call, 4=alert)
2       2     u16le   sample_rate (Hz)
4       1     u8      channels (1=mono, 2=stereo)
5       3     u24le   payload_length (bytes, max 16MB)
--- header end (8 bytes) ---
8       N     bytes   raw PCM (16-bit signed little-endian)
```

Direction 0 (bridge→app): playback audio routed by purpose.
Direction 1 (app→bridge): microphone capture. Purpose field = 2 (assistant) or 3 (call).

### PCM Format
- Encoding: 16-bit signed integer, little-endian
- Interleaved for stereo (L R L R ...)
- No compression — raw PCM over TCP (local network, bandwidth is not a constraint)

## Remote Diagnostics Channel

The control channel doubles as a diagnostics backhaul. Since the GM AAOS head unit has **no ADB access**, the app sends structured log/telemetry messages back to the bridge over the existing control TCP connection (port 5288). The bridge writes these to its stderr (visible via `journalctl -u openautolink.service -f` over SSH), giving near-real-time visibility into app behavior on the car.

Diagnostics are **opt-in** — the app only sends them when enabled in Settings (default: off). When enabled, the app sends two types of messages:

### App → Bridge: `app_log`

Structured log events from the app. These are **not** raw logcat lines — they are curated, tagged events for specific areas of interest.

```jsonl
{"type":"app_log","ts":1711929600000,"level":"INFO","tag":"video","msg":"Codec selected: c2.qti.avc.decoder (HW)"}
{"type":"app_log","ts":1711929600100,"level":"WARN","tag":"audio","msg":"AudioTrack underrun on purpose=media, count=3"}
{"type":"app_log","ts":1711929600200,"level":"ERROR","tag":"cluster","msg":"ClusterMainSession destroyed by system after 2300ms"}
{"type":"app_log","ts":1711929600300,"level":"INFO","tag":"vhal","msg":"Property PERF_VEHICLE_SPEED unavailable, skipping"}
{"type":"app_log","ts":1711929600400,"level":"DEBUG","tag":"update","msg":"PackageInstaller session rejected: INSTALL_FAILED_USER_RESTRICTED"}
{"type":"app_log","ts":1711929600500,"level":"INFO","tag":"input","msg":"KeyEvent KEYCODE_VOICE_ASSIST intercepted=false, sent to system"}
```

| Field | Type | Description |
|-------|------|-------------|
| `ts` | u64 | Unix timestamp milliseconds (System.currentTimeMillis) |
| `level` | string | `DEBUG`, `INFO`, `WARN`, `ERROR` |
| `tag` | string | Subsystem: `video`, `audio`, `cluster`, `vhal`, `update`, `input`, `transport`, `session`, `system` |
| `msg` | string | Human-readable event description (max 500 chars) |

### App → Bridge: `app_telemetry`

Periodic snapshot of app-side metrics. Sent every 5 seconds when diagnostics are enabled.

```jsonl
{"type":"app_telemetry","ts":1711929605000,"video":{"fps":58.2,"decoded":1746,"dropped":3,"codec":"c2.qti.avc.decoder","width":1920,"height":1080},"audio":{"active":["media"],"underruns":{"media":0},"frames_written":{"media":14400}},"session":{"state":"STREAMING","uptime_ms":30000},"cluster":{"bound":true,"alive":true,"rebinds":0}}
```

| Field | Type | Description |
|-------|------|-------------|
| `ts` | u64 | Snapshot timestamp |
| `video` | object | fps, decoded/dropped frame counts, active codec, resolution |
| `audio` | object | active purposes, underrun counts, frames written per purpose |
| `session` | object | current state, session uptime |
| `cluster` | object | bound/alive status, rebind count (tracks GM kill behavior) |

### Bridge Handling

The bridge treats `app_log` and `app_telemetry` as opaque — it writes them to stderr with a `[CAR]` prefix and does not parse or act on them. This keeps the bridge simple and makes all app diagnostics visible in the SSH journal stream:

```
[CAR] INFO  video   Codec selected: c2.qti.avc.decoder (HW)
[CAR] WARN  audio   AudioTrack underrun on purpose=media, count=3
[CAR] ERROR cluster ClusterMainSession destroyed by system after 2300ms
[CAR] TELEM {"video":{"fps":58.2,...},"audio":{...},...}
```

### Performance Guardrails

- `app_log` messages are rate-limited to **20/second** in the app (ring buffer, newest wins). This prevents log storms from blocking the control channel
- `app_telemetry` is sent every 5 seconds — one JSON line, negligible bandwidth
- Both are suppressed entirely when diagnostics are disabled
- Neither message type generates a response from the bridge
- Log level filtering in Settings: choose minimum level to send (DEBUG sends everything, ERROR sends only errors)

## Error Handling

- If control channel disconnects: app drops video/audio connections, returns to DISCONNECTED
- If video channel disconnects: app shows "No Video" overlay, keeps control/audio alive
- If audio channel disconnects: app continues with no audio, keeps control/video alive
- Bridge sends `error` control messages for non-fatal issues (phone AA errors, config rejections)

## Discovery

### mDNS (preferred)
Bridge advertises `_openautolink._tcp` via Avahi. TXT records include:
- `version=1`
- `name=OpenAutoLink`
- `video_port=5290`
- `audio_port=5289`

### Manual
User enters bridge IP in app settings. Control port 5288 is default.

### UDP Broadcast
Bridge responds to UDP broadcast on port 5287 with a JSON discovery response.

## Design Rationale

### Why not CPC200?
CPC200 was designed for USB adapters with constrained FFS endpoints. Its patterns (heartbeat-gated single-packet writes, deferred bootstrap, magic+checksum headers) were workarounds for hardware limitations we no longer have. TCP gives us proper flow control, multiplexing (via separate ports), and framing (via length-prefixed messages).

### Why 3 connections instead of multiplexing?
- **Eliminates head-of-line blocking**: a large video frame doesn't delay an audio frame
- **Independent lifecycle**: audio can reconnect without dropping video
- **Simpler implementation**: no framing/demuxing needed — each connection knows its type
- **Proven**: the v1.14.0 separate audio TCP in carlink_native resolved choppy audio

### Why JSON for control?
- Human-readable for debugging (`nc bridge-ip 5288` shows live state)
- Extensible without breaking backward compatibility
- Control messages are infrequent (<10/sec) — JSON overhead is negligible
- No protobuf dependency in the app

### Why raw binary for video/audio?
- Minimal overhead (12 or 8 bytes per frame vs 16+ for CPC200)
- No serialization library needed
- Deterministic parsing (fixed-size header, then payload)
- Video: ~500-2000 frames/sec. Audio: ~100 frames/sec. Overhead matters here
