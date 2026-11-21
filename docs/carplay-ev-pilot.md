# EV data and Apple Maps routing

Open **Settings → EV & routing** to check the car’s battery and remaining range. Android Auto continues to use its existing automatic sharing. Neither reading needs a connected phone or manual calibration.

## Try Apple Maps EV routing

1. Connect CarPlay. **CarPlay EV data** is on by default for eligible vehicle data; a previously saved Off choice stays off.
2. In Apple Maps, add or select the detected vehicle, then preview a route. Look for an estimated battery percentage at arrival. Longer routes may include charging stops.

Sharing battery and range data does not guarantee EV routing. Maps also needs a compatible routing model. OAL uses an automatic charging estimate where it has a matching profile. On other EVs, you can enter an approximate **Average DC charging power (kW)** under **Apple Maps → Advanced**. This is average power into the battery, not peak charger power. Select **Save changes** and reconnect to apply it; for USB, unplug and reconnect. Use **Use automatic** to clear the override. Changing the estimate may prevent EV setup or routing. Verify charging stops and allow extra margin. If vehicle data wasn’t available at connection time, reconnect after the readings are available.

To stop sharing, turn **CarPlay EV data** off, save and reconnect. If Apple Maps still lists the vehicle, you can remove it in Maps without changing CarPlay pairing.

**Automatic compact EV contribution** is a separate opt-in in **Diagnostics**. The CarPlay switch does not enable uploads. The optional **Drive Card** is also separate: see [Driver-display settings](secondary-presentation.md).
