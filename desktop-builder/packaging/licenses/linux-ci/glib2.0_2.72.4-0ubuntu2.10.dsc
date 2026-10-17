-----BEGIN PGP SIGNED MESSAGE-----
Hash: SHA512

Format: 3.0 (quilt)
Source: glib2.0
Binary: libglib2.0-0, libglib2.0-tests, libglib2.0-udeb, libglib2.0-bin, libglib2.0-dev, libglib2.0-dev-bin, libglib2.0-data, libglib2.0-doc
Architecture: any all
Version: 2.72.4-0ubuntu2.10
Maintainer: Ubuntu Developers <ubuntu-devel-discuss@lists.ubuntu.com>
Uploaders: Iain Lane <laney@debian.org>, Jeremy Bicha <jbicha@ubuntu.com>, Simon McVittie <smcv@debian.org>
Homepage: https://wiki.gnome.org/Projects/GLib
Standards-Version: 4.6.0
Vcs-Browser: https://salsa.debian.org/gnome-team/glib/tree/ubuntu/jammy
Vcs-Git: https://salsa.debian.org/gnome-team/glib.git -b ubuntu/jammy
Testsuite: autopkgtest
Testsuite-Triggers: build-essential, dbus, dbus-x11, dpkg-dev, gnome-desktop-testing, locales, locales-all, xauth, xvfb
Build-Depends: dbus <!nocheck> <!noinsttest>, debhelper-compat (= 13), dh-sequence-python3, dh-sequence-gnome, docbook-xml, docbook-xsl, dpkg-dev (>= 1.17.14), gettext, libdbus-1-dev (>= 1.2.14) <!nocheck> <!noinsttest>, libelf-dev (>= 0.142), libffi-dev (>= 3.3), libgamin-dev [hurd-any] | libfam-dev [hurd-any], libmount-dev (>= 2.35.2-7~) [linux-any], libpcre3-dev (>= 1:8.35), libselinux1-dev [linux-any], libxml2-utils, linux-libc-dev [linux-any], meson (>= 0.52.0), pkg-config (>= 0.16.0), python3-distutils, python3:any (>= 2.7.5-5~), xsltproc, zlib1g-dev
Build-Depends-Arch: desktop-file-utils <!nocheck>, locales <!nocheck> | locales-all <!nocheck>, python3-dbus <!nocheck>, python3-gi <!nocheck>, shared-mime-info <!nocheck>, tzdata <!nocheck>, xterm <!nocheck>
Build-Depends-Indep: gtk-doc-tools (>= 1.32.1)
Package-List:
 libglib2.0-0 deb libs optional arch=any
 libglib2.0-bin deb misc optional arch=any
 libglib2.0-data deb libs optional arch=all
 libglib2.0-dev deb libdevel optional arch=any
 libglib2.0-dev-bin deb libdevel optional arch=any
 libglib2.0-doc deb doc optional arch=all
 libglib2.0-tests deb libs optional arch=any profile=!noinsttest
 libglib2.0-udeb udeb debian-installer optional arch=any profile=!noudeb
Checksums-Sha1:
 abb94eb7a918382272c98b39d70cad0a5bc02275 4884256 glib2.0_2.72.4.orig.tar.xz
 85c8bcc29254f3751e6f24ee0774f22f7f6ccdf3 170208 glib2.0_2.72.4-0ubuntu2.10.debian.tar.xz
Checksums-Sha256:
 8848aba518ba2f4217d144307a1d6cb9afcc92b54e5c13ac1f8c4d4608e96f0e 4884256 glib2.0_2.72.4.orig.tar.xz
 e81d6f72522eb2092ddec06760753d5fe7866eab553237a4c237b613e592df69 170208 glib2.0_2.72.4-0ubuntu2.10.debian.tar.xz
Files:
 bfecfad1ab9754d3b8534fb99f1efaec 4884256 glib2.0_2.72.4.orig.tar.xz
 bdb7f87a38b17196ed1322a39109c2a7 170208 glib2.0_2.72.4-0ubuntu2.10.debian.tar.xz
Debian-Vcs-Browser: https://salsa.debian.org/gnome-team/glib
Debian-Vcs-Git: https://salsa.debian.org/gnome-team/glib.git
Original-Maintainer: Debian GNOME Maintainers <pkg-gnome-maintainers@lists.alioth.debian.org>

-----BEGIN PGP SIGNATURE-----

iQIzBAEBCgAdFiEEf+ebRFcoyOoAQoOeRbznW4QLH2kFAmqoRT8ACgkQRbznW4QL
H2lnBw//VQobguAUtg7lCyqReFDRBSjP7fxjtjhXGfMfqUzn8J6/ToT8YGaRslpf
klKwYx2M5Hi31zXQT91SZyWggjyChr1wz/HCDVrh7pglkoDIWhG9qlZF6LT1jtc5
upqTvPN8fQn2xCHDF+bHlftJSWazKslZIOTdPD2f7Cto5F8eCnL0rmLB3WKftA29
5lAl07d1wUuYOadgheBwPcviZOQZFYJGyY1sltoHFWyEvMh2UbJJ4/tboZtUeVxt
ga+e7UoaqgRSnILtWZn1x2kl3vD/At80oXMCJkoXi8SGHXFp7Xnn2rhOzc+5quFB
2uQ1Mo59djyvueFiU0LLTIKEbM3UK9GUtpBuferszmugJNqfdYrrzUhC7jssz4tZ
LlgzxJoVptaXzI+F1QBPqt+gRyRRnv1BQlxnqjLs5zJ0Xv18IjlqqAEUCiqf49aJ
V/+OFE+7Cn4RgwADebUYm60gKBHhbCRPmSLXx1VFbOavW/cwAS+m7EK04seaAMm5
3a9huYCocPebOJcpvqu1IQcf9/6SYUHo59Jpg0toC+zmNG0zoaLBDr8tc4TcGlX9
veGRzBVL9x8e5YES7EWopmexWVHGg6r/srnKY0zw42f+dUmlqRRU4gjANRBZ/H99
S/B6VXFMxEZwqS6pL740EP7D5IkWwZzvOsjm1Qsyu69v7/SxjgE=
=o13M
-----END PGP SIGNATURE-----
