# GM AAOS APK Recon â€” Findings for OpenAutoLink

This document captures concrete, code-level findings from decompiling the GM
AAOS firmware APKs (Chevy Blazer EV, Android 12 / SDK 32, GM build
`VCUSR5-163/239`). Source dump and triage notes live in the gitignored
`recon_dump/` directory; this file is the durable summary of what we
learned and how it shaped the project.

> **Scope.** Only the highest-value APKs for OpenAutoLink were analysed in
> depth: `com_gm_aapclient` (32 MB) and `com_gm_hmi_androidauto` (45 MB).
> Other APKs in `recon_dump/apks/` (Ultifi services, EV energy model,
> trusted device, etc.) are catalogued but not deeply examined here.

## TL;DR

GM does **not** implement Android Auto from scratch â€” they ship Google's
`com.google.android.projection.*` library and wrap it in a thin GM service
layer (`gm.gal`, `gm.connection`, `com.gm.server.gal`). The interesting
parts for us are:

1. **Wire-level service IDs and audio config** â€” confirmed against our
   own implementation.
2. **The Service Discovery Response (SDR) `VideoConfiguration` fields they
   actually populate** â€” this is what the phone-side AA module honours,
   and several of our previous defaults were wrong.
3. **The aspect-ratio strategy** â€” they use `width_margin` /
   `height_margin` to letterbox a 16:9 codec frame inside a non-16:9 panel
   rect, and let the car compositor uniformly scale the inner content rect
   onto the panel. They do *not* use `pixel_aspect_ratio_e4` for AR
   compensation (it's hard-coded to `10000` = 1.0).
4. **Rectangular `content_insets` only.** The AA protocol has no
   non-rectangular safe-area / mask. Curved corners must be approximated
   by the largest rectangle that fits inside the curve.

These findings are the basis of the
[pixel-aspect.instructions.md](../.github/instructions/pixel-aspect.instructions.md)
rules, the auto-margin/margin-zoom renderer in
[ProjectionScreen.kt](../app/src/main/java/com/openautolink/app/ui/projection/ProjectionScreen.kt),
the per-tier margin computation in
[jni_session.cpp](../app/src/main/cpp/jni_session.cpp), and the GM-matching
SDR defaults in
[AasdkSdrConfig.kt](../app/src/main/java/com/openautolink/app/transport/aasdk/AasdkSdrConfig.kt).

## 1. AA service IDs

From `com.p087gm.server.gal.service.internals.utils.AAServiceID`:

| ID | Service |
|---:|---|
| 1 | DISPLAY (video) |
| 2 | INPUT (touch + key) |
| 3 | SENSOR |
| 4 | AUDIO_MEDIA_PCM |
| 5 | AUDIO_MEDIA_AAC |
| 6 | AUDIO_TTS_PCM |
| 7 | AUDIO_TTS_AAC |
| 8 | AUDIO_SOURCE (mic) |
| 9 | BLUETOOTH |
| 10 | MEDIA_PLAYBACK_STATUS |
| 11 | MEDIA_BROWSE |
| 12 | PHONE_STATUS |
| 16 | WIFI_PROJECTION |
| 20 | HOTWORD_VENDOR_EXTENSION |
| 21 | GAL_VENDOR_EXTENSION |
| 30 | NAVIGATION_STATUS |
| 31 | DISPLAY_AUXILIARY |
| 32 | DISPLAY_AUXILIARY_INPUT |
| 33 | DISPLAY_CLUSTER |
| 34 | DISPLAY_CLUSTER_INPUT |

Our [`jni_channel_handlers.cpp`](../app/src/main/cpp/jni_channel_handlers.cpp)
matches IDs 1â€“11 and 30/33; we don't currently implement 16 (WiFi
projection control), 20/21 (vendor extensions), or 31/32 (auxiliary
display).

## 2. Wireless transport: TCP server on port 5277

From `GALManager.startTcpServerMode()`:

```java
TransportFactory factory = TransportFactory.builder()
    .setContext(context)
    .setServerSocketProvider(serverSocketProvider)
    .build();
Log.i(TAG, "Starting TCP server mode on port 30515");
factory.open(null, new WireLessTransportFactoryCallback(sessionId));
```

(The `30515` constant in the log is an internal mDNS service port; the
actual AA TCP listener is `5277`, the well-known wireless AA port â€” confirmed
by the WiFiInfoResponse handler in our companion's `TcpAdvertiser`.)

Notable preconditions:

- The car requires a **valid BT MAC** (`BluetoothProxy.getHostAddress()`)
  before opening the TCP server. The MAC is sent in the AA wireless
  bootstrap so the phone knows which device to dial.
- The car is the TCP **server**; the phone dials in. Our companion app
  inverts this in Car-Hotspot mode (companion is the server; the car app
  dials the companion). Both modes match this pattern at the AA layer â€”
  what differs is which endpoint runs the TCP listener.

## 3. Audio configuration â€” only 4 streams, not 5

From `GALManager.registerAudioSinkService`:

```java
mMediaStreamPCMPlayer  = new AudioPlayer("MEDIA CHANNEL_PCM",     AUDIO_STREAM_MEDIA,    48000, 12, 2, ...);
mTtsStreamPCMPlayer    = new AudioPlayer("GUIDANCE CHANNEL_PCM",  AUDIO_STREAM_GUIDANCE, 48000, 4,  2, ...);
mMediaStreamAACPlayer  = new AudioPlayer("MEDIA CHANNEL_AAC",     AUDIO_STREAM_MEDIA,    48000, 12, 2, ...);
mTtsStreamAACPlayer    = new AudioPlayer("GUIDANCE CHANNEL_AAC",  AUDIO_STREAM_GUIDANCE, 48000, 4,  2, ...);
```

- GM uses only **2 logical streams**: `MEDIA` and `GUIDANCE`. There is no
  separate ALERT/SYSTEM/SIRI purpose â€” the phone routes those through one
  of the two.
- All sinks are **48 kHz / 16-bit**. Media is stereo (channel mask `12` =
  `CHANNEL_OUT_FRONT_LEFT | FRONT_RIGHT`); guidance is mono (mask `4` =
  `FRONT_CENTER`).
- **PCM and AAC are separate AAServiceIDs** (4/5 and 6/7). GM advertises
  both, then negotiates per-session. This explains why the phone sometimes
  flips codecs mid-session â€” same logical stream, different codec.

We have a more granular 5-purpose model in [audio/](../app/src/main/java/com/openautolink/app/audio).
This is fine â€” extra purposes simply receive nothing if the phone never
emits them â€” but allocating empty `AudioTrack`s is small wasted work. Not
worth changing unless we see the wasted allocation in a profiler.

## 4. SDR `VideoConfiguration` â€” the GM defaults

From `GALDisplayManager.buildVC` (lightly reformatted):

```java
VideoConfiguration.newBuilder()
    .setCodecResolution(VIDEO_1920x1080 | VIDEO_1280x720 | VIDEO_800x480)
    .setFrameRate(VIDEO_FPS_60)
    .setWidthMargin(wm)              // pixels of black bar L+R inside the codec frame
    .setHeightMargin(hm)             // pixels of black bar T+B inside the codec frame
    .setDensity(scaledDensity)       // logical DPI (sized so UI elements end up correct)
    .setRealDensity(realDensity)     // physical DPI for fonts
    .setDecoderAdditionalDepth(1)    // extra reordered frames the HU can buffer
    .setViewingDistance(700)         // mm from driver eye to display
    .setPixelAspectRatioE4(10000)    // = 1.0, ALWAYS. Phone ignores non-1.0.
    .setUiConfig(uiConfig);          // content_insets, stable_content_insets, margins
```

GM advertises only **`VIDEO_1920x1080`, `VIDEO_1280x720`, `VIDEO_800x480`**
(in that order) regardless of trim. The full `VideoCodecResolutionType`
enum supports up to `VIDEO_3840x2160` plus portrait mirrors
(`VIDEO_720x1280` â€¦ `VIDEO_2160x3840`); GM's choice to omit them keeps the
session bandwidth/CPU budget tight on retail trims.

### What the phone actually honours

- `width_margin` / `height_margin` â€” yes, phone respects these and packs
  margin pixels at the **bottom and right** (UI top-left anchored inside
  the inner content rect).
- `density` and `real_density` â€” yes, used for UI sizing.
- `decoder_additional_depth` â€” yes; impacts encoder pacing.
- `viewing_distance` â€” yes; subtle font-size effect.
- `pixel_aspect_ratio_e4` â€” **ignored**. We confirmed this via experiment
  on multiple phones; setting non-1.0 produces no change. GM hardcodes
  `10000` and uses margins instead.
- `UiConfig.content_insets` / `stable_content_insets` â€” yes, AA places
  dropdowns/dialogs inside.
- `UiConfig.margins` â€” phones we tested ignore this. GM still sends it
  (split half/half top/bottom and left/right) defensively. We mirror
  that â€” see
  [jni_session.cpp `applyVideoConfig`](../app/src/main/cpp/jni_session.cpp).

### Key formulas

`getScaledDensity` and `getRealDensity` compute density assuming the inner
content rect (codec frame minus margins) maps **uniformly** onto the
projection area:

```text
fWidth        = projectionArea.width / (codec.width  - width_margin)
scaledDensity = round(reportedDensity / fWidth)              // floor MINIMUM_SCALED_DENSITY = 120
realDensity   = round((codec.height - height_margin) / physicalHeightInches)
```

`MINIMAL_SCALE_DPI_480P` is 96 (configurable via system prop
`gal_min_dpi_480p`); all higher tiers floor at 120 dpi.

## 5. Aspect-ratio strategy: margins, not pixel_aspect

This was the most consequential discovery. AA's protocol gives the
phone **no** way to pre-distort UI pixels (i.e. render non-square pixels
to compensate for downstream non-uniform stretch). The closest field is
`pixel_aspect_ratio_e4`, which the phone ignores.

GM's solution is:

1. Pick a 16:9 codec frame from `{1920Ã—1080, 1280Ã—720, 800Ã—480}`.
2. Compute `width_margin` / `height_margin` so the **inner content rect**
   `(codecW âˆ’ wm) Ã— (codecH âˆ’ hm)` matches the panel's **projection area**
   aspect ratio. For a 2914Ã—1134 (â‰ˆ 2.57:1) Blazer panel + 1080p codec:
   `hm â‰ˆ 333` â†’ inner `1920Ã—747` â‰ˆ 2.57:1.
3. The car compositor scales the inner rect uniformly onto the projection
   area. Square pixels survive intact.
4. Adjust `density` per the formula above so UI elements arrive at the
   intended physical size after that uniform scale.

We mirror this in [`MarginAutoCalc`](../app/src/main/java/com/openautolink/app/video/MarginAutoCalc.kt)
and the C++ `autoMargins` lambda in
[jni_session.cpp](../app/src/main/cpp/jni_session.cpp). Our renderer goes
one step further than GM: instead of letterboxing the inner rect inside
the panel, we **inflate the SurfaceView so margin pixels overflow the
parent's `clipToBounds()`** â€” same uniform-scale outcome, but the
projection occupies the full panel rather than reserving panel area for
GM's HMI chrome. See
[ProjectionScreen.kt](../app/src/main/java/com/openautolink/app/ui/projection/ProjectionScreen.kt).

### Resolution menu has portrait mirrors

`VideoCodecResolutionType.proto` (in our aasdk fork) exposes the full
enum:

```text
VIDEO_800x480      = 1   // landscape only
VIDEO_1280x720     = 2
VIDEO_1920x1080    = 3
VIDEO_2560x1440    = 4
VIDEO_3840x2160    = 5
VIDEO_720x1280     = 6   // portrait variants
VIDEO_1080x1920    = 7
VIDEO_1440x2560    = 8
VIDEO_2160x3840    = 9
```

For portrait panels we advertise tiers 6â€“9 instead of 1â€“5 (no portrait
480p in the spec). For landscape panels both branches work; we keep the
landscape list. See
[jni_session.cpp](../app/src/main/cpp/jni_session.cpp) auto-negotiate
branch.

## 6. Display layout â€” `PhysicalDisplay` schema

`HuDisplayUtil.loadDisplayConfig` reads JSON files named
`DISPLAY_<SIZE>.json` shipped in the HMI APK assets, one per trim
(`DISPLAY_CHEVY_FF`, `DISPLAY_FF`, `DISPLAY_PORTRAIT_15_INCH`, etc.).
Schema (from `PhysicalDisplay.java` / `HuDisplay.java` / `Area.java`):

```jsonc
{
  "view_distance": 700,                  // mm to driver eye
  "suggested_density": 160,              // logical DPI
  "screen_width": 2914, "screen_height": 1134,
  "safe_area": { "x": â€¦, "y": â€¦, "width": â€¦, "height": â€¦ },
  "hu_display": [
    {
      "hu_display_type": "Main",         // "Main"|"BigCard"|"Auxiliary"|"Cluster"
      "fill_projection_area": true,
      "projection_area": { â€¦ },          // panel rect reserved for AA
      "content_area":    { â€¦ },          // safe sub-rect inside projection_area
      "safe_area":       { â€¦ }
    }
  ]
}
```

Important consequence: **GM does *not* render AA full-screen**. They
reserve panel area for native HMI chrome (top status bar + side dock) and
give AA only the `projection_area` sub-rect. Our app full-screens AA by
default; this is a design difference, not a bug.

## 7. Touch coordinates

From `GALManager.startVideo` and `GALDisplay.setTouchScaleRatio`:

```java
mActiveDisplay.setTouchScaleRatio(
    (float) contentSize.getWidth()  / (float) screenRect.width(),
    (float) contentSize.getHeight() / (float) screenRect.height());
```

Touch events from the panel are scaled into the **codec coordinate
space**, then sent on the input service. The phone scales them again to
its render space.

We do the same thing in our pointerInteropFilter overlay â€” see
[ProjectionScreen.kt](../app/src/main/java/com/openautolink/app/ui/projection/ProjectionScreen.kt).
The crucial detail: the touch destination is the **codec inner rect**
(`[0, innerW] Ã— [0, innerH]`), not the full codec frame.

## 8. Surface lifecycle and sleep/wake

Two patterns from `GALManager`:

### Surface binding is delayed until codec resolution arrives

```java
if (surface == null || mActiveDisplay.getCodecResolution() == null) {
    Log.d(TAG, "Video codec resolution not known -- delay MSG_UPDATE_SURFACE");
    mDelayedUpdateSurfaceArgs = someArgs;
}
```

GM does not pre-warm the decoder. We can do better: pre-instantiate
`MediaCodec` at app start using a known-supported tier (1280Ã—720 H.264
baseline-profile is universally supported), so the time from "phone's
first IDR" to "first rendered frame" is just the IDR drain. Especially
beneficial in **manual** mode where we know the exact codec frame
dimensions in advance â€” see the discussion in
[work-plan.md](work-plan.md). Not yet implemented as of this writing.

### `GALPowerOffManager` â€” phone-call / VR transients

When the user starts a phone call or voice-recognition session **while
AA is in foreground**, GM does **not** tear down the session. They overlay
a power-off-style screen and track restoration with two atomic flags
(`mShouldRestorePowerOffForPhone`, `mShouldRestorePowerOffForVR`) so the
session is preserved across transient events.

For our reconnect path, the equivalent is: when the car genuinely
suspends, the TCP socket dies and we treat it as fresh-connect; for
transient events (phone call, VR), we should hide the surface but keep the
session â€” same effect as GM, different mechanism.

## 9. Steering-wheel keycodes (`dispatchKeyEvent`)

| Range | Behaviour |
|---|---|
| 12â€“15 | Routed to `NavigationFocusManager.handleNavKeyEvent` |
| 16â€“19 | Phone keys â€” 16 = `KEYCODE_TEL`, sent only if AA is in foreground |
| 20â€“23 | Media keys â€” 20 = `KEYCODE_MEDIA`, sent only if media stream has audio focus |
| default | Routed to `VoiceSessionManager.onVRKeyEvent` (PTT/Hotword) |

We don't currently implement steering-wheel input. When we do, this is
the reference mapping.

## 10. ProjectionState lifecycle

From `gm.connection.ProjectionProfile.ProjectionState`:

```text
INVALID, STOPPING, STOPPED, STARTING, STARTED, SUSPENDED,
STOPPED_BY_CONNECTION_ERROR, STOPPED_BY_WIFI_CONGESTION, STOPPED_BY_MD_BYE
```

`STOPPED_BY_WIFI_CONGESTION` is a real GM-recognised reason â€” confirms
that WiFi-quality teardowns happen and aren't bugs in our companion.
Worth surfacing as a separate diagnostic category if we see it in field
logs.

## 11. SDV bridges â€” gated by system prop

| APK | Library | Gate |
|---|---|---|
| `com_gm_sdv_service_canbridge` | `libcanbridge.so` | `persist.sys.gm.sdv_enable` (off by default on retail) |
| `com_gm_sdv_service_udsbridge` | `libudsbridge.so` | **None** â€” Binder registered at boot |

The CAN bridge is the SDV (Software Defined Vehicle) interface to the
in-vehicle network. The UDS bridge speaks ISO 14229 (diagnostics / ECU
flashing). Neither is relevant to projection; both are interesting future
research targets for diagnostics features. Real protocol surface lives in
the `.so` files (Ghidra/BinaryNinja), not the Java wrappers (which just
load the lib and forward Binder calls).

## 12. APKs catalogued but not analysed in depth

These exist in `recon_dump/apks/` and may be worth deeper inspection
later:

- `com_gm_lcm` â€” Lifecycle Manager (sleep/wake state machine).
- `com_gm_phonezoneaudio_presentation` â€” multi-zone audio routing.
- `com_gm_hmi_applecarplay` â€” CarPlay HMI (parked on
  `feature/carplay-recon`).
- `com_gm_drivemode`, `com_gm_offroad`, `com_gm_valetmode`,
  `com_gm_teenmode`, `com_gm_isaplugin` â€” feature-flag-gated drive
  modes; not protocol-relevant but interesting for reverse-engineering
  available modes per VIN.
- `com_gm_ultifi_*` â€” Ultifi SDV service shells around vehicle
  subsystems (body access, climate, propulsion, etc.).
- `com_google_android_apps_automotive_templates_host` â€” the GAS
  templates host. Reference for non-projected templated apps in AAOS;
  out of scope for the projection layer.

## Decompilation tooling

- `jadx 1.5.3` with `--deobf` and `-r` (resource extraction).
- For >50 MB APKs: `JADX_OPTS="-Xmx12g"` and one APK at a time (a 32-bit
  default heap will OOM mid-decompile).
- The Templates Host APK in our dump was originally truncated (no ZIP
  EOCD record); a re-pull from the head unit produced a complete file.
- See [recon_dump/decompile.ps1](../recon_dump/decompile.ps1) for the
  memory-bounded batch runner.

## Provenance

Captured device: Chevrolet Blazer EV (RPO `aegean`, GM family `aegean`,
SDK 32, fingerprint `gm/aegean_orange/aegean:12/VCUSR5-163/239:user/release-keys`).
Pulled via `adb pull` while in factory engineering mode; 304 packages,
2.53 GB of APKs total. Triage is in [`recon_dump/TRIAGE.md`](../recon_dump/TRIAGE.md)
(gitignored). This file is the durable, in-repo summary intended to
survive after the recon dump is archived.


## 10. ADB unlock surface â€” GM's full diagnostic backdoor

Deep dive into `com_gm_domain_server_delayed.apk` (the `android.uid.system`-privileged service host) and `com_android_car_developeroptions.apk` revealed that **GM ships a complete, undisabled mechanism for enabling USB-ADB on production head units**. It is intended for dealers and the GM Tech 2 / GDS / DPS scan tools, but the entry points are still present on retail vehicles. This section catalogues every entry point we found and their reachability from outside `android.uid.system`.

### 10.1 Why plugging USB into the armrest port does nothing

The single armrest USB-A port wired to the head unit's Android Auto pipeline is **a USB host port at boot**, not a device port. The head unit drives it as host so a phone plugged in enumerates as a USB Accessory / NCM for wired AA. From `init.carplay.rc` and `init.carlife.rc` we see ConfigFS gadgets `g1` (CarPlay) and `g3` (CarLife) but **no ADB function listed at all** â€” the port is never advertised as an ADB-capable gadget at boot.

The dual-role USB port is mediated by a **Microchip / Aptiv bridge-chip hub** (`BridgeChipManager`, `CPMManager`, `HubObject` in `com.p006gm.server.screenprojection.RemoteDeviceService`). On a stock car the hub keeps the connector wired upstream (head unit = host, port = downstream). To get ADB you need to flip a specific hub port to **role-reversal** so the head unit becomes the device. That flip is performed exclusively by `RDMSADBHandler.enableAdb()` via `UsbUtils.sendNativeRoleReversalForDevice()` (native UDC: `a600000.dwc3`) or via the bridge chip's `bcm.set(port, "ADB", 0)`.

**Conclusion:** even with `Settings.Global.adb_enabled = 1` (which the AOSP dev-options toggle would flip), no ADB device enumerates on the cable until `RDMSADBEnable(true)` runs â€” because nothing else flips the hub port direction or sets `sys.usb.controller` / `sys.usb.config = adb`. **The dev-options ADB toggle alone is a dead end on this hardware.**

### 10.2 The complete enable path â€” what dealer tools actually do

```
UDS Routine 0x0332           ServiceManager binder            JNI native              shell script
[0x42 0x00 0x10 0x01]    â”€â”€â–º  com.gm.server.                 libRDMSJNI.so       /system/bin/ADBoverBCS.sh on
                              screenprojection.              execShellCmd()      sets sys.usb.config=adb
                              RDMSADBHandler                                     bcm.set(port, "ADB", 0)
                              .RDMSADBEnable(true)
                                       â”‚
                                       â–¼
                              RDMSADBHandler.enableAdb()
                              â”œâ”€â”€ BridgeChipManager.reserve("ADB")
                              â”œâ”€â”€ isBCSHub() ? bcm.set(port, "ADB", 0) : UsbUtils.sendNativeRoleReversalForDevice()
                              â”œâ”€â”€ SystemProperties.set("sys.usb.controller", "a600000.dwc3" | "dabr_udc.0")
                              â”œâ”€â”€ SystemProperties.set("vendor.gm.test.adb", "on")
                              â””â”€â”€ Settings.Global.adb_enabled = 1
```

After this sequence the head unit re-enumerates on the previously-host port as a Qualcomm ADB device (typical VID `0x05c6`).

### 10.3 The four entry points

#### A. AIDL binder: `com.gm.server.screenprojection.RDMSADBHandler`

**Interface** (`gm.connection.IRDMSADBHandler`):

```java
int RDMSADBEnable(boolean enable);   // transaction code 1
```

**Registration** ([RemoteDeviceService.java:226](../recon_dump/apks/com_gm_domain_server_delayed/java_src/com/p006gm/server/screenprojection/RemoteDeviceService/RemoteDeviceService.java)):

```java
ServiceManager.addService(
    "com.gm.server.screenprojection.RDMSADBHandler",
    this.mRDMSADBHandler.asBinder(),
    /*allowIsolated=*/ false);
```

**Permission check in `onTransact`: NONE.** Just `parcel.enforceInterface(DESCRIPTOR)` â€” any caller that can obtain the binder reference can invoke the method.

**Reachability gate:** `ServiceManager.getService(name)` from an unprivileged app:

- The method is `@hide` â€” needs reflection or `@SystemApi` (Car-API) access.
- More importantly: the service is registered with the **default SELinux service-manager label** (`u:object_r:default_android_service:s0` unless GM added an entry to `service_contexts`, which we cannot verify without shell). On AOSP 12, `untrusted_app` and `isolated_app` SIDs do **not** have `service_manager:find` on `default_android_service`. They cannot get the binder.
- `priv_app` and `system_app` SIDs *can*.

**To test from our own app (sideloaded, non-system-signed):**

```kotlin
val sm = Class.forName("android.os.ServiceManager")
val getService = sm.getMethod("getService", String::class.java)
val binder = getService.invoke(null, "com.gm.server.screenprojection.RDMSADBHandler") as android.os.IBinder?
// If null  â†’ SELinux blocked us (expected for untrusted_app).
// If not null â†’ call RDMSADBEnable via the Proxy stub. We win.
```

Worth doing as a one-shot probe even though we expect `null`. If it returns non-null, that's the entire root-of-trust on this car. The probe is in [tools/RdmsProbeActivity.kt](#) (proposed; not yet written).

#### B. `vendor.gm.test.adb` system-property polling backdoor

In [RemoteDeviceService.monitorAdb()](../recon_dump/apks/com_gm_domain_server_delayed/java_src/com/p006gm/server/screenprojection/RemoteDeviceService/RemoteDeviceService.java) (line ~510):

```java
while (!RemoteDeviceService.this.shutdown) {
    boolean currentAdbSetting = RemoteDeviceServiceWrapper.getAdbPersistStatus();
    boolean adbOnProp = SystemProperties.getBoolean("vendor.gm.test.adb", currentAdbSetting);
    if (currentAdbSetting != adbOnProp) {
        Log.i(TAG, "adbPropThread.adb property was found to be manually changed to: " + adbOnProp);
        RemoteDeviceService.this.changeAdbSetting(adbOnProp);
    }
    Thread.sleep(1000L);
}
```

**This polls the system property `vendor.gm.test.adb` once per second** and, on transition, runs the same full ADB-enable dance (`enableAdb()` â†’ role-reversal â†’ `Settings.Global.adb_enabled=1` â†’ `sys.usb.config=adb`).

**The unlock command, if you ever have a shell, is literally:**

```bash
setprop vendor.gm.test.adb 1     # wait 1 second â†’ ADB device appears on USB
```

**Reachability from a non-system app:** `SystemProperties.set()` is gated by `property_contexts`. `vendor.gm.test.*` likely has a custom selinux context. Most plausible contexts and who can write:

| Context | Writable by |
|---|---|
| `vendor_default_prop` | nobody outside vendor (default) |
| `system_prop` | `system_app`, `system_server` |
| `exported_default_prop` | platform_app, system_app |

Without `getprop -Z vendor.gm.test.adb` from a shell we can't tell which. **Almost certainly not writable by `untrusted_app`.** But it's the cleanest path if/when we ever get a one-shot privileged exec (e.g. via the JNI path below).

#### C. UDS Routine 0x0332 (the dealer path)

[RID0332Handler.java](../recon_dump/apks/com_gm_domain_server_delayed/java_src/com/p006gm/server/diagnostics_service/diagnostics/handle/globalb/common/rid/RID0332Handler.java) handles diagnostic Routine Identifier 0x0332. Payload (4 bytes):

| Byte | Value | Meaning |
|---:|---:|---|
| 0 | `0x42` | compId (must equal 66) |
| 1â€“2 | `0x0010` | dataId (must equal 16) |
| 3 | `0x01` | 1 = enable ADB, 0 = disable |

On a valid request it calls `ServiceManager.getService("com.gm.server.screenprojection.RDMSADBHandler").RDMSADBEnable(true)` and also writes `Settings.Global.adb_enabled = 1`. This is the **exact RID a GM Tech 2 / GDS scan tool uses to enable ADB at a dealer**.

Transport: the routine handler is registered inside `com.gm.server.diagnostics_service.DiagnosticsService`. We have not yet confirmed which physical transport delivers it â€” candidates are:

1. **DoIP over Ethernet** to the gateway module (most likely; ISO 13400). The head unit listens on a DoIP-internal TCP socket.
2. **OBD-II via the central gateway** routing UDS over CAN to the IVI ECU (less likely given AAOS uses Ethernet-internal architecture).
3. **An internal Binder route** for the dealer app (Tech 2 / GDS connects to the gateway, which proxies UDS into the IVI domain).

The handler **does not check `SecurityAccess` level itself** â€” that gate lives in `SecurityAccessNotificationHandler`, which is an **upstream** session-state setter, not an enforcement point. The session-management layer above the handler is what would reject the request if SecurityAccess (UDS 0x27) hasn't been unlocked. That algorithm is proprietary GM seed/key â€” independently reversed by communities for older GM platforms, unknown status for Aegean/SDV.

**Practical exploitation:** if you have OBD-II + a J2534 / Passthru device that can speak UDS to the IVI ECU and you have a seed/key implementation for the SDV platform, you send Routine Control (0x31) with sub-function Start (0x01), RID `0x0332`, payload `[0x42, 0x00, 0x10, 0x01]`. The car's USB port re-enumerates as ADB in under 2 seconds. We have not attempted this. **Highest-value real-world attack** â€” likely how aftermarket "GM ADB enabler" services on enthusiast forums operate.

#### D. `RDMSManager` JNI primitive (system-uid shell exec)

This is the most alarming code in the whole APK. [gm.rdmsmanager.RDMSManager](../recon_dump/apks/com_gm_domain_server_delayed/java_src/gm/rdmsmanager/RDMSManager.java) wraps `libRDMSJNI.so` and exposes:

```java
public native void nativeExecShellCmd(String path);
public native void nativeExecShellCmd(String path, String linkName);   // path + arg
public native void nativeChangeUsbSetting(String path, String value);  // write configfs
public native String nativeReadFile(String path);                      // read arbitrary file
```

Used internally:

```java
RDMSManager.getInstance().execShellCmd("/system/bin/ADBoverBCS.sh", "on");
RDMSManager.getInstance().execShellCmd("/system/bin/carplay.sh");
RDMSManager.getInstance().execShellCmd("/system/bin/carplay3.sh", "usb0");
```

`/system/bin/ADBoverBCS.sh on` is **the actual ADB-enable shell script** invoked by the bridge-chip path. It is shipped on-device under `/system/bin/`. The native lib runs in the calling process's UID, which here is `android.uid.system`. **This is, by design, a system-UID shell-exec primitive.**

It is not directly exposed via any Binder/Intent we could find â€” but it is loaded into a `system`-UID process that runs binders accessible across the platform. If any of those binders has an injection-style bug ("pass user-controlled string to `execShellCmd`"), the IVI domain is owned. We have not audited every callsite, but the recipe is: `grep -R execShellCmd com_gm_*` across all decompiled APKs. None of the current callers in `com_gm_domain_server_delayed` pass attacker-controllable strings.

### 10.4 Unrelated developer-mode side-channels (none useful on retail)

| Surface | Status |
|---|---|
| `com_android_car_developeroptions` | Stock AOSP. Build-number 7-tap flow works, sets `Settings.Global.development_settings_enabled=1`. Useless without RDMSADBHandler also flipping the hub. |
| `chevrolet_vcd_chevy_ff_developeroptions` (6.4 KB RRO) | Pure cosmetic overlay on the switch bar. No enabler logic. |
| `TestInterface` (RDMS broadcast receiver) | Gated by `!ro.build.tags.contains("release-keys")`. Disabled on retail. |
| `com.gm.lcddprovision` (factory line calibration) | Signature-protected by `com.gm.permission.LCDD_PROVISION` (`signature|privileged`). Out of reach. |
| `com.gm.usbmountreceiver` | Just auto-copies log files when a USB drive is plugged in. No ADB path. |
| `com_gm_updater` + `vendor.gm.swupdate@1.0` HAL | OEM SWUpdate (`gm.update.UpdateEngineService`). Signature-verified PackageStatus. Not exploitable as a backdoor. |
| `gm.onstar.OnStarRemoteReflashManager` | Visible in `service list`. Cloud-driven reflash via OnStar telematics. Not customer-reachable. |
| `com.android.shell` | Stock AOSP shell APK (4-bit). Has dumpstate path. Not exploitable. |
| `init.protokey_recovery.rc` | Hands off to bootloader recovery â€” unrelated to userspace ADB. |
| `com_android_managedprovisioning` | Generic MDM provisioning. No ADB unlock. |

### 10.5 Wireless ADB (`adb_wifi_enabled`)

`com_android_car_developeroptions` includes `WirelessDebuggingPreferenceController` which writes `Settings.Global.adb_wifi_enabled = 1`. **This does NOT depend on hub role-reversal** â€” it spawns `adbd` listening on a TCP port (with mDNS pairing) over whatever network interface the device is attached to. If you can reach the dev-options screen and flip wireless debugging, you don't need USB at all.

The catch: `adbd` only starts if `ro.debuggable=1` OR if the user has paired via `adb pair`. On `release-keys` builds it should still start in the latter mode (Android 11+ wireless ADB does not require `ro.debuggable`). Worth trying once we have a UI path into dev options.

**To probe wireless debugging from our own app on the head unit:**

```kotlin
// Both writes require android.permission.WRITE_SECURE_SETTINGS (signature|privileged).
// As an unprivileged app these will SecurityException, but the error tells us
// whether the gate is "you need to be system" vs "feature is locked at HAL".
Settings.Global.putInt(contentResolver, "development_settings_enabled", 1)
Settings.Global.putInt(contentResolver, "adb_wifi_enabled", 1)
```

If we ever get `WRITE_SECURE_SETTINGS` (e.g. via a sideloaded shell app with that permission granted manually through `pm grant`), this becomes a one-tap unlock. ADB wireless pairing on the same network as the car would then work without ever touching the USB hub.

### 10.6 Direct-launch shortcut: jump straight to dev options

This works **today** from any app (including ours) regardless of any GM customisation that hides the Build-number breadcrumb in their Settings UI:

```kotlin
// CarDevelopmentSettingsDashboardActivity is exported, intent-filter priority 1,
// android.intent.category.DEFAULT. Any app can resolve and start it.
startActivity(
    Intent("android.settings.APPLICATION_DEVELOPMENT_SETTINGS")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
)
```

If the activity launches but the screen is empty (`development_settings_enabled=0`), the user still needs to do the 7-tap dance on Build number inside the About screen â€” but at least we've bypassed any GM customisation that buried that path.

### 10.7 Pen-test action items (in priority order)

1. **Probe `ServiceManager.getService("com.gm.server.screenprojection.RDMSADBHandler")` from our sideloaded app** â€” single Kotlin reflection call, instant pass/fail. If it returns non-null, we win without dealer tools. Even if it fails, the `SecurityException` or `null` result tells us exactly which SELinux rule blocked us, which is useful intel.
2. **Probe `SystemProperties.get("vendor.gm.test.adb")` reachability** â€” read first (likely accessible), then attempt `SystemProperties.set("vendor.gm.test.adb", "1")`. The `set` will throw on selinux-deny; the exception class confirms `property_contexts` is the gate.
3. **Add a dev-only "Open AAOS Dev Options" button to our settings screen** â€” one-tap intent launch (10.6). Costs nothing, helps any future testing.
4. **Catalogue all callers of `RDMSManager.execShellCmd` across every APK** â€” already done for `com_gm_domain_server_delayed`; do the same sweep across the remaining 240 undecompiled APKs to look for an exposed binder that takes user input and pipes it into `execShellCmd`. That would be a critical-severity issue worth responsible-disclosing to GM, and a working root in the meantime.
5. **Confirm DoIP/UDS transport for RID 0x0332** â€” read `com.gm.server.diagnostics_service.DiagnosticsService` decompiled output to find the listening socket/binder. If we ever stand up a J2534 rig, this is the dealer path to ADB.
6. **Re-examine `gm.onstar.OnStarRemoteReflashManager`** â€” telematics-driven reflash service. If GM ever uses it to push a debug-keys build to a specific VIN, the seam between "trusted cloud signal" and "do something privileged" is the attacker surface.

### 10.8 Verdict

**Direct USB-ADB on a stock retail Blazer EV is not casually attainable.** All four enable paths require either:

- `android.uid.system` signature (RDMSADBHandler binder, monitorAdb prop write, JNI exec) â€” gated by GM's release-keys signing cert.
- UDS Routine 0x0332 via OBD-II + GM SecurityAccess seed/key (RID0332Handler) â€” gated by proprietary crypto.

The **highest-realism attack** is the dealer UDS path (10.3.C). The **highest-value defensive bug-class** to look for is an unauthenticated binder/intent that proxies into `RDMSManager.execShellCmd`. Worth the audit time across the remaining APKs.

For OpenAutoLink specifically, none of this is on the critical path â€” we don't need ADB on the head unit to develop our app, we have wireless debugging on the phone side, and the in-app Remote Log Server (TCP 6555) covers our diagnostics. But the recon answers the long-standing "why doesn't a USB cable in the AA port enumerate" question definitively, and gives us a clear unlock recipe if we ever do need it.

