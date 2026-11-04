# Driver-display settings

Open **Settings → Secondary Displays**. **Driver-display video** applies to both Android Auto and CarPlay; it does not change the main infotainment display.

**Driver-display maps are disabled by default.** On GM vehicles, you must choose either driver-display maps or native navigation icons and HUD guidance—not both. GM's privileged navigation/display restrictions prevent OAL from keeping both active together; this trade-off is why the feature ships turned off.

## Enable it

1. Turn on **Driver-display video**.
2. Select **Save changes**.
3. **After first enabling it, manually restart the GM head unit.** While parked, hold the steering-wheel **End Call** button for about **30 seconds**, then wait for the head unit to restart itself.

Wireless settings reconnect automatically when safe. **USB: unplug and reconnect after saving to apply.** The current USB session keeps its admitted settings until physical reconnect.

To return to native navigation icons/HUD, turn **Driver-display video** off and select **Save changes**. Native guidance requires an active route supplying usable navigation instructions. Saved Android Auto roles and custom geometry are preserved; opening Settings or editing a draft does not change the running session.

## Make it suit your display

**Your phone controls what appears on the driver display.** OAL does not generate that view; it displays the video supplied by Android Auto or CarPlay and your navigation app, with some layout customization options. The layout and information shown can therefore change as Android Auto, iOS/CarPlay and your navigation apps are updated.

### Android Auto

With video on, choose the phone-facing role:

- **Cluster:** a more compact navigation layout.
- **Auxiliary:** Google Maps adds destination and speed information. A useful starting point for larger driver displays.

### Apple CarPlay

There is no equivalent Cluster/Auxiliary selector in OAL.

See the [Android Auto and CarPlay photos](../README.md#driver-display-maps--oal-01575).

## Directions area

**Usually no adjustment is needed. Edit only if directions or other non-map content are hidden or cut off.** This area applies to Android Auto and CarPlay. Video still fills the whole available drawable; the editor never resizes the full video.

1. Open **Directions area**.
2. Drag the green box to move it; drag its corner handles to resize it.
3. Optionally select **Show on driver display · 30s**. Stop the preview or let it expire; edits do not restart its timer. If preview is unavailable, the editor shows the required next action.
4. Select **Done** to stop the preview and return the draft to Settings.
5. Tap **Save changes**. Wireless reconnects automatically when safe. **USB: unplug and reconnect to apply.**

**Done does not save.** **Restore default** restores the inner area without changing the full video window or unrelated saved geometry. Existing custom areas remain unchanged unless edited or reset. See [direct-window details](direct-cluster-window.md).

## Returning to the maps page

If the driver-display video stays black after changing pages, select the maps page, open OAL's floating menu and choose **Restore driver display video**. This restores the current negotiated video window without reconnecting the phone.

If the menu says **Reconnect required to enable driver display**, use the existing **Reconnect** action while the maps page is selected. If restoration fails, upload logs with the action and approximate time before reconnecting or restarting OAL. See [troubleshooting](troubleshooting.md#driver-display-video-stays-black-after-changing-pages).
