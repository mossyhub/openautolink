# OAL Wire Protocol — OpenAutoLink App ↔ Bridge

## Overview

Three independent TCP connections between the car app and the bridge. Each connection carries one type of data. This replaces the CPC200 protocol (which wrapped everything in 16-byte magic headers with inverted checksums and heartbeat-gated writes).

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
5. Bridge sends `phone_connected` when phone AA session starts
6. Media streams begin on video/audio channels
7. `phone_disconnected` when phone leaves — app returns to waiting state
8. App can disconnect at any time; bridge handles cleanup

## Control Channel (TCP 5288)

Bidirectional newline-delimited JSON. Each message is a single JSON object followed by `\n`.

### Bridge → App

```jsonl
{"type":"hello","version":1,"name":"OpenAutoLink","capabilities":["h264","h265","vp9"],"video_port":5290,"audio_port":5289}
{"type":"phone_connected","phone_name":"Pixel 10","phone_type":"android"}
{"type":"phone_disconnected","reason":"user_disconnect"}
{"type":"audio_start","purpose":"media","sample_rate":48000,"channels":2}
{"type":"audio_stop","purpose":"media"}
{"type":"mic_start","sample_rate":16000}
{"type":"mic_stop"}
{"type":"nav_state","maneuver":"turn_right","distance_meters":150,"road":"Main St","eta_seconds":420}
{"type":"media_metadata","title":"Song Name","artist":"Artist","album":"Album","duration_ms":240000,"position_ms":60000,"playing":true}
{"type":"config_echo","video_codec":"h264","video_width":1920,"video_height":1080,"video_fps":60,"aa_resolution":"1080p"}
{"type":"error","code":100,"message":"Phone connection lost"}
{"type":"stats","video_frames_sent":1200,"audio_frames_sent":3400,"uptime_seconds":120}
```

### App → Bridge

```jsonl
{"type":"hello","version":1,"name":"OpenAutoLink App","display_width":2628,"display_height":800,"display_dpi":160}
{"type":"touch","action":0,"x":500,"y":300,"pointer_id":0}
{"type":"touch","action":2,"pointers":[{"id":0,"x":100,"y":200},{"id":1,"x":300,"y":400}]}
{"type":"button","keycode":87,"down":true,"metastate":0,"longpress":false}
{"type":"gnss","nmea":"$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*6A"}
{"type":"vehicle_data","speed_kmh":65.0,"gear":"D","battery_pct":72,"turn_signal":"left"}
{"type":"config_update","video_codec":"h265","video_fps":30}
{"type":"keyframe_request"}
```

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
