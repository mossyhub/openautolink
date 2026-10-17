-----BEGIN PGP SIGNED MESSAGE-----
Hash: SHA512

Format: 3.0 (quilt)
Source: dbus
Binary: dbus, dbus-session-bus-common, dbus-system-bus-common, dbus-bin, dbus-daemon, dbus-1-doc, dbus-tests, dbus-udeb, dbus-user-session, dbus-x11, libdbus-1-3, libdbus-1-3-udeb, libdbus-1-dev
Architecture: any all
Version: 1.16.2-2
Maintainer: Utopia Maintenance Team <pkg-utopia-maintainers@lists.alioth.debian.org>
Uploaders: Sjoerd Simons <sjoerd@debian.org>, Sebastian Dröge <slomo@debian.org>, Michael Biebl <biebl@debian.org>, Loic Minier <lool@dooz.org>, Simon McVittie <smcv@debian.org>,
Homepage: https://dbus.freedesktop.org/
Standards-Version: 4.7.2
Vcs-Browser: https://salsa.debian.org/utopia-team/dbus
Vcs-Git: https://salsa.debian.org/utopia-team/dbus.git
Testsuite: autopkgtest
Testsuite-Triggers: apparmor, build-essential, gnome-desktop-testing, init, systemd, xauth, xvfb
Build-Depends: debhelper (>= 13.11.6~), debhelper-compat (= 13), dh-exec, libapparmor-dev [linux-any], libaudit-dev [linux-any], libcap-ng-dev [linux-any], libexpat-dev, libglib2.0-dev <!nocheck> <!noinsttest>, libnss-wrapper <!nocheck>, libselinux1-dev [linux-any], libsystemd-dev [linux-any], libx11-dev, meson, pkgconf, valgrind-if-available, xml-core, xmlto <!nodoc>
Build-Depends-Indep: doxygen <!nodoc>, ducktype <!nodoc>, xsltproc <!nodoc>, yelp-tools <!nodoc>
Package-List:
 dbus deb admin standard arch=any
 dbus-1-doc deb doc optional arch=all profile=!nodoc
 dbus-bin deb admin optional arch=any
 dbus-daemon deb admin optional arch=any
 dbus-session-bus-common deb admin optional arch=all
 dbus-system-bus-common deb admin optional arch=all
 dbus-tests deb misc optional arch=any profile=!noinsttest
 dbus-udeb udeb debian-installer optional arch=any profile=!noudeb
 dbus-user-session deb admin optional arch=linux-any
 dbus-x11 deb x11 optional arch=any
 libdbus-1-3 deb libs optional arch=any
 libdbus-1-3-udeb udeb debian-installer optional arch=any profile=!noudeb
 libdbus-1-dev deb libdevel optional arch=any
Checksums-Sha1:
 f44e9a36af548909e46aec8b29b965aeabc3bfbd 1115644 dbus_1.16.2.orig.tar.xz
 f61d50bd8831e1941f9254a252685fe87b6c8651 833 dbus_1.16.2.orig.tar.xz.asc
 d439f84abaeb7656484d60cc6700aac2fdab83d8 66364 dbus_1.16.2-2.debian.tar.xz
Checksums-Sha256:
 0ba2a1a4b16afe7bceb2c07e9ce99a8c2c3508e5dec290dbb643384bd6beb7e2 1115644 dbus_1.16.2.orig.tar.xz
 108723e6bb2b0655abcb8ec7b06d27918bd7ac215f835ba086b45f9780cb529b 833 dbus_1.16.2.orig.tar.xz.asc
 f774fe3bd1ac24265e17c70999bfa26a92a15f2fb72e1fb8c64fa5d436fb9119 66364 dbus_1.16.2-2.debian.tar.xz
Files:
 97832e6f0a260936d28536e5349c22e5 1115644 dbus_1.16.2.orig.tar.xz
 cc33831a5978a6583644bc2bbb2dbfb3 833 dbus_1.16.2.orig.tar.xz.asc
 b1925795f8c3ba743bcf943068b489b5 66364 dbus_1.16.2-2.debian.tar.xz
Dgit: 7b202407a4ed1c0ad454d5f09a5537e434f73aad debian archive/debian/1.16.2-2 https://git.dgit.debian.org/dbus


-----BEGIN PGP SIGNATURE-----

iQIzBAEBCgAdFiEEegc60a5pT6Jb/2LlI1wJnT6zMHYFAmfMygsACgkQI1wJnT6z
MHYSJA/+JNR9KFAe46QLFsGN4IH/loPdoKu4HV9sI8OOtAaLYCUy9+K9zvC5lrMq
cN4T2cfrqrXijj4LIfPVYIhtdmoLZqNEC8EXyLX6h0J2j0ist75L5hvdJOelGy9U
vFkjkkbTK0/nmv6dRCa0sOIGFgRlcoZzFiDDcGXACDmvnvYir54zOsvcxQkH1KIf
Ahxcwj1pFAGj102owrHY8ytp9wJ3o7aaBaGlQiNjd+/MxKJSbdaLm7JFjNrAPz4Y
4OlKD1SmiD0tw8RzZALn/ErzZ17yppx8jfCb9+SWMJ7OlcRRQrhBbbMoacYEDUkN
hQcoAKjhol9Qbsxs9DdbXItgntVPbolk+P80Cds0HUBw7/YK+NdaIIaIcq2pIEIp
67EEJvC0CLOhCjd2GA7NxflflZdAg8s6zq5/TdhxvIz3BCtAaKxMBio7t9d2evVY
5Z+KlXLHPWXFWePRoUUkdWSymSEvJVegV4pmbaaMNRiWbTOnNTLSDTQiOCEGzega
52rD3SdOBSs8r4K0DRqSjbIScH5QWLTIkIIX25OMkImQ++Zdt5ja6k2X5kwqJJug
+xn2+Y439sHobJUKL6aXqrFdCkJovQ9PE05WhszQlgavU/jTjGlwqyhycewcqujw
tCRScUxMJoyxV9Yro2vIEZ7EefE+Umo+xEY4970ih0z/+j8AGwc=
=tJ6r
-----END PGP SIGNATURE-----
