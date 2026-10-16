-----BEGIN PGP SIGNED MESSAGE-----
Hash: SHA512

Format: 3.0 (quilt)
Source: systemd
Binary: systemd, systemd-sysv, systemd-container, systemd-journal-remote, systemd-coredump, systemd-timesyncd, systemd-tests, libpam-systemd, libnss-myhostname, libnss-mymachines, libnss-resolve, libnss-systemd, libsystemd0, libsystemd-dev, udev, libudev1, libudev-dev, udev-udeb, libudev1-udeb, systemd-standalone-sysusers, systemd-standalone-tmpfiles, systemd-oomd, systemd-repart
Architecture: linux-any
Version: 249.11-0ubuntu3.22
Maintainer: Ubuntu Developers <ubuntu-devel-discuss@lists.ubuntu.com>
Uploaders: Michael Biebl <biebl@debian.org>, Marco d'Itri <md@linux.it>, Sjoerd Simons <sjoerd@debian.org>, Martin Pitt <mpitt@debian.org>, Felipe Sateler <fsateler@debian.org>
Homepage: https://www.freedesktop.org/wiki/Software/systemd
Standards-Version: 4.6.0
Vcs-Browser: https://git.launchpad.net/~ubuntu-core-dev/ubuntu/+source/systemd
Vcs-Git: https://git.launchpad.net/~ubuntu-core-dev/ubuntu/+source/systemd
Testsuite: autopkgtest
Testsuite-Triggers: acl, apparmor, attr, autopkgtest, build-essential, busybox-static, cron, cryptsetup-bin, dbus-user-session, dmeventd, dnsmasq-base, dosfstools, e2fsprogs, evemu-tools, fdisk, gcc, gdm3, iproute2, iputils-ping, isc-dhcp-client, kbd, less, libc-dev, libc6-dev, libcap2-bin, libfido2-dev, liblz4-tool, libtss2-dev, locales, lsb-release, make, net-tools, netcat-openbsd, network-manager, perl, pkg-config, plymouth, policykit-1, python3, python3-colorama, qemu-system-arm, qemu-system-ppc, qemu-system-s390x, qemu-system-x86, quota, rsyslog, seabios, snapd, socat, squashfs-tools, strace, tree, vim-tiny, xserver-xorg, xserver-xorg-video-dummy, xz-utils, zstd
Build-Depends: debhelper-compat (= 13), pkg-config, xsltproc, docbook-xsl, docbook-xml, meson (>= 0.52.1), gettext, gperf, gnu-efi [amd64 i386 arm64 armhf], libcap-dev (>= 1:2.24-9~), libpam0g-dev, libapparmor-dev (>= 2.13) <!stage1>, libidn2-dev <!stage1>, libiptc-dev <!stage1>, libaudit-dev <!stage1>, libdbus-1-dev (>= 1.3.2) <!nocheck> <!noinsttest>, libcryptsetup-dev (>= 2:1.6.0) <!stage1>, libselinux1-dev (>= 2.1.9), libacl1-dev, liblzma-dev, liblz4-dev (>= 0.0~r125), liblz4-tool <!nocheck>, libbz2-dev <!stage1>, zlib1g-dev <!stage1> | libz-dev <!stage1>, libcurl4-gnutls-dev <!stage1> | libcurl-dev <!stage1>, libmicrohttpd-dev <!stage1>, libgnutls28-dev <!stage1>, libpcre2-dev <!stage1>, libgcrypt20-dev, libkmod-dev (>= 15), libblkid-dev (>= 2.24), libmount-dev (>= 2.30), libfdisk-dev (>= 2.33), libseccomp-dev (>= 2.3.1) [amd64 arm64 armel armhf i386 mips mipsel mips64 mips64el x32 powerpc ppc64 ppc64el riscv64 s390x], libdw-dev (>= 0.158) <!stage1>, libpolkit-gobject-1-dev <!stage1>, libzstd-dev (>= 1.4.0), libtss2-dev [!i386] <!stage1>, libfido2-dev <!stage1>, libssl-dev <!stage1>, linux-base <!nocheck>, acl <!nocheck>, python3:native, python3-jinja2:native, python3-lxml:native, python3-pyparsing:native <!nocheck>, python3-evdev:native <!nocheck>, tzdata <!nocheck>, libcap2-bin <!nocheck>, iproute2 <!nocheck>, zstd <!nocheck>, gawk <!nocheck>, fdisk <!nocheck>
Package-List:
 libnss-myhostname deb admin optional arch=linux-any
 libnss-mymachines deb admin optional arch=linux-any
 libnss-resolve deb admin optional arch=linux-any
 libnss-systemd deb admin standard arch=linux-any
 libpam-systemd deb admin standard arch=linux-any
 libsystemd-dev deb libdevel optional arch=linux-any
 libsystemd0 deb libs optional arch=linux-any
 libudev-dev deb libdevel optional arch=linux-any
 libudev1 deb libs optional arch=linux-any
 libudev1-udeb udeb debian-installer optional arch=linux-any profile=!noudeb
 systemd deb admin important arch=linux-any
 systemd-container deb admin optional arch=linux-any profile=!stage1
 systemd-coredump deb admin optional arch=linux-any profile=!stage1
 systemd-journal-remote deb admin optional arch=linux-any profile=!stage1
 systemd-oomd deb admin optional arch=linux-any
 systemd-repart deb admin optional arch=linux-any
 systemd-standalone-sysusers deb admin optional arch=linux-any
 systemd-standalone-tmpfiles deb admin optional arch=linux-any
 systemd-sysv deb admin important arch=linux-any
 systemd-tests deb admin optional arch=linux-any profile=!noinsttest
 systemd-timesyncd deb admin standard arch=linux-any
 udev deb admin important arch=linux-any
 udev-udeb udeb debian-installer optional arch=linux-any profile=!noudeb
Checksums-Sha1:
 228ac48e74776641b63ac3302398966dd2098b2f 10622702 systemd_249.11.orig.tar.gz
 8f250f7f474d566ccb38c80fd50baa8307642fa8 275816 systemd_249.11-0ubuntu3.22.debian.tar.xz
Checksums-Sha256:
 305ba81cc33593bc2e8e9d6dc7f964b1c0a9303155fced5e6b1d236577441bf2 10622702 systemd_249.11.orig.tar.gz
 4ce334b6483e938692c7fa1a29c6c69a518053a62b2b67752d57fcadf24bce57 275816 systemd_249.11-0ubuntu3.22.debian.tar.xz
Files:
 36755223c51bb7cbc0e353610b67449a 10622702 systemd_249.11.orig.tar.gz
 2d0f7073ee6828feaf084e4451525eab 275816 systemd_249.11-0ubuntu3.22.debian.tar.xz
Original-Maintainer: Debian systemd Maintainers <pkg-systemd-maintainers@lists.alioth.debian.org>

-----BEGIN PGP SIGNATURE-----

iQIzBAEBCgAdFiEEUMSg3c8x5FLOsZtRZWnYVadEvpMFAmpqCPMACgkQZWnYVadE
vpPF5hAAgdqtt8FA1AdX5Sr6Z8aHBLJq8MN5fNxLlla5I1xR8MjfouCO5rP31pA0
5wFiqnm5OfFW5ve+WgX298DnD+fKcbYyYSbGQ4hYou/73tANHIT8Xedb/c6QfKUb
82fWt7cDNBwhHEHhQ/C+0ufP1Bp4TjNJMv+Zb/cTv8h7MwwYNCPArGXEKUZ43yFF
INxnyKzwF64e98eT6l2/PRari2nBgsLpz3ZqyjjW43O5QHmys+hA6ms70uQcqM0y
91oOsjDukQXJbPfBt+zS3lYVbDs9F7KqkWCSNCRZxEq5D5d6UG+Vg6bWpuRCUJIB
rZKaFpyJMyKkkhbVjaLMLJmuXa+gRtsa0C7BbRUj7nsuLrNydEI7q1sqkCdL/VF5
6Q/nTX2MTuBswaCss1TIgOSArcQwvyXyR6iW9hwHWBz7FUvOZl2M2s2uJ4ljDLu6
fj45fesJ+W4P0+0B3tD8jNjjqb0G8dhRN86xdlJUQXjkc+wAiJ8GtEr+AB68YfSP
eRTSQQNHQ0Tb9NACNiKeE5eDgDEP9qD0580ZlBMzzGbwc1SoLM/upC3JQIRhhZ94
Bq3RiGAIJu0ris8PY/AYMFRJhYBd2KgC9zSDxEReUidBnCf/HrvIifz7XLjqr3IT
Mi/NmjsVwukJfdXtG3gTVugrJei9XyuP5uDEj37rwHaI+A60puA=
=y5cc
-----END PGP SIGNATURE-----
