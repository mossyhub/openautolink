# CarPlay two-contact input verification

Implemented stable two-slot Android pointer-ID mapping through ProjectionViewModel, OalCarPlayRuntime, ProjectionReceiver and existing AirPlaySession/HID. Copy coordinates synchronously before queuing; never retain MotionEvent. Preserve slot identity when Android pointer indices reorder or the primary finger lifts. Pointer-up and cancellation release contacts; third fingers are ignored because the advertised HID descriptor supports two contacts. Mapper is owned by each runtime attempt. AA input code unchanged.

Compiling RED reproduced missing mapping; mapper tests then passed. Focused mapper/coordinator/existing AA TouchForwarderImpl tests passed. Actual tablet APK built and installed without clearing trust.

Device proof uses a temporary shell-UID app_process probe injecting genuine two-pointer MotionEvents via InputManager, not two sequential adb swipes. Apple Maps on the connected iPhone visibly zoomed from neighborhood roads to individual buildings on pinch-out, then back to neighborhood coverage on pinch-in. CarPlay survived both gestures. Owner-private screenshots: qa-pinch-map-before.png, qa-pinch-out.png, qa-pinch-in.png; logs: qa-pinch-runtime.log under dev-profile scratch.

This verifies the real Android MotionEvent → OAL → CarPlay HID → iPhone Apple Maps → returned video path. Not a hands-on physical-finger/car test or Google Maps proof. Full combined-tree release gates remain separate.
