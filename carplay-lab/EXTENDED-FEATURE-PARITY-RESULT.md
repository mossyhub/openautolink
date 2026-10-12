# Extended-feature parity discriminator

Pinned DiPlay source AirPlayInfoPlist.kt:55 and exact working APK declare vocoderInfo plus enhancedRequestCarUI. Independent Showcase:923 and OCBM info.rs:1041-1056 corroborate presence; no demonstrated separate vocoder control endpoint justifies the prior omission assumption. Source comment corrected; declaration restored without changing codecs, microphone gating or routing.

Focused tests first failed at extended-feature assertions, then expectations covering the old omission were reconciled. Full receiver tests/lint and app assembly passed (Gradle BUILD SUCCESSFUL). Installed and launched real experimental OAL on TCL.

Physical capture build/media-attempt-20261009-124022.log still only streams130/110; MainAudio resource2 remainsentity2/permanent2, screen changesentity1, first frame rendered. No audioSETUP. Restoring vocoderInfo alone is insufficient; no audio fix claimed. Exact current human playback outcome not requested because the missing stream already fails acceptance.

Production/Play unchanged. Source-first diagnosis must now reconcile complete bootstrap/control exchange rather than claim any tested declaration was the cause.
