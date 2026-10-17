# macOS CI native coverage: scope and limits

These are exact **observed-byte** maps for the two failed native discoveries in
run `38861042629`, attempt `1`, verified checkout
`40c10a5f68f6e7ecb17e95053da075df944314ba`. Neither is a release qualification.
They cover the complete arm64 and x86_64 runtime inventories independently.
The delivered Mac product must contain BOTH full runtimes in one DMG with
architecture selection; these manifests do not implement or test that launcher.

## Evidence actually acquired

`provenance/macos-ci/supplier-inputs.lock.json` pins the official macOS wheels,
Python 3.12.10 source and macOS installer, Qt Base/PySide 6.8.3 sources, and
supplier notices/packaging implementation. `acquisition-report.json` is evidence,
not approval. Supplier inputs were downloaded over HTTPS and hashes checked;
computed HTTPS digests are labelled, not passed off as published checksums.

`python-installer-evidence.json` records exact universal2 installer-member
matches for all 48 installed Python candidates on EACH architecture. This is
not a match of the final frozen members. Apple's installer signature was not
validated on Linux. Source version 3.12.10 and OpenSSL 3.0.16 agree with the
observed runtime and CPython's versioned Mac/BuildScript/build-installer.py.

`wheel-member-evidence.json` records original wheel hashes, architecture-slice
hashes, Mach-O load dependencies and signature ranges. Wheel candidates were
checked against discovery's installed hashes. A universal2 member, an arm64
slice, and a PyInstaller-rewritten/ad-hoc-signed member are different objects.
No equalities between them are invented. There were no frozen executables in
the discovery artifact with which to prove a byte-by-byte transformation.

## Frameworks, signatures, and repeat freezes

Every observed path, kind, size, SHA-256, symlink target and bootloader hash scope
is retained verbatim. Symlink hashes/sizes cover UTF-8 target bytes followed by
one NUL, matching the packager's inventory contract. Framework root links, Versions/Current, resource links,
root aliases and binding dylib links are all assigned, not ignored as non-code.
The materializer rejects missing, extra, duplicate or changed paths and changed
link targets, even if a caller supplies the old hash. Relative framework links
are checked against escaping the runtime root. Resource directories themselves
are non-native, but their collected symlinks still require exact coverage.

PyInstaller 6.13.0 thins universal2 objects, changes Mach-O install names/rpaths,
and signs modified binaries ad hoc. Its supplier osx.py is pinned and retained.
The code signature and all Mach-O load commands stay inside the reviewed hash.
There is **no** signature stripping, zeroing, normalized executable hash, or
executable-byte rewriting in this materializer. A second freeze can change the
bootloader's __LINKEDIT/signature metadata when its embedded compliance archive
changes. That is not grounds to accept a new hash automatically. The parent
packager must retain final verbatim hashes and either review the new exact
payload or introduce a separately justified, tested metadata-only inventory
scope. Do not normalize executable sections or accept hash differences merely
because codesign ran. These old manifests must fail closed on changed bytes.

## License/source decisions

* Qt Core/Gui/Widgets/Network/DBus, Cocoa/offscreen/style/network/TLS plugins and
  PySide/Shiboken use the LGPL-3.0 route. Full pinned Qt Base and PySide source
  archives, LGPL/GPL terms, attribution-referenced notices and replacement
  instructions accompany them. Supplier release/version correspondence is
  factual provenance, NOT a reproducible-build or frozen-byte/source proof.
* The Qt static map is an explicit conservative feature closure, not a linker
  map. It retains module-specific source attributions, the macOS Core Foundation
  event dispatcher and Cocoa platform plugin BSD notices, and optional portable
  dependencies. No Linux ELF or distro-library hash is reused. Public suffix
  data has separate MPL-2.0 duties and preferred source in the Qt archive.
  DBus headers retain AFL-2.1 plus source. The original ICC identification must
  be removed if that profile is modified. Portions of this software are
  copyright The FreeType Project (www.freetype.org). All rights reserved.
* cryptography 44.0.3 uses its BSD option, all application Cargo.lock registry
  crate notices, OpenSSL 3.4.1 and Rust standard-library notice closure. The
  **actual Mac wheel** contains OpenSSL 3.4.1 and rustc commit
  `05f9846f893b09a1be1fc8560e33fc3c815cfecb` (Rust 1.86.0), plus addr2line,
  gimli, hashbrown, object and rustc-demangle paths. The Rust supplier COPYRIGHT
  and library lock notices are reused because they apply to the same source
  version; they are not evidence of Linux binary equivalence. Optional crate
  notices form a conservative superset, including build-only/platform crates.
  Fortanix SGX-specific code is not selected for Darwin.
* CFFI 2.1.1 is MIT-0. BOTH actual Mac wheel slices load Apple's
  `/usr/lib/libffi.dylib`; the Linux static libffi-3.4.6 claim is deliberately
  not copied. Apple libSystem, libiconv, libz and platform frameworks are OS
  dependencies, not bundled payloads. No Apple runtime redistribution grant is
  inferred or needed for files not shipped.
* CPython uses PSF and its separately retained HACL, BLAKE2, mpdecimal, Expat,
  SipHash, Mersenne Twister, dtoa and cfuhash notices. Its installer recipe
  identifies static XZ/liblzma 5.2.3 for _lzma. The exact XZ notice says liblzma
  is public domain (with an explicit fallback permission grant), NOT GPL simply
  because the upstream package also contains GPL tools. The component uses
  `LicenseRef-XZ-5.2.3-Public-Domain`, bound to that notice and library-only scope.
  Parent integration must add this narrowly reviewed term to build.py's policy;
  this OS materializer validates it separately without relabelling its license.
  Permissive components require notices/provenance, not invented exact-build
  source attestations. The Python source is retained in the evidence cache.
* PyInstaller's exception is scoped to embedding its standard loader, not a
  standalone GPL exemption. The original Mac loader dynamically uses system
  zlib. Source-level loader modifications are not authorized by this review;
  loader prefix equality to its original supplier slice has NOT been proven.

## Remaining native qualification gates

Run both Mac freezes and exact final payload audits; investigate any hash drift.
Exercise the documented replacement route on both architectures, capture actual
modified-member evidence, launch each frozen UI and verify readable text, then
verify the dual-runtime DMG and architecture dispatch. None was executable on
this Linux research host. Materialization success establishes asset integrity
and exact mapping, not signing acceptance, notarization, a working DMG, static
linker attestation, or native recombination. Do not offer these discoveries as
qualified binaries.
