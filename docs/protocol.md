# OAL Wire Protocol — OpenAutoLink App ↔ Bridge

## Overview

Three independent TCP connections between the car app and the bridge. Each connection carries one type of data. The OAL protocol is phone-protocol-agnostic — the same framing carries Android Auto streams. The car app does not know which phone protocol is active.

```
App ──TCP:5288──▶ Bridge    (control: bidirectional JSON lines)
App ◀─TCP:5290── Bridge     (video: bridge → app, binary frames)
App ◀─TCP:5289──▶ Bridge    (audio: bidirectional binary frames)
```

## Connection Lifecycle

1. App connects to bridge control port (5288)
2. Bridge sends `hello` with capabilities and protocol version
3. App checks protocol compatibility (see Protocol Versioning below)
4. App sends `hello` back with its capabilities and protocol version
5. App opens video (5290) and audio (5289) connections
6. Bridge sends `phone_connected` when phone session starts
7. Media streams begin on video/audio channels
8. `phone_disconnected` when phone leaves — app returns to waiting state
9. App can disconnect at any time; bridge handles cleanup

## Protocol Versioning

Both sides declare `protocol_version` (what I speak) and `min_protocol_version` (oldest I can talk to) in their hello messages. On receiving a hello, each side checks:

```
if peer.min_protocol_version > my.protocol_version → INCOMPATIBLE
```

**Current values:** `protocol_version=1`, `min_protocol_version=1`

**Rules for protocol changes:**
- **Additive changes** (new optional JSON fields, new message types): no version bump needed. The other side ignores unknown fields/types.
- **Breaking changes** (altered binary headers, new mandatory fields, removed messages): bump `protocol_version` AND set `min_protocol_version` to exclude the old format.
- The app refuses to push a bridge update if the new bridge would be incompatible with the running app.

## Control Channel (TCP 5288)

Bidirectional newline-delimited JSON. Each message is a single JSON object followed by `\n`.

### Bridge → App

```jsonl
{"type":"hello","version":1,"name":"OpenAutoLink","capabilities":["h264","h265","vp9"],"video_port":5290,"audio_port":5289,"protocol_version":1,"min_protocol_version":1,"bridge_version":"0.1.54","bridge_sha256":"a1b2c3...","build_source":"github"}
{"type":"phone_connected","phone_name":"Pixel 10","phone_type":"android"}
{"type":"phone_disconnected","reason":"user_disconnect"}
{"type":"audio_start","purpose":"media","sample_rate":48000,"channels":2}
{"type":"audio_stop","purpose":"media"}
{"type":"mic_start","sample_rate":16000}
{"type":"mic_stop"}
{"type":"nav_state","maneuver":"turn_right","distance_meters":150,"road":"Main St","eta_seconds":420}
{"type":"nav_state","maneuver":"turn_right","distance_meters":150,"road":"Main St","eta_seconds":420,"nav_image_base64":"iVBORw0KGgo..."}
{"type":"nav_state","maneuver":"turn_right","distance_meters":150,"road":"Main St","eta_seconds":420,"cue":"Turn right onto Main St","lanes":[{"directions":[{"shape":"straight","highlighted":false},{"shape":"normal_right","highlighted":true}]}],"current_road":"Highway 101","destination":"123 Elm St","eta_formatted":"2:45 PM","time_to_arrival_seconds":1800}
{"type":"nav_state_clear"}
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
{"type":"paired_phones","phones":[{"mac":"AA:BB:CC:DD:EE:FF","name":"Pixel 10","connected":true},{"mac":"11:22:33:44:55:66","name":"Galaxy S24","connected":false}]}
```

### App → Bridge

```jsonl
{"type":"hello","version":1,"name":"OpenAutoLink App","display_width":2914,"display_height":1134,"display_dpi":200,"protocol_version":1,"min_protocol_version":1,"cutout_top":167,"cutout_bottom":0,"cutout_left":0,"cutout_right":285,"bar_top":166,"bar_bottom":0,"bar_left":0,"bar_right":0}
{"type":"touch","action":0,"x":500,"y":300,"pointer_id":0}
{"type":"touch","action":2,"pointers":[{"id":0,"x":100,"y":200},{"id":1,"x":300,"y":400}]}
{"type":"button","keycode":87,"down":true,"metastate":0,"longpress":false}
{"type":"gnss","nmea":"$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*6A"}
{"type":"vehicle_data","speed_kmh":65.0,"gear":"D","battery_pct":72,"turn_signal":"left","parking_brake":false,"night_mode":false}
{"type":"vehicle_data","accel_x_e3":123,"accel_y_e3":-9810,"accel_z_e3":45,"gyro_rx_e3":10,"gyro_ry_e3":-5,"gyro_rz_e3":2,"compass_bearing_e6":127500000,"sat_in_use":8,"sat_in_view":12}
{"type":"vehicle_data","rpm_e3":2500000}
{"type":"config_update","video_codec":"h265","video_fps":30,"aa_width_margin":"0","aa_height_margin":"0","aa_pixel_aspect":"14444"}
{"type":"keyframe_request"}
{"type":"list_paired_phones"}
{"type":"switch_phone","mac":"AA:BB:CC:DD:EE:FF"}
```

### App → Bridge: `hello` fields

| Field | Type | Description |
|-------|------|-------------|
| `display_width` | int | Full AAOS framebuffer width (pixels) |
| `display_height` | int | Full AAOS framebuffer height (pixels) |
| `display_dpi` | int | Logical display density |
| `protocol_version` | int | OAL protocol version this app speaks |
| `min_protocol_version` | int | Oldest OAL protocol version this app can talk to |
| `cutout_top/bottom/left/right` | int | Display cutout insets — physically curved/missing screen areas (pixels) |
| `bar_top/bottom/left/right` | int | System bar insets — status bar, nav bar (pixels) |

### Bridge → App: `hello` fields

| Field | Type | Description |
|-------|------|-------------|
| `capabilities` | string[] | Supported video codecs (`"h264"`, `"h265"`, `"vp9"`) |
| `video_port` | int | TCP port for video channel |
| `audio_port` | int | TCP port for audio channel |
| `protocol_version` | int? | OAL protocol version this bridge speaks (absent on old bridges) |
| `min_protocol_version` | int? | Oldest OAL protocol version this bridge can talk to (absent on old bridges) |
| `bridge_version` | string? | Human-readable version string (e.g. `"0.1.54"`) |
| `bridge_sha256` | string? | SHA-256 hex of the running binary |
| `build_source` | string? | `"github"` (CI build) or `"local"` (WSL/dev build). Absent on old bridges |

The bridge uses these to auto-compute AA video parameters:
- **`pixel_aspect_ratio_e4`**: Tells AA the display is wider than 16:9, so it layouts UI for the actual display AR. Computed as `display_ar / video_ar × 10000`.
- **`height_margin`**: Tells AA how many video pixels are outside the visible area. AA keeps buttons in the visible center band.
- **`stable_insets`**: Tells AA where the physical screen curves are (from cutout), scaled to video coordinates. AA keeps interactive UI away from these areas.

All three are auto-computed if not overridden by env file or app config_update.

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

### Bridge → App: `paired_phones`

Sent in response to `list_paired_phones`. Contains all Bluetooth-paired devices on the bridge, with their connection status.

| Field | Type | Description |
|-------|------|-------------|
| `phones` | array | List of paired phone objects |
| `phones[].mac` | string | Bluetooth MAC address (e.g. `"AA:BB:CC:DD:EE:FF"`) |
| `phones[].name` | string | Device name from BlueZ |
| `phones[].connected` | bool | Whether the device is currently connected |

### App → Bridge: `list_paired_phones`

Request the bridge to enumerate all Bluetooth-paired devices and respond with `paired_phones`. No fields — just the type.

### App → Bridge: `switch_phone`

Request the bridge to disconnect the current phone and connect to a different paired device. The bridge disconnects all connected devices, then initiates a BT connection to the target MAC. This triggers the RFCOMM WiFi credential exchange, leading to a new AA session.

| Field | Type | Description |
|-------|------|-------------|
| `mac` | string | Bluetooth MAC of the target phone |

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

## Bridge Update Protocol

The app can update the bridge binary over the existing control channel. This allows bridge-only releases without requiring users to rebuild their AAB.

### Hello Extensions

The bridge's `hello` message includes version and identity fields:

```jsonl
{"type":"hello","version":1,"name":"OpenAutoLink","capabilities":["h264","h265"],"video_port":5290,"audio_port":5289,"bridge_version":"0.1.53","bridge_sha256":"a1b2c3d4..."}
```

| Field | Type | Description |
|-------|------|-------------|
| `bridge_version` | string | Semantic version from build (e.g. `"0.1.53"`) |
| `bridge_sha256` | string | SHA-256 hex of the running binary (64 chars) |

The app uses `bridge_sha256` (not version string) to determine if an update is needed. This handles the case where a developer builds locally with arbitrary version numbers — when they re-enable auto-update, the SHA mismatch triggers a pull from the GitHub release.

### Update Flow

```
1. Bridge sends hello with bridge_version + bridge_sha256
2. App compares bridge_sha256 against cached latest GitHub Release asset hash
3. If mismatch AND auto-update enabled:
   a. App downloads binary from GitHub (cached in app internal storage)
   b. App sends bridge_update_offer
   c. Bridge responds with bridge_update_accept (or bridge_update_reject)
   d. App sends bridge_update_data chunks
   e. App sends bridge_update_complete with SHA-256
   f. Bridge verifies hash, swaps binary, sends bridge_update_status
   g. Bridge restarts — app reconnects via existing reconnect logic
```

### App → Bridge: `bridge_update_offer`

```jsonl
{"type":"bridge_update_offer","version":"0.1.54","size":2845632,"sha256":"abc123..."}
```

| Field | Type | Description |
|-------|------|-------------|
| `version` | string | Version of the update |
| `size` | int | Total binary size in bytes |
| `sha256` | string | Expected SHA-256 of the complete binary |

### Bridge → App: `bridge_update_accept`

```jsonl
{"type":"bridge_update_accept"}
```

Sent when the bridge is ready to receive update data. Bridge will not accept updates when `OAL_BRIDGE_UPDATE_MODE=disabled`.

### Bridge → App: `bridge_update_reject`

```jsonl
{"type":"bridge_update_reject","reason":"disabled"}
```

| Field | Type | Description |
|-------|------|-------------|
| `reason` | string | `"disabled"` (dev mode), `"in_session"` (phone connected), `"disk_space"` |

### App → Bridge: `bridge_update_data`

```jsonl
{"type":"bridge_update_data","offset":0,"length":65536,"data":"base64..."}
```

| Field | Type | Description |
|-------|------|-------------|
| `offset` | int | Byte offset in the binary |
| `length` | int | Number of decoded bytes in this chunk |
| `data` | string | Base64-encoded chunk (max 64KB decoded per chunk) |

### App → Bridge: `bridge_update_complete`

```jsonl
{"type":"bridge_update_complete","sha256":"abc123..."}
```

Signals end of transfer. Bridge verifies reassembled binary matches SHA-256.

### Bridge → App: `bridge_update_status`

```jsonl
{"type":"bridge_update_status","status":"verified","message":"Update verified, restarting..."}
{"type":"bridge_update_status","status":"failed","message":"SHA-256 mismatch"}
{"type":"bridge_update_status","status":"applying","message":"Swapping binary..."}
```

| Field | Type | Description |
|-------|------|-------------|
| `status` | string | `"verified"`, `"applying"`, `"applied"`, `"failed"` |
| `message` | string | Human-readable status |

### Dev Mode

Bridge env `OAL_BRIDGE_UPDATE_MODE` controls update behavior:

| Value | Behavior |
|-------|----------|
| `auto` (default) | Accept updates from app |
| `disabled` | Reject all update offers (for developers building locally) |

App preference `bridge_auto_update` (default: true) controls whether the app checks for and pushes updates. When a user toggles this back on after developing locally, the SHA-256 mismatch against the GitHub release triggers a fresh download and push.

### Security

- Binary integrity verified by SHA-256 at both ends
- SHA-256 sourced from GitHub Releases API over HTTPS
- Bridge writes to temp file, verifies, then atomically replaces
- Update rejected while phone session is active (prevents mid-drive restart)

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
