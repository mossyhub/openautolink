# Rebuild/recombine the Ubuntu CI native libraries

Export with `OpenAutoLink-Builder --export-compliance compliance.zip` and unpack
into a private directory. `builder-source.zip` contains the application source;
`native/files/sources` contains the manifest's corresponding library sources.
`native/coverage.json` binds all source inputs and notices by SHA-256. This is
a usable rebuild recipe, not evidence of a locally executed Ubuntu rebuild.
Reverse engineering for debugging modifications to LGPL libraries is permitted.

## Ubuntu distribution libraries

Use an Ubuntu 22.04 amd64 development container/VM (not your daily-driver host).
Install a C/C++ compiler and each source package's declared `Build-Depends`.
For util-linux 2.37.2-4ubuntu3.6, dbus 1.12.20-2ubuntu4.1,
glib2.0 2.72.4-0ubuntu2.10, libgcrypt20 1.9.4-3ubuntu3.3,
libgpg-error 1.43-3, keyutils 1.6.1-2ubuntu3 and systemd 249.11-0ubuntu3.22,
put the copied `.dsc` and all its pinned orig/debian members in one directory:

```sh
dpkg-source -x PACKAGE_VERSION.dsc
cd PACKAGE-VERSION
dpkg-buildpackage -b -uc -us
```

The exact descriptors are in `native/files/licenses/linux-ci/`. Ubuntu's
`debian/rules`, patches and build definitions are included. Keep SONAMEs,
architecture, exported ABI and the library's expected feature set compatible.
Extract the resulting `.deb` in a private directory with `dpkg-deb -x` rather
than installing it over your operating system. Do not substitute a newer
upstream tarball for the patched Ubuntu source.

GCC's outer orig archive contains GCC, unrelated newlib and Modula-2 sources.
The runtime-source delivery includes the complete nested `gcc-12.3.0.tar.xz`
and Ubuntu's `gcc-12_12.3.0-1ubuntu1~22.04.3.debian.tar.xz`. For a full Ubuntu
package rebuild, obtain the outer orig archive from the accompanying
`ubuntu-sources.lock.json`, checking its SHA-256, and run `dpkg-source` as above.
For a compatible private upstream libgcc/libstdc++ build, extract the nested
GCC source and build in a sibling directory:

```sh
../gcc-12.3.0/configure --prefix="$PWD/../gcc-install" \
  --enable-languages=c,c++ --disable-multilib
make -j4
make install
```

Use Ubuntu's patch/rules archive when reconstructing Ubuntu's exact patched
source. The runtime exception covers eligible compilation of independent
modules; it does not excuse omitting sources for standalone runtime libraries.

## Qt/PySide and personal replacement

The unchanged Qt/PySide 6.8.3 wheel payload retains the prior reviewed Qt static
closure. The included `LINUX-REBUILD.md` Qt/PySide section gives configure and
binding-build commands for the full supplied Qt Base and PySide source
archives. Its **Debian distribution-library section does not apply** to this
Ubuntu inventory; use the instructions above instead.

Install the pinned application requirements into a private Python 3.12 virtual
environment. Use PyInstaller 6.13.0. Replace a library in that private source
installation, or explicitly override it with `--add-binary`. Re-freeze using
the included application's `desktop-builder` directory, for example:

```sh
python -m PyInstaller --noconfirm --clean --noupx --onefile \
  --name OpenAutoLink-Builder-personal --paths . \
  --hidden-import oal_builder.engine --exclude-module tkinter \
  --additional-hooks-dir packaging --runtime-hook packaging/runtime_hook.py \
  --add-data /absolute/path/to/compliance.zip:. \
  --add-binary /absolute/path/to/modified/libglib-2.0.so.0:. \
  oal_builder/__main__.py
```

For Qt libraries use the `PySide6/Qt/lib` destination and retain consistent
6.8 module/plugin ABI versions; for extensions preserve their package-relative
paths. Launch the resulting private artifact and exercise the modified
library. Merely placing a library next to the onefile executable is not a
replacement method. No signature/key is necessary. A private changed build
will fail the distributor's exact-byte coverage map by design; it need not use
that publication gate. Neither this recipe nor evidence generation claims a
native-runner or modified-library launch has already passed.
