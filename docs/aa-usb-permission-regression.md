# Android Auto USB regression investigation

## Evidence

A vehicle capture from car app 0.1.519 reproduces the failure. Source for that build was inspected at 7b756014; USB source is identical to repair baseline cd9a367c.

The Android phone enumerated, was auto-selected, and USB handoff reached ADMITTED. There was no USB permission grant/denial/error, AOA switch, endpoint open, or USB native-session start. Subsequent selections logged a permission request still in flight for an already detached enumeration. Switching from wireless and pressing Switch reaches the same admission path, not a failed detection/button gate.

## Proven defects

1. CarPlay integration commit d86944df added an oal-usb data URI to the AA permission PendingIntent but retained an action-only receiver filter. Android IntentFilter matching rejects the data-bearing callback. Before that commit the intent was data-less. Real Robolectric regression exercises the actual PendingIntent and registered filters; baseline failed at the matching assertion.
2. AtomicReference<String>.compareAndSet compares reference identity, not string value. A separately reconstructed USB device with an equal name cannot clear the stored permission name. Real manager regression with deliberately equal-but-distinct String objects failed at cleanup. Existing failedAdmission also releases selection without retiring its pending permission guard.

The dispatch detach hypothesis was falsified: it marks the current token retiring and releaseSelection emits admission-ended; RESTORED is present in the vehicle capture. Do not attribute this issue to failure to restore the wireless owner.

## Scope

Detection and admission worked. This capture cannot prove whether Android displayed or answered the first access dialog. The app's switch prompt and Android's USB access prompt are distinct. Software tests are not vehicle proof. Acceptance on a new installed build requires USB permission completion, AOA re-enumeration, endpoints opened, native USB startup and sustained projection. Test both saved USB and wireless-to-USB Switch, plus replug and Android/iPhone swaps.
