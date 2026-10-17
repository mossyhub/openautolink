-----BEGIN PGP SIGNED MESSAGE-----
Hash: SHA512

Format: 3.0 (quilt)
Source: gcc-12
Binary: gcc-12-base, libgcc-s1, libgcc-s2, libgcc-s4, libgcc-12-dev, lib64gcc-s1, lib64gcc-12-dev, lib32gcc-s1, lib32gcc-12-dev, libn32gcc-s1, libn32gcc-12-dev, libx32gcc-s1, libx32gcc-12-dev, gcc-12, gcc-12-multilib, gcc-12-test-results, gcc-12-plugin-dev, gcc-12-hppa64-linux-gnu, cpp-12, cpp-12-doc, gcc-12-locales, g++-12, g++-12-multilib, libgomp1, lib32gomp1, lib64gomp1, libn32gomp1, libx32gomp1, libitm1, lib32itm1, lib64itm1, libx32itm1, libatomic1, lib32atomic1, lib64atomic1, libn32atomic1, libx32atomic1, libasan8, lib32asan8, lib64asan8, libx32asan8, liblsan0, lib32lsan0, libx32lsan0, libtsan2, libubsan1, lib32ubsan1, lib64ubsan1, libx32ubsan1, libquadmath0, lib32quadmath0, lib64quadmath0, libx32quadmath0, libcc1-0, libgccjit0, libgccjit-12-doc, libgccjit-12-dev, gobjc++-12, gobjc++-12-multilib, gobjc-12, gobjc-12-multilib, libobjc-12-dev, lib64objc-12-dev, lib32objc-12-dev, libn32objc-12-dev, libx32objc-12-dev, libobjc4, lib64objc4, lib32objc4, libn32objc4,
 libx32objc4, gfortran-12, gfortran-12-multilib, gfortran-12-doc, libgfortran-12-dev, lib64gfortran-12-dev, lib32gfortran-12-dev, libn32gfortran-12-dev, libx32gfortran-12-dev, libgfortran5, lib64gfortran5, lib32gfortran5, libn32gfortran5, libx32gfortran5, gccgo-12, gccgo-12-multilib, gccgo-12-doc, libgo-12-dev, lib64go-12-dev, lib32go-12-dev, libn32go-12-dev, libx32go-12-dev, libgo21, lib64go21, lib32go21, libn32go21, libx32go21, libstdc++6, lib32stdc++6, lib64stdc++6, libn32stdc++6, libx32stdc++6, libstdc++-12-dev, libstdc++-12-pic, libstdc++6-12-dbg, lib32stdc++-12-dev, lib32stdc++6-12-dbg, lib64stdc++-12-dev, lib64stdc++6-12-dbg, libn32stdc++-12-dev, libn32stdc++6-12-dbg, libx32stdc++-12-dev, libx32stdc++6-12-dbg, libstdc++-12-doc, gnat-12, libgnat-12, gnat-12-doc, gdc-12, gdc-12-multilib, libgphobos-12-dev, lib64gphobos-12-dev, lib32gphobos-12-dev, libn32gphobos-12-dev, libx32gphobos-12-dev, libgphobos3, lib64gphobos3, lib32gphobos3, libn32gphobos3,
 libx32gphobos3, gm2-12, libgm2-12-dev, libgm2-17, gm2-12-doc, gcc-12-doc, gcc-12-offload-nvptx, libgomp-plugin-nvptx1, gcc-12-offload-amdgcn, libgomp-plugin-amdgcn1,
 gcc-12-source
Architecture: any all
Version: 12.3.0-1ubuntu1~22.04.3
Maintainer: Ubuntu Developers <ubuntu-devel-discuss@lists.ubuntu.com>
Uploaders: Matthias Klose <doko@debian.org>
Homepage: http://gcc.gnu.org/
Standards-Version: 4.6.2
Vcs-Browser: https://salsa.debian.org/toolchain-team/gcc/tree/gcc-12-debian
Vcs-Git: https://salsa.debian.org/toolchain-team/gcc.git -b gcc-12-debian
Testsuite: autopkgtest
Testsuite-Triggers: apt, libc-dev, libc6-dev, python3-minimal
Build-Depends: debhelper (>= 9.20141010), dpkg-dev (>= 1.17.14), g++-multilib [amd64 i386 kfreebsd-amd64 mips mips64 mips64el mips64r6 mips64r6el mipsel mipsn32 mipsn32el mipsn32r6 mipsn32r6el mipsr6 mipsr6el powerpc ppc64 s390 s390x sparc sparc64 x32] <!cross>, libc6.1-dev (>= 2.13-0ubuntu6) [alpha ia64] | libc0.3-dev (>= 2.13-0ubuntu6) [hurd-i386] | libc0.1-dev (>= 2.25) [kfreebsd-i386 kfreebsd-amd64] | libc6-dev (>= 2.13-0ubuntu6), libc6-dev-amd64 [i386 x32], libc6-dev-sparc64 [sparc], libc6-dev-sparc [sparc64], libc6-dev-s390 [s390x], libc6-dev-s390x [s390], libc6-dev-i386 [amd64 x32], libc6-dev-powerpc [ppc64], libc6-dev-ppc64 [powerpc], libc0.1-dev-i386 [kfreebsd-amd64], lib32gcc-s1 [amd64 ppc64 kfreebsd-amd64 mipsn32 mipsn32el mips64 mips64el mipsn32r6 mipsn32r6el mips64r6 mips64r6el s390x sparc64 x32], libn32gcc-s1 [mips mipsel mips64 mips64el mipsr6 mipsr6el mips64r6 mips64r6el], lib64gcc-s1 [i386 mips mipsel mipsn32 mipsn32el mipsr6 mipsr6el mipsn32r6 mipsn32r6el powerpc sparc s390 x32], libc6-dev-mips64 [mips mipsel mipsn32 mipsn32el mipsr6 mipsr6el mipsn32r6 mipsn32r6el], libc6-dev-mipsn32 [mips mipsel mips64 mips64el mipsr6 mipsr6el mips64r6 mips64r6el], libc6-dev-mips32 [mipsn32 mipsn32el mips64 mips64el mipsn32r6 mipsn32r6el mips64r6 mips64r6el], libc6-dev-x32 [amd64 i386], libx32gcc-s1 [amd64 i386], libc6.1-dbg [alpha ia64] | libc0.3-dbg [hurd-i386] | libc0.1-dbg [kfreebsd-i386 kfreebsd-amd64] | libc6-dbg, kfreebsd-kernel-headers (>= 0.84) [kfreebsd-any], linux-libc-dev [m68k], m4, libtool, autoconf2.69, dwz, libunwind8-dev [ia64], libatomic-ops-dev [ia64], gawk, lzma, xz-utils, patchutils, libzstd-dev, zlib1g-dev, systemtap-sdt-dev [linux-any], binutils:native (>= 2.37), binutils-hppa64-linux-gnu:native (>= 2.37) [hppa amd64 i386 x32], gperf (>= 3.0.1), bison (>= 1:2.3), flex, gettext, gdb:native [!riscv64 !mipsel !mips64el], nvptx-tools [amd64 ppc64el], amdgcn-tools [amd64], texinfo (>= 4.3), locales-all, sharutils, procps, gnat-11:native [!m32r !sh3 !sh3eb !sh4eb !arc !ia64 !alpha !m68k], g++-11:native, gnat-12 [alpha m68k], g++-12:native [alpha m68k], netbase, gdc-11:native [!arc !ia64 !m68k !sh4 !s390 !sparc64 !alpha !hurd-alpha !hurd-amd64 !hurd-i386 !hurd-alpha !kfreebsd-amd64 !kfreebsd-i386 !kfreebsd-alpha], python3:any, libisl-dev (>= 0.20), libmpc-dev (>= 1.0), libmpfr-dev (>= 3.0.0-9~), libgmp-dev (>= 2:5.0.1~), lib32z1-dev [amd64 kfreebsd-amd64], lib64z1-dev [i386], libx32z1-dev [amd64 kfreebsd-amd64 i386], unzip <!nocheck>, dejagnu <!nocheck>, coreutils (>= 2.26) | realpath (>= 1.9.12), chrpath, lsb-release, quilt, time, pkg-config, libgc-dev, g++-12-alpha-linux-gnu [alpha] <cross>, gobjc-12-alpha-linux-gnu [alpha] <cross>, gfortran-12-alpha-linux-gnu [alpha] <cross>, gdc-12-alpha-linux-gnu [alpha] <cross>, gccgo-12-alpha-linux-gnu [alpha] <cross>, gnat-12-alpha-linux-gnu [alpha] <cross>, gm2-12-alpha-linux-gnu [alpha] <cross>, g++-12-x86-64-linux-gnu [amd64] <cross>, gobjc-12-x86-64-linux-gnu [amd64] <cross>, gfortran-12-x86-64-linux-gnu [amd64] <cross>, gdc-12-x86-64-linux-gnu [amd64] <cross>, gccgo-12-x86-64-linux-gnu [amd64] <cross>, gnat-12-x86-64-linux-gnu [amd64] <cross>, gm2-12-x86-64-linux-gnu [amd64] <cross>, g++-12-arm-linux-gnueabi [armel] <cross>, gobjc-12-arm-linux-gnueabi [armel] <cross>, gfortran-12-arm-linux-gnueabi [armel] <cross>, gdc-12-arm-linux-gnueabi [armel] <cross>, gccgo-12-arm-linux-gnueabi [armel] <cross>, gnat-12-arm-linux-gnueabi [armel] <cross>, gm2-12-arm-linux-gnueabi [armel] <cross>, g++-12-arm-linux-gnueabihf [armhf] <cross>, gobjc-12-arm-linux-gnueabihf [armhf] <cross>, gfortran-12-arm-linux-gnueabihf [armhf] <cross>, gdc-12-arm-linux-gnueabihf [armhf] <cross>, gccgo-12-arm-linux-gnueabihf [armhf] <cross>, gnat-12-arm-linux-gnueabihf [armhf] <cross>, gm2-12-arm-linux-gnueabihf [armhf] <cross>, g++-12-aarch64-linux-gnu [arm64] <cross>, gobjc-12-aarch64-linux-gnu [arm64] <cross>, gfortran-12-aarch64-linux-gnu [arm64] <cross>, gdc-12-aarch64-linux-gnu [arm64] <cross>, gccgo-12-aarch64-linux-gnu [arm64] <cross>, gnat-12-aarch64-linux-gnu [arm64] <cross>, gm2-12-aarch64-linux-gnu [arm64] <cross>, g++-12-i686-linux-gnu [i386] <cross>, gobjc-12-i686-linux-gnu [i386] <cross>, gfortran-12-i686-linux-gnu [i386] <cross>, gdc-12-i686-linux-gnu [i386] <cross>, gccgo-12-i686-linux-gnu [i386] <cross>, gnat-12-i686-linux-gnu [i386] <cross>, gm2-12-i686-linux-gnu [i386] <cross>, g++-12-mipsel-linux-gnu [mipsel] <cross>, gobjc-12-mipsel-linux-gnu [mipsel] <cross>, gfortran-12-mipsel-linux-gnu [mipsel] <cross>, gdc-12-mipsel-linux-gnu [mipsel] <cross>, gccgo-12-mipsel-linux-gnu [mipsel] <cross>, gnat-12-mipsel-linux-gnu [mipsel] <cross>, gm2-12-mipsel-linux-gnu [mipsel] <cross>, g++-12-mips64-linux-gnuabi64 [mips64] <cross>, gobjc-12-mips64-linux-gnuabi64 [mips64] <cross>, gfortran-12-mips64-linux-gnuabi64 [mips64] <cross>, gdc-12-mips64-linux-gnuabi64 [mips64] <cross>, gccgo-12-mips64-linux-gnuabi64 [mips64] <cross>, gnat-12-mips64-linux-gnuabi64 [mips64] <cross>, gm2-12-mips64-linux-gnuabi64 [mips64] <cross>, g++-12-mips64el-linux-gnuabi64 [mips64el] <cross>, gobjc-12-mips64el-linux-gnuabi64 [mips64el] <cross>, gfortran-12-mips64el-linux-gnuabi64 [mips64el] <cross>, gdc-12-mips64el-linux-gnuabi64 [mips64el] <cross>, gccgo-12-mips64el-linux-gnuabi64 [mips64el] <cross>, gnat-12-mips64el-linux-gnuabi64 [mips64el] <cross>, gm2-12-mips64el-linux-gnuabi64 [mips64el] <cross>, g++-12-mips64-linux-gnuabin32 [mipsn32] <cross>, gobjc-12-mips64-linux-gnuabin32 [mipsn32] <cross>, gfortran-12-mips64-linux-gnuabin32 [mipsn32] <cross>, gdc-12-mips64-linux-gnuabin32 [mipsn32] <cross>, gccgo-12-mips64-linux-gnuabin32 [mipsn32] <cross>, gnat-12-mips64-linux-gnuabin32 [mipsn32] <cross>, gm2-12-mips64-linux-gnuabin32 [mipsn32] <cross>, g++-12-powerpc-linux-gnu [powerpc] <cross>, gobjc-12-powerpc-linux-gnu [powerpc] <cross>, gfortran-12-powerpc-linux-gnu [powerpc] <cross>, gdc-12-powerpc-linux-gnu [powerpc] <cross>, gccgo-12-powerpc-linux-gnu [powerpc] <cross>, gnat-12-powerpc-linux-gnu [powerpc] <cross>, g++-12-powerpc64-linux-gnu [ppc64] <cross>, gobjc-12-powerpc64-linux-gnu [ppc64] <cross>, gfortran-12-powerpc64-linux-gnu [ppc64] <cross>, gdc-12-powerpc64-linux-gnu [ppc64] <cross>, gccgo-12-powerpc64-linux-gnu [ppc64] <cross>, gnat-12-powerpc64-linux-gnu [ppc64] <cross>, g++-12-powerpc64le-linux-gnu [ppc64el] <cross>, gobjc-12-powerpc64le-linux-gnu [ppc64el] <cross>, gfortran-12-powerpc64le-linux-gnu [ppc64el] <cross>, gdc-12-powerpc64le-linux-gnu [ppc64el] <cross>, gccgo-12-powerpc64le-linux-gnu [ppc64el] <cross>, gnat-12-powerpc64le-linux-gnu [ppc64el] <cross>, gm2-12-powerpc64le-linux-gnu [ppc64el] <cross>, g++-12-m68k-linux-gnu [m68k] <cross>, gobjc-12-m68k-linux-gnu [m68k] <cross>, gfortran-12-m68k-linux-gnu [m68k] <cross>, gdc-12-m68k-linux-gnu [m68k] <cross>, gm2-12-m68k-linux-gnu [m68k] <cross>, g++-12-riscv64-linux-gnu [riscv64] <cross>, gobjc-12-riscv64-linux-gnu [riscv64] <cross>, gfortran-12-riscv64-linux-gnu [riscv64] <cross>, gdc-12-riscv64-linux-gnu [riscv64] <cross>, gccgo-12-riscv64-linux-gnu [riscv64] <cross>, gnat-12-riscv64-linux-gnu [riscv64] <cross>, gm2-12-riscv64-linux-gnu [riscv64] <cross>, g++-12-sh4-linux-gnu [sh4] <cross>, gobjc-12-sh4-linux-gnu [sh4] <cross>, gfortran-12-sh4-linux-gnu [sh4] <cross>, gnat-12-sh4-linux-gnu [sh4] <cross>, g++-12-sparc64-linux-gnu [sparc64] <cross>, gobjc-12-sparc64-linux-gnu [sparc64] <cross>, gfortran-12-sparc64-linux-gnu [sparc64] <cross>, gdc-12-sparc64-linux-gnu [sparc64] <cross>, gccgo-12-sparc64-linux-gnu [sparc64] <cross>, gnat-12-sparc64-linux-gnu [sparc64] <cross>, gm2-12-sparc64-linux-gnu [sparc64] <cross>, g++-12-s390x-linux-gnu [s390x] <cross>, gobjc-12-s390x-linux-gnu [s390x] <cross>, gfortran-12-s390x-linux-gnu [s390x] <cross>, gdc-12-s390x-linux-gnu [s390x] <cross>, gccgo-12-s390x-linux-gnu [s390x] <cross>, gnat-12-s390x-linux-gnu [s390x] <cross>, gm2-12-s390x-linux-gnu [s390x] <cross>, g++-12-x86-64-linux-gnux32 [x32] <cross>, gobjc-12-x86-64-linux-gnux32 [x32] <cross>, gfortran-12-x86-64-linux-gnux32 [x32] <cross>, gdc-12-x86-64-linux-gnux32 [x32] <cross>, gccgo-12-x86-64-linux-gnux32 [x32] <cross>, gnat-12-x86-64-linux-gnux32 [x32] <cross>, gm2-12-x86-64-linux-gnux32 [x32] <cross>, g++-12-mips64el-linux-gnuabin32 [mipsn32el] <cross>, gobjc-12-mips64el-linux-gnuabin32 [mipsn32el] <cross>, gfortran-12-mips64el-linux-gnuabin32 [mipsn32el] <cross>, gdc-12-mips64el-linux-gnuabin32 [mipsn32el] <cross>, gccgo-12-mips64el-linux-gnuabin32 [mipsn32el] <cross>, gnat-12-mips64el-linux-gnuabin32 [mipsn32el] <cross>, gm2-12-mips64el-linux-gnuabin32 [mipsn32el] <cross>, g++-12-mipsisa32r6-linux-gnu [mipsr6] <cross>, gobjc-12-mipsisa32r6-linux-gnu [mipsr6] <cross>, gfortran-12-mipsisa32r6-linux-gnu [mipsr6] <cross>, gdc-12-mipsisa32r6-linux-gnu [mipsr6] <cross>, gccgo-12-mipsisa32r6-linux-gnu [mipsr6] <cross>, gnat-12-mipsisa32r6-linux-gnu [mipsr6] <cross>, gm2-12-mipsisa32r6-linux-gnu [mipsr6] <cross>, g++-12-mipsisa32r6el-linux-gnu [mipsr6el] <cross>, gobjc-12-mipsisa32r6el-linux-gnu [mipsr6el] <cross>, gfortran-12-mipsisa32r6el-linux-gnu [mipsr6el] <cross>, gdc-12-mipsisa32r6el-linux-gnu [mipsr6el] <cross>, gccgo-12-mipsisa32r6el-linux-gnu [mipsr6el] <cross>, gnat-12-mipsisa32r6el-linux-gnu [mipsr6el] <cross>, gm2-12-mipsisa32r6el-linux-gnu [mipsr6el] <cross>, g++-12-mipsisa64r6-linux-gnuabi64 [mips64r6] <cross>, gobjc-12-mipsisa64r6-linux-gnuabi64 [mips64r6] <cross>, gfortran-12-mipsisa64r6-linux-gnuabi64 [mips64r6] <cross>, gdc-12-mipsisa64r6-linux-gnuabi64 [mips64r6] <cross>, gccgo-12-mipsisa64r6-linux-gnuabi64 [mips64r6] <cross>, gnat-12-mipsisa64r6-linux-gnuabi64 [mips64r6] <cross>, gm2-12-mipsisa64r6-linux-gnuabi64 [mips64r6] <cross>, g++-12-mipsisa64r6el-linux-gnuabi64 [mips64r6el] <cross>, gobjc-12-mipsisa64r6el-linux-gnuabi64 [mips64r6el] <cross>, gfortran-12-mipsisa64r6el-linux-gnuabi64 [mips64r6el] <cross>, gdc-12-mipsisa64r6el-linux-gnuabi64 [mips64r6el] <cross>, gccgo-12-mipsisa64r6el-linux-gnuabi64 [mips64r6el] <cross>, gnat-12-mipsisa64r6el-linux-gnuabi64 [mips64r6el] <cross>, gm2-12-mipsisa64r6el-linux-gnuabi64 [mips64r6el] <cross>, g++-12-mipsisa64r6-linux-gnuabin32 [mipsn32r6] <cross>, gobjc-12-mipsisa64r6-linux-gnuabin32 [mipsn32r6] <cross>, gfortran-12-mipsisa64r6-linux-gnuabin32 [mipsn32r6] <cross>, gdc-12-mipsisa64r6-linux-gnuabin32 [mipsn32r6] <cross>, gccgo-12-mipsisa64r6-linux-gnuabin32 [mipsn32r6] <cross>, gnat-12-mipsisa64r6-linux-gnuabin32 [mipsn32r6] <cross>, gm2-12-mipsisa64r6-linux-gnuabin32 [mipsn32r6] <cross>, g++-12-mipsisa64r6el-linux-gnuabin32 [mipsn32r6el] <cross>, gobjc-12-mipsisa64r6el-linux-gnuabin32 [mipsn32r6el] <cross>, gfortran-12-mipsisa64r6el-linux-gnuabin32 [mipsn32r6el] <cross>, gdc-12-mipsisa64r6el-linux-gnuabin32 [mipsn32r6el] <cross>, gccgo-12-mipsisa64r6el-linux-gnuabin32 [mipsn32r6el] <cross>, gnat-12-mipsisa64r6el-linux-gnuabin32 [mipsn32r6el] <cross>, gm2-12-mipsisa64r6el-linux-gnuabin32 [mipsn32r6el] <cross>
Build-Depends-Indep: doxygen (>= 1.7.2), graphviz (>= 2.2), ghostscript, texlive-latex-base, xsltproc, libxml2-utils, docbook-xsl-ns
Package-List:
 cpp-12 deb interpreters optional arch=any
 cpp-12-doc deb doc optional arch=all
 g++-12 deb devel optional arch=any
 g++-12-multilib deb devel optional arch=amd64,i386,kfreebsd-amd64,mips,mips64,mips64el,mips64r6,mips64r6el,mipsel,mipsn32,mipsn32el,mipsn32r6,mipsn32r6el,mipsr6,mipsr6el,powerpc,ppc64,s390,s390x,sparc,sparc64,x32
 gcc-12 deb devel optional arch=any
 gcc-12-base deb libs optional arch=any
 gcc-12-doc deb doc optional arch=all
 gcc-12-hppa64-linux-gnu deb devel optional arch=hppa,amd64,i386,x32
 gcc-12-locales deb devel optional arch=all
 gcc-12-multilib deb devel optional arch=amd64,i386,kfreebsd-amd64,mips,mips64,mips64el,mips64r6,mips64r6el,mipsel,mipsn32,mipsn32el,mipsn32r6,mipsn32r6el,mipsr6,mipsr6el,powerpc,ppc64,s390,s390x,sparc,sparc64,x32
 gcc-12-offload-amdgcn deb devel optional arch=amd64
 gcc-12-offload-nvptx deb devel optional arch=amd64,ppc64el
 gcc-12-plugin-dev deb devel optional arch=any
 gcc-12-source deb devel optional arch=all
 gcc-12-test-results deb devel optional arch=any
 gccgo-12 deb devel optional arch=any
 gccgo-12-doc deb doc optional arch=all
 gccgo-12-multilib deb devel optional arch=amd64,i386,kfreebsd-amd64,mips,mips64,mips64el,mips64r6,mips64r6el,mipsel,mipsn32,mipsn32el,mipsn32r6,mipsn32r6el,mipsr6,mipsr6el,powerpc,ppc64,s390,s390x,sparc,sparc64,x32
 gdc-12 deb devel optional arch=any
 gdc-12-multilib deb devel optional arch=any
 gfortran-12 deb devel optional arch=any
 gfortran-12-doc deb doc optional arch=all
 gfortran-12-multilib deb devel optional arch=amd64,i386,kfreebsd-amd64,mips,mips64,mips64el,mips64r6,mips64r6el,mipsel,mipsn32,mipsn32el,mipsn32r6,mipsn32r6el,mipsr6,mipsr6el,powerpc,ppc64,s390,s390x,sparc,sparc64,x32
 gm2-12 deb devel optional arch=any
 gm2-12-doc deb doc optional arch=all
 gnat-12 deb devel optional arch=any
 gnat-12-doc deb doc optional arch=all
 gobjc++-12 deb devel optional arch=any
 gobjc++-12-multilib deb devel optional arch=amd64,i386,kfreebsd-amd64,mips,mips64,mips64el,mips64r6,mips64r6el,mipsel,mipsn32,mipsn32el,mipsn32r6,mipsn32r6el,mipsr6,mipsr6el,powerpc,ppc64,s390,s390x,sparc,sparc64,x32
 gobjc-12 deb devel optional arch=any
 gobjc-12-multilib deb devel optional arch=amd64,i386,kfreebsd-amd64,mips,mips64,mips64el,mips64r6,mips64r6el,mipsel,mipsn32,mipsn32el,mipsn32r6,mipsn32r6el,mipsr6,mipsr6el,powerpc,ppc64,s390,s390x,sparc,sparc64,x32
 lib32asan8 deb libs optional arch=amd64,ppc64,kfreebsd-amd64,s390x,sparc64,x32,mipsn32,mipsn32el,mips64,mips64el,mipsn32r6,mipsn32r6el,mips64r6,mips64r6el
 lib32atomic1 deb libs optional arch=amd64,ppc64,kfreebsd-amd64,s390x,sparc64,x32,mipsn32,mipsn32el,mips64,mips64el,mipsn32r6,mipsn32r6el,mips64r6,mips64r6el
 lib32gcc-12-dev deb libdevel optional arch=amd64,ppc64,kfreebsd-amd64,s390x,sparc64,x32,mipsn32,mipsn32el,mips64,mips64el,mipsn32r6,mipsn32r6el,mips64r6,mips64r6el
 lib32gcc-s1 deb libs optional arch=amd64,ppc64,kfreebsd-amd64,s390x,sparc64,x32,mipsn32,mipsn32el,mips64,mips64el,mipsn32r6,mipsn32r6el,mips64r6,mips64r6el
 lib32gfortran-12-dev deb libdevel optional arch=amd64,ppc64,kfreebsd-amd64,s390x,sparc64,x32,mipsn32,mipsn32el,mips64,mips64el,mipsn32r6,mipsn32r6el,mips64r6,mips64r6el
 lib32gfortran5 deb libs optional arch=amd64,ppc64,kfreebsd-amd64,s390x,sparc64,x32,mipsn32,mipsn32el,mips64,mips64el,mipsn32r6,mipsn32r6el,mips64r6,mips64r6el
 lib32go-12-dev deb libdevel optional arch=amd64,ppc64,kfreebsd-amd64,s390x,sparc64,x32,mipsn32,mipsn32el,mips64,mips64el,mipsn32r6,mipsn32r6el,mips64r6,mips64r6el
 lib32go21 deb libs optional arch=amd64,ppc64,kfreebsd-amd64,s390x,sparc64,x32,mipsn32,mipsn32el,mips64,mips64el,mipsn32r6,mipsn32r6el,mips64r6,mips64r6el
 lib32gomp1 deb libs optional arch=amd64,ppc64,kfreebsd-amd64,s390x,sparc64,x32,mipsn32,mipsn32el,mips64,mips64el,mipsn32r6,mipsn32r6el,mips64r6,mips64r6el
 lib32gphobos-12-dev deb libdevel optional arch=amd64,ppc64,kfreebsd-amd64,s390x,sparc64,x32,mipsn32,mipsn32el,mips64,mips64el,mipsn32r6,mipsn32r6el,mips64r6,mips64r6el
 lib32gphobos3 deb libs optional arch=amd64,ppc64,kfreebsd-amd64,s390x,sparc64,x32,mipsn32,mipsn32el,mips64,mips64el,mipsn32r6,mipsn32r6el,mips64r6,mips64r6el
 lib32itm1 deb libs optional arch=amd64,ppc64,kfreebsd-amd64,s390x,sparc64,x32,mipsn32,mipsn32el,mips64,mips64el,mipsn32r6,mipsn32r6el,mips64r6,mips64r6el
 lib32lsan0 deb libs optional arch=amd64,ppc64,kfreebsd-amd64,s390x,sparc64,x32,mipsn32,mipsn32el,mips64,mips64el,mipsn32r6,mipsn32r6el,mips64r6,mips64r6el
 lib32objc-12-dev deb libdevel optional arch=amd64,ppc64,kfreebsd-amd64,s390x,sparc64,x32,mipsn32,mipsn32el,mips64,mips64el,mipsn32r6,mipsn32r6el,mips64r6,mips64r6el
 lib32objc4 deb libs optional arch=amd64,ppc64,kfreebsd-amd64,s390x,sparc64,x32,mipsn32,mipsn32el,mips64,mips64el,mipsn32r6,mipsn32r6el,mips64r6,mips64r6el
 lib32quadmath0 deb libs optional arch=amd64,ppc64,kfreebsd-amd64,s390x,sparc64,x32,mipsn32,mipsn32el,mips64,mips64el,mipsn32r6,mipsn32r6el,mips64r6,mips64r6el
 lib32stdc++-12-dev deb libdevel optional arch=amd64,ppc64,kfreebsd-amd64,s390x,sparc64,x32,mipsn32,mipsn32el,mips64,mips64el,mipsn32r6,mipsn32r6el,mips64r6,mips64r6el
 lib32stdc++6 deb libs optional arch=amd64,ppc64,kfreebsd-amd64,s390x,sparc64,x32,mipsn32,mipsn32el,mips64,mips64el,mipsn32r6,mipsn32r6el,mips64r6,mips64r6el
 lib32stdc++6-12-dbg deb debug optional arch=amd64,ppc64,kfreebsd-amd64,s390x,sparc64,x32,mipsn32,mipsn32el,mips64,mips64el,mipsn32r6,mipsn32r6el,mips64r6,mips64r6el
 lib32ubsan1 deb libs optional arch=amd64,ppc64,kfreebsd-amd64,s390x,sparc64,x32,mipsn32,mipsn32el,mips64,mips64el,mipsn32r6,mipsn32r6el,mips64r6,mips64r6el
 lib64asan8 deb libs optional arch=i386,powerpc,sparc,s390,mips,mipsel,mipsn32,mipsn32el,mipsr6,mipsr6el,mipsn32r6,mipsn32r6el,x32
 lib64atomic1 deb libs optional arch=i386,powerpc,sparc,s390,mips,mipsel,mipsn32,mipsn32el,mipsr6,mipsr6el,mipsn32r6,mipsn32r6el,x32
 lib64gcc-12-dev deb libdevel optional arch=i386,powerpc,sparc,s390,mips,mipsel,mipsn32,mipsn32el,mipsr6,mipsr6el,mipsn32r6,mipsn32r6el,x32
 lib64gcc-s1 deb libs optional arch=i386,powerpc,sparc,s390,mips,mipsel,mipsn32,mipsn32el,mipsr6,mipsr6el,mipsn32r6,mipsn32r6el,x32
 lib64gfortran-12-dev deb libdevel optional arch=i386,powerpc,sparc,s390,mips,mipsel,mipsn32,mipsn32el,mipsr6,mipsr6el,mipsn32r6,mipsn32r6el,x32
 lib64gfortran5 deb libs optional arch=i386,powerpc,sparc,s390,mips,mipsel,mipsn32,mipsn32el,mipsr6,mipsr6el,mipsn32r6,mipsn32r6el,x32
 lib64go-12-dev deb libdevel optional arch=i386,powerpc,sparc,s390,mips,mipsel,mipsn32,mipsn32el,mipsr6,mipsr6el,mipsn32r6,mipsn32r6el,x32
 lib64go21 deb libs optional arch=i386,powerpc,sparc,s390,mips,mipsel,mipsn32,mipsn32el,mipsr6,mipsr6el,mipsn32r6,mipsn32r6el,x32
 lib64gomp1 deb libs optional arch=i386,powerpc,sparc,s390,mips,mipsel,mipsn32,mipsn32el,mipsr6,mipsr6el,mipsn32r6,mipsn32r6el,x32
 lib64gphobos-12-dev deb libdevel optional arch=i386,powerpc,sparc,s390,mips,mipsel,mipsn32,mipsn32el,mipsr6,mipsr6el,mipsn32r6,mipsn32r6el,x32
 lib64gphobos3 deb libs optional arch=i386,powerpc,sparc,s390,mips,mipsel,mipsn32,mipsn32el,mipsr6,mipsr6el,mipsn32r6,mipsn32r6el,x32
 lib64itm1 deb libs optional arch=i386,powerpc,sparc,s390,mips,mipsel,mipsn32,mipsn32el,mipsr6,mipsr6el,mipsn32r6,mipsn32r6el,x32
 lib64objc-12-dev deb libdevel optional arch=i386,powerpc,sparc,s390,mips,mipsel,mipsn32,mipsn32el,mipsr6,mipsr6el,mipsn32r6,mipsn32r6el,x32
 lib64objc4 deb libs optional arch=i386,powerpc,sparc,s390,mips,mipsel,mipsn32,mipsn32el,mipsr6,mipsr6el,mipsn32r6,mipsn32r6el,x32
 lib64quadmath0 deb libs optional arch=i386,powerpc,sparc,s390,mips,mipsel,mipsn32,mipsn32el,mipsr6,mipsr6el,mipsn32r6,mipsn32r6el,x32
 lib64stdc++-12-dev deb libdevel optional arch=i386,powerpc,sparc,s390,mips,mipsel,mipsn32,mipsn32el,mipsr6,mipsr6el,mipsn32r6,mipsn32r6el,x32
 lib64stdc++6 deb libs optional arch=i386,powerpc,sparc,s390,mips,mipsel,mipsn32,mipsn32el,mipsr6,mipsr6el,mipsn32r6,mipsn32r6el,x32
 lib64stdc++6-12-dbg deb debug optional arch=i386,powerpc,sparc,s390,mips,mipsel,mipsn32,mipsn32el,mipsr6,mipsr6el,mipsn32r6,mipsn32r6el,x32
 lib64ubsan1 deb libs optional arch=i386,powerpc,sparc,s390,mips,mipsel,mipsn32,mipsn32el,mipsr6,mipsr6el,mipsn32r6,mipsn32r6el,x32
 libasan8 deb libs optional arch=any
 libatomic1 deb libs optional arch=any
 libcc1-0 deb libs optional arch=any
 libgcc-12-dev deb libdevel optional arch=any
 libgcc-s1 deb libs optional arch=any
 libgcc-s2 deb libs optional arch=m68k
 libgcc-s4 deb libs optional arch=hppa
 libgccjit-12-dev deb libdevel optional arch=any
 libgccjit-12-doc deb doc optional arch=all
 libgccjit0 deb libs optional arch=any
 libgfortran-12-dev deb libdevel optional arch=any
 libgfortran5 deb libs optional arch=any
 libgm2-12-dev deb libdevel optional arch=any
 libgm2-17 deb libs optional arch=any
 libgnat-12 deb libs optional arch=any
 libgo-12-dev deb libdevel optional arch=any
 libgo21 deb libs optional arch=any
 libgomp-plugin-amdgcn1 deb libs optional arch=amd64
 libgomp-plugin-nvptx1 deb libs optional arch=amd64,ppc64el
 libgomp1 deb libs optional arch=any
 libgphobos-12-dev deb libdevel optional arch=amd64,arm64,armel,armhf,i386,x32,hppa,mips,mips64,mipsel,mips64el,mipsn32,mipsn32el,mipsr6,mipsr6el,mipsn32r6,mipsn32r6el,mips64r6,mips64r6el,riscv64,s390x,powerpc,ppc64,ppc64el
 libgphobos3 deb libs optional arch=amd64,arm64,armel,armhf,i386,x32,hppa,mips,mips64,mipsel,mips64el,mipsn32,mipsn32el,mipsr6,mipsr6el,mipsn32r6,mipsn32r6el,mips64r6,mips64r6el,riscv64,s390x,powerpc,ppc64,ppc64el
 libitm1 deb libs optional arch=any
 liblsan0 deb libs optional arch=any
 libn32atomic1 deb libs optional arch=mips,mipsel,mips64,mips64el,mipsr6,mipsr6el,mips64r6,mips64r6el
 libn32gcc-12-dev deb libdevel optional arch=mips,mipsel,mips64,mips64el,mipsr6,mipsr6el,mips64r6,mips64r6el
 libn32gcc-s1 deb libs optional arch=mips,mipsel,mips64,mips64el,mipsr6,mipsr6el,mips64r6,mips64r6el
 libn32gfortran-12-dev deb libdevel optional arch=mips,mipsel,mips64,mips64el,mipsr6,mipsr6el,mips64r6,mips64r6el
 libn32gfortran5 deb libs optional arch=mips,mipsel,mips64,mips64el,mipsr6,mipsr6el,mips64r6,mips64r6el
 libn32go-12-dev deb libdevel optional arch=mips,mipsel,mips64,mips64el,mipsr6,mipsr6el,mips64r6,mips64r6el
 libn32go21 deb libs optional arch=mips,mipsel,mips64,mips64el,mipsr6,mipsr6el,mips64r6,mips64r6el
 libn32gomp1 deb libs optional arch=mips,mipsel,mips64,mips64el,mipsr6,mipsr6el,mips64r6,mips64r6el
 libn32gphobos-12-dev deb libdevel optional arch=mips,mipsel,mips64,mips64el,mipsr6,mipsr6el,mips64r6,mips64r6el
 libn32gphobos3 deb libs optional arch=mips,mipsel,mips64,mips64el,mipsr6,mipsr6el,mips64r6,mips64r6el
 libn32objc-12-dev deb libdevel optional arch=mips,mipsel,mips64,mips64el,mipsr6,mipsr6el,mips64r6,mips64r6el
 libn32objc4 deb libs optional arch=mips,mipsel,mips64,mips64el,mipsr6,mipsr6el,mips64r6,mips64r6el
 libn32stdc++-12-dev deb libdevel optional arch=mips,mipsel,mips64,mips64el,mipsr6,mipsr6el,mips64r6,mips64r6el
 libn32stdc++6 deb libs optional arch=mips,mipsel,mips64,mips64el,mipsr6,mipsr6el,mips64r6,mips64r6el
 libn32stdc++6-12-dbg deb debug optional arch=mips,mipsel,mips64,mips64el,mipsr6,mipsr6el,mips64r6,mips64r6el
 libobjc-12-dev deb libdevel optional arch=any
 libobjc4 deb libs optional arch=any
 libquadmath0 deb libs optional arch=any
 libstdc++-12-dev deb libdevel optional arch=any
 libstdc++-12-doc deb doc optional arch=all
 libstdc++-12-pic deb libdevel optional arch=any
 libstdc++6 deb libs optional arch=any
 libstdc++6-12-dbg deb debug optional arch=any
 libtsan2 deb libs optional arch=any
 libubsan1 deb libs optional arch=any
 libx32asan8 deb libs optional arch=amd64,i386
 libx32atomic1 deb libs optional arch=amd64,i386
 libx32gcc-12-dev deb libdevel optional arch=amd64,i386
 libx32gcc-s1 deb libs optional arch=amd64,i386
 libx32gfortran-12-dev deb libdevel optional arch=amd64,i386
 libx32gfortran5 deb libs optional arch=amd64,i386
 libx32go-12-dev deb libdevel optional arch=amd64,i386
 libx32go21 deb libs optional arch=amd64,i386
 libx32gomp1 deb libs optional arch=amd64,i386
 libx32gphobos-12-dev deb libdevel optional arch=amd64,i386
 libx32gphobos3 deb libs optional arch=amd64,i386
 libx32itm1 deb libs optional arch=amd64,i386
 libx32lsan0 deb libs optional arch=amd64,i386
 libx32objc-12-dev deb libdevel optional arch=amd64,i386
 libx32objc4 deb libs optional arch=amd64,i386
 libx32quadmath0 deb libs optional arch=amd64,i386
 libx32stdc++-12-dev deb libdevel optional arch=amd64,i386
 libx32stdc++6 deb libs optional arch=amd64,i386
 libx32stdc++6-12-dbg deb debug optional arch=amd64,i386
 libx32ubsan1 deb libs optional arch=amd64,i386
Checksums-Sha1:
 9a1b5a76ff8589d6499db06c5906fdde7b16e2aa 91555468 gcc-12_12.3.0.orig.tar.gz
 a81929d91c30a5a72edfae28e8bb1e7616986f26 587456 gcc-12_12.3.0-1ubuntu1~22.04.3.debian.tar.xz
Checksums-Sha256:
 62b0fc89b6d41f9df2470d0fb4995f6ff5885f910518a2c6a44a6888ea5a9ea1 91555468 gcc-12_12.3.0.orig.tar.gz
 44074c3d5e7d97365f1f7b45291ade2ee40ed6300176530d912e7cc0ceba77ab 587456 gcc-12_12.3.0-1ubuntu1~22.04.3.debian.tar.xz
Files:
 a66f5334730c55b59415e907c8d4821d 91555468 gcc-12_12.3.0.orig.tar.gz
 c6ef484e884b885115ff0efbe963a190 587456 gcc-12_12.3.0-1ubuntu1~22.04.3.debian.tar.xz
Original-Maintainer: Debian GCC Maintainers <debian-gcc@lists.debian.org>

-----BEGIN PGP SIGNATURE-----

iQIzBAEBCgAdFiEEi0Ab7+QbQ0yQSEMs9jtmipabB8AFAmlp7wQACgkQ9jtmipab
B8DSRA/7Blua82xnXIBwy8mP9ByQf193lbvrNrNQbK1e5FNg1bWADbisx8S8X88N
2GbEWyDu3nqhnQSZyNRdfWcGPIjszt2gS5ICcq3lsONE6ofyziKi48X+ak+rpGH0
WDJsEmPcnXl2UN5dBYBHbIkLIyS0fUlzYXppvsNnUZo5HvMPnQSglmYL+hrsVyuL
2kO3dfUaN5ij+EjGbdm5Og4sWIp1QasUaosG+w7vYbPfGeXquTpBcWksakAPj3G6
NeHayh5QbehitoTMX3eHEWxgYMsHKjV53aTgUbonFtftopimAGkiLCzppqKElRBU
vpHfq4hQSFNdtfNI0ORONXp6YsQauww8rRW66uXnB7NzoEU25wcip7qPHLPpYdu8
C0YCB+mCQbiMv9cQ4d1S6+3asPfgDrkI4CX3O1pwaOWUMFARKBJCbaZafFtKqn5G
Av6XorU40SgoVQG3H5e/AK0aL5tQTp5UMs51GRZzzZtNDcTzoUBJd7XikTvUcAQR
FNiYjhpC89NDySCmP9BYcXvJhdVgMZ/LDex2BthSPQhDr0jFJo9uY77HponDaq6J
Rnj61MQOnd8KcOxg/XGPvXa3C47NFCdJy9WZm7W/DG5cWr7XSr4tucfSJQoeF3CY
kFahlk8HeHra+C3xTszOibsgiWtVpll3/ykSqzwot5eG0jt5HcI=
=zgRs
-----END PGP SIGNATURE-----
