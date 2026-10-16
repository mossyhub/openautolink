# Native payload coverage

## Policy: requirements follow the selected license

Schema 2 replaces the old blanket requirement for a source archive and exact-build
attestation for **every** library. That was not a requirement of the PSF license:
its grant covers source or binary copies provided its agreement and copyright
notice are retained. CPython's third-party notices still matter.[4][9]

| Selected terms | Implemented evidence route |
| --- | --- |
| MIT, BSD-2/3-Clause, Apache-2.0, PSF-2.0, ISC, Zlib, BSL-1.0, Unicode-3.0, ICU, libpng-2.0, bzip2-1.0.6, FTL, X11 | Component/version, byte provenance, complete notices (including attribution/NOTICE when applicable), static dependency records. **No mandatory source archive, build recipe, or source attestation.** |
| LGPL-2.0/2.1/3.0 and GPL-2.0/3.0, `-only` or `-or-later` | Notices, matching source archives and source basis, plus build/recombination instructions. |
| MPL-2.0 | Notices and covered-file source with source basis; no LGPL-style relink recipe. |
| PyInstaller `GPL-2.0-or-later WITH Bootloader-exception` | Notice/exception text and `exception_basis`; limited to the embedded `@bootloader`, not arbitrary GPL libraries or standalone/modified loader distribution. |
| AFL-2.1 | Complete notices and source (section 3); the implemented package also carries rebuild directions. |
| GPL-3.0-or-later WITH GCC-exception-3.1 | Runtime/eligible-compilation basis, notices, source and rebuild directions; standalone runtime source is not waived. |
| Additional reviewed notice terms | MIT-0, 0BSD, CC0, Imlib2, HPND, MIT-open-group, Apache WITH LLVM-exception, Qt's ICC/SHA1/PCRE2 LicenseRefs and libselinux's explicit public-domain grant. Conjunctions require **every** term to have a supported route. |
| Version-specific composite grants | Kerberos 1.21.3, libX11 1.8.12 and com_err 1.47.2 retain their actual full mixed notices, not a generic MIT relabel. Exact reviewed notice hash and library-specific scope are mandatory; see `LINUX-REVIEW.md`. |
| Other license/exception/expression | Fail closed until its specific route is implemented and reviewed. Do not relabel a component to fit the table. |

The PyInstaller exception explicitly permits embedding its compiled loader and
distributing the combined executable without restrictions arising from those
files. It does not erase the distinct terms of static third-party code.[17]

The table describes the implemented packaging policy, not a legal opinion or an
exhaustive interpretation of every license. Selecting a permissive option in a
dual-license grant requires evidence that the option covers the actual code.
A component with permissive top-level terms can still contain copyleft code;
its `statically_linked` references are checked independently. A license name or
nonempty notice file alone does not establish notice completeness.

MPL-2.0 section 3.2 requires source availability for its covered code, not a
relink kit for the entire larger work. Its implemented route embeds covered-file
source and notices without requiring a library build recipe.[27]

## LGPL source and replacement are still required

Bundling a shared library does not in itself discharge LGPL obligations.
LGPL-2.1 section 4 requires complete corresponding machine-readable source for
the distributed library; its section 6 describes application/linking options.
LGPL-3 section 4 requires notices, license copies and either recombination code
or a suitable replaceable shared-library mechanism.[1][2]

GPL-3 section 6(d), incorporated by LGPL-3, permits equivalent network source
access, including a different server with clear directions, but the distributor
remains responsible for keeping that access available. A bare upstream homepage
or a promise invented by a build script is not that arrangement.[2][3]

**The currently implemented copyleft route embeds source archives.** Other lawful
routes are not claimed to be forbidden; an external-access/offer route needs an
actual distributor, publication location, retention responsibility and appropriate
license-specific checks before enabling it. No such publication/offer was authorized
or created here. Do not demand source archives for permissive components just
because this is the chosen route for LGPL components.

Onefile applications unpack libraries into a temporary directory. Do not claim
that simply replacing a sibling DLL/SO works. Supply the exact builder source,
the library source/build instructions and a tested rebuild/recombination procedure
with the coverage manifest. The source export is not the library source.[2]

## Retrieved evidence, not fabricated approval

`provenance/source-inputs.lock.json` pins **79 supplier inputs/derived members** by SHA-256.
`prepare_native.py` downloads them to an explicit cache, validates every digest,
reads only explicit notice members (without extracting or executing supplier
code), and matches real frozen bytes against official wheel members. Offline
replay rechecks the cache; a missing/changed notice or input fails.

The checked-in `licenses/supplier-notices/` contains **69 generated notice bundles**, copied
verbatim from those inputs with supplier URL, hash and member headings. This is
a deliberately labelled superset, not an assertion that every listed component
is linked. `provenance/supplier-evidence.json` binds their exact bytes and records
**25 historical Linux payload paths** matched to official PyPI wheel members.
It supplements, rather than overwrites, `linux-observed.json`'s dpkg/local-file
and Debian archive observations.

Retrieved inputs include:

- Qt Base 6.8.3 source, its attribution records and referenced license/copyright
  files; the archive matches Qt's published SHA-256. PySide/Shiboken 6.8.3 source
  and the official Linux Essentials and Shiboken wheels are also retained in the
  cache. The installed Essentials native bytes match the downloaded wheel.[7][13][14]
- CPython 3.12.15 LICENSE, its full source-version license documentation, and
  bundled HACL/BLAKE2 notices; no corresponding-source mandate is imposed on PSF
  code. The source archive was retrieved to obtain reliable notices, not because
  binary distribution requires carrying that archive.[4][15]
- cryptography 44.0.3 source and its 35 checksum-pinned Cargo.lock registry crate
  archives/notices (a dependency superset, including build/target-only crates).
  OpenSSL 3.4.1's license is separate from Debian OpenSSL's notice. This does not
  by itself prove the wheel's compiled Cargo feature/dependency closure.[23]
- Official PyInstaller and CFFI wheels/source notices and ICU 73.2's full notice.
  Permissive ICU/OpenSSL/CFFI code does not acquire a source-distribution
  requirement merely because it is native or statically linked.[16][17][25]
- Debian source control files **and every SHA-256-listed source member** for
  systemd `257.13-1~deb13u1`, GLib `2.84.4-3~deb13u5`, keyutils `1.6.3-6`,
  D-Bus `1.16.2-2` and util-linux `2.41.5-0+deb13u1`. These include upstream
  archives and Debian patches/build rules, not just directory URLs.[18][19][20]
  D-Bus and util-linux source control references are also locked.[21][22]

Digest provenance is explicit: supplier-published digests where available;
otherwise the digest was computed from the HTTPS retrieval. Debian `.dsc` member
hashes were checked; this is not a claim that OpenPGP signatures were verified.
Large archives/wheels remain outside the checkout. The lock and notice bundles
are included in the builder source snapshot so recipients can replay preparation.

```sh
# From desktop-builder; cache location is your choice:
python packaging/prepare_native.py \
  --inventory packaging/provenance/linux-observed.json --output /path/to/evidence
# Verify exactly the same downloaded bytes without network:
python packaging/prepare_native.py --offline \
  --inventory packaging/provenance/linux-observed.json --output /path/to/evidence
```

## Reviewed Linux coverage and remaining native-platform limits

`provenance/linux-reviewed.json` now maps the **121 fresh Linux native paths** to
license-specific records, including explicit static dependency records. It is a
real schema-2 input to the packaging gate, not an inventory-only milestone.
`LINUX-REVIEW.md` records the selected library terms and conservative static
closure; `LINUX-REBUILD.md` supplies source/build/recombination instructions.

- Qt's per-module edges cover Linux-relevant optional third-party code as a
  conservative superset. This preserves all relevant obligations without
  pretending to know a supplier linker map. MPL PSL source is embedded.
- Debian libraries have library-specific terms, including AFL D-Bus and
  GPL-with-GCC-exception runtime source/patches. Unicode data is recognized as
  preferred source, rather than rejecting it just because it is `.txt`.
- Cryptography covers its 35 locked registry crates, separate OpenSSL, and
  Rust 1.86 standard-library notices. Four backtrace crates missing from the
  supplier library-only HTML are independently pinned from Rust's Cargo.lock.
  CFFI's static libffi 3.4.6 and MIT-0 grant are distinguished from Debian libffi.
- Native Windows/Mac still require their own inventories and supplier-runtime
  rights. Linux evidence does not approve MSVC/UCRT, Mach-O frameworks or signing
  changes. Offscreen UI success is not a real XCB-display qualification.

The generator never assigns an unknown path by a filename glob or new hash:

```sh
python packaging/prepare_native.py --offline --inventory /path/to/fresh-inventory.json \
  --output /path/to/verified-cache
python packaging/generate_native.py --inventory /path/to/fresh-inventory.json \
  --cache /path/to/verified-cache --output /path/to/new-coverage
python packaging/build.py --output /path/to/empty-dist \
  --native-coverage /path/to/new-coverage/coverage.json
```

Only the final build's exported evidence, actual inventory and smoke results
qualify its executable. A new byte, path or platform requires a new review.

The raw-wheel matcher intentionally leaves `@bootloader` unmatched: the frozen
ELF prefix has PyInstaller's added-section metadata and is not the wheel's whole
`run` file. A targeted comparison found only ELF section-table metadata changes
in its retained prefix; this is useful provenance, not blanket permission to
normalize more executable bytes. Final inventory still normalizes **only**
`e_shoff`, and final artifact hashes remain verbatim.

## Coverage manifest and final-artifact gate

Use `schema: 2`, the native `system` and `architecture`, and `components` with:

- `id`, `version`, selected `license`, and factual `provenance`;
- explicit `payload` path/SHA-256 pairs, assigned exactly once;
- nonempty component-specific `notices` assets (`path`, `sha256`);
- explicit `statically_linked` component IDs, each with its own terms/evidence;
- for the implemented copyleft route: `source_basis`, `sources` assets (local
  ZIP/tar, `path`, `sha256`, HTTPS `url`) and a `build_instructions` asset;
- for the scoped loader exception: `exception_basis` describing supplier identity,
  unchanged loader code and embedding scope. Its required notice includes the
  exception, not only GPL boilerplate.

Schema 1 remains the stricter legacy all-source format; migrate real manifests
to schema 2 rather than populating unnecessary dummy sources.

```sh
python packaging/build.py --audit-native /path/to/candidate --output audit
python packaging/build.py --output new-dist --native-coverage /path/to/coverage.json
```

Unknown payloads, stale/duplicate entries, missing static records, unsafe paths,
changed notices and unsupported schema-2 licenses fail closed. The first freeze
is discovery only. The second freeze must have identical native payload, and the
final executable's exported evidence is checked again before qualification.
No source ZIP, notice superset or unit test automatically approves a release.

## Sources

[1] https://www.gnu.org/licenses/old-licenses/lgpl-2.1.txt
[2] https://www.gnu.org/licenses/lgpl-3.0.txt
[3] https://www.gnu.org/licenses/gpl-3.0.txt
[4] https://raw.githubusercontent.com/python/cpython/v3.12.15/LICENSE
[7] https://download.qt.io/archive/qt/6.8/6.8.3/submodules/qtbase-everywhere-src-6.8.3.tar.xz.sha256
[9] https://docs.python.org/3.12/license.html
[13] https://download.qt.io/archive/qt/6.8/6.8.3/submodules/qtbase-everywhere-src-6.8.3.tar.xz
[14] https://download.qt.io/official_releases/QtForPython/pyside6/PySide6-6.8.3-src/pyside-setup-everywhere-src-6.8.3.tar.xz
[15] https://www.python.org/ftp/python/3.12.15/Python-3.12.15.tar.xz
[16] https://raw.githubusercontent.com/unicode-org/icu/release-73-2/icu4c/LICENSE
[17] https://raw.githubusercontent.com/pyinstaller/pyinstaller/v6.13.0/COPYING.txt
[18] https://deb.debian.org/debian/pool/main/s/systemd/systemd_257.13-1~deb13u1.dsc
[19] https://deb.debian.org/debian/pool/main/g/glib2.0/glib2.0_2.84.4-3~deb13u5.dsc
[20] https://deb.debian.org/debian/pool/main/k/keyutils/keyutils_1.6.3-6.dsc
[21] https://deb.debian.org/debian/pool/main/d/dbus/dbus_1.16.2-2.dsc
[22] https://deb.debian.org/debian/pool/main/u/util-linux/util-linux_2.41.5-0+deb13u1.dsc
[23] https://files.pythonhosted.org/packages/53/d6/1411ab4d6108ab167d06254c5be517681f1e331f90edf1379895bcb87020/cryptography-44.0.3.tar.gz
[25] https://files.pythonhosted.org/packages/9e/ef/008a1939e372c06329a3fce4279c02f328488f3526744906eeec3da7ad5f/cffi-2.1.1.tar.gz
[27] https://www.mozilla.org/media/MPL/2.0/index.txt
