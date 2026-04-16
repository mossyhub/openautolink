---
description: "Use when writing Kotlin app code: AAOS components, Compose UI, ViewModels, repositories, DataStore preferences, coroutines. Covers AAOS-specific patterns and test conventions."
applyTo: "app/**/*.kt"
---
# App Kotlin Conventions

## Bridge Cross-Reference Rule (CRITICAL)
aasdk runs **in-process** via NDK/JNI — the native AA session code is in `app/src/main/cpp/aa_session.cpp`.
When implementing or modifying transport, video, audio, or input code:
1. **Read `app/src/main/cpp/aa_session.cpp`** to understand how aasdk callbacks deliver data to Kotlin
2. **Read `app/src/main/cpp/jni_bridge.cpp`** for the JNI function signatures and callback patterns
3. The bridge (`bridge/openautolink/`) is now a thin TCP relay — it does NOT process AA protocol
4. **Key files:**
   - `app/src/main/cpp/aa_session.cpp` — aasdk entity, video/audio/nav callbacks
   - `app/src/main/cpp/jni_bridge.cpp` — JNI entry points for Kotlin ↔ C++
   - `app/src/main/cpp/CMakeLists.txt` — NDK build with stub/full mode toggle

## Architecture
- **MVVM** with StateFlow — ViewModels expose `StateFlow<UiState>`, composables collect
- **Repository pattern** — interfaces in domain layer, implementations in data layer
- **Component islands** — each island (transport, video, audio, input, ui, navigation, session) is an independent package with a public API surface and internal implementation
- **Dependency injection** — constructor injection, no service locators. Manual DI or Hilt

## Package Structure
```
com.openautolink.app/
├── transport/   # TCP connection management
├── video/       # MediaCodec decoder + Surface
├── audio/       # AudioTrack management + mic
├── input/       # Touch, GNSS, vehicle data
├── ui/          # Compose screens + ViewModels
├── navigation/  # Nav state + cluster
├── session/     # Session orchestrator
└── di/          # Dependency injection setup
```

## Coroutines
- `viewModelScope` for UI-bound work
- `Dispatchers.IO` for network, disk, DataStore
- Dedicated threads ONLY for: MediaCodec decode loop, AudioTrack write loop
- Never use `runBlocking` in production code
- Use `Flow` for streams (TCP messages, audio frames), `suspend` for one-shot operations

## Testing
- Every island has unit tests mocking island boundaries
- Use `kotlinx-coroutines-test` with `UnconfinedTestDispatcher` for coroutine tests
- Use `Turbine` for Flow testing
- Integration tests use a mock TCP server (real sockets, test data)
- Compose tests use `createComposeRule()` with test tags
- Name test files: `{ClassName}Test.kt` (unit), `{Feature}IntegrationTest.kt` (integration)

## AAOS Specifics
- Min SDK 32 (Android 12.1 Automotive)
- Use `Car` API via reflection — graceful fallback when `android.car` not available
- `VehiclePropertyMonitor` subscribes to VHAL properties — always check property availability before subscribing
- Cluster service: `InstrumentClusterRenderingService` — may be restricted by OEM

## DataStore Preferences
- Single DataStore instance via companion `getInstance(context)` — thread-safe singleton
- Typed keys with defaults — never raw string access
- Use `Flow<T>` for reactive reads, `suspend` for writes
- Preferences survive app restart; bridge config echo verifies bridge-side state
