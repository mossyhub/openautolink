# Windows source and recombination directions (not yet native-qualified)

Keep these instructions with the original LGPL/GPL notices, all referenced
source archives and the exact builder source export. No contract may prohibit
reverse engineering for debugging modifications to LGPL libraries. Microsoft
terms apply only to Microsoft's code, not to the independent open-source code.

1. On Windows x64 install compatible Visual Studio C++ build tools, CMake,
   Ninja, Python 3.12, and Qt's documented build prerequisites. Verify the SHA-256
   of qtbase-everywhere-src-6.8.3.tar.xz, pyside-setup-everywhere-src-6.8.3.tar.xz
   and qttranslations-everywhere-src-6.8.3.tar.xz against the coverage manifest.
   These are upstream source releases corresponding to supplier module versions,
   not a claim to reproduce Microsoft's toolchain or byte-identical wheel builds.
2. Extract the archives into private work folders. Configure Qt Base with
   `configure.bat -opensource -confirm-license -release -shared -nomake tests
   -nomake examples -prefix C:\\work\\modified-qt`, then run
   `cmake --build . --parallel` and `cmake --install .` in its separate build
   directory. Apply your changes before the build. Follow the included Qt
   configure/CMake documents for SDK/toolset selection; use the same architecture
   and Qt ABI. Include required module features for the retained plugins.
3. Build modified bindings using pyside-setup's README and setup.py build options
   with the modified Qt's qtpaths.exe, matching Python and architecture. Keep
   Shiboken, PySide and Qt consistent. To modify translations, edit the supplied
   `.ts` files and use Qt 6.8's lrelease (Qt Linguist tools) to produce `.qm`
   files at the same PySide6/translations relative paths.
4. Unpack the builder's exact source export, create a fresh venv and install its
   pinned requirements. Replace the private venv's Qt DLLs/plugins, PySide or
   Shiboken modules with your matching modified builds. Do not modify a globally
   installed Python/Qt or patch the running onefile extraction directory.
5. Rebuild with the source packaging script. The onefile program unpacks to a
   temporary directory; replacing a DLL next to the executable is NOT a valid
   replacement route. Audit the new CArchive, compare the chosen modified DLL
   hash and establish a new exact native coverage review. The official release
   allowlist intentionally rejects modified bytes. A recipient can adapt their
   own private source/build recipe; this is not permission to publish a falsely
   qualified release under the original manifest.
6. Launch the actual rebuilt executable and exercise the widgets/Qt platform
   plugin and selected modified library. Export its compliance/source material
   and verify final bytes. This procedure still needs a native Windows
   replacement test; no Linux simulation establishes that result.

The public suffix list's covered-file source is inside the supplied Qt Base
archive. Permissive dependency sources and Microsoft's proprietary source are
not demanded merely because they are native or statically linked.
