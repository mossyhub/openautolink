# Changelog

## 0.1.535 — cluster guidance recovery and microphone comparisons

- Keep CarPlay's advancing maneuver-definition window from evicting the upcoming turn while protecting the previous selection. Retain a bounded current-display snapshot separately; preserve explicit clear, stop, arrival and ownership expiry.
- Preserve valid formatted-only navigation distances, including sourced zero feet. On the AAOS publication path, withdraw guidance when step distance is missing and resume when it returns; omit destinations without their own distance rather than inventing zero or borrowing step distance.
- Keep destination ETA independent of the next turn's ETA. Unknown destination times stay unknown; sourced zero and positive times remain valid. These changes avoid a null-distance failure found in an archived host serializer; installed-host crash causality and physical cluster recovery remain unverified.
- Add an optional parked microphone-source comparison at fixed 24 kHz mono: recognition → communication → recognition. Retain up to twelve immutable numeric diagnostic summaries for manual upload, including completion delivered after another run begins. No audio recordings are stored or uploaded.
- Preserve permission, Park, foreground, projection and capture-exclusion gates. Normal CarPlay/Android Auto microphone routing and automatic projection handoff are unchanged. This is diagnostic support, not a claimed call-microphone repair.
- Verification: full app, receiver and companion debug/release suites completed 4,804 test cases with zero failures/errors and two existing optional skips. Both native build variants, app release lint, corrected receiver release lint and independent combined-source review passed. Final permission-boundary change additionally passed fifteen focused tests per variant.
- Missing Android Auto music metadata remains unresolved. Vehicle cluster rendering, installed-host compatibility and physical microphone/call outcomes require owner testing. Distribution remains the original and Groups 2–5 Automotive Internal listings; Community and tester lists are unchanged.

## 0.1.534 — microphone diagnostics and connection recovery

- Add an explicit, foreground-only parked microphone diagnostic with passive input inspection and separate channel, sample-rate and preferred-input comparisons. Report requested/recorder/client/device formats, route stability and numeric signal aggregates without recording or uploading audio.
- Require fresh actual Park on automotive devices, explicit consent, no active/connecting projection and safe recording policy. Cancel on background/tab exit, policy loss or deadlines; retain capture exclusion until the recorder actually releases. Normal CarPlay and Android Auto capture settings are unchanged when the tool is idle.
- Save only the latest completed numeric result for the existing manual log uploader. A completed procedure or nonzero samples do not establish speech recognition or caller audibility.
- Preserve useful wired CarPlay failure guidance when Android Auto resumes. Reject a fenced USB attachment before another destructive handoff and avoid the repeated picker loop; retain the separate explicit permission-denial retry path. The initial network-control timeout remains unresolved.
- Restore persistent amber/green/red upload status, clear success wording and safe failure codes in the single OAL menu. Keep the upload action visible and prevent duplicate uploads.
- Physical tablet testing confirmed channel/rate comparisons, stable route reporting, absent preferred-input rejection, Home cancellation, consent reset and subsequent capture reservation availability. GM input mapping, car microphone recovery and two-way CarPlay call audio remain unverified.
- No companion runtime changes. Distribution remains the original and Groups 2–5 internal listings; Community and tester lists are unchanged.

## 0.1.532 — cluster guidance and vehicle data

### Cluster guidance and maneuver coverage
- Withdraw unavailable guidance instead of sending a loading Trip that GM labels “Rerouting”; retain source-route identity so a later usable instruction can restart guidance. Preserve deliberate host stop, arrival and generation ownership boundaries.
- Retain CarPlay's selected maneuver across omitted-current route deltas and quiet intervals within the finite route lifetime. Explicit empty selection, route replacement, stop and arrival still invalidate guidance. Expire dynamic estimates independently rather than letting unrelated fields renew them.
- Apply AndroidX roundabout constraints by maneuver family: generic entry/exit cannot carry an exit ordinal; combined types require a sourced positive ordinal. Unsupported direction or geometry does not become an invented left turn, straight arrow or exit 1.
- Expand original directional vector coverage and legacy Android Auto ferry/roundabout mapping; clear old turn artwork and maneuver-only fields when a new turn arrives while preserving distance-only delta behavior.

### Vehicle metadata, GPS and optional CarPlay restrictions
- Supply source-qualified vehicle identity, connector metadata and outside temperature through the existing production adapters. Retain missing/unsupported outcomes; do not invent EV profiles, capacities or charging powers.
- Keep GPS provider/source timestamps and freshness distinct from forwarding time. Physical phone GPS-source selection remains unverified.
- Add independently saved, default-OFF vehicle-based CarPlay UI restrictions and a separate default-OFF Always in Park override. Missing/stale gear stays restricted unless the explicit override is enabled. Neither option changes actual gear, Android Auto policy or video permission.
- Use the same observation receipt sample for gear status authority, avoiding a second clock sample invalidating an otherwise valid observation.

### Verification and limitations
- Full app, receiver and companion debug/release suites: 4,576 test executions, zero failures/errors, two existing optional private-archive replay skips. Independent source review, real Android vector rendering, both native ABI builds, and app/receiver release lint passed.
- Physical cluster recovery/glyph rendering, vehicle-data/phone GPS-source consumption and restrictions remain vehicle-specific acceptance gates; software verification does not claim these reported failures are resolved on every vehicle. Direct-AA numeric roundabout angle rendering remains deferred until its source convention is proved; current phone artwork and neutral fallbacks remain available.
- No companion behavior changes. Internal Play distribution covers the original and four existing capacity-group listings only; the paused Community track and tester lists are unchanged.

## 0.1.530

### Multiple iPhones with CarPlay
- Remove the persistent first-iPhone-only pairing restriction. Preserve the existing receiver identity and remembered phone keys when another iPhone connects; no app-data reset is required.
- Allow one additional cryptographically verified phone within each bounded, authorized USB or wireless bootstrap. Keep existing-key replacement forbidden, trusted reconnects available, exact transport/owner checks and one active projection session.
- USB continues to use the attached phone rather than a saved preferred-phone list. WPP continues to follow the incoming phone selected by the vehicle; its identity correlation and active-session protection are unchanged.
- Wireless CarPlay retains explicit bootstrap selection. This does not add automatic wireless-phone arbitration, resolve GM wireless ingress, change Bluetooth profiles, or fix the separate CarPlay audio issues.
- Add persistence/restart, A → B → A cryptographic verification, enrollment deadline/capacity and real receiver/socket pairing regressions. Physical two-iPhone vehicle acceptance remains pending.
- No companion behavior changes; the matching companion APK is version-aligned only.

## 0.1.528

### USB permission retry after explicit selection
- Allow a fresh explicit selection of the currently attached iPhone to request USB access again after a denied permission result. Keep automatic retries suppressed and retain real platform permission enforcement.
- Bind picker entries to manager-qualified attachment receipts so stale entries cannot authorize a replacement after detach, manager restart or same-address reattachment. Preserve one-shot owner, cancellation and replacement-consent fences.

### CarPlay return to the car
- Supply the OpenAutoLink logo and label for CarPlay's return-to-car tile.
- Route authenticated, current-session generic return requests to background OAL without stopping CarPlay transport or audio. Reopening OAL retains the existing presentation reattachment path rather than deliberately reconnecting.
- Reject stale-session and retired-Activity requests, coalesce repeated requests, and keep unsupported URL and video-player actions separate from generic return.
- Physical iPhone tile rendering, OEM landing behavior, retained audio and same-session return still require vehicle verification. This does not fix the separately investigated Bluetooth audio interruption or explain the original permission-denied callback.
- No companion behavior changes; matching GitHub companion APK is version-aligned only.

## 0.1.527

### CarPlay cluster guidance recovery
- Preserve the route's bounded maneuver-definition cache when a quiet interval makes the displayed instruction stale. A fresh explicit turn selection can restore cluster guidance without requiring the iPhone to resend the same maneuver details.
- Keep stale instructions hidden until that confirmation, expire old distance/time estimates, and retain existing route-expiry, stop, arrival, cancellation and host-stop behavior. Do not hold an obsolete turn on screen indefinitely.
- Add parser-to-host regression coverage for the captured quiet-interval failure and deadline, selection, cache-bound and route-replacement cases. Vehicle recovery still requires confirmation on this build.

### Uninterrupted launcher entry
- Remove the automatic Projection controls dialog when opening or returning to OAL from the launcher. Continue directly to the normal app screen.
- Keep Settings and Restore button positions available through the movable OAL-logo menu. Projection connections, saved transport choices and VPN/upload routing are unchanged.
- No companion behavior changes; the matching companion APK is version-aligned only and is not required for these car-side fixes.

## 0.1.526

### Wired CarPlay mode selection
- Distinguish confirmed CarPlay mode (USBMUX, NCM and Apple Ethernet) from a compatible USBMUX/NCM configuration that still needs the mode request. Reuse confirmed mode without requesting another switch.
- Attempt one bounded mode transition when needed. Preserve older-peer rejection fallback only for the unchanged attached, permitted device and current owner; retain replacement consent and detach/cancellation fences.
- Add finite mode-decision, transition-result and selected-descriptor receipts to uploaded logs. No raw device identifiers, packet replay, generic USB resets, audio or authentication changes.
- Correct the Release APK badge to show actual release-event runs instead of a historical cancelled manual build; failed release builds still show red.
- Full debug/release app, receiver and companion verification: 4,170 tests, zero failures/errors, two existing optional private replay skips. Independent exact-tree review passed. Physical first-connect/replug improvement and the cause of the prior vehicle failure remain unproven until vehicle testing.
- No companion behavior changes; matching GitHub companion APK is version-aligned only.

## 0.1.525

### One movable OAL controls menu
- Replace separate floating buttons with one OAL-logo button. Tap for supported actions; hold and drag to reposition it. Start centered, with a one-time drag hint.
- Reclaim the fixed recovery strip and hidden-system-bar space while respecting visible system controls and cutouts. Preserve preferred placement across temporary window, inset and UI-size changes; Restore button positions recenters the logo.
- Keep diagnostic/maintainer enable gates. Normal menu actions no longer depend on retired floating-button visibility settings, including previously hidden Phone Switcher buttons.
- Offer best-effort launcher re-entry recovery without interrupting fresh startup. Actual GM launcher delivery remains unverified.
- Default native OAL interface size to 125% without overriding saved choices. Display the build version in Settings with a small internet-enabled Wi-Fi update reminder.
- Leave Android Auto/CarPlay video geometry, projected touch mapping and stream scaling unchanged. Vehicle display-transition acceptance remains pending.

### Bounded CarPlay startup diagnostics
- Add sanitized startup packet classifications and native USB write timing to distinguish forwarding, write completion, and control-connection admission.
- Exclude diagnostic observer work from native write timing; stop new reservations at accepted-owner admission, before fallible session setup. Keep disabled/capped observations out of the diagnostic monitor.
- Preserve exact-owner retirement and bounded export in uploaded logs without raw network identities or payload bytes. No new retries, framing, timeout policy, authentication or audio changes; this does not claim faster startup or phone consumption.

### Test isolation and compatibility
- Keep JVM component fixtures from unintentionally starting process-wide Bluetooth services; add runtime application-identity regressions without weakening token-visibility assertions.
- No companion source or behavior changes. The matching public companion APK is version-aligned only; these car-side changes do not require a companion update.

## 0.1.524

### CarPlay startup diagnostics and bounded writes
- Bound USB NCM writes so a stalled endpoint cannot wait indefinitely. Report control-listener timing and endpoint-write receipts without claiming that receipt proves peer consumption or first video.
- Preserve USB authorization, session ownership, first-video recovery and admission checks. Startup timing and sustained projection still require vehicle verification.

### Log-upload token visibility
- Add an accessible Show/Hide upload token action. Tokens start masked; disclosure is local, resets when leaving/reopening the field, and does not persist or change the token.

### OAL interface size
- Add Normal, Large, and Extra Large sizes for OAL menus, text, and controls, independent of Android Auto/CarPlay stream settings. Apply immediately without reconnecting and preserve the system font-size preference.
- Keep settings navigation scrollable at larger sizes and use the available settings-pane width. Normal preserves the existing size; Large is the recommended option when the car UI is too small.

### Floating-control recovery
- Measure floating buttons independently so shrinking the display does not squeeze later buttons or their icons away. Default positions wrap into extra columns when needed.
- Keep a fixed OAL controls menu separate from draggable positions. It provides Settings and every enabled/supported action even when saved buttons overlap; Restore button positions resets only overlay placements, including disabled controls.
- Reproject saved positions from known layout geometry instead of inferring anchors from already-offset buttons. Cancel stale drags when the available area changes.
- On windows too small for the full set of buttons, use the fixed menu rather than shrinking or stacking inaccessible controls.
- Bound controls to the clipped, system-bar/cutout-safe window area without changing the video viewport. Keep them above the Stats panel and preserve physical placement in right-to-left layouts.
- Does not change Android Auto/CarPlay stream scaling, touch mapping, connection behavior, or other saved settings. Vehicle confirmation is still required.

## 0.1.523

### CarPlay first-video recovery
- Fix a configuration-publication race introduced with 0.1.521 background-video retention that could discard the first keyframe arriving while the decoder worker waited for input.
- Preserve the current decoder and queued frames for identical codec configuration notifications; genuinely changed configurations still create a new fenced decoder receipt.
- Add bounded configuration, decoder-reconfiguration and recovery-reason diagnostics without exporting raw codec data.
- Preserve surface/session ownership, stale callback rejection, background retention, audio and USB permission behavior.
- Debug/release suites passed per variant: app 1,344, receiver 478, companion 118; one optional private-archive app replay test skipped per variant. Independent scoped review passed.
- Vehicle first-video latency, sustained rendering and unplug/replug recovery still require verification. No guarantee of under-10-second startup or a single USB permission prompt.

## 0.1.522

### USB startup recovery
- Fix the 0.1.521 silent authorization wait after USB mode switching that could cancel startup and return to the phone picker.
- Request permission for the exact admitted initial/replacement device when Android has not granted access, preserving the same connection attempt and platform authorization checks.
- Two real permission approvals can still be necessary across USB re-enumeration; a single system prompt is not guaranteed. Vehicle verification remains pending.

### CarPlay startup feedback
- Show a compact Waiting for video indicator until the current presentation actually renders a frame.
- Fence queued USB/Wi-Fi presentation replacements against predecessor rendered telemetry, and hide the indicator on stop/failure and behind projection dialogs.
- Debug/release unit suites passed: app 1,344, receiver 467 and companion 118 tests per variant; one private-archive-dependent app test skipped per variant.
- No companion behavior changes or claim of faster final-approval-to-first-render timing.

## 0.1.520

### Android Auto USB
- Repair Android USB access-result delivery after the unified CarPlay USB integration; permission callbacks now match the registered receiver.
- Clear stale permission requests when a phone disconnects or an exact USB admission fails, preserving current-owner and stale-generation checks.
- Reset failed USB admission state so deliberate Retry USB can request access again.
- Cover permission delivery and denial, equal-but-distinct device-name cleanup, retry and stale callback protection on Android API 32 and 34.
- Applies to saved Android Auto USB mode and wireless-to-USB Switch. Software regression tests pass; physical permission prompts and sustained projection still require vehicle confirmation.
- No companion update is required for this car-side correction. CarPlay permission handling and saved connection modes are unchanged.

### CarPlay vehicle data
- Discover each EV property's actual HAL change mode instead of inferring it from the requested sensor rate.
- Permit stable STATIC/ONCHANGE event timestamps only while real status reads or events keep the exact registration live; retain existing age limits for continuous or unknown modes.
- Revalidate supported stable-source properties on the existing serialized vehicle-data lane and promptly revoke unavailable/null observations. Retain actual usable capacity requirements and do not substitute nominal capacity.
- Vehicle change-mode confirmation and Apple Maps EV recognition remain unverified.

### CarPlay microphone diagnostics
- Add bounded exact-recorder input-route, client-silenced, permission and global-mute receipts to distinguish capture policy from uplink delivery.
- Keep OS queries on one zero-backlog process worker with at most three attempts per capture and fence late results at close.
- Export only finite sanitized metadata, not raw audio or device identities. No microphone source/rate change or claim that Siri silence is fixed.

## 0.1.518

### CarPlay audio negotiation
- Add permission-gated, independently qualified 24 kHz mono PCM microphone input for default, phone and voice-recognition streams, alongside existing Opus support.
- Use the negotiated capture rate, sealed RTP transmission and exact microphone retirement; reject malformed input formats and stream declarations.
- Validate authenticated audio packet headers and payloads before allowing microphone activation.
- Export bounded microphone format, capture and transmission diagnostics in uploaded logs.
- Addresses a wired input-format incompatibility identified in representative iOS firmware. Actual phone audio routing, navigation-volume selection, calls and Siri still require vehicle confirmation; this release does not claim those issues resolved.

### Video defaults and guidance
- Fresh CarPlay settings use Automatic codec (H.264 plus decoder-qualified HEVC) and automatic 1440p-class detail, retaining bounded lower-detail fallbacks.
- Preserve existing saved codec, resolution and manual-mode choices; invalid codec data falls back to H.264.
- Recommend Automatic codec with 1440p for manual setup and clarify protocol-specific video and curved-screen guidance.

### Distribution
- Deliver the same runtime revision and version to OpenAutoLink and OpenAutoLink Test Group 2, with separate package identities and tester lists.
- No Android companion update is required for these receiver changes.

## 0.1.517

### Display controls
- Keep floating controls inside the available projection area when switching between fullscreen and visible system UI.
- Recover previously saved off-screen positions automatically, without clearing settings or phone trust.
- Preserve dragged positions across layout changes while keeping untouched controls in their normal stack.

### CarPlay audio
- Correct output-purpose attributes for admitted alternate/navigation, phone, assistant and alert audio; preserve the existing media path.
- Add bounded audio-owner and device-wide playback-route diagnostics to distinguish OAL playback from other vehicle audio sources.
- These corrections do not yet establish the cause or resolution of the reported GM navigation-volume behavior. Vehicle confirmation is still required.

### Documentation
- Showcase CarPlay Dashboard in fullscreen and with the vehicle system bar visible.

## 0.1.516

### Phone projection
- Add native USB Apple CarPlay alongside wireless/WPP and USB Android Auto in the existing OAL application.
- Provide independent normal connection modes. USB phone attachment during projection offers Switch / Stay here; a takeover is temporary and does not change saved modes.
- Provide Retry USB recovery and guarded, awaited retirement of the previous projection owner.
- Support qualified H.264/HEVC video, two-contact gestures, audio and media/artwork metadata through the existing vehicle host.
- Add window-aspect fitting for standard detail tiers and independent manual control-safe calibration in the actual projection rectangle.
- Prefer H.265 and 1440p-class Android Auto Auto tiers while retaining saved/manual choices.

### Reliability and security
- Validate binary-plist trailer dimensions and object bounds before allocation; bound decoding and reject cyclic references.
- Retain exact resources across cancellation and teardown failure; acknowledge receiver retirement only after media owners exit.
- Preserve independent Android Auto preferences, explicit saved CarPlay disable choices and malformed-storage fail-closed behavior.
- Keep private accessory credentials out of Git and public GitHub artifacts. Maintainer Play builds inject them locally; other builders provide their own compatible identity.
- Include the CarPlay receiver in CI and verify public APKs contain no private accessory assets.

### Availability and limitations
- USB CarPlay is supported. Wireless CarPlay remains a development option and is not working in the maintainer's vehicle.
- Music playback quality and cluster route guidance remain under refinement; vehicle behavior varies by platform and phone.
- USB handoff, safe calibration and system-bars-visible behavior require continued vehicle validation. Frame-rate settings are caps, not sustained-rate guarantees.
- OAL is not an Apple-certified product. See the README and authentication/build policy for requirements and limitations.
