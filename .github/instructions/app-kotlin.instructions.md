---
description: "Use when writing Kotlin app code: AAOS components, Compose UI, ViewModels, repositories, DataStore preferences, coroutines. Covers AAOS-specific patterns and test conventions."
applyTo: "app/**/*.kt"
---
# App Kotlin Conventions

## JNI Cross-Reference Rule (CRITICAL)
When implementing or modifying any app code that communicates with the JNI layer (transport, video, audio, input, session):
1. **Always read the corresponding C++ JNI code** in `app/src/main/cpp/` before writing Kotlin code. Verify what the native side actually sends/receives via JNI callbacks.
2. **Check for JNI signature mismatches.** The Kotlin `external fun` declarations in `AasdkNative.kt` must match the C++ `JNICALL` signatures exactly.
3. **It is OK to modify the C++ JNI code** if it improves the interface, simplifies the Kotlin side, or fixes bugs.
4. **Key C++ files to reference:**
   - `app/src/main/cpp/aasdk_jni.cpp` — JNI entry point, native method registration
   - `app/src/main/cpp/jni_session.{h,cpp}` — aasdk pipeline: SSL → Cryptor → Messenger → channels
   - `app/src/main/cpp/jni_channel_handlers.{h,cpp}` — audio, sensor, input, nav, mic handlers
   - `app/src/main/cpp/jni_transport.{h,cpp}` — ITransport backed by TCP socket streams
   - `external/opencardev-aasdk/include/aasdk/Channel/` — aasdk channel interfaces

## Architecture
- **MVVM** with StateFlow — ViewModels expose `StateFlow<UiState>`, composables collect
- **Repository pattern** — interfaces in domain layer, implementations in data layer
- **Component islands** — each island (transport, video, audio, input, ui, navigation, session) is an independent package with a public API surface and internal implementation
- **Dependency injection** — constructor injection, no service locators. Manual DI or Hilt

## Package Structure
```
com.openautolink.app/
├── transport/   # aasdk JNI session + TCP companion transport
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
