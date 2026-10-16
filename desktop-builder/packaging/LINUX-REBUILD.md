# Linux native libraries: source, rebuilding and replacement

This recipe accompanies the **Linux x86_64** exact-byte manifest. It is not a
Windows/Mac recipe, a claim of bit-reproducibility, or a restriction on your
right to modify libraries. Reverse engineering for debugging modifications to
the LGPL libraries is permitted. No signing key or activation service is needed.

## Obtain the complete materials

Run the executable with `--export-compliance compliance.zip`. Extract it in a
new work directory. `builder-source.zip` contains the corresponding application
source and the Python packaging scripts. `native/coverage.json` names every
shipped native file, notices, and corresponding library sources. Sources are in
`native/files/sources/`; their SHA-256 hashes and supplier URLs are recorded.
The recipe refers to these extracted paths, not temporary onefile `_MEI*` paths.

Use Python 3.12 on Linux x86_64, a C/C++ compiler, CMake, Ninja, pkg-config,
Python development headers and the relevant library development headers. Work
in a private build environment, not by overwriting operating-system libraries.
Install the pinned builder requirements into a new virtual environment. The
original freeze used Python 3.12.15, PySide/Shiboken 6.8.3 and PyInstaller 6.13.0.
The host C library and display server remain operating-system prerequisites.

## Qt Base 6.8.3 and PySide/Shiboken 6.8.3

The manifest matches the Qt Company PySide6 Essentials wheel byte-for-byte;
Qt Base's published 6.8.3 archive contains these modules and their third-party
sources. The wheel build's complete configure command is not asserted. The
static map deliberately includes each Linux-relevant optional third-party
implementation for the shipped modules, even when a system-library option may
have replaced it. This is a conservative notice/source closure, not an exact
list of objects selected by the supplier linker.

Extract `qtbase-everywhere-src-6.8.3.tar.xz`. In a separate build directory:

```sh
/path/to/qtbase-everywhere-src-6.8.3/configure \
  -prefix "$PWD/../qt-install" -release -shared -opensource -confirm-license \
  -nomake tests -nomake examples -xcb -opengl desktop \
  -qt-doubleconversion -qt-pcre -qt-harfbuzz -qt-libpng -qt-freetype -qt-zlib
cmake --build . --parallel
cmake --install .
```

Install missing build prerequisites in your chosen development environment if
configure reports them. `src/{corelib,gui,network,widgets,dbus}/CMakeLists.txt`,
`src/plugins/platforms/xcb` and `src/3rdparty/*/qt_attribution.json` describe the
module/feature mapping. Network's embedded public suffix list is MPL-2.0; its
preferred source `src/3rdparty/libpsl/psl_data.cpp`, lookup generator and license
are included in the full Qt archive. Do not omit these because Qt itself is LGPL.
The ICC profile is unmodified; if you modify that profile, remove its original
identification as its notice requires. FreeType attribution: portions of this
software are copyright The FreeType Project (www.freetype.org). All rights reserved.

Retain the 6.8 ABI, names and relative plugin paths. To change exported APIs or
bindings, also extract `pyside-setup-everywhere-src-6.8.3.tar.xz`; follow its
`README.md`, `setup.py --help` and `sources/pyside6/doc/building_from_source`
documentation. With a supported libclang development installation, the source
build command is:

```sh
python setup.py bdist_wheel --qtpaths=/path/to/qt-install/bin/qtpaths \
  --parallel=4 --ignore-git
```

Install the resulting Shiboken/PySide wheels into the private builder environment.
Do not mix Qt versions across Core, Gui, Widgets, Network, DBus and XcbQpa. Replace
all affected shared libraries and plugins together. The full archives retain
build scripts and the Qt/PySide licenses and exceptions.

## Debian shared libraries

The manifest records the binary package, binary version, source package/version,
observed SHA-256 and notice. Mixed-license source packages are **not** classified
by the license of their command-line utilities:

* GLib libraries: LGPL-2.1-or-later, with the included per-file permissive notices.
* libsystemd: LGPL-2.1-or-later; the daemon's GPL utilities are not the library.
* libblkid/libmount: LGPL-2.1-or-later; util-linux utilities are separate.
* libkeyutils: `keyutils.*`, LGPL-2.0-or-later, not the GPL `keyctl` command.
* libdbus: AFL-2.1 selected from the explicit dual grant. Full source is supplied
  under AFL section 3, with the original notices; no source-code copyleft is
  imposed on independent programs merely using its interface.
* libgcc_s/libstdc++/libatomic: GPL-3.0-or-later WITH GCC-exception-3.1. The exception
  permits eligible compiled independent modules, **not** omission of source for
  redistributed standalone runtime libraries. Both source and exception are here.

For systemd, glib2.0, keyutils, dbus and util-linux, the source set comprises the
upstream orig archive, Debian tar archive, and every auxiliary orig member
listed by the supplier `.dsc`; `.dsc` files accompany the notices. Reconstruct
with `dpkg-source -x package.dsc` from a directory containing those members. In a
Debian 13 development environment install that source package's `Build-Depends`
and run `dpkg-buildpackage -b -uc -us` from its root. `debian/rules` and the
patches define the Debian configuration. This is a source rebuild recipe, not a
claim that the local qualification rebuilt these upstream packages.

The GCC orig archive contains a nested `gcc-14.2.0-dfsg.tar.xz`. It is supplied
verbatim along with `gcc-14_14.2.0-19.debian.tar.xz`; the lock records its parent
archive and member hash. For Debian's full packaging recipe, fetch the pinned
outer archive using `prepare_native.py` and use its `.dsc` with `dpkg-source` as
above (the unrelated newlib archive is not part of these Linux runtimes). For a
private compatible upstream runtime build, extract the inner archive, build in
a sibling directory with `../gcc-14.2.0/configure --prefix="$PWD/../gcc-install"
--enable-languages=c,c++ --disable-multilib`, then `make -j4` and `make install`.
Use Debian's patches/rules when reproducing Debian's exact patched source.

## Recombine the application with changed libraries

Onefile extracts its libraries internally. Merely placing a shared library next
to the executable is **not** the supported replacement mechanism. Instead use
the included application source and re-freeze it with your replacement. From
`desktop-builder` in the extracted source tree, with the private venv active:

```sh
python -m PyInstaller --noconfirm --clean --noupx --onefile \
  --name OpenAutoLink-Builder-personal --paths . \
  --hidden-import oal_builder.engine --exclude-module tkinter \
  --additional-hooks-dir packaging --runtime-hook packaging/runtime_hook.py \
  --add-data /absolute/path/to/compliance.zip:. \
  --add-binary /absolute/path/to/modified/libQt6Core.so.6:PySide6/Qt/lib \
  oal_builder/__main__.py
```

Repeat `--add-binary` for each changed file, using its manifest directory (use
`.` for a root library such as libglib-2.0.so.0). Alternatively install all of
your rebuilt libraries at the original paths in the private venv before the
freeze. `--noupx` avoids recompressing native code. PyInstaller preserves the
library byte content unless the target platform needs loader path rewriting.
On Linux, verify the actual archive member before running:

```sh
python packaging/build.py --audit-native dist/OpenAutoLink-Builder-personal --output personal-audit
QT_QPA_PLATFORM=offscreen dist/OpenAutoLink-Builder-personal \
  --smoke-test --workspace /path/that/does/not/exist --screenshot personal.png
```

Compare the intended replacement's SHA-256 to the named path in
`personal-audit/native-payload-review.json`. Confirm readable text in the PNG.
The original release's coverage must reject altered bytes. A personal modified
executable is not the original qualified release: its embedded old evidence is
historical until you review the new inventory and produce updated coverage.
To redistribute a modification, retain notices, provide changed library source
where required, identify modifications, update the exact-byte review, and run
`generate_native.py` then the two-pass `build.py --native-coverage ...` gate.
There is no distributor permission or proprietary tool needed to perform this.

### Executed replacement check

`provenance/linux-recombination.json` records the container check of the command
above. A non-loaded `.oal_recombine_probe` section was added to a private copy
of QtCore, leaving its code unchanged. The changed library SHA-256
`fafc6cc4e5d43fecedb47949342b2bbbda6ac5fb9e2cdbccf747bb7c7e96d894`
matched the actual rebuilt CArchive member. The frozen application rendered a
readable offscreen screenshot without creating a workspace. This tests the
replacement/re-freeze mechanism, not a full Qt source compilation, and the
personal probe is not offered as a qualified release.
