# Windows bootstrap pipeline contract

Run these commands from `desktop-builder` on native Windows x64. Python/build
requirements belong to the packaging runner, not to an end user. No Visual
Studio purchase/license key is requested. Windows 10 1809+ or Windows 11 x64 is
the recipient target; no Python, Java, Android SDK or source SDK is installed by
the bootstrap. AAB personalization remains the primary app path; source builds
remain available separately in Advanced options.

## Commands and artifact boundary

```powershell
python packaging/coverage_windows_ci.py fetch --cache windows-cache
python packaging/build.py --windows-ci-cache windows-cache --output windows-inner
# Only after the exact native/legal coverage gates above succeed:
python packaging/windows_setup.py --inner windows-inner --cache windows-cache --output windows-public
# In the clean verifier job, with the same immutable checkout:
$env:QT_QPA_PLATFORM = 'windows'
python packaging/build.py --smoke-windows-qwindows windows-public
```

`build.py` now emits a **private** Windows stage, never an end-user download:

- `OpenAutoLink-Builder.exe`: inner PyInstaller executable, with independently
  distributed MSVC/UCRT removed from Analysis binaries AND data before freezing.
- `OpenAutoLink-Builder-windows-x86_64.json`: `kind=internal-runtime-stage`,
  `runtimeCollection=official-vc-runtime-bootstrap-v1`, source/artifact hashes,
  exact native coverage and successful frozen smoke required.

`windows_setup.py` reads that manifest, rechecks actual embedded compliance,
source/supplier bytes and absent runtime DLLs, verifies bootstrap source matches
its embedded source ZIP, then builds using the hash-pinned official NSIS 3.13 ZIP
and its zlib Unicode x86 stub. Compiler acquisition is per-workspace and cached;
`--offline` prohibits missing-cache downloads. No host installation occurs.

Only `windows-public` is eligible for upload. It contains one outer
`OpenAutoLink-Builder.exe`, its manifest of the same name above, and a smoke PNG.
The final manifest retains `kind=direct-executable` for the publication interface
and adds `bootstrap=official-vc-runtime-bootstrap-v1`, `innerSha256`,
`embeddedPayload` (all five exact member hashes), `bootstrapLoader`,
`bootstrapExtraction=exact-payload-verified`, and
`nativePlatformSmoke=qwindows-passed`. A manifest/artifact name is never reused
between inner and outer in the same directory.

The final EXE contains only builder.exe, generated bootstrap.ps1,
setup-manifest.json, NSIS-COPYING.txt and BOOTSTRAP-NOTICE.txt. The Microsoft
installer is never embedded. An absent/outdated runtime triggers a default-No
download prompt, exact pinned SHA-256 plus Microsoft Authenticode checks, and
Microsoft's interactive installer; `/quiet` and `/passive` are not used. Decline,
failed verification, UAC failure, failed install or required reboot never starts
the builder. Automated smoke refuses an absent runtime instead of accepting
terms. A verified installed runtime uses both registry views and System32 files.

## Extract the inner runtime/notices without running it

For inspection or private LGPL replacement work, the outer executable has an
extraction-only route. It does not download/install a runtime, run Python/Qt, or
record acceptance. Use a fresh absolute destination:

```powershell
$env:OAL_SETUP_EXTRACT_ONLY = "$env:USERPROFILE\oal-bootstrap-inspection"
& .\OpenAutoLink-Builder.exe
Remove-Item Env:OAL_SETUP_EXTRACT_ONLY
# After a suitable Microsoft runtime is independently installed:
& "$env:USERPROFILE\oal-bootstrap-inspection\builder.exe" --export-compliance "$env:USERPROFILE\oal-compliance.zip"
```

`oal-compliance.zip` includes exact builder source, notices, required LGPL source
archives and rebuild instructions. Private modified Qt must not be relabeled as
the original hash-covered supplier. The automatic replacement probe deliberately
requires the shared final coverage validator to reject the modified Qt bytes.

## Before legal approval

The existing fail-closed coverage gate remains. Before it rejects a private
candidate, `build.py` now collects actual-loader, frozen UI, private QtCore
replacement, and private outer-bootstrap evidence. The Qt test appends only a
non-loaded overlay to an official-wheel clone; it is not a Qt source rebuild.
The outer test compiles the real template, checks exact official code/imports,
extracts through the actual outer executable, compares all five files, and
launches the actual read-only BuilderWindow through the outer PowerShell path.
Temporary executable/replacement trees are deleted even on failure.

Allowlisted diagnostics available to the workflow owner:

- `windows-candidate-review.json`
- `windows-recombination.json` and, only on successful probe, `.png`
- `windows-bootstrap.json` and, only on successful probe, `.png`
- existing `native-payload-review.json`

These reports are explicitly `notQualification=true`. Upload only those explicit
names, never private candidates, installed DLLs, work folders or caches. A probe
is not authorization to change review status or remove an unknown legal blocker.
The embedded Microsoft-code terms/static supplier scope and native assent route
still require review; the shared gate intentionally rejects unresolved
`LicenseRef-Microsoft-*` terms. Passing all technical probes alone cannot produce
a public executable. This is not a demand for an end-user/publisher VS license.

The native unit suite includes an offline PowerShell call-order harness for
runtime-present, decline, failed download/hash/signature, cancellation, failed
install, restart, successful install and missing-after-install paths. External
operations are mocked and no real EULA is accepted. Separately qualify real
Microsoft installation/decline/UAC/restart behavior on disposable supported
Windows machines with a human choosing consent. Native registry/trust behavior,
the pinned Windows compiler, frozen loader, actual qwindows and modified private
Qt loading cannot be qualified on Linux.

## Linux parser/cross-compiler evidence

The optional `OAL_TEST_NSIS_ROOT` and `OAL_TEST_NSIS_ARTIFACT` variables point tests
to an extracted official 3.13 ZIP and a locally cross-compiled fixture. Their
real-PE tests check unchanged code, exact imports, resource layout, user-variable
allocation, headers and deliberate corruptions. A Linux NSIS compiler using the
3.13 stub proves only compilation/parser behavior, not native installer/runtime
execution. It must never enter `windows-public` or replace a native gate.
