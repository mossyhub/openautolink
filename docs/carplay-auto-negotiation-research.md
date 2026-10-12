# CarPlay automatic resolution and codec negotiation

## Decision

**Do not advertise 720p/1080p/1440p/4K as separate `displays[]` entries for one OAL Surface.** The evidence supports a display/stream-description contract, not Android Auto's alternative video-mode catalog. Implement **receiver-selected automatic geometry**, followed by **phone-selected AVC/HEVC within truthful receiver capabilities**. No supported-resolution-list field or phone-side best-mode ranking algorithm was established in the examined CarPlay paths.

This is static, exact-build research, not a phone/TCL acceptance receipt. No code, builds, devices or uploads were changed. The only repository deliverable is this document. The older scratch packet's statements that OAL's sink is AVC-only are historical: the current integrated tree now passes a qualified AVC-plus-optional-HEVC receipt.

## Inputs and reproducibility

- Existing firmware inventory: `/hermes_work/research/carplay-ios/README.md`, `analysis/24A446/baseline.json`, `analysis/24A446/dyld-images.json`.
- Target: inventory identifies iPhone 15 Pro / `iPhone16,1`, iOS `27.0.1`, build `24A446`; cache UUID `7BFCA80F-65CD-32E3-B361-4D2BAB2282F4`. These identifiers are preserved exactly, not inferred from the owner's phone.
- Original cache: `extracted/24A446__iPhone16,1/dyld_shared_cache_arm64e` with its existing numerical siblings.
- AirPlaySender: `/System/Library/PrivateFrameworks/AirPlaySender.framework/AirPlaySender`, version `980.77.1.2.0`, image UUID `2D49B39B-FB65-366B-9AB5-6995E5E0FEBF`.
- CoreMedia: `/System/Library/Frameworks/CoreMedia.framework/CoreMedia`, version `3350.77.1.6.0`, UUID `A1E3885B-22BE-3C76-95C1-7821276212F6`.
- CarKit: `/System/Library/PrivateFrameworks/CarKit.framework/CarKit`, version `799.3.0.0.0`, UUID `62C181F8-D193-3F7A-BA03-6A263DCDD759`.
- Prior instruction receipts: `/home/hermeswebui/.hermes/profiles/dev/cache/scratch/carplay-video-*.disass.txt`, particularly `display-properties`, `stream-display-info`, `virtual-options`, `endpoint-options`, and the existing `carplay-video-contract-24A446.md`.
- Fresh probes used `GOMEMLIMIT=512MiB GOMAXPROCS=1 tools/ipsw dyld disass CACHE --vaddr ADDRESS --count N --quiet --no-color`, serialized with bounded instruction counts and timeouts. No full cache/string dump or new firmware extraction. Fresh results are preserved as tool output and exact branches below; prior receipts remain untouched.
- Address readers decoded only selected mapped bytes. Chained pointers were resolved using `0x180000000 + (raw & 0xffffffff)` when required. Linear register comments in old receipts can be stale across `csel`/calls; branch interpretation below follows actual registers, not those annotations.

## Primary Apple evidence

Apple WWDC19, around 1:00: “The vehicle declares the physical size and resolution of the display and iPhone will setup an H.264 video stream of that size”. This is a declared target geometry, not an instruction to send alternatives and have the phone choose the largest.[1]

WWDC19 distinguishes another screen from another layout: instrument-cluster support uses additional H.264 streams, while dynamic screen sizing uses View Area 1 and View Area 2 on the same center-console/cluster display, with vehicle-directed transitions.[1][4]

WWDC23 explicitly says: “CarPlay now supports HEVC encoding for all UI streams” and “Your system should continue to support H.264 for backwards compatibility.” This is primary public confirmation of CarPlay HEVC; it is not merely an AirPlay-mirroring inference. The same talk discusses main-display widgets and instrument-cluster navigation streams, reinforcing that stream identity and physical display identity are related but not necessarily one-to-one.[3]

WWDC24 says next-generation CarPlay builds on existing CarPlay and requires HEVC video streaming; its wireless-only description is for next-generation CarPlay, **not proof that ordinary CarPlay HEVC cannot work wired**.[5]

The WWDC16 standard list is 800×480, 960×540, 1280×720 and 1920×720. Its landscape/profile/refresh requirements are dated integration guidance, not a present-day exhaustive maximum-size table. Do not conflate panel refresh recommendation with constant encoded FPS.[2][6]

## 1. What `displays[]` means

### Established

The current OAL `/info` serializer emits `uuid`, `type`, one `widthPixels`/`heightPixels` pair, independent `widthPhysical`/`heightPhysical`, `maxFPS`, input features and view/safe areas. HID uses `displayUUID` to identify the corresponding geometry. Main type110 and optional cluster descriptions are separate identities; they are not codec choices.

AirPlaySender `APEndpointDisplayDescriptionCopyProperty`, region `0x251964e54`:

- `PixelSize` reads scalar `widthPixels`/`heightPixels` at `0x25196511c/0x251965134`.
- `PhysicalSize` independently reads `widthPhysical`/`heightPhysical` at `0x2519650b8/0x2519650d0`.
- `PixelSizeMax` independently reads `widthPixelsMax`/`heightPixelsMax` at `0x251965180/0x251965198`.

CarKit's actual `-[CARDisplayInfo initWithPhysicalScreenDictionary:displayPluginDictionary:]` at `0x1c253e978` calls pixel-size reader `0x1c2522f08` and physical-size reader `0x1c2523374`, stores separate CGSize fields, obtains physical `uid` via key global `0x1e17604e0`, and stores the identifier at `[self,#0x10]` (`0x1c253eaac–0x1c253eab4`). Its stream collection is separate (`properties` read via `0x1e17604f8`; update call at `0x1c253eb94`). Recovered `CARDisplayInfo.h` exposes `identifier`, `pixelSize`, `physicalSize`, `streams`, `videoStreamIdentifiers` and physical/logical-screen constructors. This is an identity/layout model, not a resolution-preference enum.

### Necessary precision

Do not overstate “every `displays[]` row equals one physical panel”. Modern CarPlay has physical displays, logical video streams, widget streams and view areas. The inspected CarKit physical-display dictionary is not proven byte-for-byte identical to the legacy `/info.displays[]` row. **What is ruled out as an evidence-backed implementation is treating those rows as mutually exclusive resolutions for the same main display.** No examined parser/ranker chooses the “best” row among duplicate main-screen size entries. Adding fake rows may instead describe extra streams/screens, duplicate identity or invalid geometry.

For OAL today, keep one main display/stream identity for the actual Surface, matching HID UUID and one qualified encoded geometry. Add a cluster only if a real separately implemented output exists. Keep layouts in `viewAreas[]` and use their real update protocol when implemented; view areas are not a codec-resolution capability list.[1][3][4]

## 2. `PixelSizeMax` has an actual consumer — not a supported-size list

AirPlaySender `screenstream_initDisplayInfo`, start `0x251a0d3cc`:

1. `PixelSize` is copied to storage `[x19,#0xd8/#0xe0]` at `0x251a0d4e4–0x251a0d514`.
2. CarPlay flag `[x19,#0x183]` branches at `0x251a0d564` into positive-width/height checks `0x251a0d5c0–0x251a0d5d0`; failure reaches `0x251a0de48`, status `0xffffbe88` (-16760). The short-axis-to720 scaling path is the other branch, not a CarPlay minimum-resolution theorem.
3. Max-size storage `[x19,#0xf8/#0x100]` is initialized to the zero-size constant at `0x251a0d67c–0x251a0d680`.
4. At `0x251a0d684–0x251a0d688`, flag `[x19,#0x174] == 0` skips `PixelSizeMax` reading. With the flag enabled, property read `0x251a0d68c–0x251a0d6a4` copies CGSize to `[x19,#0xf8]` at `0x251a0d6ac–0x251a0d6b4`. Read failure takes error path `0x251a0de70`.
5. `0x251a0d6f0–0x251a0d700` performs **componentwise maximum** of stored maximum and current pixel dimensions. It is not a minimum clamp of the requested stream down to a phone-supported resolution.
6. The >1920 or >1080 branch at `0x251a0d704–0x251a0d738` has platform/helper gates; failing them resets max-size storage to current pixel size. Passing reaches `0x251a0d9c0`, sets flag `[x19,#0x17a]`, and checks CarPlay at `0x251a0d9c8–0x251a0d9cc`: CarPlay skips the generic `0x2581074b0` size-transform call and rejoins `0x251a0d740`.

**Fresh gate discovery:** `screenstream_initFromPrefs`, `0x251a0a814–0x251a0a82c`, reads preference `screenEnableMaxDisplaySize` (CFString `0x276154300`, domain `com.apple.airplay`) using the current flag as default and writes `[x25,#0x174]`. This is a sender-side preference/policy gate, not an accessory “Auto” field. Its original default initialization was not recovered; do not claim always on or always off.

**Fresh downstream discovery:** `screenstream_createVirtualDisplayActivationOptions`, `0x251aa0dc0`, has a non-CarPlay subpath at `0x251aa1508–0x251aa1524` passing `[x19,#0xf8/#0x100]` and effective FPS to helper `0x258108e00`. The CarPlay branch at `0x251aa1284–0x251aa1298` instead sets `clientName=CarPlayScreen` (`0x251aa12dc–0x251aa12ec`) and, when stored override canvas dimensions `[x19,#0xe8/#0xf0]` are positive, emits **OverrideCanvasWidth** / **OverrideCanvasHeight** at `0x251aa164c–0x251aa166c`. It also passes effective FPS as `VSyncRate` and `defaultFramerate` at `0x251aa1250–0x251aa1280`.

Therefore max-size fields participate in gated maximum-display/virtual-display machinery, but neither this consumer nor activation path implements a list of acceptable alternative resolutions. Their complete CarPlay producer/default/dynamic-resize semantics and every later encoder consumer remain unresolved. **Do not add `widthPixelsMax/heightPixelsMax` as a shortcut to phone-best-resolution selection.**

## 3. Codec selection: real negotiation, real hardware gate

Prior exact AirPlaySender receipt:

- `carEndpoint_readSupportedFeaturesFromSetupResponseAndNotify`, `0x2519abc50–0x2519abc78`: membership test of `hevc` in the setup response's feature array, followed by conditional write `[x27,#0x4b]`.
- Feature-building region `0x2519ab61c–0x2519ab630` passes `hevc` and `enableCarPlayEncodingStreamTypeHEVC` into its policy helper. This is not an accessory resolution-list field or proof of an unconditional enable.
- `screenstream_createVirtualDisplayActivationOptions`, `0x251aa0e34–0x251aa0e60`: if CarPlay flag `[x20,#0x183]`, HEVC-related flag `[x20,#0x184]`, and helper `0x25810a160` are all true, select **CarPlayHEVC**, set `[x20,#0x3c7]=1`; otherwise fall through `0x251aa10bc` to mode resolution at `0x251aa10dc–0x251aa10f8`, selecting **CarPlay** for this branch. An already supplied usage mode `[x20,#0xa8]` bypasses this default decision at `0x251aa0e30`; this is not a universal predicate for every possible internal caller.

### Newly resolved helper

The previously unnamed hardware helper is materially narrowed:

- AirPlaySender stub `0x25810a160–0x25810a168` branches to CoreMedia `0x1970219e8`.
- `0x1970219e8–0x197021a00` returns cached byte `[0x1e4864370]`; uninitialized once-token at `0x1e4864378` invokes `0x1974c1900`, a thunk to `0x197022984`.
- `0x197022984–0x197022994` dispatches once via `0x198105020` and block `0x1ea8d5d08`; its authenticated invoke pointer resolves to `0x197021a20`.
- Block `0x197021a20` locates **AppleAVE2Driver** (`0x1976469d7`) at `0x197021a68–0x197021a7c`, reads registry property **HEVCEncoderMaxPixelRate** (`0x1976469fe`) at `0x197021a88–0x197021a98` into an eight-byte buffer, validates return and length at `0x197021aa0–0x197021aa8`, and at `0x197021ab4–0x197021ac4` caches true only if its value is **greater than `0x1da9bfff` (497663999)**. Failed lookup/read/length yields or preserves false in the examined normal branch. Another flag at `[0x1e48643f8]` branches to `0x197021af0`; that alternate path was not exhaustively traced.

This is a concrete driver-capability pixel-rate threshold gate, **not a list of HEVC profiles, nor the measured maximum rate of this phone**. The firmware contains a reader for the property; no live iPhone registry value was obtained. Do not turn the threshold into a guaranteed 4K60 CarPlay promise or assert a transport matrix from it.

### Current integrated OAL behavior (read-only)

- `CarPlayVideoQualification.select`: exact AVC WxH/FPS decoder receipt is mandatory. If `preferHevc=true`, qualify optional HEVC at the **same geometry**. If AVC candidate fails, select only safe 1280×720@30 AVC or fail closed.
- `AirPlayInfoPlist`: `hevcInfo={}` only with matching immutable selection and qualified HEVC; scalar geometry is still chosen before `/info`.
- `AirPlaySession.handleSetup`, lines703–708: intersect supported setup features through `setupEnabledFeatures`, record whether `hevc` was negotiated, reply with `enabledFeatures`.
- The selector's initial Android probe uses AVC High / HEVC Main; incoming hvcC/SPS profile, tier, level, chroma and bit depth are parsed separately. This is Android implementation admission, **not Apple profile-selection policy**.

Thus label the optional setting **Prefer HEVC (AVC fallback)** or **Automatic codec (AVC + qualified HEVC)**, not HEVC-only and not “phone automatically selects best resolution”. The iPhone may use AVC even when HEVC is offered.

## 4. Profiles and FPS: what is and is not known

`screenstream_initDisplayInfo` `0x251a0da38–0x251a0da74` fetches `MaxFPS`, converts to integer, ignores values <1, and lowers `[x19,#0x1d0]` only when the internally selected rate is greater. Result is a positive-integer maximum cap over an internal rate, not guaranteed constant FPS. There is no step5 quantization here.

The downstream activation options use that effective rate as `VSyncRate` and `defaultFramerate`. The CarPlay branch also configures `maxIdleFrameReencodes` and conditionally `minNumRepeatedFramesToEncode=30`; **that30 counts repeated frames, not proof of30FPS** (`0x251aa129c–0x251aa12c4`). Static UI need not produce continuously changing frames.

No complete current phone-side selection of H.264/HEVC profile, level, tier, bit depth, bitrate, effective FPS or canvas override policy was recovered. No evidence-backed accessory list of HEVC profiles/resolutions/FPS tuples was found. Apple's public HEVC guidance says support H.264 backward compatibility but does not publish that tuple catalog.[3]

Use actual in-band avcC/hvcC/SPS and frame timestamps as the runtime truth; distinguish announced dimensions, coded dimensions, visible crop, actual decoder, negotiated feature and rendered FPS. Neither hvcC enum presence nor Android supportedTypes proves the phone will emit that mode.

## 5. Concrete OAL Auto design

This is a proposed implementation contract, not changes made by this investigation.

1. Add independent resolution policy `{Automatic, Manual}`; codec policy remains independent `{AVC, PreferHEVCWithAVCFallback}`. Retain manual requested values and physical-size calibration when switching to Auto.
2. For the **next session**, capture the actual laid-out Surface viewport and owning Display, its orientation, available refresh ceiling, plus separately sourced physical width/height. Do not use Android density as real physical DPI and do not scale millimeters by encoded resolution.
3. Choose one even/aligned aspect-preserving target geometry at or below useful viewport detail and bounded product limits. Rank a bounded **local** candidate set (exact viewport-aligned candidate plus lower aspect-preserving/source-backed candidates); no fictitious protocol “full list”. Prefer no avoidable upscaling and no stretch. Define explicit tie-breaks and product FPS ceiling; sixty is product policy, not a newly proved global sender maximum.
4. Qualify each bounded candidate through exact AVC size/rate/format/alignment support; optional HEVC must also qualify at the selected AVC-safe geometry. Preserve the same immutable decoder/geometry receipt into `/info`, session and sink. Do not independently reselect in serializer or sink.
5. Advertise one main `uuid` with that WxH/maxFPS, truthful mm and matching HID/view/safe spaces. `hevcInfo`/SETUP `hevc` stay optional and qualification-gated. Never serialize a local candidate array as extra `displays[]` rows or invented `supportedResolutions`.
6. If no preferred candidate qualifies, use the exact safe1280×720@30 AVC receipt; clearly disclose fallback. That fixed baseline may require aspect-fit on a non16:9 viewport. If it too fails, do not start media ownership.
7. A phone rejection does **not** prove every smaller candidate works or that AVC automatically fixes resolution. Initially show stage/offer failure and offer an explicit reconnect with safe baseline. Only introduce bounded automatic retry after session retirement, concrete phone rejection classification and no-loop policy are tested; never renegotiate geometry by sending unsupported list fields.
8. Keep dynamic layout/view-area updates separate from encoded-resolution adaptation. Reconnect on Auto geometry changes until real live stream/canvas transition support and owner fencing are established.
9. Expose “Auto selected WxH @ cap; offered AVC [+HEVC]”, then separately “phone actual codec/coded/cropped size/profile/tier/level; rendered rate”. Cache successful compatibility only per real phone/model/build/transport and display/decoder tuple, not as a universal list.

### Parent acceptance gates

- Pure policy tests: deterministic bounded ranking; viewport unavailable; wide/portrait viewport; alignment; below-cap FPS; HEVC unavailable; requested AVC failure; exact baseline failure; stable physical mm; matching HID space; no extra resolution rows/max-size shortcuts.
- Real decoder execution remains parent-owned: named configure/start/CSD/decode plus replacement/retirement. Initial inventory is not this receipt.
- Phone runs remain parent-owned: compare exact `/info` to SETUP feature agreement, in-band actual codec/SPS dimensions/profile and rendered screenshot/post-touch change; run AVC and optionalHEVC at identical geometry first. Separate automatic local geometry selection from phone codec negotiation. Wired/wireless and other phone generations require their own receipts.

## Unknowns and limitations

- No exhaustive public/current CarPlay maximum dimensions, supported-resolution list or best-mode ranking recovered. This is scoped negative evidence, not a proof that no private future extension exists.
- Complete legacy `/info.displays[]` parser-to-physical/logical-display mapping was not reconstructed; do not equate each row with precisely one panel. Evidence still does not authorize alternative-mode rows.
- `screenEnableMaxDisplaySize` initial default, every max-size producer/consumer, CarPlay override canvas selection and all alternate hardware helper branches remain untraced.
- HEVC capability property was read in executable code, not on a live phone; exact property value, per-profile limits, sustained encoder behavior, thermal/transport effects and runtime selection remain unknown.
- `GOMEMLIMIT` is a Go runtime target, not a hard process RSS guarantee; all probes stayed quiet/serialized/bounded. `free` was absent, so memory prerequisite inspection used selected `/proc/meminfo` fields instead. No huge outputs were emitted.

## Sources

[1] https://developer.apple.com/videos/play/wwdc2019/252
[2] https://developer.apple.com/videos/play/wwdc2016/722
[3] https://developer.apple.com/videos/play/wwdc2023/10150
[4] https://devstreaming-cdn.apple.com/videos/wwdc/2019/252ogueb17a2lv/252/252_advances_in_carplay_systems.pdf?dl=1
[5] https://developer.apple.com/videos/play/wwdc2024/10111
[6] https://devstreaming-cdn.apple.com/videos/wwdc/2016/722x2eefo3u2rp8k8qs/722/722_developing_carplay_systems_part_1.pdf?dl=1
