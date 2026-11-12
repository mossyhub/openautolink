# Direct secondary video window

Direct video is the standard optional secondary-video Surface provider for Android Auto and CarPlay. Settings has one **Driver-display video** switch: off selects native navigation guidance; on selects video. There are no separate direct-window, protocol, per-screen enable or native-coexistence controls. AA roles, custom geometry, live-diagnostics auto-connect and main projection/audio/input keep their existing ownership. Legacy protocol/direct-window preference fields are compatibility shadows, not runtime vetoes.

## Use

In **Settings → Secondary Displays**, turn on **Driver-display video**, choose the AA display role if needed, then tap **Save changes**. The connected Android Auto or CarPlay session supplies the video. Wireless follows the existing reconnect transaction; USB requires unplugging and reconnecting. Opening Settings or changing a draft never starts video. Saving with video off selects native guidance and disables physical video-output flags; saving with video on enables only the selected target. Saved roles and custom geometry are preserved.

Direct video admits one output per connection; other saved output choices are retained. CarPlay withholds its alternate offer when more than one eligible output is enabled. The nonexported Activity is launched once on the selected actual non-default display, after an ordinary public start-allowed check. It rechecks its actual display, current identity/attachment epoch, public validity and Surface. Private, unavailable, ambiguous and denied targets fail closed. No numeric display-2 assumption, vehicle calibration database, build allowlist, privileged Binder, OEM plugin, overlay permission, fixed activity mode, forced foreground, or second projection receiver is used.

## Automatic background; editable Directions area

The video background is the **full actual drawable delivered to the owned TextureView**, in that buffer's local coordinates. The actual origin and dimensions are logged. A smaller/offset delivered drawable is used as measured; OAL does not pretend it received a whole physical panel, apply the old hosted top-135 seed, or intersect it with a vehicle profile's projection rectangle. Android may still constrain the application window. Ordinary display/window APIs do not reveal an instrument mask or establish physical visibility.

Decoded video is aspect-fitted into this full background; fixed-format CarPlay can letterbox. Codec crop, local drawable coordinates, physical window position and phone-safe insets remain separate.

When applicable nonzero system-bar/cutout insets are actually reported for this window, their intersection is translated into drawable-local safe coordinates. Otherwise OAL's default Directions area is normalized from the confirmed **558,70–1694,668 in the full 2000×810 window**. It scales with actual drawable dimensions for AA and CarPlay; it is a user-selected default, not a measured instrument mask. **Restore default** uses that same fallback without rewriting other saved custom layouts.

Open **Directions area** to move the green inner box or resize it using its corner handles. Usually no adjustment is needed; edit only if directions or other non-map content are hidden or cut off. The full video remains automatic and cannot be resized in this editor. **Show on driver display · 30s** starts a bounded transparent preview when projection is idle; edits do not renew its deadline. **Done** stops the preview and returns the draft to Settings. Then tap **Save changes**. USB: unplug and reconnect to apply. **Restore default** changes only the inner area, preserving the outer window and unrelated saved geometry. The preview does not borrow a phone-video Surface or stop an admitted phone session.

The target/build/dimensions/density namespace is a persistence scope, not a calibration allowlist. Direct-safe overrides are stored separately from legacy `output` and `safe` fields. Legacy outer layouts remain stored but do not resize/reposition a direct window. Saved safe intent is revalidated against the current actual drawable and protocol fit; stale scope/invalid geometry falls back to the automatic safe area with a diagnostic reason. No settings observation changes an already serialized offer.

## Android Auto UI density

Under **Android Auto display mode**, enable **Custom Android Auto density** to adjust the selected secondary display from **80–320 DPI**. Lower DPI makes phone-rendered text, cards and badges smaller; higher DPI makes them larger. Start with a modest reduction and check that directions remain readable. Turning the control off restores **Automatic**, which uses the display's Android-reported DPI and preserves the previous behavior.

This is a draft until **Save changes**. Wireless applies it through the normal reconnect transaction; USB requires unplugging and reconnecting. The density is frozen when the next session is prepared. Opening Settings or moving the slider does not change the active stream or start video.

The setting affects only the selected Android Auto secondary display. It does not change the main AA feed, CarPlay, the AA Cluster/Auxiliary role, the Directions area, or the full output window. Logical UI density is separate from physical-density metadata. Video remains 1280×720 with the existing aspect crop; higher-resolution secondary video is not part of this control. Navigation apps may rearrange information at different densities. This is not a map-camera zoom control, and the final appearance depends on the phone and navigation app.

## Hidden and returning windows

Start/stop, actual own-window visibility, Activity identity and Surface generation are separate facts. Input focus is not page visibility; the window is intentionally nonfocusable/non-touchable. Pause alone does not end projection or navigation.

On stop, own-window hiding or exact Surface destruction, the existing renderer clears the old owned output and positively retires its EGL window before an owned Surface/SurfaceTexture is released. The existing decoder, internal decode SurfaceTexture, EGL context and phone stream remain alive on a small pbuffer. Hidden frames continue decoding with bounded queues; return therefore does not require the phone to emit a fresh IDR or advertise a hot-added channel.

A real restarted/visible Activity and actual replacement Surface receive a new attachment generation only after predecessor cleanup acknowledges true. Same-Activity texture recreation and Activity recreation are supported. Stale callbacks cannot retire the successor. False/failed/pending cleanup retains exact debt and the process output claim; a timeout is never success. Terminal disconnect closes the renderer before releasing the process claim. Direct owners never enter the AndroidX retained-host reconnect transaction.

There is no periodic relaunch, page polling, move-to-front loop or navigation ownership manipulation. Display/identity/geometry changes revoke the optional output instead of silently retargeting its frozen descriptor. A removed task or a transition without usable own-window callbacks is not an authenticated GM page-return signal; an explicit normal reconnect can prepare a fresh owner.

### Startup while another page is selected

An initial attachment followed by acknowledged clean GPU parking can complete preparation without claiming that the video is visible. This preserves the secondary-stream offer when the page is hidden during connection; preparation still uses its original deadline and rejects failed or unresolved cleanup.

Authenticated automatic return remains limited to a valid paired page transition for the exact current task and output. A newer different or invalid page event cancels queued automatic foregrounding. An unpaired initial page snapshot is not treated as permission to pull a task forward.

If the page remains black, select the maps page and choose **Restore driver display video** in OAL's floating menu. The explicit action targets only the current negotiated video task and waits for a real attachment acknowledgment. It does not reconnect the phone, launch a replacement task, hot-add a stream or simulate GPU/window callbacks. A denied, timed-out, stale or unsafe request reports failure. **Reconnect required to enable driver display** means no secondary stream is negotiated; use ordinary Reconnect instead.

## GM native guidance / page-choice limit

**Driver-display video** is off by default. Off selects native navigation icons/HUD; on selects video. There is no separate native-icons switch. On GM, Video suppresses native guidance even if an old saved coexistence preference was true. GM’s privileged-access restrictions mean it’s video or native guidance—not both. Other vehicles retain their existing compatibility policy without an additional settings control. Native navigation publication still requires a genuine sourced phone instruction.

Retained GM source shows that genuine AndroidX navigation publication requires `navigationStarted` and Templates Host system focus. That host identity causes VMS to withdraw native MAP_FEED capability. Moving the video Surface into an ordinary Activity does not bypass that policy. On the tested vehicle, GM page-selection events reached OAL's protected observer after a headunit reboot; local component eligibility alone does not establish current delivery.

Consequently this is **not** a promise of all three: OAL direct video + OAL-fed native icons/HUD + preserved selectable native Maps. It does not register OAL as a GM map-feed provider. An OEM page change may mask an application window without delivering the callback OAL needs, or may leave it visible over the wrong page. Automatic page-following, physical instrument layering, ignition behavior and actual phone/vehicle pixels remain vehicle-validation gates. No privileged workaround or synthetic ownership claim is added.

AA Cluster and Auxiliary roles are preserved literally. They select different phone-side shells/providers; information/layout can differ by app. Neither role is advertised as a speed-limit entitlement, passenger launcher or touch capability. Changing the role does not change hidden-UI flags or the independent Android Auto density setting.

## Software qualification boundary

Focused regressions exercise the production Activity callbacks and real runtime preparation/ingress for both AA and CarPlay, measured subset/local geometry, held and false cleanup, stale/recreated owners, repeated page cycles, predictive frames without a new IDR, real EGL driver bodies with recording OS shadows, safe-only Back/Save/file-backed cold restore/Reset, and actual serialized optional descriptors with complete main-descriptor controls. These are controlled software/OS-boundary fixtures—not real AVC hardware, phone negotiation, GM compositor/page acceptance or fleet-wide qualification.
