# Ubuntu 22.04 CI native coverage

This review applies only to the exact 141-entry Linux x86_64 discovery map.
It is neither a native launch/replacement qualification nor permission to
accept a later Ubuntu runner's changed libraries. The generator compares
complete path, digest, kind, size and symlink-target records before downloads.

## Supplier correspondence and static closure

* Qt/PySide/Shiboken 6.8.3, ICU 73, CFFI 2.1.1, cryptography 44.0.3 and the
  embedded PyInstaller 6.13.0 loader have the **same exact path/digest** as the
  previously reviewed Linux wheel payload. Only those unchanged components
  and their reviewed static closure are reused. This is not Debian mapping
  reuse. The discovery wheel URL/digest/member matches are retained separately.
* All 45 CPython 3.12.15 native files differ from the Debian-container build.
  Each Ubuntu file matches the runner's installed CPython candidate. The
  runner identifies GCC 11.4.0, OpenSSL 3.0.2 and zlib 1.2.11. The existing
  hash-pinned CPython 3.12.15 source notices cover HACL, BLAKE2, mpdecimal,
  bundled Expat, SipHash, Mersenne Twister, dtoa and cfuhash. These are a
  conservative source-version closure, not a claim of identical compiler
  options, reproducible binaries or a new CPython build attestation.
* Distro mappings are newly constructed from exact installed candidate hash
  matches and Ubuntu binary/source package metadata. No unmatched Google
  Cloud SDK library is covered. XCB, libbsd, libmd, libgcrypt, libgpg-error,
  liblz4 and libpcre3 are additional Ubuntu dependencies, not old Debian
  entries with rewritten hashes.
* Ubuntu package copyright files are retained byte-for-byte, with the SHA-256
  reported by the runner checked against UTF-8 bytes. Mixed source-package
  grants are library-specific: command-line utilities, tests, documentation,
  packaging tools and other unshipped programs are not classified as shipped.
* libgcrypt's full LICENSES (including Intel assembly, jitterentropy and OCB
  terms), GLib's bundled gnulib/permissive code, systemd's internal shared
  helpers, and util-linux's internal libcommon helpers are included as
  conservative embedded-code closures with their source-package notices and
  full sources. External dynamic dependencies are separately inventoried,
  not falsely declared static. libgcrypt and libgpg-error use LGPL-2.1+;
  libkeyutils uses LGPL-2.0+; GLib, libsystemd, libmount and libblkid LGPL-2.1+.
  libuuid is separately BSD-3-Clause, not the utilities' GPL.
* GCC 12.3.0 libgcc_s and libstdc++ use GPL-3.0-or-later with the runtime
  exception. Source and runtime-exception notices accompany these standalone
  libraries; the exception does not remove that source obligation.
* FreeType selects FTL with its retained MIT/OpenGroup/zlib notices. Portions
  of this software are copyright The FreeType Project (www.freetype.org).
  All rights reserved. libdbus selects AFL-2.1, with full patched source.
* X11, Kerberos, libbsd, libmd and XZ use exact-notice-bound composite LicenseRefs,
  not a blanket MIT/public-domain alias. Their complete supplier terms,
  attribution and no-endorsement restrictions remain applicable. The libbsd
  advertising-clause man pages are not shipped, but the full notice is kept.
  Beerware code in libbsd/libmd is not omitted or renamed BSD. Kerberos's
  source-package notice includes code not linked into these four libraries;
  preserve it conservatively, including export and modification conditions.

The `.dsc` files are retrieved from Ubuntu's archive for each **exact observed
source version**; every source member is pinned to its descriptor's SHA-256.
Descriptors are retained as notices/provenance, not passed off as source.
Upstream archives plus Ubuntu Debian-patch archives are supplied, with GCC's
nested upstream archive pinned to its actual member digest. These are patched
source inputs, not a claim that an upstream release alone reproduces Ubuntu.
Acquisition uses HTTPS and descriptor checksums; no new OpenPGP signature
verification is asserted. Archive contents are inspected, never executed.

## Rebuild and replacement

Use the accompanying `REBUILD.md`. Users may modify and reverse engineer the
LGPL libraries for debugging those modifications. No signing key is required.
The exact-byte release gate intentionally refuses newly modified libraries;
that does not restrict a user's private PyInstaller re-freeze.
