-----BEGIN PGP SIGNED MESSAGE-----
Hash: SHA512

Format: 3.0 (quilt)
Source: dbus
Binary: dbus, dbus-1-doc, dbus-tests, dbus-udeb, dbus-user-session, dbus-x11, libdbus-1-3, libdbus-1-3-udeb, libdbus-1-dev
Architecture: any all
Version: 1.12.20-2ubuntu4.1
Maintainer: Ubuntu Developers <ubuntu-devel-discuss@lists.ubuntu.com>
Uploaders:  Sjoerd Simons <sjoerd@debian.org>, Sebastian Dröge <slomo@debian.org>, Michael Biebl <biebl@debian.org>, Loic Minier <lool@dooz.org>, Simon McVittie <smcv@debian.org>,
Homepage: https://dbus.freedesktop.org/
Standards-Version: 4.5.1
Vcs-Browser: https://salsa.debian.org/utopia-team/dbus
Vcs-Git: https://salsa.debian.org/utopia-team/dbus.git
Testsuite: autopkgtest
Testsuite-Triggers: apparmor, build-essential, gnome-desktop-testing, init, xauth, xvfb
Build-Depends: autoconf-archive (>= 20150224), automake, debhelper-compat (= 12), dh-exec, libapparmor-dev [linux-any], libaudit-dev [linux-any], libcap-ng-dev [linux-any], libexpat-dev, libglib2.0-dev <!nocheck> <!noinsttest>, libnss-wrapper <!nocheck>, libselinux1-dev [linux-any], libsystemd-dev [linux-any], libx11-dev, pkg-config, python3 <!nocheck> <!noinsttest>, python3-dbus <!nocheck> <!noinsttest>, python3-gi <!nocheck> <!noinsttest>, valgrind [amd64 arm64 armhf i386 mips64 mips64el mips mipsel powerpc ppc64 ppc64el s390x], xmlto <!nodoc>
Build-Depends-Indep: doxygen <!nodoc>, ducktype <!nodoc>, xsltproc <!nodoc>, yelp-tools <!nodoc>
Package-List:
 dbus deb admin standard arch=any
 dbus-1-doc deb doc optional arch=all profile=!nodoc
 dbus-tests deb misc optional arch=any profile=!noinsttest
 dbus-udeb udeb debian-installer optional arch=any profile=!noudeb
 dbus-user-session deb admin optional arch=linux-any
 dbus-x11 deb x11 optional arch=any
 libdbus-1-3 deb libs optional arch=any
 libdbus-1-3-udeb udeb debian-installer optional arch=any profile=!noudeb
 libdbus-1-dev deb libdevel optional arch=any
Checksums-Sha1:
 f7fe130511aeeac40270af38d6892ed63392c7f6 2095511 dbus_1.12.20.orig.tar.gz
 d8b79da7f50fb2362993af8c3ea7938c28a9d997 65220 dbus_1.12.20-2ubuntu4.1.debian.tar.xz
Checksums-Sha256:
 f77620140ecb4cdc67f37fb444f8a6bea70b5b6461f12f1cbe2cec60fa7de5fe 2095511 dbus_1.12.20.orig.tar.gz
 c1f655920ef22949145c7b2287a057012c71ae39b3998b8bc260ce0609c30585 65220 dbus_1.12.20-2ubuntu4.1.debian.tar.xz
Files:
 dfe8a71f412e0b53be26ed4fbfdc91c4 2095511 dbus_1.12.20.orig.tar.gz
 67fb87955291526eda0aa18db2b9016e 65220 dbus_1.12.20-2ubuntu4.1.debian.tar.xz
Original-Maintainer: Utopia Maintenance Team <pkg-utopia-maintainers@lists.alioth.debian.org>

-----BEGIN PGP SIGNATURE-----

iQHSBAEBCgA8FiEEs16801xnF7wK3rCK7Ic6ztRocjwFAmNX+Z8eHG5pc2hpdC5t
YWppdGhpYUBjYW5vbmljYWwuY29tAAoJEOyHOs7UaHI8C50MAK8M4TAcRIKqpktB
33WJUGhXRdGzWBhqLkjuNTaAho45vxuugaYq2MV1jxzuAcxOkABiXrHvYqj5TvUX
2vwSeSvHBf3251Mifqezw5RLPDGsjgc0DkCgVSAf6/kfAPiU+vGz5VJUNRWv5iWJ
3U1vaCPiOJ3U6E+x19E1wm4/zu2J8RVeCzcWrb3uAS4aZHp3pnPiB0WDDNMvfLb9
DH+DIXsGRsECBqtkyHFMKgFf7PzLFmUPs0MHTlifQaZ54xEc5DsCppElAEc8UblE
f6hv1mhPRKbpehNDlKYD6ef1Q/eSPXxuWDmNjQmoPnY1YsXHt4Enr16ToxZyAZOq
IhDPIFSd6cSqO9EM53jBSg7S8Hbkcbcg5zL54zvT0M/9dl9rwSzm68lGvub2SmjW
zB2+C8YOiya74pQ56XtsOTshkMwlhEot8Ln4e4YhHH+ksbOjxVuTcEFxa3MJe5cU
R2v49HxnYGdyNePKlnnroTLI/NmeNa2TS48ZdCo5wzHmnXnW2g==
=Esbe
-----END PGP SIGNATURE-----
