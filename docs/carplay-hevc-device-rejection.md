# TCL HEVC preference rejection discriminator

## Physical observation

The installed tablet development APK failed with Prefer HEVC selected at 2560x1440, maxFPS 30. Saved codec selection was read from the actual Video UI. Captured owner-private evidence is `qa-owner-failure.log` and `qa-owner-failure.png` under the active dev profile scratch directory.

Attempts 11 and 12 reached real PairVerify/authenticated control, SETUP, GET info and RECORD with HTTP/RTSP 200 responses. The iPhone then sent TEARDOWN (42-byte body) within approximately 7 ms of RECORD, before any video output receipt. This is not a demonstrated unreachable-IP or Wi-Fi-join failure. The final connection-timeout UI recommends Wi-Fi/IP checks, which misidentifies this failure stage.

## Controlled recovery

Changed ONLY the codec selection from Prefer HEVC to H.264 through the existing global Save & Reconnect. Kept resolution 2560x1440 and maximum FPS 30. Within the 10-second observation, actual Stats reported authentication accepted, rendering confirmed, `c2.mtk.avc.decoder`, and advertised/coded/visible 2560x1440 with positive frame count. Artifacts: `qa-failure-avc-control.log` and `.png` in owner-private scratch.

## Conclusion and limits

The optional HEVC offer/negotiation path is implicated; exact defective field, phone policy or stream-stage cause is not yet established. There is no physical HEVC decode evidence in these failures. Local MediaCodec capability qualification is insufficient to claim phone acceptance or automatic AVC fallback for this case. Keep H.264 default pending investigation; preserve deliberate HEVC testing without automatic retry loops. Do not attribute this to the concurrent cluster or Auto source edits without an installed-artifact receipt.
