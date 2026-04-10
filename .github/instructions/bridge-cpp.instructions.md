---
description: "Use when writing or modifying C++ bridge code: headless binary, aasdk integration, CPC/OAL session management, TCP transport, service handlers. Covers thread safety, build patterns, and aasdk v1.6 requirements."
applyTo: "bridge/openautolink/headless/**"
---
# Bridge C++ Conventions

## Build
- C++20 standard, CMake build system
- Dependencies: Boost (system, log), OpenSSL, libusb, protobuf
- Build via WSL cross-compile (`scripts/deploy-bridge.ps1`) or GitHub CI (`.github/workflows/release-bridge.yml`)
- Do NOT build on the SBC directly — always cross-compile

## Thread Model
- **boost::asio::io_service** — single worker thread for aasdk callbacks
- **TCP accept threads** — one per port (5288 control, 5290 video, 5289 audio)
- **Output sink** — thread-safe queue with mutex; aasdk callbacks enqueue, TCP threads dequeue
- Never call aasdk methods from TCP threads directly

## aasdk Submodule (Forked)
- aasdk lives at `external/opencardev-aasdk/` — a git submodule pointing to **mossyhub/aasdk** branch `openautolink`
- When modifying aasdk source (e.g. adding service handlers, extending channels): commit inside the submodule, push to fork, then update the parent repo's submodule pointer
- Keep `origin` (opencardev/aasdk) as upstream remote for rebasing; `fork` (mossyhub/aasdk) is where our changes live

## aasdk v1.6 Requirements
- Protocol version 1.6 in version exchange (phones respond 1.7)
- ServiceDiscoveryResponse uses typed ServiceConfiguration (MediaSinkService, SensorSourceService, etc.)
- Old ChannelDescriptor format (v1.1) causes phone to silently ignore the response
- Must send ChannelOpenResponse for every ChannelOpenRequest — omitting causes phone disconnect after ~60s
- Must send MediaAckIndication for every video frame — omitting causes phone flow-control backup
- Phone sends PingRequests to bridge; bridge must respond. Bridge pings to phone may be ignored

## OAL Protocol Output (replacing CPC200)
- Control channel: JSON lines to TCP 5288
- Video: 16-byte header + raw codec to TCP 5290
- Audio: 8-byte header + raw PCM to TCP 5289

## Current State: OAL Protocol

### OAL Protocol
- **OalSession** manages the OAL protocol session
- **Control (5288)**: newline-delimited JSON lines
- **Video (5290)**: 16-byte header (payload_length, width, height, pts_ms, flags, reserved) + raw codec data
- **Audio (5289)**: 8-byte header (direction, purpose, sample_rate, channels, payload_length) + raw PCM
- OAL is the only protocol — CPC200 legacy code was removed in B3
- See docs/protocol.md for full spec

## Session Modes
- `aasdk-live` — production (real phone AA session)
- `aasdk-placeholder` — modeled control flow, no phone needed
- `stub` — synthetic output for app testing

## Key Classes
- `LiveAasdkSession` — real aasdk transport, owns HeadlessAutoEntity
- `HeadlessAutoEntity` — control channel orchestration, service handlers
- `TcpCarTransport` — TCP server for app connections (ICarTransport interface)
- `OalSession` — OAL protocol session, routes audio/video between aasdk and TCP
- `ScoAudio` — BT SCO socket for HFP phone call audio (listen, read/write threads)
- `HeadlessConfig` — shared config (video dims, DPI, codec, resolution tier)
