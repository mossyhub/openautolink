-----BEGIN PGP SIGNED MESSAGE-----
Hash: SHA512

Format: 3.0 (quilt)
Source: systemd
Binary: systemd, systemd-sysv, systemd-container, systemd-journal-remote, systemd-coredump, systemd-timesyncd, systemd-tests, libpam-systemd, libnss-myhostname, libnss-mymachines, libnss-resolve, libnss-systemd, libsystemd0, libsystemd-dev, libsystemd-shared, udev, libudev1, libudev-dev, systemd-standalone-sysusers, systemd-standalone-tmpfiles, systemd-standalone-shutdown, systemd-oomd, systemd-userdbd, systemd-homed, systemd-boot, systemd-boot-tools, systemd-boot-efi, systemd-boot-efi-amd64-signed-template, systemd-boot-efi-arm64-signed-template, systemd-ukify, systemd-resolved, systemd-dev, systemd-repart, systemd-cryptsetup
Architecture: linux-any all
Version: 257.13-1~deb13u1
Maintainer: Debian systemd Maintainers <pkg-systemd-maintainers@lists.alioth.debian.org>
Uploaders: Marco d'Itri <md@linux.it>, Sjoerd Simons <sjoerd@debian.org>, Martin Pitt <mpitt@debian.org>, Felipe Sateler <fsateler@debian.org>, Luca Boccassi <bluca@debian.org>, Nick Rosbrook <enr0n@ubuntu.com>
Homepage: https://systemd.io
Standards-Version: 4.7.2
Vcs-Browser: https://salsa.debian.org/systemd-team/systemd
Vcs-Git: https://salsa.debian.org/systemd-team/systemd.git
Testsuite: autopkgtest
Testsuite-Triggers: acl, apparmor, attr, autopkgtest, bash, bind9-dnsutils, bind9-host, bsdutils, btrfs-progs, bubblewrap, build-essential, busybox-static, ca-certificates, cron, cryptsetup-bin, curl, dbus-user-session, debian-archive-keyring, dhcpcd-base, dhcpcd5, dmeventd, dnsmasq-base, dosfstools, e2fsprogs, erofs-utils, evemu-tools, fdisk, gdm3, git, gnupg, gnutls-bin, gperf, iproute2, iputils-ping, jq, kbd, knot, knot-dnssecutils, knot-dnsutils, kpartx, less, libarchive-dev, libblkid-dev, libcap-dev, libcap2-bin, libcryptsetup-dev, libdw-dev, libelf-dev, libfido2-dev, libgcrypt20-dev, libkmod-dev, libmount-dev, libp11-kit-dev, libpwquality-dev, libqrencode-dev, libssl-dev, libtss2-dev, linux-image-amd64, linux-image-arm64, linux-image-generic, locales, locales-all, lsb-release, lvm2, lz4, mdadm, meson, mount, mtools, multipath-tools, net-tools, netcat-openbsd, netlabel-tools, network-manager, nftables, nvme-cli, open-iscsi, opensc, openssh-client, openssh-server, openssl, ovmf, ovmf-ia32, perl, pkgconf, plymouth, polkitd, psmisc, python3, python3-colorama, python3-cryptography, python3-jinja2, python3-pefile, python3-pexpect, qemu-efi-aarch64, qemu-efi-arm, qemu-efi-loongarch64, qemu-efi-riscv64, qemu-system-arm, qemu-system-misc, qemu-system-ppc, qemu-system-s390x, qemu-system-x86, quota, reprepro, rsyslog, sbsigntool, screen, seabios, snapd, socat, softhsm2, squashfs-tools, strace, stress, swtpm, swtpm-tools, sysvinit-utils, tgt, tpm2-tools, tree, tzdata, tzdata-legacy, ubuntu-keyring, uidmap, xkb-data, xserver-xorg, xserver-xorg-video-dummy, xz-utils, zstd
Build-Depends: debhelper-compat (= 13), debhelper (>= 13.11.6), dh-exec, dh-package-notes, dh-sequence-dlopenlibdeps <!stage1>, dh-sequence-installnss, dh-sequence-installsysusers, pkgconf, xsltproc <!nodoc>, docbook-xsl <!nodoc>, docbook-xml <!nodoc>, meson (>= 0.60.0), rsync | meson (>= 1.3.0~), clang:native [amd64 arm64 ppc64el riscv64 s390x] <!pkg.systemd.nobpf !stage1>, linux-bpf-dev [amd64 arm64 ppc64el riscv64 s390x] <!pkg.systemd.nobpf !stage1>, libbpf-dev [amd64 arm64 ppc64el riscv64 s390x] <!pkg.systemd.nobpf !stage1>, bpftool:native [amd64 arm64 ppc64el riscv64 s390x] <!pkg.systemd.nobpf !stage1 !pkg.systemd.upstream>, linux-tools-generic <pkg.systemd.upstream> | bpftool <pkg.systemd.upstream>, gettext, gperf, libcap-dev, libcrypt-dev, libpam0g-dev, libapparmor-dev (>= 2.13) <!stage1>, libidn2-dev <!stage1>, libiptc-dev <!stage1>, libaudit-dev <!stage1>, libdbus-1-dev (>= 1.3.2) <!nocheck> <!noinsttest>, libcryptsetup-dev (>= 2:2.4) <!stage1>, libselinux1-dev (>= 2.1.9), libacl1-dev, liblzma-dev, liblz4-dev (>= 1.3.0), lz4 <!nocheck>, libbz2-dev <!stage1>, zlib1g-dev <!stage1> | libz-dev <!stage1>, libcurl4-openssl-dev <!stage1>, libmicrohttpd-dev <!stage1>, libpcre2-dev <!stage1>, libgcrypt20-dev, libkmod-dev (>= 15), libblkid-dev (>= 2.24), libmount-dev (>= 2.30), libfdisk-dev (>= 2.33), libseccomp-dev (>= 2.3.1) [amd64 arm64 armel armhf i386 hppa loong64 mips mipsel mips64 mips64el x32 powerpc ppc64 ppc64el riscv64 s390x], libdw-dev (>= 0.158) <!stage1>, libpolkit-gobject-1-dev <!stage1>, libzstd-dev (>= 1.4.0), libtss2-dev <!stage1>, libfido2-dev <!stage1>, libp11-kit-dev <!stage1>, libssl-dev <!stage1>, libqrencode-dev <!stage1>, libxen-dev [amd64 arm64] <!stage1>, libpwquality-dev <!stage1>, libarchive-dev <!stage1>, linux-base <!nocheck>, acl <!nocheck>, python3:native, python3-jinja2:native, python3-lxml:native, python3-pefile:native <!stage1 !nocheck> <!stage1 !noinsttest>, python3-pyelftools [amd64 i386 arm64 armhf loong64 riscv64] <!stage1>, jq [amd64 arm64] <!stage1>, python3-pyparsing <!nocheck>, python3-evdev:native <!nocheck>, tzdata-legacy <!nocheck> | tzdata (<< 2025a-2) <!nocheck>, libcap2-bin <!nocheck>, iproute2 <!nocheck>, zstd <!nocheck>, gawk <!nocheck>, fdisk <!nocheck>
Package-List:
 libnss-myhostname deb admin optional arch=linux-any
 libnss-mymachines deb admin optional arch=linux-any
 libnss-resolve deb admin optional arch=linux-any
 libnss-systemd deb admin standard arch=linux-any
 libpam-systemd deb admin standard arch=linux-any
 libsystemd-dev deb libdevel optional arch=linux-any
 libsystemd-shared deb libs optional arch=linux-any
 libsystemd0 deb libs optional arch=linux-any
 libudev-dev deb libdevel optional arch=linux-any
 libudev1 deb libs optional arch=linux-any
 systemd deb admin important arch=linux-any
 systemd-boot deb admin optional arch=amd64,i386,arm64,armhf,loong64,riscv64 profile=!stage1 profile:v1=!stage1
 systemd-boot-efi deb admin optional arch=amd64,i386,arm64,armhf,loong64,riscv64 profile=!stage1 profile:v1=!stage1
 systemd-boot-efi-amd64-signed-template deb admin optional arch=amd64 profile=!stage1 profile:v1=!stage1
 systemd-boot-efi-arm64-signed-template deb admin optional arch=arm64 profile=!stage1 profile:v1=!stage1
 systemd-boot-tools deb admin optional arch=linux-any profile=!stage1 profile:v1=!stage1
 systemd-container deb admin optional arch=linux-any profile=!stage1 profile:v1=!stage1
 systemd-coredump deb admin optional arch=linux-any profile=!stage1 profile:v1=!stage1
 systemd-cryptsetup deb admin optional arch=linux-any profile=!stage1 profile:v1=!stage1
 systemd-dev deb devel optional arch=all
 systemd-homed deb admin optional arch=linux-any profile=!stage1 profile:v1=!stage1
 systemd-journal-remote deb admin optional arch=linux-any profile=!stage1 profile:v1=!stage1
 systemd-oomd deb admin optional arch=linux-any
 systemd-repart deb admin optional arch=linux-any
 systemd-resolved deb admin optional arch=linux-any
 systemd-standalone-shutdown deb admin optional arch=linux-any
 systemd-standalone-sysusers deb admin optional arch=linux-any
 systemd-standalone-tmpfiles deb admin optional arch=linux-any
 systemd-sysv deb admin important arch=linux-any protected=yes
 systemd-tests deb admin optional arch=linux-any profile=!noinsttest profile:v1=!noinsttest
 systemd-timesyncd deb admin standard arch=linux-any
 systemd-ukify deb admin optional arch=all profile=!stage1,!pkg.systemd.noukify profile:v1=!stage1&!pkg.systemd.noukify
 systemd-userdbd deb admin optional arch=linux-any profile=!stage1 profile:v1=!stage1
 udev deb admin important arch=linux-any
Checksums-Sha1:
 a30ac2421a76ec05eeb4627e19d94f653279dff0 16543653 systemd_257.13.orig.tar.gz
 6f9f1a95623f849371303ee6fd11b95c2b7a2493 183128 systemd_257.13-1~deb13u1.debian.tar.xz
Checksums-Sha256:
 1eb7d5f9ff8a426ff880a3cded9ce819613ba8003ac5ddde9eca162f14ddabe7 16543653 systemd_257.13.orig.tar.gz
 9c6e435d613b996efcdb3b7dd9a4cebaa745333a3f64dc722f3fad646f1fdc1a 183128 systemd_257.13-1~deb13u1.debian.tar.xz
Files:
 254373d8d4f5a89c33f3c364c3911e48 16543653 systemd_257.13.orig.tar.gz
 6d0f88e399c9250f135970e895bad136 183128 systemd_257.13-1~deb13u1.debian.tar.xz

-----BEGIN PGP SIGNATURE-----

iQGzBAEBCgAdFiEEV4kucFIzBRM39v3RBWQfF1cS+lsFAmnxJUEACgkQBWQfF1cS
+lu0iQwAsn0k1KezMt1Ln9oWniwXr+uBKy+kBPMbpakONu6sP4/p/lobkdObP1ic
uo6AVs+EcGkhUL5RYxCyitC9BusKjMygJq9y+EwwW2ZKE0naHO2nkrnf1MlPObqG
NUnQcd6whCQVgYdVs0NxUlcFd8PxD66m206e8/6VFKZq7szVtyrY3OPi9Nnedv6V
9aA+siUg7AIVW5YifVh85mZTo61z1x6JVJJiw3Gl4nQatGXZQ/TKxvL02EZnRPUG
a6U2pWkDolXYzoyQW76JAa2fcsK8HCeJ4+DpYvma7bCLnuaYs6HDqf4A9X3F4xxu
+Q2vtLfiWh1Cw5OW1fjZMIDXrInDkvkvzOdu0H6mtGaiOOJ8NG0VUmt98UYapnTp
PTcYrHD+Oqa4JC7B4JWJuHcR/QYOgsf8GWm0x8srw0guR0HNTe0PZIfW/q8DsMyQ
55Nz9FkcAbbsWSjHL+KOXriV6e7bL9nXG5aqdX7ElrQeNMetcngeZhNAxWaAVlKv
zp2B7sSz
=NRSz
-----END PGP SIGNATURE-----
