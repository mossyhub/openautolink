-----BEGIN PGP SIGNED MESSAGE-----
Hash: SHA512

Format: 3.0 (quilt)
Source: util-linux
Binary: util-linux, util-linux-locales, mount, bsdutils, bsdextrautils, fdisk, fdisk-udeb, libblkid1, libblkid1-udeb, libblkid-dev, libfdisk1, libfdisk1-udeb, libfdisk-dev, libmount1, libmount1-udeb, libmount-dev, libsmartcols1, libsmartcols1-udeb, libsmartcols-dev, libuuid1, uuid-runtime, libuuid1-udeb, uuid-dev, util-linux-udeb, rfkill, eject, eject-udeb
Architecture: any all
Version: 2.37.2-4ubuntu3.6
Maintainer: Ubuntu Developers <ubuntu-devel-discuss@lists.ubuntu.com>
Uploaders: Chris Hofstaedtler <zeha@debian.org>
Homepage: https://www.kernel.org/pub/linux/utils/util-linux/
Standards-Version: 4.6.0
Vcs-Browser: https://salsa.debian.org/debian/util-linux
Vcs-Git: https://salsa.debian.org/debian/util-linux.git
Testsuite: autopkgtest
Testsuite-Triggers: bash, bc, bsdmainutils, build-essential, dpkg, grep, pkg-config
Build-Depends: asciidoctor, bc <!stage1 !nocheck>, bison, debhelper-compat (= 13), dh-exec, gettext, libaudit-dev, libcap-ng-dev [linux-any] <!stage1>, libcryptsetup-dev [linux-any] <!pkg.util-linux.noverity>, libncurses5-dev, libncursesw5-dev, libpam0g-dev <!stage1>, libreadline-dev, libselinux1-dev [linux-any], libsystemd-dev [linux-any] <!stage1>, libtool, libudev-dev [linux-any] <!stage1>, netbase <!stage1 !nocheck>, pkg-config, po-debconf, socat <!stage1 !nocheck>, systemd [linux-any] <!stage1>, zlib1g-dev
Build-Conflicts: libedit-dev
Package-List:
 bsdextrautils deb utils optional arch=any profile=!stage1
 bsdutils deb utils required arch=any profile=!stage1 essential=yes
 eject deb utils optional arch=linux-any
 eject-udeb udeb debian-installer optional arch=linux-any profile=!noudeb
 fdisk deb utils important arch=any profile=!stage1
 fdisk-udeb udeb debian-installer optional arch=hurd-any,linux-any profile=!stage1,!noudeb
 libblkid-dev deb libdevel optional arch=any
 libblkid1 deb libs optional arch=any
 libblkid1-udeb udeb debian-installer optional arch=any profile=!noudeb
 libfdisk-dev deb libdevel optional arch=any
 libfdisk1 deb libs optional arch=any
 libfdisk1-udeb udeb debian-installer optional arch=any profile=!noudeb
 libmount-dev deb libdevel optional arch=linux-any
 libmount1 deb libs optional arch=any
 libmount1-udeb udeb debian-installer optional arch=linux-any profile=!noudeb
 libsmartcols-dev deb libdevel optional arch=any
 libsmartcols1 deb libs optional arch=any
 libsmartcols1-udeb udeb debian-installer optional arch=any profile=!noudeb
 libuuid1 deb libs optional arch=any
 libuuid1-udeb udeb debian-installer optional arch=any profile=!noudeb
 mount deb admin required arch=linux-any profile=!stage1
 rfkill deb utils optional arch=linux-any profile=!stage1
 util-linux deb utils required arch=any profile=!stage1 essential=yes
 util-linux-locales deb localization optional arch=all profile=!stage1
 util-linux-udeb udeb debian-installer optional arch=any profile=!stage1,!noudeb
 uuid-dev deb libdevel optional arch=any
 uuid-runtime deb utils optional arch=any profile=!stage1
Checksums-Sha1:
 4e85e2f533ef0fe79a4505695453a91f25e87605 5621624 util-linux_2.37.2.orig.tar.xz
 b0d2abe49a6d59f7cdb6807e504fe77bd68280dc 120724 util-linux_2.37.2-4ubuntu3.6.debian.tar.xz
Checksums-Sha256:
 6a0764c1aae7fb607ef8a6dd2c0f6c47d5e5fd27aa08820abaad9ec14e28e9d9 5621624 util-linux_2.37.2.orig.tar.xz
 d556ef8fb39638a6fee023dc02d1ed4c9a15d6065f844fa885ddb5b9e755ae31 120724 util-linux_2.37.2-4ubuntu3.6.debian.tar.xz
Files:
 d659bf7cd417d93dc609872f6334b019 5621624 util-linux_2.37.2.orig.tar.xz
 e15fe1c9b7d8c2d26ffaeb7ad49a3654 120724 util-linux_2.37.2-4ubuntu3.6.debian.tar.xz
Original-Maintainer: util-linux packagers <util-linux@packages.debian.org>

-----BEGIN PGP SIGNATURE-----

iQIzBAEBCgAdFiEEUMSg3c8x5FLOsZtRZWnYVadEvpMFAmqF+kQACgkQZWnYVadE
vpNAmxAAqf3/cusTnrUD2TwIBxvgEV89r1x3JYYrfMZ+Hi5FHp9BXGSBhySLefWB
F9RxMEJGUvGmw+jeN5qswJTKzSD4/dpXA3UQ9wGWc0J/36nd2YV/TG2Vfvfjp/aA
X8rrJFd4yTDzSBnBqHFhg1sWurwWyza3J+IzM6TpQqjLENAQCUGHpHl96Ah7Rm7N
BWpX6CH0kuMTxr+1j8PPV2iV5C/7P21RugnCdV6zgRErqwzv5O5u6lODFPsvKmoc
jW12IINt0BbHpY2R4KRuAn5zKwlgrsBC9q4jIEXrT9d4E4W0aR8AI4D100L8q1P4
r2bSv2AeWACuwpErJC73G1zGX3Jmf68kfezFM+gAIo6DxBWYq1h1yEmmD2qUwr9H
OtEhEAdfpfuJQoiRNKbv6gYDhXbOycq5/e49+vHDI61U4qnzNRFwYRqqM16esxlZ
ORR1a+7BpO8C3aOYuIUZNmr6bKdOfZDdA8x4PKRZzBxr31rNhg3dDWHMLXUGKUc4
7A9etNrqUoYSPOJUxshKMs51J8aG1Bcr9W3HXRHZqRkcDzttMC2R9cqfHGLDtVoe
MsUSm3dKforvnZpw6kdFYXtkDLJPmvBSVFpUjNNE1qUrtqyAmeOgohu9zVPO7QXE
dure2XRmfq/DjBNjvce8eLjGzLnQ8O/vOt8GlQl1e0ZtqKPWLxk=
=7BfL
-----END PGP SIGNATURE-----
