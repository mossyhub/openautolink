-----BEGIN PGP SIGNED MESSAGE-----
Hash: SHA512

Format: 3.0 (quilt)
Source: glib2.0
Binary: libglib2.0-0t64, libglib2.0-tests, libglib2.0-udeb, libglib2.0-bin, libgio-2.0-dev, libglib2.0-dev, libgio-2.0-dev-bin, libglib2.0-dev-bin, libglib2.0-data, libglib2.0-doc, girepository-tools, libgirepository-2.0-0, libgirepository-2.0-dev, gir1.2-glib-2.0, gir1.2-glib-2.0-dev, gir1.2-girepository-3.0, gir1.2-girepository-3.0-dev
Architecture: any all
Version: 2.84.4-3~deb13u5
Maintainer: Debian GNOME Maintainers <pkg-gnome-maintainers@lists.alioth.debian.org>
Uploaders: Jeremy Bícha <jbicha@ubuntu.com>, Marco Trevisan (Treviño) <marco@ubuntu.com>, Simon McVittie <smcv@debian.org>
Homepage: https://gitlab.gnome.org/GNOME/glib
Standards-Version: 4.7.0
Vcs-Browser: https://salsa.debian.org/gnome-team/glib
Vcs-Git: https://salsa.debian.org/gnome-team/glib.git -b debian/trixie
Testsuite: autopkgtest
Testsuite-Triggers: build-essential, dbus-daemon, dbus-x11, dconf-gsettings-backend, dpkg-dev, dpkg-repack, gnome-desktop-testing, gsettings-desktop-schemas, locales, locales-all, xauth, xvfb
Build-Depends: dbus-daemon <!nocheck> <!noinsttest>, debhelper-compat (= 13), dh-sequence-gnome, dh-sequence-python3, docbook-xml, docbook-xsl, dpkg-dev (>= 1.22.5), gettext, libdbus-1-dev <!nocheck> <!noinsttest>, libelf-dev, libffi-dev, libmount-dev [linux-any], libpcre2-dev, libselinux1-dev [linux-any], libsysprof-capture-4-dev (>= 3.38.0) [amd64 arm64 armel armhf i386 mips64el ppc64el riscv64 s390x hppa loong64 powerpc ppc64 sh4] <!pkg.glib2.0.nosysprof>, libxml2-utils, linux-libc-dev [linux-any], meson (>= 1.4.0), pkgconf, python3-debian:native, python3-docutils <!nodoc>, python3-packaging:native, python3:native, xsltproc, zlib1g-dev
Build-Depends-Arch: cross-exe-wrapper <cross !nogir>, desktop-file-utils <!nocheck>, dh-sequence-gir <!nogir>, gobject-introspection (>= 1.80.0) <!nogir>, locales <!nocheck> | locales-all <!nocheck>, python3-dbus <!nocheck>, python3-gi <!nocheck>, shared-mime-info <!nocheck>, tzdata <!nocheck>, tzdata-legacy <!nocheck>, xterm <!nocheck>
Build-Depends-Indep: cross-exe-wrapper <cross !nodoc>, gi-docgen (>= 2024.1) <!nodoc>, gobject-introspection (>= 1.80.0) <!nodoc>
Package-List:
 gir1.2-girepository-3.0 deb introspection optional arch=any profile=!nogir profile:v1=!nogir
 gir1.2-girepository-3.0-dev deb libdevel optional arch=any profile=!nogir profile:v1=!nogir
 gir1.2-glib-2.0 deb introspection optional arch=any profile=!nogir profile:v1=!nogir
 gir1.2-glib-2.0-dev deb libdevel optional arch=any profile=!nogir profile:v1=!nogir
 girepository-tools deb libdevel optional arch=any
 libgio-2.0-dev deb libdevel optional arch=any
 libgio-2.0-dev-bin deb libdevel optional arch=any
 libgirepository-2.0-0 deb libs optional arch=any
 libgirepository-2.0-dev deb libdevel optional arch=any
 libglib2.0-0t64 deb libs optional arch=any
 libglib2.0-bin deb misc optional arch=any
 libglib2.0-data deb libs optional arch=all
 libglib2.0-dev deb libdevel optional arch=any
 libglib2.0-dev-bin deb libdevel optional arch=any
 libglib2.0-doc deb doc optional arch=all profile=!nodoc profile:v1=!nodoc
 libglib2.0-tests deb libs optional arch=any profile=!noinsttest,!nogir profile:v1=!noinsttest&!nogir
 libglib2.0-udeb udeb debian-installer optional arch=any profile=!noudeb profile:v1=!noudeb
Checksums-Sha1:
 ade0b6ba8926c1cc81e28c86ae2652f47ceff885 660708 glib2.0_2.84.4.orig-unicode-data.tar.xz
 7d021a627322082de873c483b540443c524712e1 5618200 glib2.0_2.84.4.orig.tar.xz
 6d92bf8dffbeee2369394a9f40a67f8fb71b478c 172260 glib2.0_2.84.4-3~deb13u5.debian.tar.xz
Checksums-Sha256:
 c1742461e8c0e9673a3453a3127671169de9cb0138493e5c916f1b989530efcd 660708 glib2.0_2.84.4.orig-unicode-data.tar.xz
 8a9ea10943c36fc117e253f80c91e477b673525ae45762942858aef57631bb90 5618200 glib2.0_2.84.4.orig.tar.xz
 d8e3603c3780d2cc883d762cf81db9199d8c4c492a82a1a791c117a6d402dbeb 172260 glib2.0_2.84.4-3~deb13u5.debian.tar.xz
Files:
 2b38b2623d9b97ba703de7c94fd25ba2 660708 glib2.0_2.84.4.orig-unicode-data.tar.xz
 5655d0ff809b98dd77c02490609fadde 5618200 glib2.0_2.84.4.orig.tar.xz
 3465d9f4fbaee31d4d9bf933b3ad43ed 172260 glib2.0_2.84.4-3~deb13u5.debian.tar.xz
Dgit: a5c1010af460c1aecac604d56c4de40ff5e923fa debian archive/debian/2.84.4-3_deb13u5 https://git.dgit.debian.org/glib2.0
Git-Tag-Info: tag=eb1c6804e6bb2fa38dec94d4037e3b30a55fbf5d fp=7a073ad1ae694fa25bff62e5235c099d3eb33076
Git-Tag-Tagger: Simon McVittie <smcv@debian.org>

-----BEGIN PGP SIGNATURE-----

iQIzBAEBCgAdFiEEN02M5NuW6cvUwJcqYG0ITkaDwHkFAmqLMEcACgkQYG0ITkaD
wHnd7A/+PBNYk8D3kryjMc3+b56ZkuDf/4+6o3bwB3N+wFfJcEJCWVOQ+0CSqMEN
RBypNe2y1MAoX+M0izbEeWEDFfxcALu3JPBfQHJmx/AU8aQ+tER0eqNS9L+zTAsU
QOBd+DhTZXwpc58q5A/Ri49ZDg4ZP+Thlj1z17r/MyTljQqb2WIGNUYLnHQFGJNr
LxQQ5wdPk8u/mbGjyu2oqXV4+GonT8NuF0V3KFcuuFqdzlcSRy2uMMXtVvyqcA96
IrQMJCv5oG3EdjlhLhsMlwQwgUSeVeSFABG5IWJ/BIrCdprK74r0gkyt/hoKkS3v
81/Z2pSKkfKFNZWi4N07tWN8yTy+zjPmbv296ySrt2QtCvXiXy8eCXhf1SitJC4d
zUwIpcwIxVEjjJnnf0aVU5I+s7nkZaaRudmCWIscccvFNwGiMSawkYk0EREp4pzg
8/q/ZoNO8lfHj6JL+Jxzkkkdx66cmZn92Qy1vBGaOanqcafHhJ7MT5lOXP2/DFE8
TkEgusVx6AGvvKoIt8Y3oICWglNDtbbZGBN4Yo7rQehzVftDuSxCfcV3dv/RtQXZ
5h9GFb82+AWskruu2AT2L+XFmSnmcBw1odUc57plwVSjHIf8EKh/8U8Vl00iTN9Y
Yg4GW1g8TovpnaJ43Xi+jjZsGE0Dx+jCoR6laiZm49rIqiUdz/E=
=bS1t
-----END PGP SIGNATURE-----
