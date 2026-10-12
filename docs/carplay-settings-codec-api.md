# Settings / codec recovery API contract

App/display recovery worker owns `CarPlayDisplayPreferences` and app settings/runtime mapping. Codec worker owns ProjectionReceiver, sink, qualification and wire parser.

- Add trailing default `preferHevc: Boolean = false` to `CarPlayDisplayPreferences` (recovery worker).
- Persist app `videoCodec: String = "h264"`; accepted IDs `h264` and `hevc`. Map HEVC to display.preferHevc. This is preference, never force.
- Codec worker: receiver must call `CarPlayVideoQualification.select(display.width, display.height, display.fps, preferHevc = display.preferHevc)` and carry exactly that immutable decoder receipt through wire/sink. If HEVC does not qualify, retain AVC at exact dimensions; if AVC exact does not qualify, only qualified 1280x720/30 fallback is permitted. Never offer unqualified HEVC.
- Recovery worker delegates AVC display qualification to `CarPlayVideoQualification.androidProbe()` using exact H264 request (profile/alignment included), not duplicate loose MediaFormat checks.
- Receiver `displayConfiguration` must report actual qualified/fallback width/height/FPS while retaining Auto/manual physical sizing input; runtime reports this to app for actual aspect-fit layout.
- Do not edit app settings/display files from codec worker. Do not edit codec/receiver files from recovery worker. Read handoffs before combined gates.

## Receiver/engine ownership gap requiring parent integration

Codec handoff explicitly excludes ProjectionReceiver and CarPlayMediaEngine. This recovery worker's user scope explicitly permits receiver CarPlayDisplayPreferences/Policy and AirPlayDisplaySettings ONLY. Therefore parent must own ProjectionReceiver/CarPlayMediaEngine bridge; neither worker may silently cross ownership.

Required parent bridge: read `docs/carplay-hevc-handoff.md` lines 7–20. Receiver uses the same videoSelection in sink/session/info and actual displayConfiguration; media engine admits `session.acceptsVideoCodec(codec)` rather than hardcoded AVC. Settings now carry `display.preferHevc` and use the exact AVC codec-worker probe including profile/alignment. H264 default retained.

Latest forced combined execution: app tablet tests 985 / 0 failures / 1 skipped; receiver 257 / 1 failure (`CarPlayHevcSessionTest.actualSessionInfoSetupAndRetirementShareTheExactReceipt`, NoSuchMethodException); assembleTabletDebug completed with `--continue`. This is not a green combined gate. Earlier 20 NoSuchMethodError failures were stale mixed incremental bytecode after constructor extension and disappeared with --rerun-tasks; remaining session failure is codec-owned.

Physical HEVC/custom-profile acceptance remains parent-owned and unverified by unit tests.
