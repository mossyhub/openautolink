# Windows packaging: practical integration handoff

**No Windows EXE is qualified by this work.** Shared build.py/workflow were not modified. Original 198-entry discovery assignments remain historical evidence; do not publish that artifact or relabel its unknown components.

## 1. Proven false native classification — shared owner action

Pinned PyInstaller 6.13.0 `building/api.py:334-340` emits CArchive type `b` for DATA whenever `os.access(src_name, os.X_OK)` succeeds, solely to restore executable permissions. CPython 3.12.10 `Modules/posixmodule.c:os_access_impl` Windows branch checks file existence and requested write access, not execute permission. Thus existing Windows DATA files become `b`: this flag is NOT proof of machine code.

Actual archive-byte verification found **148 unique matched paths: 48 PE files and 100 data files**. Those data files include **94 .qm translations** and six package metadata/license files. The other 50 historical paths are the loader, 43 UCRT/API-set DLLs, two unrelated OpenSSL DLLs, two ZIPs, and two installed package metadata files. Neither `builder-compliance.zip` nor `base_library.zip` should be called native code.

Precise shared fix in `native_inventory`: classify actual magic for every extracted CArchive entry regardless of `b`/`x`; retain symlink/dependency safeguards. Safely inspect ZIP members with bounded expansion/count/depth and validated unique names, rejecting or separately accounting for unexpected native content. Do not simply exempt everything named .zip. Keep the compliance ZIP under its dedicated content/source verifier, not its own embedded native payload hash (self-reference); full artifact hashes still bind every packaged byte. Retain all license/source obligations for non-native translations and Python code in the separate compliance inventory.

Tests needed in the shared gate: `b` ZIP and .qm are data; PE disguised as `x` is native; nested/renamed native data cannot evade inspection; malformed/duplicate/traversal/bomb ZIPs fail; embedded compliance ZIP changes do not create a native self-reference.

## 2. A second, independent Windows two-pass hash problem

PyInstaller `building/api.py:864-871` calls `winutils.set_exe_build_timestamp` and `update_exe_pe_checksum` AFTER appending CArchive. The former updates **COFF and DEBUG timestamps**; the latter hashes the entire EXE. Therefore raw PE prefix hashes cannot remain stable across the current two-pass compliance freeze. This is separate from the ZIP classification bug.

`coverage_windows_ci.normalize_pe_build_metadata(prefix)` now zeroes exactly those COFF/DEBUG timestamps and OptionalHeader.CheckSum fields, preserving all other bytes, including resources. Use it from the shared Windows native inventory, and record:

```
hash_scope = "PE-prefix-with-build-timestamps-and-checksum-zeroed"
```

Do not replace code comparison with this normalization. `compare_pe_loader` separately verifies non-resource section bytes/layout (only DEBUG timestamp fields normalized), machine/entrypoint/import and other loader-critical headers/directories against the pinned official loader. It allows a non-executable .rsrc addition, and rejects overlapping sections. The actual PyInstaller timestamp transformation and a recomputed checksum were replayed on the real official `runw.exe` locally: raw bytes changed, normalized prefix stayed identical, all six loader sections matched. `windows-ci-loader-parser-replay.json` explicitly labels this **offline supplier metadata replay, not native frozen qualification**.

## 3. Remove unnecessary Windows payload at collection time

Two new hooks are automatically reachable through the existing `--additional-hooks-dir packaging`:

* `hook-PySide6.py`: preserves the stock hook but drops optional `opengl32sw.dll` on Windows. This app uses ordinary raster QWidget painting, not QOpenGLWidget/Qt Quick. Other platforms retain stock behavior. Native qwindows and offscreen screenshots still required.
* `hook-PySide6.QtNetwork.py`: on Windows retains native Schannel/certificate/network-information plugins, omits `qopensslbackend.dll`, and does not invoke the stock extra OpenSSL search. Python HTTPS retains its official `libcrypto-3.dll`/`libssl-3.dll`. The stock Qt collector searches the Qt directory/system PATH for the unrelated `libcrypto-3-x64.dll`/`libssl-3-x64.dll`; their original precise source directory is absent from discovery and their old bytes remain unapproved. Other platforms use the unmodified stock QtNetwork hook.

The **43 UCRT/API-set files need not be distributed** for Windows 10+; UCRT is an OS component and Windows uses its system copy even when an app-local copy exists. Qt 6.8 supports Windows 10 1809+ / Windows 11. PyInstaller's `depend/dylib.py:138-147` explicitly documents its broad inclusion policy as needed for older targets.

Shared owner: apply the supplied `windows10_runtime_binaries(a.binaries)` **after Analysis dependency collection and before constructing EXE**, normally in the generated .spec:

```python
# Windows-10-1809+ branch only, after a = Analysis(...)
a.binaries = windows10_runtime_binaries(a.binaries)
# Then PYZ/EXE construction with this a.binaries.
```

It excludes only `ucrtbase.dll`, `api-ms-win-core-*.dll`, and `api-ms-win-crt-*.dll`. It preserves MSVC DLLs. Never remove these from an already-frozen inventory to fake coverage. If retaining the UCRT copies instead, their exact SDK/OS supplier and applicable redistribution license still must be obtained; there is no source-disclosure obligation merely because they are native.

`windows-ci-minimal-policy.json` contains **46 exact supplier-matched expected native path/hash entries plus a freshly verified loader = expected 47**, conditional on these collection changes and corrected inventory. It is marked **candidate-policy-not-coverage**. Unexpected/missing/changed payload must fail. Old loader hashes are not valid for new source/resources.

Sources: https://doc.qt.io/qt-6.8/supported-platforms.html ; https://doc.qt.io/qt-6.8/windows-graphics.html ; https://learn.microsoft.com/en-us/cpp/windows/universal-crt-deployment?view=msvc-170 .

## 4. Supplier / license evidence actually resolved

`windows-ci-supplier-verified.json` retains 167 verified supplier-match rows (148 unique payloads), exact member hashes, actual PE/data classification and PE file versions. Every referenced wheel/ZIP was checked against the pinned supplier input SHA-256 before reading its members.

* CPython 3.12.10 DLLs/extensions and `libffi-8.dll` match the actual python.org embeddable ZIP. Its LICENSE.txt is byte-identical to the already-retained `licenses/windows-ci/CPython-3.12.10-Windows-LICENSE.txt` (no duplicate asset necessary).
* Official Python `libcrypto-3.dll` and `libssl-3.dll` match that ZIP and report version **3.0.16.0**. Existing OpenSSL 3.0.16 Apache license and Python composite notices cover this supplier, subject to the applicable notice terms; no corresponding-source requirement was invented.
* **All 10 MSVC DLL paths match official archives**: PySide/Shiboken supply **14.39.33520.0**; CPython supplies **14.42.34438.0**. Supplier identification is resolved, not a reason to keep searching for source.
* The only retained mandatory source inputs remain Qt Base, PySide setup, and Qt translations under their selected copyleft terms. Existing conservative static closure and required third-party notices remain in the review. Removing a native-data misclassification does not remove those obligations.

### Actual Microsoft permission/terms still missing

The publisher must establish its applicable redistribution entitlement (e.g. licensed Visual Studio distribution rights or an applicable downstream distributor grant), and require distributors/external end users to agree to protective Microsoft-runtime terms. Python's exact shipped Windows LICENSE explicitly requires this. The retained VS 2022 Community license section 4 gives the distributable-code grant and downstream-agreement condition; the retained Microsoft deployment page says redistribution of individual runtime DLLs is limited to licensed Visual Studio users. A public DLL filename or presence on a CI runner is not that entitlement.

No paid-license purchase is asserted necessary: Community/open-source eligibility may apply. No consent was accepted on the user's behalf. These conditions apply to Microsoft's code only; do not impose a blanket reverse-engineering ban or proprietary terms on LGPL Qt/PySide or the builder. If publisher entitlement and scoped downstream assent are already established, record that evidence and implement/verify the scoped notice route through the shared gate. Do not demand Microsoft's proprietary source.

## 5. Exact native probe mechanism — inside the freeze job

The packager deletes its private candidate when its context exits on coverage failure. Therefore run this probe **after creating the first candidate/discovery and before add_native_compliance raises**, or keep it in a separate private native CI staging procedure. Upload only allowlisted diagnostic JSON, never the unqualified EXE.

The cache must contain this official wheel from `supplier_inputs`:

```
pyinstaller-6.13.0-py3-none-win_amd64.whl
SHA256 8d4296236b85aae570379488c2da833b28828b17c57c2cc21fccd7e3811fe372
```

Native Windows command (EXE/INVENTORY/CACHE are the job's actual private paths):

```powershell
python packaging/coverage_windows_ci.py probe-loader --executable "$EXE" --inventory "$INVENTORY" --cache "$CACHE" --output "$REPORT"
```

Or call `probe_native_loader(candidate, discovery, review, supplier_cache)` inside the shared build before its private candidate is deleted. The inventory may be the raw inventory object or wrapped native discovery. The command checks native Windows x64, whole-artifact hash, actual CArchive prefix/hash scope, pinned wheel hash, and official `PyInstaller/bootloader/Windows-64bit-intel/runw.exe` section identity. Its result remains `evidence-only`, bound to artifact/loader/source hashes and any supplied discovery identity. The CI owner must supply verified checkout/run identity rather than treating a missing identity as qualification.

After collection and shared-gate integration, inspect the new native inventory against the 46 expected non-loader entries and separately verified loader. Run the actual frozen `--runtime-info`, `--smoke-test --screenshot ...` using both qwindows and offscreen platforms; inspect screenshots for text. Confirm unwanted binaries are absent from the actual CArchive. Complete the native modified-LGPL-library/rebuild demonstration from `licenses/windows-ci/REBUILD.md`, then re-freeze and verify the final compliance export and source hashes. A green source AAB job is not this frozen qualification.

## Verification performed locally

* `python -m pytest tests/test_windows_ci_coverage.py tests/test_packaging.py -q`: **140 passed**.
* Windows module alone: **22 passed**, including hook behavior, unchanged MSVC preservation, native-platform guard, section/code/entrypoint/overlap rejection, timestamp normalization, and fail-closed materialization.
* All 167 recorded archive-member matches rechecked against real hash-pinned supplier archives; source-only resources are not called native code.
* No host access, commit, push, native Windows execution, entitlement assumption, or EXE publication.
