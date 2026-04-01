# OpenAutoLink Headless Backend

C++ headless Android Auto head unit that bridges phone AA sessions to the car's app via TCP.

## Architecture

### Wireless Mode (default)
```
Phone → WiFi → SBC:5277 (aasdk AA protocol v1.6, TCP)
                    ↓ OAL protocol
               SBC:5288 (control), SBC:5289 (audio), SBC:5290 (video)
                    ↓ TCP/IP over Ethernet
               Car App on AAOS head unit
```

### Wired Mode (USB Host)
```
Phone → USB cable → SBC USB host port (libusb AOA mode)
                    ↓ aasdk AA protocol v1.6 (USB transport)
               SBC:5288 (control), SBC:5289 (audio), SBC:5290 (video)
                    ↓ TCP/IP over Ethernet
               Car App on AAOS head unit
```

## Key Components

- **TcpCarTransport** (`tcp_car_transport.hpp`) — TCP server for OAL protocol to car app
- **ICarTransport** (`i_car_transport.hpp`) — abstract transport interface
- **OalSession** (`oal_session.hpp/cpp`) — OAL protocol session (JSON control, binary video/audio)
- **LiveAasdkSession** (`live_session.hpp/cpp`) — aasdk v1.6 AA session (TCP wireless or USB wired)
- **HeadlessAutoEntity** (in `live_session.hpp/cpp`) — aasdk control channel orchestration + service handlers
- **HeadlessConfig** (`headless_config.hpp`) — shared configuration

## Usage

```bash
openautolink-headless \
  --session-mode=aasdk-live \
  --tcp-car-port=5288 \
  --tcp-port=5277 \
  --video-width=2400 --video-height=960 \
  --video-fps=60 --video-dpi=160 \
  --video-codec=h264 --aa-resolution=1080p \
  --head-unit-name=OpenAutoLink

# Wired AA (phone via USB):
openautolink-headless \
  --session-mode=aasdk-live \
  --tcp-car-port=5288 \
  --usb \
  --video-width=2400 --video-height=960 \
  --video-fps=60 --video-dpi=160 \
  --video-codec=h264 --aa-resolution=1080p
```

## AA Resolution Tiers

The `--aa-resolution` flag controls what resolution the phone encodes at:

| Tier | Resolution | Status |
|------|-----------|--------|
| 480p | 800x480 | Working |
| 720p | 1280x720 | Working |
| 1080p | 1920x1080 | Working (default) |
| 1440p | 2560x1440 | In AA spec, untested |
| 4k | 3840x2160 | In AA spec, untested |

## Building

Requires: cmake, g++ (C++20), libboost-system-dev, libboost-log-dev, libprotobuf-dev, protobuf-compiler, libssl-dev, libusb-1.0-0-dev

```bash
cmake -S headless -B build \
  -DPI_AA_AASDK_SOURCE_DIR=/path/to/aasdk \
  -DPI_AA_ENABLE_AASDK_LIVE=ON \
  -DCMAKE_BUILD_TYPE=Release
cmake --build build -j$(nproc)
```

## systemd Service

Uses the `run-openautolink.sh` launcher which reads `/etc/openautolink.env`:

```ini
# /etc/systemd/system/openautolink.service
[Service]
ExecStart=/opt/openautolink/scripts/run-openautolink.sh
Restart=always
RestartSec=3
```
- `touch`, `audio_input`, and `gnss` messages update internal state and affect later metadata output

The scaffold also now has an explicit non-stub mode selector:

- default mode: `stub`
- placeholder future-backend mode: `aasdk-placeholder`

The placeholder aasdk mode does not speak Android Auto yet. It exists to provide a concrete implementation target for future transport and control-path wiring while preserving the same process boundary.

That mode now has its own aasdk-style control adapter, which tracks version request, version response, SSL handshake, auth complete, service discovery, audio-focus request or response, navigation-focus request or response, ping, and shutdown-related milestones and emits them through the existing media status payload. The current adapter is still synthetic, but it matches the real upstream control-flow shape much more closely than the earlier placeholder phase-only model.

That control adapter can now also optionally compile upstream-generated aasdk protobuf types when Protobuf tooling is available at CMake configure time. When enabled, the placeholder status payload reports serialized sizes for the real upstream auth-complete, audio-focus-response, navigation-focus-response, ping-request, shutdown-request, and shutdown-response messages that the adapter is modeling, and the service catalog payload reports the serialized size and channel count of a real upstream `ServiceDiscoveryResponse` built from the same modeled service table.

The placeholder status payload now also exposes explicit audio-focus-request, audio-focus-response, navigation-focus-request, navigation-focus-response, shutdown-request, and shutdown-response counters. The session uses those counters as event-like signals when deciding whether it still needs to answer a modeled control request and when it is allowed to advance main-stream or nav-stream behavior, which is closer to the way a real control-channel callback bridge will need to behave than relying on pending flags or local timing alone.

Once that control path reaches `active`, the session now also emits a modeled OpenAuto-style service-discovery catalog that advertises the current planned service topology: audio input, media audio, speech audio, system audio, sensor, video, Bluetooth, and input.

After the catalog is emitted, the placeholder aasdk mode now also emits a modeled input-binding payload and a modeled sensor-start payload. Those outputs are the current stand-ins for the upstream InputService binding flow and SensorService startup flow, so the input and sensor services now have explicit bridge-visible runtime behavior in addition to their later touch and GNSS driven side effects.

The placeholder aasdk mode now also emits a modeled Bluetooth bootstrap payload using the same RFCOMM-oriented service metadata exposed by upstream OpenAuto's Bluetooth helper. On the following heartbeats it advances through media type `912` to mark the placeholder service as listening for wireless setup over RFCOMM, media type `913` to model a placeholder pairing request, media type `914` to model a placeholder transport-ready state for wireless projection, media type `915` to model a placeholder established session, media type `916` to model a placeholder transport-lost recovery state, and media type `917` to model a placeholder reconnect-success state. That gives the Bluetooth service a more complete backend-visible lifecycle before real pairing and transport wiring exists.

The placeholder aasdk mode now also honors host `REQUEST_NAVI_SCREEN_FOCUS` and `RELEASE_NAVI_SCREEN_FOCUS` for the modeled navigation lifecycle. It still emits navigation-focus request and release outputs through the control seam, but navigation media, audio, and `NAVI_VIDEO_DATA` no longer start until the host actually grants nav-screen focus.

If the host releases nav-screen focus while a modeled nav prompt is active, the placeholder backend now also cuts that nav lifecycle short and emits the same stop or complete and focus-release path it would otherwise emit at the end of its synthetic prompt duration.

The placeholder session now also honors host `AUDIO_TRANSFER_ON` and `AUDIO_TRANSFER_OFF` for the primary stream. When playback is paused, the backend still emits status and media metadata updates but stops main audio and `VIDEO_DATA`; when playback resumes, it forces the next main video frame back to IDR.

The placeholder session now also honors host `START_GNSS_REPORT`/`STOP_GNSS_REPORT` and `START_RECORD_AUDIO`/`STOP_RECORD_AUDIO`. GNSS and microphone uplinks are still synthetic today, but they are no longer treated as always-on inputs inside the C++ backend.

After that modeled discovery step, the placeholder aasdk mode now emits a short focus lifecycle using the existing subprocess command and navigation-focus outputs. It now models primary focus first and navigation focus second on later heartbeats, so the bridge-side focus translation path is exercised in a shape that more closely resembles real control-channel callbacks before those same focus states are released later in the flow.

Those placeholder focus command and navigation-focus outputs are now also routed through a small ordered-output queue inside `AasdkAndroidAutoSession` before emission. The same queue now also carries the synthetic navigation and voice-control `ducking` plus `audio_command` outputs, so those subprocess payloads stay the same while the backend owns their ordering explicitly instead of mixing queued and direct emit paths.

Between those focus transitions, the placeholder aasdk mode now also emits a short modeled navigation-content sequence using subprocess ducking, audio command, media, audio, and `NAVI_VIDEO_DATA` outputs. The current content is synthetic, but it exercises the same contract surface the real aasdk-backed navigation path will need to drive. The navigation `media` payload now also exposes explicit navigation-session state, navigation-focus state, prompt sequence, and route-update visibility so the eventual real metadata path has a more explicit contract target than maneuver text alone.

After that modeled navigation flow completes and focus release is emitted, the placeholder aasdk mode now also emits a short modeled voice-input lifecycle using subprocess audio-command outputs for `AUDIO_INPUT_CONFIG` and `AUDIO_SIRI_START`, then reacts to host `audio_input` uplinks by emitting `AUDIO_SIRI_STOP`. The voice lifecycle now starts from the focus-release event itself instead of waiting for an extra heartbeat, which keeps more of the placeholder flow tied to explicit lifecycle transitions instead of session-local timing. The placeholder status payload now also reports explicit assistant-session state plus start and stop counters, and it emits an updated status frame immediately when voice start occurs so assistant lifecycle transitions are visible over the subprocess seam on the same event. Host `audio_input` payload bytes are now also preserved through the parser and engine and surfaced in placeholder status as cumulative and last-packet microphone uplink sizes instead of being discarded once they reach the C++ backend. That gives the future audio-input uplink path a stable contract target as well.

Once that modeled voice-input lifecycle has stopped, the placeholder aasdk mode now also models a shutdown request on the control side and answers it by emitting `phone_disconnected`. That shutdown path now runs immediately on the same `audio_input` event that emits `AUDIO_SIRI_STOP`, so the disconnect sequence is keyed off the explicit assistant-session stop event instead of waiting for a later heartbeat. The placeholder status payload is emitted once with the shutdown request pending and once again after the modeled shutdown response is sent so the shutdown-response counter is visible over the subprocess seam before disconnect. That gives the control seam a concrete shutdown-response path to replace when real aasdk control wiring lands.

Touch and GNSS uplinks now also affect runtime behavior in the placeholder aasdk mode instead of only updating status counters. While the modeled navigation flow is active, they emit feedback media payloads and force the next `NAVI_VIDEO_DATA` frame back to IDR so the future input and sensor bindings already have a concrete bridge-facing behavior to replace. Before navigation takes over, a touch now toggles the modeled primary playback state, emits a matching request or release for video focus on the next heartbeat, and reports that action through the existing touch feedback payload. Host `touch` payload bytes are now also preserved through the parser, engine, and session seam and surfaced in both placeholder status and the touch feedback payload instead of being dropped at dispatch time. Host `gnss` payload bytes now also survive into the placeholder backend, and the status plus GNSS feedback payloads expose cumulative or last-packet byte counts and the decoded NMEA sentence instead of collapsing every update to a generic marker.

Once the modeled input, sensor, and audio-input services are active, the placeholder aasdk mode now also emits explicit service-shaped uplink payloads in addition to the older status and feedback messages. Post-binding touch uplinks produce media type `909` placeholder input-service events with touch counts, bytes, action, and current playback or primary-video-focus state, GNSS uplinks produce media type `911` placeholder sensor-service events with GNSS counts, cumulative and last-packet bytes, decoded sentence text, and reporting-enabled state, and microphone uplinks produce media type `910` placeholder audio-input-service events with microphone counts, bytes, last-packet size, and assistant-session state. Those payloads keep the subprocess contract closer to the eventual real InputService, SensorService, and AudioInputService integration without changing the Python bridge boundary.

The remaining placeholder focus-related `command` and `navi_focus` outputs are now routed through a sibling ordered-output queue inside `AasdkAndroidAutoSession`, and that same non-media path now also carries the synthetic navigation and voice `ducking` plus `audio_command` frames, the placeholder status payload, lifecycle events, and the modeled service catalog. That means the synthetic control-side lifecycle no longer depends on direct emit ordering at each call site.

Those placeholder per-service media outputs are now routed through a shared session-local queue before emission, and the same queue now also carries the touch and GNSS feedback payloads (`903`, `904`, and `905`) plus the placeholder main and navigation metadata payloads (`1` and `200`). That means most placeholder media ordering is now backend-owned in one path instead of splitting service events, feedback updates, and metadata across separate ad hoc emit calls.

Once the control path reaches `active` and the modeled primary focus response has been sent, the placeholder aasdk mode now also emits a modeled main media, audio, and `VIDEO_DATA` stream. Navigation content similarly waits until the modeled navigation-focus response has been sent. GNSS uplinks emit an additional main-stream route-update feedback payload and force the next main video frame back to IDR, so the placeholder backend now exercises both the primary and navigation downlink paths while keeping them aligned with explicit focus-response handling. The primary `media` payload now also exposes playback state, primary-focus state, and negotiated video dimensions so bridge-visible metadata better matches the current modeled session lifecycle.

## Build

This scaffold has two build modes:

### Placeholder build (dependency-light)

The placeholder and stub modes can be built before aasdk dependencies are available.

Example with CMake:

```bash
cmake -S bridge/pi-aa/openauto-headless -B bridge/pi-aa/openauto-headless/build \
  -DPI_AA_OPENAUTO_SOURCE_DIR=external/openauto \
  -DPI_AA_AASDK_SOURCE_DIR=external/aasdk
cmake --build bridge/pi-aa/openauto-headless/build
```

If `protoc` and the Protobuf development package are available, that CMake path now also generates and compiles the aasdk protobuf set from `external/aasdk/aasdk_proto`. The direct `g++` fallback below still builds the dependency-light scaffold, but it does not enable that generated protobuf path.

If `cmake` is not installed but a compiler is available, the current scaffold can also be built directly:

```bash
mkdir -p bridge/pi-aa/openauto-headless/build
g++ -std=c++17 -Wall -Wextra -pedantic \
  -I bridge/pi-aa/openauto-headless/include \
  bridge/pi-aa/openauto-headless/src/aasdk_control.cpp \
  bridge/pi-aa/openauto-headless/src/contract.cpp \
  bridge/pi-aa/openauto-headless/src/service_catalog.cpp \
  bridge/pi-aa/openauto-headless/src/session.cpp \
  bridge/pi-aa/openauto-headless/src/engine.cpp \
  bridge/pi-aa/openauto-headless/src/main.cpp \
  bridge/pi-aa/openauto-headless/src/live_session.cpp \
  -o bridge/pi-aa/openauto-headless/build/pi-aa-openauto-headless
```

### Live aasdk build (real Android Auto transport)

The `aasdk-live` mode links against the real aasdk library and can talk to a real phone over TCP or USB. This requires system packages:

```bash
# Ubuntu/Debian
sudo apt-get install cmake libboost-system-dev libboost-log-dev \
  libprotobuf-dev protobuf-compiler libssl-dev libusb-1.0-0-dev
```

Then build with CMake:

```bash
cmake -S bridge/pi-aa/openauto-headless -B bridge/pi-aa/openauto-headless/build \
  -DPI_AA_AASDK_SOURCE_DIR=$(pwd)/external/aasdk \
  -DPI_AA_ENABLE_AASDK_LIVE=ON
cmake --build bridge/pi-aa/openauto-headless/build
```

This compiles `external/aasdk` as a subdirectory and links the headless backend against it. The resulting binary supports `--session-mode=aasdk-live`, which starts a TCP server on port 5277 (configurable with `--tcp-port=PORT`) and waits for a phone to connect via the Android Auto TCP transport.

## Run

Example bridge invocation after building:

```bash
python3 -m pi_aa_bridge.main --serve --backend subprocess --backend-command ./bridge/pi-aa/openauto-headless/build/pi-aa-openauto-headless
```

Example placeholder session invocation:

```bash
python3 -m pi_aa_bridge.main --serve --backend subprocess --backend-command ./bridge/pi-aa/openauto-headless/build/pi-aa-openauto-headless --session-mode=aasdk-placeholder
```

Example live aasdk session invocation (requires live build):

```bash
python3 -m pi_aa_bridge.main --serve --backend subprocess \
  --backend-command "./bridge/pi-aa/openauto-headless/build/pi-aa-openauto-headless --session-mode=aasdk-live --tcp-port=5277"
```

Example custom phone name:

```bash
python3 -m pi_aa_bridge.main --serve --backend subprocess --backend-command ./bridge/pi-aa/openauto-headless/build/pi-aa-openauto-headless --phone-name=PiAA-Prototype
```

## Architecture (aasdk-live mode)

When built with `PI_AA_ENABLE_AASDK_LIVE=ON`, the backend runs a real Android Auto head-unit session:

```text
Android phone
  -> connects via TCP (port 5277) or USB AOAP
  -> aasdk transport + SSL handshake + service discovery
  -> HeadlessAutoEntity orchestrates control channel
  -> Service handlers receive video/audio/input from phone
  -> NDJSON subprocess output -> OAL protocol -> AAOS car app
```

Threading model:
- Main thread reads NDJSON from stdin (host packets, touch, audio input, GNSS)
- Worker thread runs boost::asio::io_service for aasdk async I/O
- Thread-safe output sink serializes NDJSON writes to stdout

Service handlers:
- `HeadlessVideoHandler` — receives H.264 frames, emits NDJSON `video` messages
- `HeadlessAudioHandler` — receives PCM for media/speech/system channels, emits NDJSON `audio`
- `HeadlessAudioInputHandler` — feeds host microphone audio into the AA audio-input channel
- `HeadlessSensorHandler` — feeds GNSS, night mode, driving status to the phone
- `HeadlessInputHandler` — sends host touch events to the phone
- `HeadlessBluetoothHandler` — handles BT pairing requests from the phone

## Next Implementation Step

The live aasdk session can now be compiled and linked. The next steps to get a real phone connected:

1. Build the live binary on a Pi (or cross-compile for ARM)
2. Set up WiFi hotspot on the Pi
3. Implement Bluetooth RFCOMM service for wireless Android Auto negotiation
4. Phone discovers Pi via Bluetooth, negotiates WiFi credentials, connects to TCP port
5. aasdk handles version exchange, SSL handshake, service discovery
6. Video and audio frames flow through the NDJSON subprocess contract to the Python bridge

## Validation Status

The scaffold has been validated as a real subprocess backend in WSL by:

- compiling the multi-file backend into `build/pi-aa-openauto-headless`
- validating the aasdk-style control-adapter milestone sequence directly against the rebuilt binary
- validating the modeled OpenAuto-style service-discovery catalog through the rebuilt binary and phase 2 tests
- validating the modeled input-binding and sensor-start lifecycle through the rebuilt binary and phase 2 tests
- validating the modeled Bluetooth bootstrap lifecycle through the rebuilt binary and phase 2 tests
- validating host `OPEN` packet dimension handling and host disconnect packet handling through the rebuilt binary and phase 2 tests
- validating host nav-screen-focus gating for the modeled navigation lifecycle through the rebuilt binary and phase 2 tests
- validating host nav-screen-focus release as an active nav cancellation path through the rebuilt binary and phase 2 tests
- validating host `AUDIO_TRANSFER_ON/OFF` pause-resume control of the modeled primary stream through the rebuilt binary and phase 2 tests
- validating host GNSS-reporting and record-audio enable or disable commands through the rebuilt binary and phase 2 tests
- validating payload-aware microphone uplink byte accounting and record-audio gating through the rebuilt binary and phase 2 tests
- validating placeholder status reporting for optional upstream-generated aasdk control-proto readiness and serialized control-message sizes through the rebuilt binary and phase 2 tests
- validating optional upstream-generated `ServiceDiscoveryResponse` serialization and channel-count reporting through the rebuilt binary and phase 2 tests
- validating sequential modeled audio-focus then navigation-focus request handling through the rebuilt binary and phase 2 tests
- validating the modeled focus request and release lifecycle through the rebuilt binary and phase 2 tests
- validating ordered queue ownership for the modeled focus command, navigation-focus, ducking, audio-command, lifecycle-event, placeholder-status, and service-catalog outputs through the rebuilt binary and phase 2 tests
- validating the modeled navigation-content lifecycle through the rebuilt binary and phase 2 tests
- validating the modeled voice-input lifecycle through the rebuilt binary and phase 2 tests
- validating explicit assistant-session state and start or stop counters in placeholder status through the rebuilt binary and phase 2 tests
- validating modeled focus-response and shutdown-response control behavior through the rebuilt binary and phase 2 tests
- validating touch and GNSS driven navigation refresh behavior through the rebuilt binary and phase 2 tests
- validating touch-driven primary playback pause or resume plus video-focus request or release before nav through the rebuilt binary and phase 2 tests
- validating payload-aware touch uplink byte accounting in placeholder status and touch feedback through the rebuilt binary and phase 2 tests
- validating payload-aware GNSS uplink byte accounting and decoded sentence reporting through the rebuilt binary and phase 2 tests
- validating explicit placeholder input-service touch events, sensor-service GNSS events, audio-input-service microphone uplink events, and multi-step Bluetooth runtime events through the rebuilt binary and phase 2 tests, now including transport-ready, session-established, transport-lost, and reconnect-success Bluetooth states
- validating the modeled main media, audio, and video downlink path through the rebuilt binary and phase 2 tests
- validating richer primary and navigation media metadata through the rebuilt binary and phase 2 tests
- running the binary-backed unittest paths in `bridge/pi-aa/tests/test_session_phase2.py` for both:
  - default `stub` mode
  - `--session-mode=aasdk-placeholder`

The Windows-side Python suite still skips that binary smoke test because the produced artifact is a Linux ELF, not a Windows executable. That skip is expected.
