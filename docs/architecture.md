# OpenAutoLink — App Architecture

## Design Principles

1. **Bridge-native**: The app exists to display what the bridge sends. No USB adapter abstraction layer. The app is phone-protocol-agnostic — it speaks OAL and renders identically regardless of whether the bridge is running Android Auto or CarPlay
2. **Island independence**: Each component is a self-contained package with public API, internal implementation, and its own test suite
3. **Test-anchored**: Tests define the contract. Write interface → write tests → write implementation
4. **Real-time first**: Video is disposable, audio is sacred, touch is immediate. Latency over completeness
5. **AAOS-aware**: Car API integration where available, graceful degradation where not

## Component Islands

### 1. Transport (`com.openautolink.app.transport`)

The TCP connection manager. Owns 3 connections to the bridge.

**Public API:**
```kotlin
interface BridgeConnection {
    val connectionState: StateFlow<ConnectionState>
    val controlMessages: Flow<ControlMessage>
    val videoFrames: Flow<VideoFrame>
    val audioFrames: Flow<AudioFrame>

    suspend fun connect(host: String, controlPort: Int = 5288)
    suspend fun disconnect()
    suspend fun sendControlMessage(message: ControlMessage)
    suspend fun sendTouchEvent(event: TouchEvent)
    suspend fun sendGnssData(nmea: String)
    suspend fun sendVehicleData(data: VehicleData)
    suspend fun sendMicAudio(pcm: ByteArray, sampleRate: Int)
}

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, PHONE_CONNECTED, STREAMING }

// Parsed control messages from bridge
sealed class ControlMessage {
    data class Hello(val version: Int, val name: String, val capabilities: List<String>) : ControlMessage()
    data class PhoneConnected(val phoneName: String, val phoneType: String) : ControlMessage()
    object PhoneDisconnected : ControlMessage()
    data class AudioStart(val purpose: AudioPurpose, val sampleRate: Int, val channels: Int) : ControlMessage()
    data class AudioStop(val purpose: AudioPurpose) : ControlMessage()
    data class NavState(val maneuver: String?, val distanceMeters: Int?, val road: String?) : ControlMessage()
    data class MediaMetadata(val title: String?, val artist: String?, val album: String?, val durationMs: Long?) : ControlMessage()
    data class ConfigEcho(val config: Map<String, Any>) : ControlMessage()
    data class MicStart(val sampleRate: Int) : ControlMessage()
    object MicStop : ControlMessage()
    data class Error(val code: Int, val message: String) : ControlMessage()
    data class Stats(val videoFramesSent: Long, val audioFramesSent: Long, val uptimeSeconds: Long) : ControlMessage()
    // P1
    data class PhoneBattery(val level: Int, val timeRemainingSeconds: Int, val critical: Boolean) : ControlMessage()
    data class VoiceSession(val started: Boolean) : ControlMessage()
    // P4
    data class PhoneStatus(val signalStrength: Int?, val calls: List<PhoneCall>) : ControlMessage()
    data class PhoneCall(val state: String, val durationSeconds: Int, val callerNumber: String?, val callerId: String?)
}
```

**Internal:**
- `TcpControlChannel` — JSON line reader/writer on port 5288
- `TcpVideoChannel` — binary frame reader on port 5290
- `TcpAudioChannel` — bidirectional binary frames on port 5289
- `ConnectionManager` — lifecycle, reconnect with exponential backoff (1s, 2s, 4s... cap 30s)
- `BridgeDiscovery` — mDNS (`_openautolink._tcp`) + manual IP entry

**Tests:**
- Unit: JSON serialization/deserialization, frame header parsing, reconnect timing
- Integration: mock TCP server, full connect/send/receive cycle

---

### 2. Video (`com.openautolink.app.video`)

MediaCodec decoder bound to a Surface.

**Public API:**
```kotlin
interface VideoDecoder {
    val decoderState: StateFlow<DecoderState>
    val stats: StateFlow<VideoStats>

    fun attach(surface: Surface, width: Int, height: Int)
    fun detach()
    fun onFrame(frame: VideoFrame)  // Called from transport flow collector
    fun requestKeyframe()           // Tells session to ask bridge for IDR
    fun pause()
    fun resume()
}

data class VideoFrame(val width: Int, val height: Int, val ptsMs: Long, val flags: Int, val data: ByteArray)
data class VideoStats(val fps: Float, val framesDecoded: Long, val framesDropped: Long, val codec: String)
enum class DecoderState { IDLE, CONFIGURING, DECODING, PAUSED, ERROR }
```

**Internal:**
- `MediaCodecDecoder` — codec lifecycle, input/output buffer management
- `CodecSelector` — picks best HW decoder for mime type via `MediaCodecList`
- `NalParser` — extracts SPS/PPS/VPS, identifies IDR frames
- Dedicated decode thread (`HandlerThread`) for MediaCodec callbacks

**Tests:**
- Unit: NAL parsing, frame flag interpretation, codec selection logic
- Integration: decode a known H.264 test stream to a mock Surface

---

### 3. Audio (`com.openautolink.app.audio`)

Pre-allocated per-purpose AudioTrack management.

**Public API:**
```kotlin
interface AudioPlayer {
    val stats: StateFlow<AudioStats>

    fun initialize()
    fun release()
    fun onAudioFrame(frame: AudioFrame)    // From transport
    fun startPurpose(purpose: AudioPurpose)
    fun stopPurpose(purpose: AudioPurpose)
    fun setVolume(purpose: AudioPurpose, volume: Float)
}

interface MicCapture {
    fun start(sampleRate: Int, onData: (ByteArray) -> Unit)
    fun stop()
}

enum class AudioPurpose { MEDIA, NAVIGATION, ASSISTANT, PHONE_CALL, ALERT }
data class AudioFrame(val direction: Int, val purpose: AudioPurpose, val sampleRate: Int, val channels: Int, val data: ByteArray)
data class AudioStats(val activePurposes: Set<AudioPurpose>, val underruns: Map<AudioPurpose, Int>)
```

**Internal:**
- `PurposeSlot` — one AudioTrack + AudioRingBuffer per purpose
- `AudioRingBuffer` — lock-free SPSC ring buffer, 500ms capacity
- `AudioFocusManager` — wraps Android AudioManager focus requests
- `MicCaptureManager` — Timer-based sampling, configurable rate

**Tests:**
- Unit: ring buffer overflow/underrun, purpose routing logic
- Integration: write PCM frames, verify AudioTrack write calls

---

### 4. Input (`com.openautolink.app.input`)

Touch, GNSS, vehicle data, and IMU sensor forwarding to bridge.

**Public API:**
```kotlin
interface TouchForwarder {
    fun onTouch(motionEvent: MotionEvent, surfaceWidth: Int, surfaceHeight: Int,
                videoWidth: Int, videoHeight: Int)
}

interface GnssForwarder {
    fun start()
    fun stop()
}

interface VehicleDataForwarder {
    fun start()
    fun stop()
}

// P5: IMU sensor forwarding (accelerometer, gyroscope, compass, GPS satellites)
class ImuForwarder(context: Context, sendMessage: (VehicleData) -> Unit) {
    fun start()
    fun stop()
}
```

**Internal:**
- `TouchScaler` — scales Android screen coordinates to bridge video coordinates
- `MultiTouchSerializer` — handles POINTER_DOWN/UP for multi-pointer events
- `GnssProvider` — Android LocationManager → NMEA sentences
- `VhalMonitor` — VHAL properties via Car API reflection (speed, gear, battery, temp, etc.)
- `ImuForwarder` — `SensorManager` listeners for accelerometer, gyroscope, magnetic field; `GnssStatus.Callback` for satellite count; compass bearing computed from rotation matrix; rate-limited to ~10 Hz

**Tests:**
- Unit: coordinate scaling math, multi-touch action code generation
- Integration: VHAL property subscription with mock Car API

---

### 5. UI (`com.openautolink.app.ui`)

Compose screens and ViewModels.

**Screens:**
- `ProjectionScreen` — SurfaceView + touch overlay + connection HUD. The main screen
- `SettingsScreen` — bridge connection, paired phones, video codec, audio, display mode, custom viewport editor
- `DiagnosticsScreen` — system info, codecs, network, bridge stats

**ViewModels:**
- `ProjectionViewModel` — observes session state, video stats, audio stats
- `SettingsViewModel` — reads/writes DataStore preferences, triggers bridge config updates
- `DiagnosticsViewModel` — collects device capabilities, connection metrics

**Tests:**
- Unit: ViewModel state transitions
- Integration: Compose test rules for UI interactions

---

### 6. Navigation (`com.openautolink.app.navigation`)

Nav state display and cluster service.

**Public API:**
```kotlin
interface NavigationDisplay {
    val currentManeuver: StateFlow<ManeuverState?>
    fun onNavState(state: ControlMessage.NavState)
}
```

**Internal:**
- `ManeuverMapper` — bridge maneuver strings → Android `TurnEvent` types
- `ManeuverIconRenderer` — Canvas-based turn arrow rendering
- `DistanceFormatter` — meters → "0.3 mi" / "500 ft" localized
- `ClusterService` — AAOS `InstrumentClusterRenderingService` (if OEM allows)

**Tests:**
- Unit: maneuver mapping, distance formatting
- Integration: cluster IPC with test binder

---

### 7. Session (`com.openautolink.app.session`)

The orchestrator. Connects all islands.

**Public API:**
```kotlin
interface SessionManager {
    val sessionState: StateFlow<SessionState>

    fun initialize(surface: Surface, width: Int, height: Int)
    fun connect(bridgeHost: String)
    fun disconnect()
    fun onPause()
    fun onResume()
    fun onSurfaceChanged(surface: Surface, width: Int, height: Int)
}

enum class SessionState { IDLE, CONNECTING, BRIDGE_CONNECTED, PHONE_CONNECTED, STREAMING, ERROR }
```

**Internal:**
- Wires transport events to video/audio/navigation consumers
- Wires touch/gnss/vehicle events to transport sender
- Manages lifecycle transitions (pause → codec release, resume → codec recreate)
- Owns reconnect policy and error recovery

**Tests:**
- Integration: full session with mock TCP bridge, verify state machine transitions

---

## Dependency Graph

```
                    ┌─────────┐
                    │ Session │ (orchestrator)
                    └────┬────┘
           ┌─────────┬──┴──┬─────────┬──────────┐
           ▼         ▼     ▼         ▼          ▼
      Transport    Video  Audio    Input    Navigation
           │                                    │
           └──── TCP ◄──── Bridge (SBC) ────────┘
                         │
                   ┌─────┴─────┐
                   │ AA (aasdk) │  ── Android phone
                   │ CarPlay   │  ── iPhone
                   └───────────┘
```

No island depends on another island directly. All communication flows through Session.
The bridge handles phone-protocol specifics (AA vs CarPlay) — the app only sees OAL frames.

---

## Milestone Plan

### M1: Black Screen with Connection (Foundation)
- [x] Gradle project setup (app module, min SDK 32, Compose)
- [x] Transport island: connect to bridge, parse `hello` + `phone_connected`
- [x] Session island: state machine (IDLE → CONNECTING → BRIDGE_CONNECTED → PHONE_CONNECTED)
- [x] UI: ProjectionScreen with SurfaceView + "Connecting..." / "Connected" text
- [x] Unit tests for transport JSON parsing + session state machine
- **Exit criteria**: App connects to bridge over TCP, shows connection state

### M2: Video (Core Value)
- [x] Video island: MediaCodec decoder, codec selection, NAL parsing
- [x] Wire transport `videoFrames` → video decoder → Surface
- [x] OAL video frame header parsing (16-byte binary)
- [x] Codec config frame handling (SPS/PPS → MediaFormat)
- [x] Stats overlay (FPS, frames decoded/dropped, codec name)
- [x] Unit tests for NAL parsing, integration test with test H.264 stream
- **Exit criteria**: Live AA video displays on car screen

### M3: Audio (Minimum Viable Product)
- [x] Audio island: 5 purpose slots, ring buffer, AudioTrack lifecycle
- [x] Wire transport `audioFrames` → audio player
- [x] Audio focus management (request/release/duck)
- [x] OAL audio frame header parsing (8-byte binary)
- [x] Unit tests for ring buffer, purpose routing
- **Exit criteria**: Music plays, navigation prompts duck media

### M4: Touch + Input (Interactive)
- [x] Input island: touch forwarding with coordinate scaling
- [x] Multi-touch (POINTER_DOWN/UP for pinch zoom)
- [x] Wire touch events through transport to bridge
- [x] Unit tests for coordinate scaling
- **Exit criteria**: Tap and swipe work on projected Android Auto UI

### M5: Microphone + Voice
- [x] Mic capture (timer-based, configurable sample rate)
- [x] Send mic audio on audio TCP channel (direction=1)
- [x] Bridge control messages: mic_start/mic_stop
- [x] RECORD_AUDIO permission handling
- **Exit criteria**: "Hey Google" and voice commands work

### M6: Settings + Config
- [x] DataStore preferences (codec, resolution, fps, display mode)
- [x] Settings UI (Compose)
- [x] Config sync: app sends `config_update` → bridge applies → sends `config_echo`
- [x] Bridge discovery (mDNS + manual IP)
- **Exit criteria**: User can configure bridge from app

### M7: Vehicle Integration
- [x] GNSS forwarding (LocationManager → NMEA → bridge)
- [x] Vehicle data (VHAL properties → bridge → phone)
- [x] Navigation state display (bridge nav events → maneuver UI)
- **Exit criteria**: Phone gets GPS from car, nav shows in cluster

### M8: Polish + Diagnostics
- [x] Diagnostics screen (system info, codecs, network, bridge)
- [x] Error recovery (reconnect, codec reset after corruption)
- [x] Display mode (fullscreen, system bars visible, custom viewport)
- [x] Custom viewport editor — user-defined projection area with draggable edges, aspect ratio lock, presets
- [x] Overlay buttons (settings toggle, stats toggle, phone switch)
- [x] Multi-phone management — paired phone list, default phone selection, quick switch
- **Exit criteria**: Feature parity with carlink_native bridge mode
