-----BEGIN PGP SIGNED MESSAGE-----
Hash: SHA256

Format: 3.0 (quilt)
Source: libgpg-error
Binary: libgpg-error-dev, libgpg-error0, gpgrt-tools, libgpg-error0-udeb, libgpg-error-mingw-w64-dev, libgpg-error-l10n
Architecture: any all
Version: 1.43-3
Maintainer: Debian GnuPG Maintainers <pkg-gnupg-maint@lists.alioth.debian.org>
Uploaders:  Daniel Kahn Gillmor <dkg@fifthhorseman.net>, Christoph Biedl <debian.axhn@manchmal.in-ulm.de>,
Homepage: https://www.gnupg.org/related_software/libgpg-error/
Standards-Version: 4.6.0
Vcs-Browser: https://salsa.debian.org/debian/libgpg-error
Vcs-Git: https://salsa.debian.org/debian/libgpg-error.git -b debian/master
Testsuite: autopkgtest
Testsuite-Triggers: build-essential, gcc-mingw-w64-i686, gcc-mingw-w64-x86-64, pkg-config, wine32, wine64
Build-Depends: automake (>= 1.14), debhelper-compat (= 13), gettext (>= 0.19.3), texinfo
Build-Depends-Indep: mingw-w64
Package-List:
 gpgrt-tools deb devel optional arch=any
 libgpg-error-dev deb libdevel optional arch=any
 libgpg-error-l10n deb localization optional arch=all
 libgpg-error-mingw-w64-dev deb libdevel optional arch=all
 libgpg-error0 deb libs optional arch=any
 libgpg-error0-udeb udeb debian-installer optional arch=any
Checksums-Sha1:
 e71b77e8e32023dfd9cb06504942aa8e028c8795 999006 libgpg-error_1.43.orig.tar.bz2
 d8eb6fa882ca67bb7456f80661115482db52661a 238 libgpg-error_1.43.orig.tar.bz2.asc
 3dcac64b4e70f85cbfeccd0d91920150b814417f 19264 libgpg-error_1.43-3.debian.tar.xz
Checksums-Sha256:
 a9ab83ca7acc442a5bd846a75b920285ff79bdb4e3d34aa382be88ed2c3aebaf 999006 libgpg-error_1.43.orig.tar.bz2
 6f1f0354aee0abc946d7f0e604fa69d5826a312baabcc0bb4fad4f97899cfa80 238 libgpg-error_1.43.orig.tar.bz2.asc
 d9d4efa45fc8d58152cbfa3bbaa68ed1977d8c56b7d59a579ef4feb4ffe7ec83 19264 libgpg-error_1.43-3.debian.tar.xz
Files:
 f1e09cfa536a6e6a264dbf2ccdc2e534 999006 libgpg-error_1.43.orig.tar.bz2
 421f4b0f45f391e2da82b4d119ec0621 238 libgpg-error_1.43.orig.tar.bz2.asc
 2a947135d2ba37ac464362bf4abc6d46 19264 libgpg-error_1.43-3.debian.tar.xz

-----BEGIN PGP SIGNATURE-----

iHUEARYIAB0WIQQttUkcnfDcj0MoY88+nXFzcd5WXAUCYedQZQAKCRA+nXFzcd5W
XKwZAP0ReKNVufXTbrSroifUWEwdEacdw/jMhlvZTRKyxGq6vgD+KLKcSWUpUnED
q+EqGzOe6Lkdn6BARO46JVjs+rxwHQk=
=soPK
-----END PGP SIGNATURE-----
