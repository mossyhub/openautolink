# Desktop packages

**Linux exact-byte coverage is reviewed; native-platform release gates still apply.**
The formats below are packaging targets. A download is qualified only by its
actual final-artifact manifest and native smoke evidence. The Linux review now
covers the fresh container payload, including Qt static code, Debian runtime
source/exception obligations, Rust/OpenSSL and CFFI's static libffi. Permissive
libraries require notices/provenance, not invented corresponding-source
attestations; LGPL source/recombination remains enforced. See
[NATIVE-COVERAGE.md](NATIVE-COVERAGE.md), [LINUX-REVIEW.md](LINUX-REVIEW.md) and
[LINUX-REBUILD.md](LINUX-REBUILD.md). Offscreen Linux tests do not qualify XCB,
Windows/Mac dependencies, or certify legal compliance.

## One file to run/install per operating system

Python is **not required** by end users. Build tools are managed by the app inside
`~/OpenAutoLink Builder` (or your chosen dedicated folder), not installed globally.
The first Android build needs Internet access and several GB of free space. SDK
licenses require explicit consent in the app. No Play credentials are used.

| OS | File | How to use |
| --- | --- | --- |
| Windows x86_64 | `OpenAutoLink-Builder.exe` | Double-click. No installer or adjacent runtime directory. |
| Linux x86_64 | `OpenAutoLink-Builder` | In a supporting file manager: Properties → Permissions → Allow executing, then open. See prerequisites below. |
| Mac Intel **and** Apple silicon | `OpenAutoLink-Builder.dmg` | Open and drag the single app to Applications. Architecture selection is automatic. |

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
Export a copy without starting Qt or downloading anything:

```sh
# Linux (use .\OpenAutoLink-Builder.exe in Windows PowerShell):
./OpenAutoLink-Builder --export-compliance builder-compliance.zip
# Mac:
"/Applications/OpenAutoLink-Builder.app/Contents/MacOS/OpenAutoLink-Builder" --export-compliance builder-compliance.zip
```

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
python packaging/build.py --output dist --native-coverage /path/to/reviewed/coverage.json
```

Use a fresh output directory. On Windows/Linux this produces the direct file,
a screenshot and a manifest hashing both (the manifest does not hash itself).
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

The workflow runs on `feat/**` pushes, relevant pull requests and manual dispatch.
It uses `ubuntu-22.04`, `windows-2022`, `macos-15` (ARM) and `macos-15-intel` (Intel),
listed in [GitHub's supported runner images](https://github.com/actions/runner-images#available-images).
The final Mac smoke jobs install **no** builder dependencies; they use only the
bundled runtimes. Native packaging jobs run the unit/UI suite and frozen smoke.

Only after all gates pass are the three end-user artifacts uploaded:
`OpenAutoLink-Builder-Windows`, `OpenAutoLink-Builder-Linux`, and
`OpenAutoLink-Builder-Mac`. Each contains exactly one run/install file from the
table above. `OpenAutoLink-Builder-Verification` holds manifests and screenshots.
`INTERNAL-*` artifacts are intermediate CI transport, **not** recommended downloads.
GitHub Actions itself wraps downloads in ZIPs; this workflow does not create a
GitHub Release or direct public release-asset URL. A maintainer may separately
publish the already-tested EXE, executable and DMG without repackaging them.

CI has read-only repository permissions and no signing, notarization, Play API,
or publishing credentials. There is no automatic release creation. Offline smoke
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
