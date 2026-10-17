-----BEGIN PGP SIGNED MESSAGE-----
Hash: SHA512

Format: 3.0 (quilt)
Source: gcc-14
Binary: gcc-14-base, libgcc-s1, libgcc-s2, libgcc-s4, libgcc-14-dev, lib64gcc-s1, lib64gcc-14-dev, lib32gcc-s1, lib32gcc-14-dev, libn32gcc-s1, libn32gcc-14-dev, libx32gcc-s1, libx32gcc-14-dev, gcc-14-alpha-linux-gnu, gcc-14-x86-64-linux-gnu, gcc-14-arm-linux-gnueabi, gcc-14-arm-linux-gnueabihf, gcc-14-aarch64-linux-gnu, gcc-14-i686-linux-gnu, gcc-14-loongarch64-linux-gnu, gcc-14-mipsel-linux-gnu, gcc-14-mips64-linux-gnuabi64, gcc-14-mips64el-linux-gnuabi64, gcc-14-mips64-linux-gnuabin32, gcc-14-powerpc-linux-gnu, gcc-14-powerpc64-linux-gnu, gcc-14-powerpc64le-linux-gnu, gcc-14-m68k-linux-gnu, gcc-14-riscv64-linux-gnu, gcc-14-sh4-linux-gnu, gcc-14-sparc-linux-gnu, gcc-14-sparc64-linux-gnu, gcc-14-s390x-linux-gnu, gcc-14-x86-64-linux-gnux32, gcc-14-mips64el-linux-gnuabin32, gcc-14-mipsisa32r6-linux-gnu, gcc-14-mipsisa32r6el-linux-gnu, gcc-14-mipsisa64r6-linux-gnuabi64, gcc-14-mipsisa64r6el-linux-gnuabi64, gcc-14-mipsisa64r6-linux-gnuabin32,
 gcc-14-mipsisa64r6el-linux-gnuabin32, gcc-14-for-host, gcc-14-for-build, gcc-14, gcc-14-multilib, gcc-14-test-results, gcc-14-plugin-dev, gcc-14-hppa64-linux-gnu, cpp-14-alpha-linux-gnu, cpp-14-x86-64-linux-gnu, cpp-14-arm-linux-gnueabi, cpp-14-arm-linux-gnueabihf, cpp-14-aarch64-linux-gnu, cpp-14-i686-linux-gnu, cpp-14-loongarch64-linux-gnu, cpp-14-mipsel-linux-gnu, cpp-14-mips64-linux-gnuabi64, cpp-14-mips64el-linux-gnuabi64, cpp-14-mips64-linux-gnuabin32, cpp-14-powerpc-linux-gnu, cpp-14-powerpc64-linux-gnu, cpp-14-powerpc64le-linux-gnu, cpp-14-m68k-linux-gnu, cpp-14-riscv64-linux-gnu, cpp-14-sh4-linux-gnu, cpp-14-sparc-linux-gnu, cpp-14-sparc64-linux-gnu, cpp-14-s390x-linux-gnu, cpp-14-x86-64-linux-gnux32, cpp-14-mips64el-linux-gnuabin32, cpp-14-mipsisa32r6-linux-gnu, cpp-14-mipsisa32r6el-linux-gnu, cpp-14-mipsisa64r6-linux-gnuabi64, cpp-14-mipsisa64r6el-linux-gnuabi64, cpp-14-mipsisa64r6-linux-gnuabin32, cpp-14-mipsisa64r6el-linux-gnuabin32, cpp-14-for-host,
 cpp-14-for-build, cpp-14, gcc-14-locales, g++-14-alpha-linux-gnu, g++-14-x86-64-linux-gnu, g++-14-arm-linux-gnueabi, g++-14-arm-linux-gnueabihf, g++-14-aarch64-linux-gnu, g++-14-i686-linux-gnu, g++-14-loongarch64-linux-gnu, g++-14-mipsel-linux-gnu, g++-14-mips64-linux-gnuabi64, g++-14-mips64el-linux-gnuabi64, g++-14-mips64-linux-gnuabin32, g++-14-powerpc-linux-gnu, g++-14-powerpc64-linux-gnu, g++-14-powerpc64le-linux-gnu, g++-14-m68k-linux-gnu, g++-14-riscv64-linux-gnu, g++-14-sh4-linux-gnu, g++-14-sparc-linux-gnu, g++-14-sparc64-linux-gnu, g++-14-s390x-linux-gnu, g++-14-x86-64-linux-gnux32, g++-14-mips64el-linux-gnuabin32, g++-14-mipsisa32r6-linux-gnu, g++-14-mipsisa32r6el-linux-gnu, g++-14-mipsisa64r6-linux-gnuabi64, g++-14-mipsisa64r6el-linux-gnuabi64, g++-14-mipsisa64r6-linux-gnuabin32, g++-14-mipsisa64r6el-linux-gnuabin32, g++-14-for-host, g++-14-for-build, g++-14, g++-14-multilib, libgomp1, lib32gomp1, lib64gomp1, libn32gomp1, libx32gomp1, libitm1, lib32itm1,
 lib64itm1, libx32itm1, libatomic1, lib32atomic1, lib64atomic1, libn32atomic1, libx32atomic1, libasan8, lib32asan8, lib64asan8, libx32asan8, libhwasan0, liblsan0, libtsan2, libubsan1, lib32ubsan1, lib64ubsan1, libx32ubsan1, libquadmath0, lib32quadmath0, lib64quadmath0, libx32quadmath0, libcc1-0, libgccjit0, libgccjit-14-doc, libgccjit-14-dev, gobjc++-14-alpha-linux-gnu, gobjc++-14-x86-64-linux-gnu, gobjc++-14-arm-linux-gnueabi, gobjc++-14-arm-linux-gnueabihf, gobjc++-14-aarch64-linux-gnu, gobjc++-14-i686-linux-gnu, gobjc++-14-loongarch64-linux-gnu, gobjc++-14-mipsel-linux-gnu, gobjc++-14-mips64-linux-gnuabi64, gobjc++-14-mips64el-linux-gnuabi64, gobjc++-14-mips64-linux-gnuabin32, gobjc++-14-powerpc-linux-gnu, gobjc++-14-powerpc64-linux-gnu, gobjc++-14-powerpc64le-linux-gnu, gobjc++-14-m68k-linux-gnu, gobjc++-14-riscv64-linux-gnu, gobjc++-14-sh4-linux-gnu, gobjc++-14-sparc-linux-gnu, gobjc++-14-sparc64-linux-gnu, gobjc++-14-s390x-linux-gnu,
 gobjc++-14-x86-64-linux-gnux32, gobjc++-14-mips64el-linux-gnuabin32, gobjc++-14-mipsisa32r6-linux-gnu, gobjc++-14-mipsisa32r6el-linux-gnu, gobjc++-14-mipsisa64r6-linux-gnuabi64, gobjc++-14-mipsisa64r6el-linux-gnuabi64, gobjc++-14-mipsisa64r6-linux-gnuabin32, gobjc++-14-mipsisa64r6el-linux-gnuabin32, gobjc++-14-for-host, gobjc++-14-for-build, gobjc++-14, gobjc++-14-multilib, gobjc-14-alpha-linux-gnu, gobjc-14-x86-64-linux-gnu, gobjc-14-arm-linux-gnueabi, gobjc-14-arm-linux-gnueabihf, gobjc-14-aarch64-linux-gnu, gobjc-14-i686-linux-gnu, gobjc-14-loongarch64-linux-gnu, gobjc-14-mipsel-linux-gnu, gobjc-14-mips64-linux-gnuabi64, gobjc-14-mips64el-linux-gnuabi64, gobjc-14-mips64-linux-gnuabin32, gobjc-14-powerpc-linux-gnu, gobjc-14-powerpc64-linux-gnu, gobjc-14-powerpc64le-linux-gnu, gobjc-14-m68k-linux-gnu, gobjc-14-riscv64-linux-gnu, gobjc-14-sh4-linux-gnu, gobjc-14-sparc-linux-gnu, gobjc-14-sparc64-linux-gnu, gobjc-14-s390x-linux-gnu, gobjc-14-x86-64-linux-gnux32,
 gobjc-14-mips64el-linux-gnuabin32, gobjc-14-mipsisa32r6-linux-gnu, gobjc-14-mipsisa32r6el-linux-gnu, gobjc-14-mipsisa64r6-linux-gnuabi64, gobjc-14-mipsisa64r6el-linux-gnuabi64, gobjc-14-mipsisa64r6-linux-gnuabin32, gobjc-14-mipsisa64r6el-linux-gnuabin32, gobjc-14-for-host, gobjc-14-for-build, gobjc-14, gobjc-14-multilib, libobjc-14-dev, lib64objc-14-dev, lib32objc-14-dev, libn32objc-14-dev, libx32objc-14-dev, libobjc4, lib64objc4, lib32objc4, libn32objc4, libx32objc4, gfortran-14-alpha-linux-gnu, gfortran-14-x86-64-linux-gnu, gfortran-14-arm-linux-gnueabi, gfortran-14-arm-linux-gnueabihf, gfortran-14-aarch64-linux-gnu, gfortran-14-i686-linux-gnu, gfortran-14-loongarch64-linux-gnu, gfortran-14-mipsel-linux-gnu, gfortran-14-mips64-linux-gnuabi64, gfortran-14-mips64el-linux-gnuabi64, gfortran-14-mips64-linux-gnuabin32, gfortran-14-powerpc-linux-gnu, gfortran-14-powerpc64-linux-gnu, gfortran-14-powerpc64le-linux-gnu, gfortran-14-m68k-linux-gnu,
 gfortran-14-riscv64-linux-gnu, gfortran-14-sh4-linux-gnu, gfortran-14-sparc-linux-gnu, gfortran-14-sparc64-linux-gnu, gfortran-14-s390x-linux-gnu, gfortran-14-x86-64-linux-gnux32, gfortran-14-mips64el-linux-gnuabin32, gfortran-14-mipsisa32r6-linux-gnu, gfortran-14-mipsisa32r6el-linux-gnu, gfortran-14-mipsisa64r6-linux-gnuabi64, gfortran-14-mipsisa64r6el-linux-gnuabi64, gfortran-14-mipsisa64r6-linux-gnuabin32, gfortran-14-mipsisa64r6el-linux-gnuabin32, gfortran-14-for-host, gfortran-14-for-build, gfortran-14, gfortran-14-multilib, libgfortran-14-dev, lib64gfortran-14-dev, lib32gfortran-14-dev, libn32gfortran-14-dev, libx32gfortran-14-dev, libgfortran5, lib64gfortran5, lib32gfortran5, libn32gfortran5, libx32gfortran5, gccgo-14-alpha-linux-gnu, gccgo-14-x86-64-linux-gnu, gccgo-14-arm-linux-gnueabi, gccgo-14-arm-linux-gnueabihf, gccgo-14-aarch64-linux-gnu, gccgo-14-i686-linux-gnu, gccgo-14-mipsel-linux-gnu, gccgo-14-mips64-linux-gnuabi64,
 gccgo-14-mips64el-linux-gnuabi64, gccgo-14-mips64-linux-gnuabin32, gccgo-14-powerpc-linux-gnu, gccgo-14-powerpc64-linux-gnu, gccgo-14-powerpc64le-linux-gnu, gccgo-14-riscv64-linux-gnu, gccgo-14-sparc-linux-gnu, gccgo-14-sparc64-linux-gnu, gccgo-14-s390x-linux-gnu, gccgo-14-x86-64-linux-gnux32, gccgo-14-mips64el-linux-gnuabin32, gccgo-14-mipsisa32r6-linux-gnu, gccgo-14-mipsisa32r6el-linux-gnu, gccgo-14-mipsisa64r6-linux-gnuabi64, gccgo-14-mipsisa64r6el-linux-gnuabi64, gccgo-14-mipsisa64r6-linux-gnuabin32, gccgo-14-mipsisa64r6el-linux-gnuabin32, gccgo-14-for-host, gccgo-14-for-build, gccgo-14, gccgo-14-multilib, libgo-14-dev, lib64go-14-dev, lib32go-14-dev, libn32go-14-dev, libx32go-14-dev, libgo23, lib64go23, lib32go23, libn32go23, libx32go23, libstdc++6, lib32stdc++6, lib64stdc++6, libn32stdc++6, libx32stdc++6, libstdc++-14-dev, libstdc++-14-pic, libstdc++6-14-dbg, lib32stdc++-14-dev, lib32stdc++6-14-dbg, lib64stdc++-14-dev, lib64stdc++6-14-dbg, libn32stdc++-14-dev,
 libn32stdc++6-14-dbg, libx32stdc++-14-dev, libx32stdc++6-14-dbg, libstdc++-14-doc, gnat-14-alpha-linux-gnu, gnat-14-x86-64-linux-gnu, gnat-14-arm-linux-gnueabi, gnat-14-arm-linux-gnueabihf, gnat-14-aarch64-linux-gnu, gnat-14-i686-linux-gnu, gnat-14-loongarch64-linux-gnu, gnat-14-mipsel-linux-gnu, gnat-14-mips64-linux-gnuabi64, gnat-14-mips64el-linux-gnuabi64, gnat-14-mips64-linux-gnuabin32, gnat-14-powerpc-linux-gnu, gnat-14-powerpc64-linux-gnu, gnat-14-powerpc64le-linux-gnu, gnat-14-m68k-linux-gnu, gnat-14-riscv64-linux-gnu, gnat-14-sh4-linux-gnu, gnat-14-sparc-linux-gnu, gnat-14-sparc64-linux-gnu, gnat-14-s390x-linux-gnu, gnat-14-x86-64-linux-gnux32, gnat-14-mips64el-linux-gnuabin32, gnat-14-mipsisa32r6-linux-gnu, gnat-14-mipsisa32r6el-linux-gnu, gnat-14-mipsisa64r6-linux-gnuabi64, gnat-14-mipsisa64r6el-linux-gnuabi64, gnat-14-mipsisa64r6-linux-gnuabin32, gnat-14-mipsisa64r6el-linux-gnuabin32, gnat-14-for-host, gnat-14-for-build, gnat-14, libgnat-14,
 gdc-14-x86-64-linux-gnu, gdc-14-arm-linux-gnueabi, gdc-14-arm-linux-gnueabihf, gdc-14-aarch64-linux-gnu, gdc-14-i686-linux-gnu, gdc-14-loongarch64-linux-gnu, gdc-14-mipsel-linux-gnu, gdc-14-mips64-linux-gnuabi64, gdc-14-mips64el-linux-gnuabi64, gdc-14-mips64-linux-gnuabin32, gdc-14-powerpc-linux-gnu, gdc-14-powerpc64-linux-gnu, gdc-14-powerpc64le-linux-gnu, gdc-14-riscv64-linux-gnu, gdc-14-sparc-linux-gnu, gdc-14-s390x-linux-gnu, gdc-14-x86-64-linux-gnux32, gdc-14-mips64el-linux-gnuabin32, gdc-14-mipsisa32r6-linux-gnu, gdc-14-mipsisa32r6el-linux-gnu, gdc-14-mipsisa64r6-linux-gnuabi64, gdc-14-mipsisa64r6el-linux-gnuabi64, gdc-14-mipsisa64r6-linux-gnuabin32, gdc-14-mipsisa64r6el-linux-gnuabin32, gdc-14-for-host, gdc-14-for-build, gdc-14, gdc-14-multilib, libgphobos-14-dev, lib64gphobos-14-dev, lib32gphobos-14-dev, libn32gphobos-14-dev, libx32gphobos-14-dev, libgphobos5, lib64gphobos5, lib32gphobos5, libn32gphobos5, libx32gphobos5, gm2-14-alpha-linux-gnu,
 gm2-14-x86-64-linux-gnu, gm2-14-arm-linux-gnueabi, gm2-14-arm-linux-gnueabihf, gm2-14-aarch64-linux-gnu, gm2-14-i686-linux-gnu, gm2-14-loongarch64-linux-gnu, gm2-14-mipsel-linux-gnu, gm2-14-mips64-linux-gnuabi64, gm2-14-mips64el-linux-gnuabi64, gm2-14-mips64-linux-gnuabin32, gm2-14-powerpc64le-linux-gnu, gm2-14-m68k-linux-gnu, gm2-14-riscv64-linux-gnu, gm2-14-sparc-linux-gnu, gm2-14-sparc64-linux-gnu, gm2-14-s390x-linux-gnu, gm2-14-x86-64-linux-gnux32, gm2-14-mips64el-linux-gnuabin32, gm2-14-mipsisa32r6-linux-gnu, gm2-14-mipsisa32r6el-linux-gnu, gm2-14-mipsisa64r6-linux-gnuabi64, gm2-14-mipsisa64r6el-linux-gnuabi64, gm2-14-mipsisa64r6-linux-gnuabin32, gm2-14-mipsisa64r6el-linux-gnuabin32, gm2-14-for-host, gm2-14-for-build, gm2-14, libgm2-14-dev, libgm2-19, gm2-14-doc, gccrs-14-x86-64-linux-gnu, gccrs-14-arm-linux-gnueabi, gccrs-14-arm-linux-gnueabihf, gccrs-14-aarch64-linux-gnu, gccrs-14-i686-linux-gnu, gccrs-14-loongarch64-linux-gnu, gccrs-14-mipsel-linux-gnu,
 gccrs-14-mips64-linux-gnuabi64, gccrs-14-mips64el-linux-gnuabi64, gccrs-14-mips64-linux-gnuabin32, gccrs-14-powerpc-linux-gnu, gccrs-14-powerpc64-linux-gnu, gccrs-14-powerpc64le-linux-gnu, gccrs-14-riscv64-linux-gnu, gccrs-14-sparc-linux-gnu, gccrs-14-sparc64-linux-gnu, gccrs-14-s390x-linux-gnu, gccrs-14-mips64el-linux-gnuabin32, gccrs-14-mipsisa32r6-linux-gnu, gccrs-14-mipsisa32r6el-linux-gnu, gccrs-14-mipsisa64r6-linux-gnuabi64, gccrs-14-mipsisa64r6el-linux-gnuabi64, gccrs-14-mipsisa64r6-linux-gnuabin32, gccrs-14-mipsisa64r6el-linux-gnuabin32, gccrs-14-for-host, gccrs-14-for-build, gccrs-14, gcc-14-offload-nvptx, libgomp-plugin-nvptx1, gcc-14-offload-amdgcn, libgomp-plugin-amdgcn1,
 gcc-14-source
Architecture: any all
Version: 14.2.0-19
Maintainer: Debian GCC Maintainers <debian-gcc@lists.debian.org>
Uploaders: Matthias Klose <doko@debian.org>
Homepage: http://gcc.gnu.org/
Standards-Version: 4.7.0
Vcs-Browser: https://salsa.debian.org/toolchain-team/gcc/tree/gcc-14-debian
Vcs-Git: https://salsa.debian.org/toolchain-team/gcc.git -b gcc-14-debian
Testsuite: autopkgtest
Testsuite-Triggers: apt, libc-dev, libc6-dev, python3-minimal
Build-Depends: debhelper (>= 11), dpkg-dev (>= 1.17.14), g++-multilib [amd64 i386 mips mips64 mips64el mips64r6 mips64r6el mipsel mipsn32 mipsn32el mipsn32r6 mipsn32r6el mipsr6 mipsr6el powerpc ppc64 s390 s390x sparc sparc64 x32] <!cross>, libc6.1-dev (>= 2.23-1~) [alpha ia64] | libc0.3-dev (>= 2.23-1~) [hurd-amd64 hurd-i386] | libc6-dev (>= 2.23-1~), libc6-dev-amd64 [i386 x32], libc6-dev-sparc64 [sparc], libc6-dev-sparc [sparc64], libc6-dev-s390 [s390x], libc6-dev-s390x [s390], libc6-dev-i386 [amd64 x32], libc6-dev-powerpc [ppc64], libc6-dev-ppc64 [powerpc], lib32gcc-s1 [amd64 ppc64 mipsn32 mipsn32el mips64 mips64el mipsn32r6 mipsn32r6el mips64r6 mips64r6el s390x sparc64 x32], libn32gcc-s1 [mips mipsel mips64 mips64el mipsr6 mipsr6el mips64r6 mips64r6el], lib64gcc-s1 [i386 mips mipsel mipsn32 mipsn32el mipsr6 mipsr6el mipsn32r6 mipsn32r6el powerpc sparc s390 x32], libc6-dev-mips64 [mips mipsel mipsn32 mipsn32el mipsr6 mipsr6el mipsn32r6 mipsn32r6el], libc6-dev-mipsn32 [mips mipsel mips64 mips64el mipsr6 mipsr6el mips64r6 mips64r6el], libc6-dev-mips32 [mipsn32 mipsn32el mips64 mips64el mipsn32r6 mipsn32r6el mips64r6 mips64r6el], libc6-dev-x32 [amd64 i386], libx32gcc-s1 [amd64 i386], libc6.1-dbg [alpha ia64] | libc0.3-dbg [hurd-amd64 hurd-i386] | libc6-dbg, linux-libc-dev [m68k], m4, libtool, autoconf2.69, dwz, libunwind8-dev [ia64], libatomic-ops-dev [ia64], gawk, lzma, xz-utils, patchutils, libzstd-dev, zlib1g-dev, systemtap-sdt-dev [linux-any], binutils:native (>= 2.39), binutils-hppa64-linux-gnu:native (>= 2.39) [hppa amd64 i386 x32], gperf, bison, flex, gettext, nvptx-tools [amd64 arm64 ppc64el], amdgcn-tools-19 [amd64], texinfo, locales-all, sharutils, procps, gnat-14:native [!arc !ia64 !sh3 !sh3eb !sh4eb !m68k], g++-14:native [!m68k], gnat-13:native [m68k], g++-13:native [m68k], netbase, gdc-14:native [!alpha !arc !ia64 !m68k !sh4 !s390 !sparc64 !hurd-amd64 !hurd-i386], python3:any, cargo [!alpha !hppa !ia64 !m68k !sh4 !x32], libisl-dev (>= 0.20), libmpc-dev (>= 1.0), libmpfr-dev (>= 3.0.0-9~), libgmp-dev (>= 2:5.0.1~), lib32z1-dev [amd64], lib64z1-dev [i386], unzip <!nocheck>, dejagnu <!nocheck>, git <!nocheck>, coreutils, chrpath, lsb-release, quilt, time, pkgconf, libgc-dev, g++-14-for-host <cross>, gobjc-14-for-host [!avr] <cross>, gfortran-14-for-host <cross>, gdc-14-for-host [!alpha !arc !ia64 !m68k !sh4 !s390 !sparc64 !hurd-amd64 !hurd-i386] <cross>, gccgo-14-for-host [!arc !avr !hppa !loong64 !m68k !sh4] <cross>, gnat-14-for-host [!arc !ia64 !sh3 !sh3eb !sh4eb] <cross>, gm2-14-for-host [!powerpc !ppc64 !sh4] <cross>
Build-Depends-Indep: doxygen <!nodoc>, graphviz <!nodoc>, ghostscript <!nodoc>, texlive-latex-base <!nodoc>, xsltproc <!nodoc>, libxml2-utils <!nodoc>, docbook-xsl-ns <!nodoc>
Package-List:
 cpp-14 deb interpreters optional arch=any
 cpp-14-aarch64-linux-gnu deb interpreters optional arch=arm64
 cpp-14-alpha-linux-gnu deb interpreters optional arch=alpha
 cpp-14-arm-linux-gnueabi deb interpreters optional arch=armel
 cpp-14-arm-linux-gnueabihf deb interpreters optional arch=armhf
 cpp-14-for-build deb interpreters optional arch=all
 cpp-14-for-host deb interpreters optional arch=any
 cpp-14-i686-linux-gnu deb interpreters optional arch=i386
 cpp-14-loongarch64-linux-gnu deb interpreters optional arch=loong64
 cpp-14-m68k-linux-gnu deb interpreters optional arch=m68k
 cpp-14-mips64-linux-gnuabi64 deb interpreters optional arch=mips64
 cpp-14-mips64-linux-gnuabin32 deb interpreters optional arch=mipsn32
 cpp-14-mips64el-linux-gnuabi64 deb interpreters optional arch=mips64el
 cpp-14-mips64el-linux-gnuabin32 deb interpreters optional arch=mipsn32el
 cpp-14-mipsel-linux-gnu deb interpreters optional arch=mipsel
 cpp-14-mipsisa32r6-linux-gnu deb interpreters optional arch=mipsr6
 cpp-14-mipsisa32r6el-linux-gnu deb interpreters optional arch=mipsr6el
 cpp-14-mipsisa64r6-linux-gnuabi64 deb interpreters optional arch=mips64r6
 cpp-14-mipsisa64r6-linux-gnuabin32 deb interpreters optional arch=mipsn32r6
 cpp-14-mipsisa64r6el-linux-gnuabi64 deb interpreters optional arch=mips64r6el
 cpp-14-mipsisa64r6el-linux-gnuabin32 deb interpreters optional arch=mipsn32r6el
 cpp-14-powerpc-linux-gnu deb interpreters optional arch=powerpc
 cpp-14-powerpc64-linux-gnu deb interpreters optional arch=ppc64
 cpp-14-powerpc64le-linux-gnu deb interpreters optional arch=ppc64el
 cpp-14-riscv64-linux-gnu deb interpreters optional arch=riscv64
 cpp-14-s390x-linux-gnu deb interpreters optional arch=s390x
 cpp-14-sh4-linux-gnu deb interpreters optional arch=sh4
 cpp-14-sparc-linux-gnu deb interpreters optional arch=sparc
 cpp-14-sparc64-linux-gnu deb interpreters optional arch=sparc64
 cpp-14-x86-64-linux-gnu deb interpreters optional arch=amd64
 cpp-14-x86-64-linux-gnux32 deb interpreters optional arch=x32
 g++-14 deb devel optional arch=any
 g++-14-aarch64-linux-gnu deb devel optional arch=arm64
 g++-14-alpha-linux-gnu deb devel optional arch=alpha
 g++-14-arm-linux-gnueabi deb devel optional arch=armel
 g++-14-arm-linux-gnueabihf deb devel optional arch=armhf
 g++-14-for-build deb devel optional arch=all
 g++-14-for-host deb devel optional arch=any
 g++-14-i686-linux-gnu deb devel optional arch=i386
 g++-14-loongarch64-linux-gnu deb devel optional arch=loong64
 g++-14-m68k-linux-gnu deb devel optional arch=m68k
 g++-14-mips64-linux-gnuabi64 deb devel optional arch=mips64
 g++-14-mips64-linux-gnuabin32 deb devel optional arch=mipsn32
 g++-14-mips64el-linux-gnuabi64 deb devel optional arch=mips64el
 g++-14-mips64el-linux-gnuabin32 deb devel optional arch=mipsn32el
 g++-14-mipsel-linux-gnu deb devel optional arch=mipsel
 g++-14-mipsisa32r6-linux-gnu deb devel optional arch=mipsr6
 g++-14-mipsisa32r6el-linux-gnu deb devel optional arch=mipsr6el
 g++-14-mipsisa64r6-linux-gnuabi64 deb devel optional arch=mips64r6
 g++-14-mipsisa64r6-linux-gnuabin32 deb devel optional arch=mipsn32r6
 g++-14-mipsisa64r6el-linux-gnuabi64 deb devel optional arch=mips64r6el
 g++-14-mipsisa64r6el-linux-gnuabin32 deb devel optional arch=mipsn32r6el
 g++-14-multilib deb devel optional arch=amd64,i386,mips,mips64,mips64el,mips64r6,mips64r6el,mipsel,mipsn32,mipsn32el,mipsn32r6,mipsn32r6el,mipsr6,mipsr6el,powerpc,ppc64,s390,s390x,sparc,sparc64,x32
 g++-14-powerpc-linux-gnu deb devel optional arch=powerpc
 g++-14-powerpc64-linux-gnu deb devel optional arch=ppc64
 g++-14-powerpc64le-linux-gnu deb devel optional arch=ppc64el
 g++-14-riscv64-linux-gnu deb devel optional arch=riscv64
 g++-14-s390x-linux-gnu deb devel optional arch=s390x
 g++-14-sh4-linux-gnu deb devel optional arch=sh4
 g++-14-sparc-linux-gnu deb devel optional arch=sparc
 g++-14-sparc64-linux-gnu deb devel optional arch=sparc64
 g++-14-x86-64-linux-gnu deb devel optional arch=amd64
 g++-14-x86-64-linux-gnux32 deb devel optional arch=x32
 gcc-14 deb devel optional arch=any
 gcc-14-aarch64-linux-gnu deb devel optional arch=arm64
 gcc-14-alpha-linux-gnu deb devel optional arch=alpha
 gcc-14-arm-linux-gnueabi deb devel optional arch=armel
 gcc-14-arm-linux-gnueabihf deb devel optional arch=armhf
 gcc-14-base deb libs optional arch=any
 gcc-14-for-build deb devel optional arch=all
 gcc-14-for-host deb devel optional arch=any
 gcc-14-hppa64-linux-gnu deb devel optional arch=hppa,amd64,i386,x32
 gcc-14-i686-linux-gnu deb devel optional arch=i386
 gcc-14-locales deb devel optional arch=all
 gcc-14-loongarch64-linux-gnu deb devel optional arch=loong64
 gcc-14-m68k-linux-gnu deb devel optional arch=m68k
 gcc-14-mips64-linux-gnuabi64 deb devel optional arch=mips64
 gcc-14-mips64-linux-gnuabin32 deb devel optional arch=mipsn32
 gcc-14-mips64el-linux-gnuabi64 deb devel optional arch=mips64el
 gcc-14-mips64el-linux-gnuabin32 deb devel optional arch=mipsn32el
 gcc-14-mipsel-linux-gnu deb devel optional arch=mipsel
 gcc-14-mipsisa32r6-linux-gnu deb devel optional arch=mipsr6
 gcc-14-mipsisa32r6el-linux-gnu deb devel optional arch=mipsr6el
 gcc-14-mipsisa64r6-linux-gnuabi64 deb devel optional arch=mips64r6
 gcc-14-mipsisa64r6-linux-gnuabin32 deb devel optional arch=mipsn32r6
 gcc-14-mipsisa64r6el-linux-gnuabi64 deb devel optional arch=mips64r6el
 gcc-14-mipsisa64r6el-linux-gnuabin32 deb devel optional arch=mipsn32r6el
 gcc-14-multilib deb devel optional arch=amd64,i386,mips,mips64,mips64el,mips64r6,mips64r6el,mipsel,mipsn32,mipsn32el,mipsn32r6,mipsn32r6el,mipsr6,mipsr6el,powerpc,ppc64,s390,s390x,sparc,sparc64,x32
 gcc-14-offload-amdgcn deb devel optional arch=amd64
 gcc-14-offload-nvptx deb devel optional arch=amd64,arm64,ppc64el
 gcc-14-plugin-dev deb devel optional arch=any
 gcc-14-powerpc-linux-gnu deb devel optional arch=powerpc
 gcc-14-powerpc64-linux-gnu deb devel optional arch=ppc64
 gcc-14-powerpc64le-linux-gnu deb devel optional arch=ppc64el
 gcc-14-riscv64-linux-gnu deb devel optional arch=riscv64
 gcc-14-s390x-linux-gnu deb devel optional arch=s390x
 gcc-14-sh4-linux-gnu deb devel optional arch=sh4
 gcc-14-source deb devel optional arch=all
 gcc-14-sparc-linux-gnu deb devel optional arch=sparc
 gcc-14-sparc64-linux-gnu deb devel optional arch=sparc64
 gcc-14-test-results deb devel optional arch=any
 gcc-14-x86-64-linux-gnu deb devel optional arch=amd64
 gcc-14-x86-64-linux-gnux32 deb devel optional arch=x32
 gccgo-14 deb devel optional arch=any
 gccgo-14-aarch64-linux-gnu deb devel optional arch=arm64
 gccgo-14-alpha-linux-gnu deb devel optional arch=alpha
 gccgo-14-arm-linux-gnueabi deb devel optional arch=armel
 gccgo-14-arm-linux-gnueabihf deb devel optional arch=armhf
 gccgo-14-for-build deb devel optional arch=all
 gccgo-14-for-host deb devel optional arch=any
 gccgo-14-i686-linux-gnu deb devel optional arch=i386
 gccgo-14-mips64-linux-gnuabi64 deb devel optional arch=mips64
 gccgo-14-mips64-linux-gnuabin32 deb devel optional arch=mipsn32
 gccgo-14-mips64el-linux-gnuabi64 deb devel optional arch=mips64el
 gccgo-14-mips64el-linux-gnuabin32 deb devel optional arch=mipsn32el
 gccgo-14-mipsel-linux-gnu deb devel optional arch=mipsel
 gccgo-14-mipsisa32r6-linux-gnu deb devel optional arch=mipsr6
 gccgo-14-mipsisa32r6el-linux-gnu deb devel optional arch=mipsr6el
 gccgo-14-mipsisa64r6-linux-gnuabi64 deb devel optional arch=mips64r6
 gccgo-14-mipsisa64r6-linux-gnuabin32 deb devel optional arch=mipsn32r6
 gccgo-14-mipsisa64r6el-linux-gnuabi64 deb devel optional arch=mips64r6el
 gccgo-14-mipsisa64r6el-linux-gnuabin32 deb devel optional arch=mipsn32r6el
 gccgo-14-multilib deb devel optional arch=amd64,i386,mips,mips64,mips64el,mips64r6,mips64r6el,mipsel,mipsn32,mipsn32el,mipsn32r6,mipsn32r6el,mipsr6,mipsr6el,powerpc,ppc64,s390,s390x,sparc,sparc64,x32
 gccgo-14-powerpc-linux-gnu deb devel optional arch=powerpc
 gccgo-14-powerpc64-linux-gnu deb devel optional arch=ppc64
 gccgo-14-powerpc64le-linux-gnu deb devel optional arch=ppc64el
 gccgo-14-riscv64-linux-gnu deb devel optional arch=riscv64
 gccgo-14-s390x-linux-gnu deb devel optional arch=s390x
 gccgo-14-sparc-linux-gnu deb devel optional arch=sparc
 gccgo-14-sparc64-linux-gnu deb devel optional arch=sparc64
 gccgo-14-x86-64-linux-gnu deb devel optional arch=amd64
 gccgo-14-x86-64-linux-gnux32 deb devel optional arch=x32
 gccrs-14 deb devel optional arch=any
 gccrs-14-aarch64-linux-gnu deb devel optional arch=arm64
 gccrs-14-arm-linux-gnueabi deb devel optional arch=armel
 gccrs-14-arm-linux-gnueabihf deb devel optional arch=armhf
 gccrs-14-for-build deb devel optional arch=all
 gccrs-14-for-host deb devel optional arch=any
 gccrs-14-i686-linux-gnu deb devel optional arch=i386
 gccrs-14-loongarch64-linux-gnu deb devel optional arch=loong64
 gccrs-14-mips64-linux-gnuabi64 deb devel optional arch=mips64
 gccrs-14-mips64-linux-gnuabin32 deb devel optional arch=mipsn32
 gccrs-14-mips64el-linux-gnuabi64 deb devel optional arch=mips64el
 gccrs-14-mips64el-linux-gnuabin32 deb devel optional arch=mipsn32el
 gccrs-14-mipsel-linux-gnu deb devel optional arch=mipsel
 gccrs-14-mipsisa32r6-linux-gnu deb devel optional arch=mipsr6
 gccrs-14-mipsisa32r6el-linux-gnu deb devel optional arch=mipsr6el
 gccrs-14-mipsisa64r6-linux-gnuabi64 deb devel optional arch=mips64r6
 gccrs-14-mipsisa64r6-linux-gnuabin32 deb devel optional arch=mipsn32r6
 gccrs-14-mipsisa64r6el-linux-gnuabi64 deb devel optional arch=mips64r6el
 gccrs-14-mipsisa64r6el-linux-gnuabin32 deb devel optional arch=mipsn32r6el
 gccrs-14-powerpc-linux-gnu deb devel optional arch=powerpc
 gccrs-14-powerpc64-linux-gnu deb devel optional arch=ppc64
 gccrs-14-powerpc64le-linux-gnu deb devel optional arch=ppc64el
 gccrs-14-riscv64-linux-gnu deb devel optional arch=riscv64
 gccrs-14-s390x-linux-gnu deb devel optional arch=s390x
 gccrs-14-sparc-linux-gnu deb devel optional arch=sparc
 gccrs-14-sparc64-linux-gnu deb devel optional arch=sparc64
 gccrs-14-x86-64-linux-gnu deb devel optional arch=amd64
 gdc-14 deb devel optional arch=any
 gdc-14-aarch64-linux-gnu deb devel optional arch=arm64
 gdc-14-arm-linux-gnueabi deb devel optional arch=armel
 gdc-14-arm-linux-gnueabihf deb devel optional arch=armhf
 gdc-14-for-build deb devel optional arch=all
 gdc-14-for-host deb devel optional arch=any
 gdc-14-i686-linux-gnu deb devel optional arch=i386
 gdc-14-loongarch64-linux-gnu deb devel optional arch=loong64
 gdc-14-mips64-linux-gnuabi64 deb devel optional arch=mips64
 gdc-14-mips64-linux-gnuabin32 deb devel optional arch=mipsn32
 gdc-14-mips64el-linux-gnuabi64 deb devel optional arch=mips64el
 gdc-14-mips64el-linux-gnuabin32 deb devel optional arch=mipsn32el
 gdc-14-mipsel-linux-gnu deb devel optional arch=mipsel
 gdc-14-mipsisa32r6-linux-gnu deb devel optional arch=mipsr6
 gdc-14-mipsisa32r6el-linux-gnu deb devel optional arch=mipsr6el
 gdc-14-mipsisa64r6-linux-gnuabi64 deb devel optional arch=mips64r6
 gdc-14-mipsisa64r6-linux-gnuabin32 deb devel optional arch=mipsn32r6
 gdc-14-mipsisa64r6el-linux-gnuabi64 deb devel optional arch=mips64r6el
 gdc-14-mipsisa64r6el-linux-gnuabin32 deb devel optional arch=mipsn32r6el
 gdc-14-multilib deb devel optional arch=any
 gdc-14-powerpc-linux-gnu deb devel optional arch=powerpc
 gdc-14-powerpc64-linux-gnu deb devel optional arch=ppc64
 gdc-14-powerpc64le-linux-gnu deb devel optional arch=ppc64el
 gdc-14-riscv64-linux-gnu deb devel optional arch=riscv64
 gdc-14-s390x-linux-gnu deb devel optional arch=s390x
 gdc-14-sparc-linux-gnu deb devel optional arch=sparc
 gdc-14-x86-64-linux-gnu deb devel optional arch=amd64
 gdc-14-x86-64-linux-gnux32 deb devel optional arch=x32
 gfortran-14 deb devel optional arch=any
 gfortran-14-aarch64-linux-gnu deb devel optional arch=arm64
 gfortran-14-alpha-linux-gnu deb devel optional arch=alpha
 gfortran-14-arm-linux-gnueabi deb devel optional arch=armel
 gfortran-14-arm-linux-gnueabihf deb devel optional arch=armhf
 gfortran-14-for-build deb devel optional arch=all
 gfortran-14-for-host deb devel optional arch=any
 gfortran-14-i686-linux-gnu deb devel optional arch=i386
 gfortran-14-loongarch64-linux-gnu deb devel optional arch=loong64
 gfortran-14-m68k-linux-gnu deb devel optional arch=m68k
 gfortran-14-mips64-linux-gnuabi64 deb devel optional arch=mips64
 gfortran-14-mips64-linux-gnuabin32 deb devel optional arch=mipsn32
 gfortran-14-mips64el-linux-gnuabi64 deb devel optional arch=mips64el
 gfortran-14-mips64el-linux-gnuabin32 deb devel optional arch=mipsn32el
 gfortran-14-mipsel-linux-gnu deb devel optional arch=mipsel
 gfortran-14-mipsisa32r6-linux-gnu deb devel optional arch=mipsr6
 gfortran-14-mipsisa32r6el-linux-gnu deb devel optional arch=mipsr6el
 gfortran-14-mipsisa64r6-linux-gnuabi64 deb devel optional arch=mips64r6
 gfortran-14-mipsisa64r6-linux-gnuabin32 deb devel optional arch=mipsn32r6
 gfortran-14-mipsisa64r6el-linux-gnuabi64 deb devel optional arch=mips64r6el
 gfortran-14-mipsisa64r6el-linux-gnuabin32 deb devel optional arch=mipsn32r6el
 gfortran-14-multilib deb devel optional arch=amd64,i386,mips,mips64,mips64el,mips64r6,mips64r6el,mipsel,mipsn32,mipsn32el,mipsn32r6,mipsn32r6el,mipsr6,mipsr6el,powerpc,ppc64,s390,s390x,sparc,sparc64,x32
 gfortran-14-powerpc-linux-gnu deb devel optional arch=powerpc
 gfortran-14-powerpc64-linux-gnu deb devel optional arch=ppc64
 gfortran-14-powerpc64le-linux-gnu deb devel optional arch=ppc64el
 gfortran-14-riscv64-linux-gnu deb devel optional arch=riscv64
 gfortran-14-s390x-linux-gnu deb devel optional arch=s390x
 gfortran-14-sh4-linux-gnu deb devel optional arch=sh4
 gfortran-14-sparc-linux-gnu deb devel optional arch=sparc
 gfortran-14-sparc64-linux-gnu deb devel optional arch=sparc64
 gfortran-14-x86-64-linux-gnu deb devel optional arch=amd64
 gfortran-14-x86-64-linux-gnux32 deb devel optional arch=x32
 gm2-14 deb devel optional arch=any
 gm2-14-aarch64-linux-gnu deb devel optional arch=arm64
 gm2-14-alpha-linux-gnu deb devel optional arch=alpha
 gm2-14-arm-linux-gnueabi deb devel optional arch=armel
 gm2-14-arm-linux-gnueabihf deb devel optional arch=armhf
 gm2-14-doc deb doc optional arch=all
 gm2-14-for-build deb devel optional arch=all
 gm2-14-for-host deb devel optional arch=any
 gm2-14-i686-linux-gnu deb devel optional arch=i386
 gm2-14-loongarch64-linux-gnu deb devel optional arch=loong64
 gm2-14-m68k-linux-gnu deb devel optional arch=m68k
 gm2-14-mips64-linux-gnuabi64 deb devel optional arch=mips64
 gm2-14-mips64-linux-gnuabin32 deb devel optional arch=mipsn32
 gm2-14-mips64el-linux-gnuabi64 deb devel optional arch=mips64el
 gm2-14-mips64el-linux-gnuabin32 deb devel optional arch=mipsn32el
 gm2-14-mipsel-linux-gnu deb devel optional arch=mipsel
 gm2-14-mipsisa32r6-linux-gnu deb devel optional arch=mipsr6
 gm2-14-mipsisa32r6el-linux-gnu deb devel optional arch=mipsr6el
 gm2-14-mipsisa64r6-linux-gnuabi64 deb devel optional arch=mips64r6
 gm2-14-mipsisa64r6-linux-gnuabin32 deb devel optional arch=mipsn32r6
 gm2-14-mipsisa64r6el-linux-gnuabi64 deb devel optional arch=mips64r6el
 gm2-14-mipsisa64r6el-linux-gnuabin32 deb devel optional arch=mipsn32r6el
 gm2-14-powerpc64le-linux-gnu deb devel optional arch=ppc64el
 gm2-14-riscv64-linux-gnu deb devel optional arch=riscv64
 gm2-14-s390x-linux-gnu deb devel optional arch=s390x
 gm2-14-sparc-linux-gnu deb devel optional arch=sparc
 gm2-14-sparc64-linux-gnu deb devel optional arch=sparc64
 gm2-14-x86-64-linux-gnu deb devel optional arch=amd64
 gm2-14-x86-64-linux-gnux32 deb devel optional arch=x32
 gnat-14 deb devel optional arch=any
 gnat-14-aarch64-linux-gnu deb devel optional arch=arm64
 gnat-14-alpha-linux-gnu deb devel optional arch=alpha
 gnat-14-arm-linux-gnueabi deb devel optional arch=armel
 gnat-14-arm-linux-gnueabihf deb devel optional arch=armhf
 gnat-14-for-build deb devel optional arch=all
 gnat-14-for-host deb devel optional arch=any
 gnat-14-i686-linux-gnu deb devel optional arch=i386
 gnat-14-loongarch64-linux-gnu deb devel optional arch=loong64
 gnat-14-m68k-linux-gnu deb devel optional arch=m68k
 gnat-14-mips64-linux-gnuabi64 deb devel optional arch=mips64
 gnat-14-mips64-linux-gnuabin32 deb devel optional arch=mipsn32
 gnat-14-mips64el-linux-gnuabi64 deb devel optional arch=mips64el
 gnat-14-mips64el-linux-gnuabin32 deb devel optional arch=mipsn32el
 gnat-14-mipsel-linux-gnu deb devel optional arch=mipsel
 gnat-14-mipsisa32r6-linux-gnu deb devel optional arch=mipsr6
 gnat-14-mipsisa32r6el-linux-gnu deb devel optional arch=mipsr6el
 gnat-14-mipsisa64r6-linux-gnuabi64 deb devel optional arch=mips64r6
 gnat-14-mipsisa64r6-linux-gnuabin32 deb devel optional arch=mipsn32r6
 gnat-14-mipsisa64r6el-linux-gnuabi64 deb devel optional arch=mips64r6el
 gnat-14-mipsisa64r6el-linux-gnuabin32 deb devel optional arch=mipsn32r6el
 gnat-14-powerpc-linux-gnu deb devel optional arch=powerpc
 gnat-14-powerpc64-linux-gnu deb devel optional arch=ppc64
 gnat-14-powerpc64le-linux-gnu deb devel optional arch=ppc64el
 gnat-14-riscv64-linux-gnu deb devel optional arch=riscv64
 gnat-14-s390x-linux-gnu deb devel optional arch=s390x
 gnat-14-sh4-linux-gnu deb devel optional arch=sh4
 gnat-14-sparc-linux-gnu deb devel optional arch=sparc
 gnat-14-sparc64-linux-gnu deb devel optional arch=sparc64
 gnat-14-x86-64-linux-gnu deb devel optional arch=amd64
 gnat-14-x86-64-linux-gnux32 deb devel optional arch=x32
 gobjc++-14 deb devel optional arch=any
 gobjc++-14-aarch64-linux-gnu deb devel optional arch=arm64
 gobjc++-14-alpha-linux-gnu deb devel optional arch=alpha
 gobjc++-14-arm-linux-gnueabi deb devel optional arch=armel
 gobjc++-14-arm-linux-gnueabihf deb devel optional arch=armhf
 gobjc++-14-for-build deb devel optional arch=all
 gobjc++-14-for-host deb devel optional arch=any
 gobjc++-14-i686-linux-gnu deb devel optional arch=i386
 gobjc++-14-loongarch64-linux-gnu deb devel optional arch=loong64
 gobjc++-14-m68k-linux-gnu deb devel optional arch=m68k
 gobjc++-14-mips64-linux-gnuabi64 deb devel optional arch=mips64
 gobjc++-14-mips64-linux-gnuabin32 deb devel optional arch=mipsn32
 gobjc++-14-mips64el-linux-gnuabi64 deb devel optional arch=mips64el
 gobjc++-14-mips64el-linux-gnuabin32 deb devel optional arch=mipsn32el
 gobjc++-14-mipsel-linux-gnu deb devel optional arch=mipsel
 gobjc++-14-mipsisa32r6-linux-gnu deb devel optional arch=mipsr6
 gobjc++-14-mipsisa32r6el-linux-gnu deb devel optional arch=mipsr6el
 gobjc++-14-mipsisa64r6-linux-gnuabi64 deb devel optional arch=mips64r6
 gobjc++-14-mipsisa64r6-linux-gnuabin32 deb devel optional arch=mipsn32r6
 gobjc++-14-mipsisa64r6el-linux-gnuabi64 deb devel optional arch=mips64r6el
 gobjc++-14-mipsisa64r6el-linux-gnuabin32 deb devel optional arch=mipsn32r6el
 gobjc++-14-multilib deb devel optional arch=amd64,i386,mips,mips64,mips64el,mips64r6,mips64r6el,mipsel,mipsn32,mipsn32el,mipsn32r6,mipsn32r6el,mipsr6,mipsr6el,powerpc,ppc64,s390,s390x,sparc,sparc64,x32
 gobjc++-14-powerpc-linux-gnu deb devel optional arch=powerpc
 gobjc++-14-powerpc64-linux-gnu deb devel optional arch=ppc64
 gobjc++-14-powerpc64le-linux-gnu deb devel optional arch=ppc64el
 gobjc++-14-riscv64-linux-gnu deb devel optional arch=riscv64
 gobjc++-14-s390x-linux-gnu deb devel optional arch=s390x
 gobjc++-14-sh4-linux-gnu deb devel optional arch=sh4
 gobjc++-14-sparc-linux-gnu deb devel optional arch=sparc
 gobjc++-14-sparc64-linux-gnu deb devel optional arch=sparc64
 gobjc++-14-x86-64-linux-gnu deb devel optional arch=amd64
 gobjc++-14-x86-64-linux-gnux32 deb devel optional arch=x32
 gobjc-14 deb devel optional arch=any
 gobjc-14-aarch64-linux-gnu deb devel optional arch=arm64
 gobjc-14-alpha-linux-gnu deb devel optional arch=alpha
 gobjc-14-arm-linux-gnueabi deb devel optional arch=armel
 gobjc-14-arm-linux-gnueabihf deb devel optional arch=armhf
 gobjc-14-for-build deb devel optional arch=all
 gobjc-14-for-host deb devel optional arch=any
 gobjc-14-i686-linux-gnu deb devel optional arch=i386
 gobjc-14-loongarch64-linux-gnu deb devel optional arch=loong64
 gobjc-14-m68k-linux-gnu deb devel optional arch=m68k
 gobjc-14-mips64-linux-gnuabi64 deb devel optional arch=mips64
 gobjc-14-mips64-linux-gnuabin32 deb devel optional arch=mipsn32
 gobjc-14-mips64el-linux-gnuabi64 deb devel optional arch=mips64el
 gobjc-14-mips64el-linux-gnuabin32 deb devel optional arch=mipsn32el
 gobjc-14-mipsel-linux-gnu deb devel optional arch=mipsel
 gobjc-14-mipsisa32r6-linux-gnu deb devel optional arch=mipsr6
 gobjc-14-mipsisa32r6el-linux-gnu deb devel optional arch=mipsr6el
 gobjc-14-mipsisa64r6-linux-gnuabi64 deb devel optional arch=mips64r6
 gobjc-14-mipsisa64r6-linux-gnuabin32 deb devel optional arch=mipsn32r6
 gobjc-14-mipsisa64r6el-linux-gnuabi64 deb devel optional arch=mips64r6el
 gobjc-14-mipsisa64r6el-linux-gnuabin32 deb devel optional arch=mipsn32r6el
 gobjc-14-multilib deb devel optional arch=amd64,i386,mips,mips64,mips64el,mips64r6,mips64r6el,mipsel,mipsn32,mipsn32el,mipsn32r6,mipsn32r6el,mipsr6,mipsr6el,powerpc,ppc64,s390,s390x,sparc,sparc64,x32
 gobjc-14-powerpc-linux-gnu deb devel optional arch=powerpc
 gobjc-14-powerpc64-linux-gnu deb devel optional arch=ppc64
 gobjc-14-powerpc64le-linux-gnu deb devel optional arch=ppc64el
 gobjc-14-riscv64-linux-gnu deb devel optional arch=riscv64
 gobjc-14-s390x-linux-gnu deb devel optional arch=s390x
 gobjc-14-sh4-linux-gnu deb devel optional arch=sh4
 gobjc-14-sparc-linux-gnu deb devel optional arch=sparc
 gobjc-14-sparc64-linux-gnu deb devel optional arch=sparc64
 gobjc-14-x86-64-linux-gnu deb devel optional arch=amd64
 gobjc-14-x86-64-linux-gnux32 deb devel optional arch=x32
 lib32asan8 deb libs optional arch=amd64,ppc64,s390x,sparc64,x32,mipsn32,mipsn32el,mips64,mips64el,mipsn32r6,mipsn32r6el,mips64r6,mips64r6el
 lib32atomic1 deb libs optional arch=amd64,ppc64,s390x,sparc64,x32,mipsn32,mipsn32el,mips64,mips64el,mipsn32r6,mipsn32r6el,mips64r6,mips64r6el
 lib32gcc-14-dev deb libdevel optional arch=amd64,ppc64,s390x,sparc64,x32,mipsn32,mipsn32el,mips64,mips64el,mipsn32r6,mipsn32r6el,mips64r6,mips64r6el
 lib32gcc-s1 deb libs optional arch=amd64,ppc64,s390x,sparc64,x32,mipsn32,mipsn32el,mips64,mips64el,mipsn32r6,mipsn32r6el,mips64r6,mips64r6el
 lib32gfortran-14-dev deb libdevel optional arch=amd64,ppc64,s390x,sparc64,x32,mipsn32,mipsn32el,mips64,mips64el,mipsn32r6,mipsn32r6el,mips64r6,mips64r6el
 lib32gfortran5 deb libs optional arch=amd64,ppc64,s390x,sparc64,x32,mipsn32,mipsn32el,mips64,mips64el,mipsn32r6,mipsn32r6el,mips64r6,mips64r6el
 lib32go-14-dev deb libdevel optional arch=amd64,ppc64,s390x,sparc64,x32,mipsn32,mipsn32el,mips64,mips64el,mipsn32r6,mipsn32r6el,mips64r6,mips64r6el
 lib32go23 deb libs optional arch=amd64,ppc64,s390x,sparc64,x32,mipsn32,mipsn32el,mips64,mips64el,mipsn32r6,mipsn32r6el,mips64r6,mips64r6el
 lib32gomp1 deb libs optional arch=amd64,ppc64,s390x,sparc64,x32,mipsn32,mipsn32el,mips64,mips64el,mipsn32r6,mipsn32r6el,mips64r6,mips64r6el
 lib32gphobos-14-dev deb libdevel optional arch=amd64,ppc64,s390x,sparc64,x32,mipsn32,mipsn32el,mips64,mips64el,mipsn32r6,mipsn32r6el,mips64r6,mips64r6el
 lib32gphobos5 deb libs optional arch=amd64,ppc64,s390x,sparc64,x32,mipsn32,mipsn32el,mips64,mips64el,mipsn32r6,mipsn32r6el,mips64r6,mips64r6el
 lib32itm1 deb libs optional arch=amd64,ppc64,s390x,sparc64,x32,mipsn32,mipsn32el,mips64,mips64el,mipsn32r6,mipsn32r6el,mips64r6,mips64r6el
 lib32objc-14-dev deb libdevel optional arch=amd64,ppc64,s390x,sparc64,x32,mipsn32,mipsn32el,mips64,mips64el,mipsn32r6,mipsn32r6el,mips64r6,mips64r6el
 lib32objc4 deb libs optional arch=amd64,ppc64,s390x,sparc64,x32,mipsn32,mipsn32el,mips64,mips64el,mipsn32r6,mipsn32r6el,mips64r6,mips64r6el
 lib32quadmath0 deb libs optional arch=amd64,ppc64,s390x,sparc64,x32,mipsn32,mipsn32el,mips64,mips64el,mipsn32r6,mipsn32r6el,mips64r6,mips64r6el
 lib32stdc++-14-dev deb libdevel optional arch=amd64,ppc64,s390x,sparc64,x32,mipsn32,mipsn32el,mips64,mips64el,mipsn32r6,mipsn32r6el,mips64r6,mips64r6el
 lib32stdc++6 deb libs optional arch=amd64,ppc64,s390x,sparc64,x32,mipsn32,mipsn32el,mips64,mips64el,mipsn32r6,mipsn32r6el,mips64r6,mips64r6el
 lib32stdc++6-14-dbg deb debug optional arch=amd64,ppc64,s390x,sparc64,x32,mipsn32,mipsn32el,mips64,mips64el,mipsn32r6,mipsn32r6el,mips64r6,mips64r6el
 lib32ubsan1 deb libs optional arch=amd64,ppc64,s390x,sparc64,x32,mipsn32,mipsn32el,mips64,mips64el,mipsn32r6,mipsn32r6el,mips64r6,mips64r6el
 lib64asan8 deb libs optional arch=i386,powerpc,sparc,s390,mips,mipsel,mipsn32,mipsn32el,mipsr6,mipsr6el,mipsn32r6,mipsn32r6el,x32
 lib64atomic1 deb libs optional arch=i386,powerpc,sparc,s390,mips,mipsel,mipsn32,mipsn32el,mipsr6,mipsr6el,mipsn32r6,mipsn32r6el,x32
 lib64gcc-14-dev deb libdevel optional arch=i386,powerpc,sparc,s390,mips,mipsel,mipsn32,mipsn32el,mipsr6,mipsr6el,mipsn32r6,mipsn32r6el,x32
 lib64gcc-s1 deb libs optional arch=i386,powerpc,sparc,s390,mips,mipsel,mipsn32,mipsn32el,mipsr6,mipsr6el,mipsn32r6,mipsn32r6el,x32
 lib64gfortran-14-dev deb libdevel optional arch=i386,powerpc,sparc,s390,mips,mipsel,mipsn32,mipsn32el,mipsr6,mipsr6el,mipsn32r6,mipsn32r6el,x32
 lib64gfortran5 deb libs optional arch=i386,powerpc,sparc,s390,mips,mipsel,mipsn32,mipsn32el,mipsr6,mipsr6el,mipsn32r6,mipsn32r6el,x32
 lib64go-14-dev deb libdevel optional arch=i386,powerpc,sparc,s390,mips,mipsel,mipsn32,mipsn32el,mipsr6,mipsr6el,mipsn32r6,mipsn32r6el,x32
 lib64go23 deb libs optional arch=i386,powerpc,sparc,s390,mips,mipsel,mipsn32,mipsn32el,mipsr6,mipsr6el,mipsn32r6,mipsn32r6el,x32
 lib64gomp1 deb libs optional arch=i386,powerpc,sparc,s390,mips,mipsel,mipsn32,mipsn32el,mipsr6,mipsr6el,mipsn32r6,mipsn32r6el,x32
 lib64gphobos-14-dev deb libdevel optional arch=i386,powerpc,sparc,s390,mips,mipsel,mipsn32,mipsn32el,mipsr6,mipsr6el,mipsn32r6,mipsn32r6el,x32
 lib64gphobos5 deb libs optional arch=i386,powerpc,sparc,s390,mips,mipsel,mipsn32,mipsn32el,mipsr6,mipsr6el,mipsn32r6,mipsn32r6el,x32
 lib64itm1 deb libs optional arch=i386,powerpc,sparc,s390,mips,mipsel,mipsn32,mipsn32el,mipsr6,mipsr6el,mipsn32r6,mipsn32r6el,x32
 lib64objc-14-dev deb libdevel optional arch=i386,powerpc,sparc,s390,mips,mipsel,mipsn32,mipsn32el,mipsr6,mipsr6el,mipsn32r6,mipsn32r6el,x32
 lib64objc4 deb libs optional arch=i386,powerpc,sparc,s390,mips,mipsel,mipsn32,mipsn32el,mipsr6,mipsr6el,mipsn32r6,mipsn32r6el,x32
 lib64quadmath0 deb libs optional arch=i386,powerpc,sparc,s390,mips,mipsel,mipsn32,mipsn32el,mipsr6,mipsr6el,mipsn32r6,mipsn32r6el,x32
 lib64stdc++-14-dev deb libdevel optional arch=i386,powerpc,sparc,s390,mips,mipsel,mipsn32,mipsn32el,mipsr6,mipsr6el,mipsn32r6,mipsn32r6el,x32
 lib64stdc++6 deb libs optional arch=i386,powerpc,sparc,s390,mips,mipsel,mipsn32,mipsn32el,mipsr6,mipsr6el,mipsn32r6,mipsn32r6el,x32
 lib64stdc++6-14-dbg deb debug optional arch=i386,powerpc,sparc,s390,mips,mipsel,mipsn32,mipsn32el,mipsr6,mipsr6el,mipsn32r6,mipsn32r6el,x32
 lib64ubsan1 deb libs optional arch=i386,powerpc,sparc,s390,mips,mipsel,mipsn32,mipsn32el,mipsr6,mipsr6el,mipsn32r6,mipsn32r6el,x32
 libasan8 deb libs optional arch=any
 libatomic1 deb libs optional arch=any
 libcc1-0 deb libs optional arch=any
 libgcc-14-dev deb libdevel optional arch=any
 libgcc-s1 deb libs optional arch=any protected=yes
 libgcc-s2 deb libs optional arch=m68k protected=yes
 libgcc-s4 deb libs optional arch=hppa protected=yes
 libgccjit-14-dev deb libdevel optional arch=any
 libgccjit-14-doc deb doc optional arch=all
 libgccjit0 deb libs optional arch=any
 libgfortran-14-dev deb libdevel optional arch=any
 libgfortran5 deb libs optional arch=any
 libgm2-14-dev deb libdevel optional arch=any
 libgm2-19 deb libs optional arch=any
 libgnat-14 deb libs optional arch=any
 libgo-14-dev deb libdevel optional arch=any
 libgo23 deb libs optional arch=any
 libgomp-plugin-amdgcn1 deb libs optional arch=amd64
 libgomp-plugin-nvptx1 deb libs optional arch=amd64,arm64,ppc64el
 libgomp1 deb libs optional arch=any
 libgphobos-14-dev deb libdevel optional arch=amd64,arm64,armel,armhf,i386,x32,hppa,mips,mips64,mipsel,mips64el,mipsn32,mipsn32el,mipsr6,mipsr6el,mipsn32r6,mipsn32r6el,mips64r6,mips64r6el,riscv64,s390x,loong64,powerpc,ppc64,ppc64el
 libgphobos5 deb libs optional arch=amd64,arm64,armel,armhf,i386,x32,hppa,mips,mips64,mipsel,mips64el,mipsn32,mipsn32el,mipsr6,mipsr6el,mipsn32r6,mipsn32r6el,mips64r6,mips64r6el,riscv64,s390x,loong64,powerpc,ppc64,ppc64el
 libhwasan0 deb libs optional arch=any
 libitm1 deb libs optional arch=any
 liblsan0 deb libs optional arch=any
 libn32atomic1 deb libs optional arch=mips,mipsel,mips64,mips64el,mipsr6,mipsr6el,mips64r6,mips64r6el
 libn32gcc-14-dev deb libdevel optional arch=mips,mipsel,mips64,mips64el,mipsr6,mipsr6el,mips64r6,mips64r6el
 libn32gcc-s1 deb libs optional arch=mips,mipsel,mips64,mips64el,mipsr6,mipsr6el,mips64r6,mips64r6el
 libn32gfortran-14-dev deb libdevel optional arch=mips,mipsel,mips64,mips64el,mipsr6,mipsr6el,mips64r6,mips64r6el
 libn32gfortran5 deb libs optional arch=mips,mipsel,mips64,mips64el,mipsr6,mipsr6el,mips64r6,mips64r6el
 libn32go-14-dev deb libdevel optional arch=mips,mipsel,mips64,mips64el,mipsr6,mipsr6el,mips64r6,mips64r6el
 libn32go23 deb libs optional arch=mips,mipsel,mips64,mips64el,mipsr6,mipsr6el,mips64r6,mips64r6el
 libn32gomp1 deb libs optional arch=mips,mipsel,mips64,mips64el,mipsr6,mipsr6el,mips64r6,mips64r6el
 libn32gphobos-14-dev deb libdevel optional arch=mips,mipsel,mips64,mips64el,mipsr6,mipsr6el,mips64r6,mips64r6el
 libn32gphobos5 deb libs optional arch=mips,mipsel,mips64,mips64el,mipsr6,mipsr6el,mips64r6,mips64r6el
 libn32objc-14-dev deb libdevel optional arch=mips,mipsel,mips64,mips64el,mipsr6,mipsr6el,mips64r6,mips64r6el
 libn32objc4 deb libs optional arch=mips,mipsel,mips64,mips64el,mipsr6,mipsr6el,mips64r6,mips64r6el
 libn32stdc++-14-dev deb libdevel optional arch=mips,mipsel,mips64,mips64el,mipsr6,mipsr6el,mips64r6,mips64r6el
 libn32stdc++6 deb libs optional arch=mips,mipsel,mips64,mips64el,mipsr6,mipsr6el,mips64r6,mips64r6el
 libn32stdc++6-14-dbg deb debug optional arch=mips,mipsel,mips64,mips64el,mipsr6,mipsr6el,mips64r6,mips64r6el
 libobjc-14-dev deb libdevel optional arch=any
 libobjc4 deb libs optional arch=any
 libquadmath0 deb libs optional arch=any
 libstdc++-14-dev deb libdevel optional arch=any
 libstdc++-14-doc deb doc optional arch=all profile=!nodoc
 libstdc++-14-pic deb libdevel optional arch=any
 libstdc++6 deb libs optional arch=any
 libstdc++6-14-dbg deb debug optional arch=any
 libtsan2 deb libs optional arch=any
 libubsan1 deb libs optional arch=any
 libx32asan8 deb libs optional arch=amd64,i386
 libx32atomic1 deb libs optional arch=amd64,i386
 libx32gcc-14-dev deb libdevel optional arch=amd64,i386
 libx32gcc-s1 deb libs optional arch=amd64,i386
 libx32gfortran-14-dev deb libdevel optional arch=amd64,i386
 libx32gfortran5 deb libs optional arch=amd64,i386
 libx32go-14-dev deb libdevel optional arch=amd64,i386
 libx32go23 deb libs optional arch=amd64,i386
 libx32gomp1 deb libs optional arch=amd64,i386
 libx32gphobos-14-dev deb libdevel optional arch=amd64,i386
 libx32gphobos5 deb libs optional arch=amd64,i386
 libx32itm1 deb libs optional arch=amd64,i386
 libx32objc-14-dev deb libdevel optional arch=amd64,i386
 libx32objc4 deb libs optional arch=amd64,i386
 libx32quadmath0 deb libs optional arch=amd64,i386
 libx32stdc++-14-dev deb libdevel optional arch=amd64,i386
 libx32stdc++6 deb libs optional arch=amd64,i386
 libx32stdc++6-14-dbg deb debug optional arch=amd64,i386
 libx32ubsan1 deb libs optional arch=amd64,i386
Checksums-Sha1:
 40f4fae7a993865facd67321c28b95ef19aa7ab1 94299633 gcc-14_14.2.0.orig.tar.gz
 47c1fc8b2b6a5643058a6d903ba5734cae7c03b7 2577016 gcc-14_14.2.0-19.debian.tar.xz
Checksums-Sha256:
 e162a5ef3f0077ecae598c6556908d2ab80841532df3398072a96a6df6e6aa29 94299633 gcc-14_14.2.0.orig.tar.gz
 31cbab4bafa48aa1c540497bd8098cbcf98e673778f927651ed944709f687781 2577016 gcc-14_14.2.0-19.debian.tar.xz
Files:
 772d9fc5b68fb1cf83240b81cedbfbf9 94299633 gcc-14_14.2.0.orig.tar.gz
 627308eae31c102450c23c3c0e851584 2577016 gcc-14_14.2.0-19.debian.tar.xz

-----BEGIN PGP SIGNATURE-----

iQJEBAEBCgAuFiEE1WVxuIqLuvFAv2PWvX6qYHePpvUFAmfU4twQHGRva29AZGVi
aWFuLm9yZwAKCRC9fqpgd4+m9ddhEACBXUN2L4jlA1OqnpgbY5UBCsGKvsJeFJik
S3hU20kduFkjoJa/Sb/QtV7sINHOxmTRxM7923Q/6nn2RUURWRX0Q4n/FlRSREep
sO1H7DR7hUL//IRS/PHISj86uU3/fZUV27I1fGHTNgXSr9FkqSvGQRDs6q1QCDdi
Oi4Wiu9WSnPIqfs7JtCQzxS2QeNkzuOC/lrJlQRX9yjo2jHGYE8qbgug+bTiPsPk
qUwC0aGOzTKal7mHk8CwlasvXXQFEjEsYELSNfc4QZg2EmLzhojm/CNdA6X17hq8
h9TIaHDkK6AweSQYyDnp6nMdm2kYfF8qYNLelQBzCgmmO0kUH+mZKkhUDuF2Vvrr
V7Ex173BISwYgI5ikbSE6kngo6XD+6X46gz0mayd0XQpJPjEQ4CUnlWlI4RrZw12
yUMam14lm5o4UejlhyE0GQdCCU3MrzMnkUrniISwhd0e6dOZHzRHCWiFbe6e4TJd
Dp8t6SA5uikJQvj4T5FBK0HjC3nv+m9QWtL38037OuxoRk+gLiM/0AwwrOhkFFMm
PqF600GvKXZXFFJmYW5kp4K2R9ruPjD9mBkcuS4d6TRwgkr5n9YxZXr28PiuVKfW
v4j0jMOUHxzrElpgO4ngHr6/lJuNlT77BSK8WVPcMIyLL5ZnRy19Y5Rp00TMB18N
YGqCuQSP/A==
=XM/C
-----END PGP SIGNATURE-----
