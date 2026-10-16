# macOS library modification and recombination

Reverse engineering for debugging modifications to LGPL libraries is permitted.
No distributor signing key, activation, or permission is needed. This recipe is
provided for both `arm64` and `x86_64`; it has **not** been executed on this Linux
research host. Native replacement evidence remains a release gate.

Export the runtime's compliance with `--export-compliance compliance.zip`.
Extract `builder-source.zip`, the full `native/files/sources/qtbase-everywhere-src-6.8.3.tar.xz`
and `pyside-setup-everywhere-src-6.8.3.tar.xz`, notices and `native/coverage.json`.
Keep the source trees and build scripts, including Qt's embedded third-party
source, Network public-suffix data/generators and attribution files. The archived
application source is the preferred form for modifying the wrapper itself.

Use a private Python 3.12 environment, macOS/Xcode command-line tools, CMake,
Ninja and a compatible libclang installation. Do not overwrite system libraries.
Select the native target architecture, or use a compatible Mac cross-build
configuration; each runtime must remain complete and internally consistent.
Build Qt Base in a separate directory (replace ARCH with arm64 or x86_64):

```sh
/path/to/qtbase-everywhere-src-6.8.3/configure \
  -prefix "$PWD/../qt-install" -release -shared -framework \
  -opensource -confirm-license -nomake tests -nomake examples \
  -- -DCMAKE_OSX_ARCHITECTURES=ARCH
cmake --build . --parallel
cmake --install .
```

This is a compatible-source build recipe, not the supplier's complete configure
command or a bit-reproduction claim. Follow configure's dependency diagnostics.
Keep the 6.8 ABI or rebuild the bindings together with the libraries. In the
PySide source directory, following its README and build documentation:

```sh
python setup.py bdist_wheel --qtpaths=/absolute/qt-install/bin/qtpaths \
  --parallel=4 --ignore-git
```

Install your rebuilt binding wheels in the private builder environment. Replace
Qt frameworks/plugins in that environment together, retaining their relative
layout, Versions/A binaries, Versions/Current links, framework root links and
resources. Do not replace just one architecture inside a two-runtime product
while assuming the other was changed. System Apple frameworks are prerequisites,
not part of the supplied Qt sources.

For a private modification, re-freeze from the extracted `desktop-builder`
source using the private environment. Install its pinned requirements first.
The Mac distribution is onedir; copying a replacement beside a onefile binary
is NOT a replacement mechanism. Installing rebuilt frameworks and plugins in
the venv before freezing is preferred to flattening framework bundles manually.

```sh
python -m PyInstaller --noconfirm --clean --noupx --onedir \
  --target-architecture ARCH --name OpenAutoLink-Builder-personal --paths . \
  --hidden-import oal_builder.engine --exclude-module tkinter \
  --additional-hooks-dir packaging --runtime-hook packaging/runtime_hook.py \
  --add-data /absolute/path/to/compliance.zip:. oal_builder/__main__.py
python packaging/build.py \
  --audit-native dist/OpenAutoLink-Builder-personal/OpenAutoLink-Builder-personal \
  --output personal-audit
```

PyInstaller applies ad-hoc signing when no identity is supplied. Its Mach-O
loader-path and signature changes mean the frozen binary hash will not normally
be the original framework hash. Audit the actual framework member, use `otool -L`
and `codesign --verify --strict` for each changed loadable binary, and launch:

```sh
dist/OpenAutoLink-Builder-personal/OpenAutoLink-Builder-personal \
  --smoke-test --workspace /path/that/does/not/exist --screenshot personal.png
```

Verify that the modified implementation actually loads; do not use hash
normalization as evidence. Compare the actual frozen inventory to your reviewed
replacement, inspect the PNG for readable text, and perform a normal GUI launch.
The embedded original compliance remains historical for a private probe. For
redistribution, identify modifications, provide the changed library preferred
source/build scripts and notices, create a new exact-byte review and rebuild
with it. The original release manifest must reject your changed bytes.

For a combined DMG, perform this procedure independently for each full runtime,
then retain both runtime directories intact under the architecture-selecting
launcher. Verify on both native architectures. There is no notarization or
Gatekeeper acceptance claim for an ad-hoc-signed personal modification.
