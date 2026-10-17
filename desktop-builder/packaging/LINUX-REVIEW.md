# Linux x86_64 native review

## Scope and evidence standard

`provenance/linux-reviewed.json` is the selected-terms manifest for the fresh
Linux payload, not an approval of every binary named similarly on another
machine. The review matches path **and** SHA-256. `generate_native.py` refuses
added, removed, changed or duplicated paths, validates/stages the evidence,
then writes `coverage.json`. `build.py` still freezes twice, compares the final
native inventory, exports the embedded evidence and runs the frozen UI.

This review covers redistribution notices, source and recombination. It does
not certify export-control compliance, patents, security, or legal advice.
No private credentials or host libraries were changed. The native payload comes
from official pinned wheels, Debian package/hash observations, and the local
CPython 3.12.15 installation. CPython is not claimed to be a Debian or upstream
bit-reproducible build. Its PSF/permissive licenses require notices, not proof
of a corresponding build environment.

Source basis for Qt/PySide is the Qt Company's versioned 6.8.3 source releases
and exact official wheel member hashes. Debian source basis is the package's
recorded source package/version and all `.dsc`-listed members, including Debian
patches. Hashes are supplier-published where recorded; HTTPS retrieval hashes
are labelled separately. No OpenPGP signature verification is claimed.

## Qt static attribution closure

The per-module dependency edges in the manifest are a **conservative closure**:
optional Linux implementations are retained even when a supplier system-library
switch might have excluded them. This deliberately avoids requiring an exact
supplier linker map just to preserve permissive notices. The Qt source and
`qt_attribution.json` records are the evidence for the mapping. The full Qt
archive supplies source for both LGPL modules and MPL Public Suffix List data.

| Shipped module | Included embedded/optional implementation records |
| --- | --- |
| Core | BLAKE2, double-conversion, easing, forkfd, MD4/MD5/SHA1/SHA3/RFC6234, SipHash, TinyCBOR, zlib, PCRE2 and SLJIT, Tika MIME definitions, Unicode UCD/CLDR |
| Gui | Vulkan allocator/registry, FreeType and BDF/PCF/zlib subparts, HarfBuzz, ICC sRGB profile, libpng, MD4C, OpenGL headers, grayraster, smooth scaling, X server region code, Adobe glyph list, WebGradients |
| Network | MPL-2.0 PSL data and BSD libpsl lookup/generator |
| DBus | AFL-2.1 libdbus header declarations; original headers included in Qt source |
| XcbQpa/platform plugins | Bundled xcb-xinput; Core/Gui-owned implementations are covered by those modules |
| Widgets | Qt code plus the already-covered Gui/Core libraries |

Excluded by OS/module: Android/Gradle, D3D12, Windows accessibility/wintab, Cocoa,
WASM fonts, ARM-only pixman, QtTest/Valgrind/perf headers, SQLite plugin and JPEG
plugin (not collected by the widget-only hook). Excluding these is not a claim
that the Qt source archive lacks them; source bundles may contain more code
than the shipped executable. License-specific texts from `LICENSES/` and the
attribution-referenced copyright files are preserved, including FTL, ICC,
Imlib2, HPND, public-domain declarations and PCRE2's exception. Selected dual
options are explicit in each record, not inferred from the word "MIT".

## Distribution libraries

Component `license_scope` fields identify the actual library, not unrelated
programs/tests/documentation in its source package. Full Debian copyright files
are retained so secondary copyright and license text is not silently dropped.
All referenced Debian common-license texts accompany copyleft records.

* libcap chooses its BSD-3-Clause option. libzstd chooses BSD-3-Clause and
  retains its zlib/MIT embedded-code notices. liblzma is **0BSD**, not the GPL
  license of xz shell scripts. libselinux library is explicitly public domain;
  the GPL `avcstat` utility and Debian packaging do not change that grant.
* libffi's runtime files use the Expat/MIT grant; GPL tests and compiler-wrapper
  tools are not shipped. Its public-domain dlmalloc fallback is retained in the
  notice. FreeType's FTL, BDF/PCF/MIT and zlib notices are all included.
* GLib, systemd, keyutils, libblkid and libmount retain their library LGPL
  obligations, embedded source and replacement instructions. Their per-file
  permissive source notices are not discarded. D-Bus selects AFL-2.1 with source
  supplied; no GPL-only command-line utilities are in this payload.
* GCC runtime's explicit exception does **not** put standalone libraries on the
  notice-only route. Source and patches plus GPL and the complete exception
  remain mandatory. Independent application code is not relicensed by these
  libraries. Debian's compiler-produced runtimes and the ordinary C/C++/Rust
  wheel compilation routes are eligible processes; no proprietary GCC IR
  optimization pipeline is used in this application freeze.

Three full, version-specific composite grants are represented by LicenseRefs,
not incorrectly relabelled as generic MIT. They require an exact reviewed
copyright-file hash as well as component scope:

* `LicenseRef-Kerberos-1.21.3`: BSD-2/3-Clause, MIT/HPND variants, OpenLDAP-2.8
  Unicode routines, permissive AES/crypto notices and other library-local
  notices in the complete MIT Kerberos copyright file. The BSD choice is used
  where a GPL alternative exists. Standalone administration programs, RPC/KDC
  components, test tools, and CC-BY-SA documentation are not these five shared
  libraries. Copyright, permission and non-endorsement clauses are preserved.
  The export and trademark warnings remain verbatim; this review does not
  independently grant export permission.
* `LicenseRef-libX11-1.8.12`: all MIT/X11/HPND legacy, Tektronix Xcms, and embedded
  uthash grants in Debian's libX11 notice, **not** just the first MIT paragraph.
  TekHVC must identify those coordinates if presented; the builder presents no
  TekHVC coordinates and does not rename any library interfaces. It does not
  claim vendor endorsement. libX11-xcb comes from the same source/version.
* `LicenseRef-com-err-1.47.2`: lib/et MIT SIPB permission, BSD com_right code and
  conservative retention of the Berkeley vfprintf attribution. The e2fsprogs
  GPL filesystem tools, libext2fs and test cases are not libcom_err. This product
  includes software developed by the University of California, Berkeley.
  This product includes software developed by Computing Services at Carnegie
  Mellon University (http://www.cmu.edu/computing/); its test notice is retained
  as a superset, without asserting those tests are part of the runtime.

## Cryptography, Rust standard library, CFFI and CPython

The frozen `_rust.abi3.so` matches the pinned cryptography 44.0.3 wheel. The
wheel's strings identify Rust commit `05f9846f893b09a1be1fc8560e33fc3c815cfecb`
(Rust 1.86.0), asn1 0.20.0, base64 0.22.1, once_cell 1.20.2, openssl 0.10.68,
openssl-sys 0.9.108, pem 3.0.4 and PyO3 0.23.5. Runtime OpenSSL is 3.4.1; the
Debian dynamically shipped OpenSSL 3.5.7 is a **different** component.

The manifest includes all registry crates in the sdist Cargo.lock, not merely
the eight names surviving in strings. This is a conservative feature/build/target
superset: it makes no unsupported claim to know every wheel build feature, and
retains all possible notices from that locked dependency set. Internal workspace
crates are covered by cryptography's BSD option. Crate archives are checksum
pinned to Cargo.lock; their own Cargo.toml/license files establish selected terms.
`unicode-ident` requires Unicode in addition to the MIT option, and
`target-lexicon` retains the Apache LLVM exception. They are not all MIT.

Rust's supplier-generated **COPYRIGHT-library.html** is extracted from the
SHA-256-pinned Rust 1.86.0 compiler distribution; it contains the standard
library's per-file and registry-dependency notices. Each included dependency
has a manifest record. The Fortanix SGX-only MPL crate is excluded on this
`x86_64-unknown-linux-gnu` build (the Rust std Cargo.toml targets it specifically
at `x86_64-fortanix-unknown-sgx`). Windows/WASI/Hermit-specific crate notices may
be retained as a harmless superset, not asserted to execute on Linux. The
compiler_builtins record preserves both MIT and Apache WITH LLVM-exception.
The supplier library-only HTML omits optional backtrace dependencies despite
their presence in the binary. `addr2line 0.24.2`, `adler2 2.0.0`,
`miniz_oxide 0.8.3` and `object 0.36.7` are therefore separately acquired and
checksum-pinned from Rust's `library/Cargo.lock`, with their own notices.
No Rust source archive is demanded for these permissive runtime parts.

CFFI 2.1.1 is **MIT-0**, not the old CFFI MIT grant. Its official wheel has no
libffi DT_NEEDED; supplier CI builds static libffi **3.4.6**, `--with-pic
--enable-shared=no`, and renames ffi_call to cffistatic_ffi_call. The CI recipe,
libffi 3.4.6 notice and wheel identity are separately pinned. This is not the
Debian dynamic libffi 3.4.8 used elsewhere in the executable.

CPython includes the complete 3.12 license documentation and bundled HACL,
BLAKE2, expat and mpdecimal notices. These are recorded as embedded-code
components, not silently equated to the PSF top-level license. ICU 73.2's full
Unicode/third-party notice accompanies its three wheel libraries and aliases.
The PyInstaller exception is restricted to the unchanged embedded loader code;
archive/ELF section metadata is not represented as a supplier whole-file match.

## Reproduction and limits

Follow `LINUX-REBUILD.md`. The expected gate rejection from the discovery freeze
is evidence of fail-closed behavior, not a compile failure. Final executable
qualification is a separate result and must be reported with its actual hash
and screenshot. This review does not qualify a real XCB display merely because
an offscreen UI works. Windows, Intel Mac and Apple Silicon Mac still require
native inventories, separate supplier rights and native execution.
