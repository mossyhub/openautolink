
## Resolved: OAL SETUP capability mismatch

The original OAL `setupEnabledFeatures` required the phone's initial proposed-feature list to contain `hevc`. Pinned DiPlay instead advertises the enabled accessory HEVC capability in that SETUP response without this echo requirement. The initial SETUP occurs before the phone's /info request; OAL advertised hevcInfo later but had withheld HEVC in SETUP.

Updated `CarPlayHevcSessionTest` to enforce the source-backed behavior for an exact qualified receipt with an empty proposed-feature list. Observed compiling assertion RED at line 39. Removed only the proposed-hevc echo gate, preserving config.hevc, exact immutable receipt matching, real HEVC decoder qualification, session retirement and AVC acceptance. Focused HEVC tests and actual tablet APK assembly passed.

Installed corrected APK without clearing trust. Same manual 2560x1440/maxFPS30 with Automatic codec selected now renders using actual `c2.mtk.hevc.decoder`. Stats report matching advertised/coded/visible 2560x1440, positive advancing rendered frame counts and authenticated session. Screenshot shows normal CarPlay dashboard without visible corruption. A subsequent tap changes dashboard to full-screen Maps, while HEVC rendering remains active. Private artifacts: `qa-hevc-real-fix.log/.png`, `qa-hevc-touch-survived.log/.png`.

This resolves the observed HEVC-startup failure on this iPhone/TCL combination. It does not establish all HEVC profiles, sustained30/60FPS, car hardware support or audible playback in this particular run.
