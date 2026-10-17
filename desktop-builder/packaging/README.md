# Desktop packages

**Desktop packaging preview: no public desktop downloads are published yet.**
The formats below are packaging targets. A download is qualified only by its
actual final-artifact manifest and native smoke evidence. The Linux review now
covers the fresh container payload, including Qt static code, Debian runtime
source/exception obligations, Rust/OpenSSL and CFFI's static libffi. Permissive
libraries require notices/provenance, not invented corresponding-source
attestations; LGPL source/recombination remains enforced. See
[NATIVE-COVERAGE.md](NATIVE-COVERAGE.md), [LINUX-REVIEW.md](LINUX-REVIEW.md) and
[LINUX-REBUILD.md](LINUX-REBUILD.md). Offscreen Linux tests do not qualify XCB,
Windows/Mac dependencies, or certify legal compliance.

## Qualification status

- AAB personalization first-build and same-identity update tests passed on Windows,
  Linux, Mac Intel and Mac Apple Silicon at builder `e8db340e`, using the
  prepublication CI template from OAL **v0.1.528**. This is not qualification of
  a template downloaded from a published release.
- Linux final-package startup and the combined Mac DMG's native startup on both
  architectures passed. These packaging checks are separate from AAB production.
- Windows bootstrap startup with an already-installed runtime passed earlier.
  Clean-machine runtime installation remains pending; installed-runtime smoke and
  mocked installer tests do not qualify interactive consent, UAC, cancellation,
  failure or restart handling on a clean machine.
- Public desktop/template delivery and separate Play/car acceptance are not
  established by those tests. Keep preview wording until the remaining gates pass.

## One file to run/install per operating system

Python, PySide and Visual Studio are **not required** by end users. Java and
bundle/signing tools are managed by the app inside
`~/OpenAutoLink Builder` (or your chosen dedicated folder), not installed globally.
The AAB-only workflow needs Internet access and space for templates, tools, caches
and output. It does not compile OAL or install Gradle, the Android SDK or NDK;
there is no Android SDK consent step. No Play credentials are used. The source
backend remains for development, not as a user-selectable mode or fallback.

| OS | File | How to use |
| --- | --- | --- |
| Windows x86_64 | `OpenAutoLink-Builder.exe` | Open the single download; if needed, it offers Microsoft's official runtime installer. No adjacent runtime directory is required. |
| Linux x86_64 | `OpenAutoLink-Builder` | In a supporting file manager: Properties → Permissions → Allow executing, then open. See prerequisites below. |
| Mac Intel **and** Apple silicon | `OpenAutoLink-Builder.dmg` | Open and drag the single app to Applications. Architecture selection is automatic. |

Windows requires Windows 10 1809 or newer / Windows 11 on x64. If the required
Microsoft Visual C++ x64 runtime is absent or outdated, the bootstrap asks before
downloading Microsoft's official installer, verifies its pinned hash and Microsoft
signature, then opens it interactively. Users review the terms and normal
administrator/UAC prompts themselves. Declining or a failed installation stops
startup; if a restart is requested, restart and reopen the same builder file.
The builder also presents its runtime terms on first use. This is one download,
**not a promise of zero prompts**, and does not require a Visual Studio installation.

The Mac app contains two complete native runtime trees in
`Contents/Resources/runtimes/{arm64,x86_64}`. Its single launcher selects a fixed
runtime path using `/usr/bin/uname -m` and forwards arguments without evaluating
them as shell code. This is a **dual-runtime app, not a universal2 binary**.
It does not combine PyInstaller onefile executables with `lipo` and does not
require Rosetta for the Apple-silicon runtime. CI targets macOS 15; older macOS
versions have not been qualified.

Windows is not Authenticode-signed. Mac packages are not Developer ID-signed or
notarized (native packaging may apply ad-hoc signatures). Normal OS security
warnings are expected; only approve packages you trust. Do not globally disable
Gatekeeper. Finder/Gatekeeper approval and interactive desktop behavior are
separate from the automated offscreen smoke gate.

Linux's supported baseline is an Ubuntu 22.04 x86_64 graphical desktop with X11
(or XWayland), OpenGL/EGL drivers, fontconfig and fonts, XKB, D-Bus, and XCB system
libraries. Other distributions, older glibc, minimal containers and pure Wayland
sessions are not qualified. PyInstaller bundles Qt and discovered applicable
native dependencies; it does not replace the OS graphics drivers, display server,
fonts or every system library. CI installs the XCB dependencies before freezing
and also starts the frozen app with the real XCB plugin under Xvfb, not just offscreen.

The Linux/Windows onefile executables unpack their bundled runtime into a temporary
directory at launch. Browser/GitHub downloads may lack the Linux execute bit.
In a file manager that supports it, use **Properties → Permissions → Allow executing
file as program** once, then open the file. Exact labels and binary-launch support
vary by desktop; some file managers will not launch binaries by double-click.
The terminal fallback is `chmod +x OpenAutoLink-Builder` followed by
`./OpenAutoLink-Builder`. **Unconditional download-and-double-click on Linux is not
promised.** A correctly configured supported desktop needs no Python installation.

### Source and licenses are inside the file

Every frozen executable embeds `builder-compliance.zip`, containing licenses,
runtime version inventory, running instructions, and `builder-source.zip` with
the exact desktop-builder source bytes used for that build. The Mac contains
this archive in **both** runtime trees. No adjacent source ZIP is required.
On Linux and Mac, export a copy without starting Qt or downloading anything:

```sh
# Linux:
./OpenAutoLink-Builder --export-compliance builder-compliance.zip
# Mac:
"/Applications/OpenAutoLink-Builder.app/Contents/MacOS/OpenAutoLink-Builder" --export-compliance builder-compliance.zip
```

The Windows bootstrap contains the archive inside its inner `builder.exe`; the
outer launcher does not forward `--export-compliance`. Developers can use that
option on the extracted inner executable, not on the downloaded wrapper.

The destination must not already exist. Keep the embedded source and legal files
when redistributing. The snapshot is the builder, **not** the Android app source;
OpenAutoLink source is https://github.com/mossyhub/openautolink. Local builds can
include uncommitted changes. The source archive uses an explicit allowlist and
fixed metadata; it excludes working folders, generated identities, keys, venvs,
and build output. Its SHA-256 is recorded and checked against the exported bytes.

## Build and test (developers only)

From `desktop-builder`, with Python 3.12 on the native target OS:

```sh
python -m venv .venv
# Linux/macOS: source .venv/bin/activate
# Windows PowerShell: .venv\Scripts\Activate.ps1
python -m pip install -r requirements-dev.txt
python -m pytest tests -q
python -m oal_builder --smoke-test
```

Before freezing, materialize coverage for the exact native target. On the reviewed
Ubuntu CI environment:

```sh
python packaging/coverage_linux_ci.py --cache native-sources --output native-coverage
python packaging/build.py --output dist --native-coverage native-coverage/coverage.json
```

For Mac use the staging instructions below. For Windows, the native pipeline is:

```sh
python packaging/coverage_windows_ci.py fetch --cache native-sources
python packaging/build.py --output windows-inner --windows-ci-cache native-sources
python packaging/windows_setup.py --inner windows-inner --cache native-sources --output dist
```

Use fresh output directories. The Windows inner stage is **not** the end-user
download; the bootstrap wraps it only after its source/native gates pass. Final
Windows/Linux output includes the single file, a screenshot and a manifest hashing
both (the manifest does not hash itself).
The script executes the **final output executable** and verifies the frozen
child's actual architecture, exported source hash, licenses, and a real Qt PNG.
The smoke must not create a work folder or perform an Android build. For headless
runs use `QT_QPA_PLATFORM=offscreen`. CI additionally requires native XCB startup:

```sh
QT_QPA_PLATFORM=xcb xvfb-run -a python packaging/build.py --smoke-linux-xcb dist
```

This adds `smoke-linux-xcb.png` and its hash to the same executable manifest.
It needs Xvfb, xauth and the documented XCB/desktop system prerequisites. Passing
only the offscreen gate is not evidence of working Linux desktop startup.

### Mac staging, combination and qualification

On an ARM Mac and an Intel Mac independently, use the same source checkout and
pinned requirements. Materialize the architecture's reviewed native coverage and
required sources using the steps in [macos-ci/README.md](macos-ci/README.md), then run
`python packaging/build.py --output dist --native-coverage /path/to/reviewed/coverage.json`.
Omitting coverage deliberately rejects the package; it is not a release-build shortcut.
Each produces an **internal** `OpenAutoLink-Builder-macos-ARCH.runtime.tar.gz`,
`OpenAutoLink-Builder-macos-ARCH.json`, and `smoke-macos-ARCH.png`. Transfer all three
files into `incoming/arm64/` and `incoming/x86_64/`, respectively. Then on a Mac:

```sh
python packaging/build.py --combine-mac incoming --output combined
```

Combination verifies stage hashes, native smoke status, and source equality with
the assembling checkout. It safely extracts tar members (rejecting traversal,
external links and special files), preserves internal framework symlinks, verifies
the executable architectures, then creates and verifies **one** DMG. This manifest
is explicitly **pending** until both final native smoke gates pass.

Transfer the same `combined/` DMG and manifest to **both** Macs, then run:

```sh
# On ARM:
python packaging/build.py --smoke-mac combined --expected-arch arm64 --output evidence/arm64
# On Intel:
python packaging/build.py --smoke-mac combined --expected-arch x86_64 --output evidence/x86_64
# After collecting both evidence directories (any Python 3.12 OS):
python packaging/build.py --qualify-mac combined --evidence evidence
```

These gates mount the exact DMG read-only and execute the **outer app launcher**,
not a manually selected inner binary. They check the actual child architecture
(to catch accidental Rosetta use), embedded source/licenses and Qt screenshot,
then detach even after a smoke failure. Qualification requires both reports to
reference the same DMG hash and source hash; it adds both screenshots and their
hashes to the final manifest. No freezing or rebuilding occurs after those gates.

## CI downloads and limits

The workflow runs on `feat/**` pushes, relevant pull requests, manual dispatch and
published-release events.
It uses `ubuntu-22.04`, `windows-2022`, `macos-15` (ARM) and `macos-15-intel` (Intel),
listed in [GitHub's supported runner images](https://github.com/actions/runner-images#available-images).
The final Mac smoke jobs install **no** builder dependencies; they use only the
bundled runtimes. Native packaging jobs run the unit/UI suite and frozen smoke.

Only after all gates pass are the three end-user artifacts uploaded:
`OpenAutoLink-Builder-Windows`, `OpenAutoLink-Builder-Linux`, and
`OpenAutoLink-Builder-Mac`. Each contains exactly one run/install file from the
table above. `OpenAutoLink-Builder-Verification` holds manifests and screenshots.
`INTERNAL-*` artifacts are intermediate CI transport, **not** recommended downloads.
GitHub Actions itself wraps artifact downloads in ZIPs. A release-event-only job
can attach the exact tested EXE, executable and DMG to the originating stable
release after all packaging gates pass. It checks the release/tag/source identity,
refuses to replace existing assets and reads uploaded bytes back for verification.
The workflow never creates a release or tag. This publication code does not mean
desktop release assets are currently available.

Build and PR jobs have read-only repository permissions. Only the release-only
publication job receives repository contents-write permission; there are no
developer-signing, notarization or Play API credentials. Offline smoke
proves packaging, embedded compliance and Qt startup—not first-run tool downloads,
full AAB production, interactive desktop behavior, or OS signing acceptance.
Native Windows and both Mac runs must pass before claiming those platforms are
verified; Linux-only tests cannot establish that.

## Runtime licensing

The builder source archive is **not** the corresponding source for its bundled
libraries. Installed Python distribution licenses and generic GPL/LGPL texts
also do not cover an arbitrary native payload. The project license is preserved,
but no blanket runtime-compliance claim is made.

The schema-2 native gate requires component-specific notices, exact binary hashes,
factual provenance and declared static dependencies for all components. Known
permissive licenses do **not** require source archives or source/build attestations.
The implemented GPL/LGPL route additionally embeds corresponding-source archives
and build/recombination instructions. The PyInstaller embedded-loader exception
has a narrowly scoped notice/provenance route. Unsupported licenses and unknown
payloads remain blocked. Evidence is checked against the **final** artifact;
passing the mechanical gate is not a legal opinion.

`packaging/provenance/linux-observed.json` preserves historical observations.
`source-inputs.lock.json`, `supplier-evidence.json` and `licenses/supplier-notices`
add reproducible official source/wheel retrieval, exact wheel-member matches and
verbatim third-party notice supersets. The Debian notices remain under
`licenses/native-linux-observed`. These evidence reports are **not complete
coverage manifests** and do not silently unlock packaging.
Do not remove legal files to reduce download size, and never describe unsigned
artifacts as signed or notarized.
