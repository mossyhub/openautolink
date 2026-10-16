# Windows x86_64 CI review — blocked, not distribution approval

This review binds the actual discovery from run 38861042629, attempt 1, commit
40c10a5f68f6e7ecb17e95053da075df944314ba. It does not qualify a Windows release.
`windows-ci-reviewed.json` deliberately retains unresolved obligations and
unsupported license expressions. Do not change them to MIT, delete payloads,
or clear blockers to make the packaging gate green.

## Established evidence

* Official hash-pinned Windows Essentials, Shiboken, cryptography, CFFI and
  PyInstaller wheels were downloaded. `supplier_matches` records byte matches,
  not filename guesses. Installed pip metadata is not falsely described as a
  member of the supplier wheel.
* CPython is **3.12.10**, not the container's 3.12.15. Its official embeddable
  archive matches the observed Python DLLs/extensions, libffi, OpenSSL 3.0.16
  DLLs and root VCRUNTIME DLLs. Its Windows LICENSE.txt has additional Microsoft
  conditions; PSF alone does not cover Microsoft's compiled code.
* Cryptography's Windows `_rust.pyd` contains Rust compiler commit
  `05f9846f893b09a1be1fc8560e33fc3c815cfecb` (Rust 1.86.0) and
  `OpenSSL 3.4.1 11 Feb 2025`. The reusable exact-version Cargo and Rust notice
  closure is a conservative superset, not a claim all target/build-only crates
  were linked. All dependencies retain their independent license records.
* CFFI 2.1.1's Windows x64 setup.py builds the **in-tree libffi_x86_x64** code
  and links win64.obj. It is not the Linux wheel's libffi 3.4.6 build. The
  Windows-specific notice bundle preserves all source headers, assembly and
  the setup recipe without executing them.
* Qt Base 6.8.3 and PySide 6.8.3 source archives/notices are reused; Windows
  Wintab's explicit unrestricted grant is added separately. Optional Qt static
  dependencies remain a labelled conservative notice/source closure.
* Qt translations are not silently excluded because they are data. Their
  observed paths are assigned, with the exact 6.8.3 source archive and its
  licenseRule.json (module default LGPL-3.0-only option). The source archive
  matches Qt's published SHA-256. They are not all present in Qt Base.
* `opengl32sw.dll` matches the official wheel and embeds **Mesa 11.2.2 / LLVM
  3.6.2** strings. Qt 6.8's Mesa notices and LLVM 3.6.2's official license are
  collected. LLVM's own license identifies additional third-party terms;
  the remaining closure is a blocker, not falsely called blanket MIT.
* PyInstaller's Windows loader has bundled zlib, unlike the examined Linux
  loader. Its separate zlib notice is retained. The loader exception is scoped
  to embedding, not arbitrary GPL code or a waiver of Microsoft's terms.

## Microsoft rights and limits

The official Visual Studio Community 2022 license was retrieved as DOCX from
Microsoft's license page. The adjacent text is an XML paragraph-text extraction;
the original DOCX is preserved. Section 4 permits listed object-code distribution,
including downstream distributors, subject to conditions. Significant primary
functionality and protective terms agreed to by distributors/end users are
required. Preview/beta redistribution, implied Microsoft endorsement and
subjecting Distributable Code source to an Excluded License are prohibited.

The official VS 2022 REDIST list is conditional on a validly licensed copy and
covers unmodified files under VC/redist with exclusions including debug_nonredist.
The official UCRT deployment document identifies the SDK Redist/ucrt/DLLs
location for app-local distribution. A DLL copied from System32 is not proven to
be a redistributable SDK file merely by its name. Discovery provides no
source-location/version/signature evidence for these UCRT/API-set DLLs and most
MSVC copies. A runtime bundled in a Qt wheel is likewise not a blanket grant.

No Microsoft source archive is required by this proprietary redistribution
route. What is required before approval is an evidenced applicable grant,
exact permitted unmodified runtime bytes, and compliance with its distribution
conditions. This task cannot attest the distributor's entitlement or invent an
end-user agreement mechanism. Notice inclusion alone is insufficient. The
review records the unresolved facts explicitly.

## Integration blockers (parent-owned shared code)

1. Add a **conditional proprietary redistributable route**, not a generic
   notice-only allowlist entry: exact terms/redist evidence hashes, per-file
   permitted supplier-origin binding, applicable grant/entitlement basis,
   unmodified release-code scope and protective end-user/distributor terms.
   Keep `LicenseRef-Microsoft-VisualStudio-2022-Distributable-Code` and
   `LicenseRef-Microsoft-Windows-SDK-UCRT` unsupported until this is implemented
   and the missing facts are supplied. No source requirement for those terms.
2. Add scoped notice routes for `LicenseRef-Lcs-Telegraphics`,
   `LicenseRef-XZ-5.2.5-liblzma-Public-Domain`, and NCSA, backed by the attached
   exact notices. Do not alias these grants to another license.
3. The discovery labels `builder-compliance.zip`, base_library.zip, translation
   files and pip metadata as CArchive binary entries. Do not drop actual native
   data. Investigate the Windows collection path with the real executable; the
   compliance archive is self-referential in the two-pass build and cannot be
   assigned a fabricated third-party license or wildcard hash.
4. Verify the Windows PE loader code against the official loader, separating
   changed resources/headers and checksum from executable code. Do not broaden
   ELF normalization or normalize arbitrary bytes. No Windows executable was
   executed here; the diagnostic JSON cannot prove PE code equivalence.
5. Identify the actual `libcrypto-3-x64.dll` / `libssl-3-x64.dll` suppliers,
   versions and notices. They do **not** match the CPython DLLs; assigning their
   version from filenames is forbidden.
6. Finish Mesa/LLVM third-party closure; retain full notices for selected
   target/runtime code. Exercise the Windows LGPL recombination recipe and the
   final frozen executable in native CI. Linux tests do not qualify Windows.

## CLI

From desktop-builder:

```
python packaging/coverage_windows_ci.py audit --inventory WINDOWS-DISCOVERY.json
python packaging/coverage_windows_ci.py fetch --cache CACHE
python packaging/coverage_windows_ci.py materialize \
  --inventory WINDOWS-DISCOVERY.json --cache CACHE --output NEW-EVIDENCE-DIR
```

Audit checks every observed path/hash/kind/size (including duplicates) and local
notice hashes; it returns exit 2 with structured blockers. Fetch only retrieves
explicit pinned source assets required for materialization; it never approves
coverage. Materialize refuses this blocked review **before creating output**.
After genuine resolution it delegates to the existing schema-2 generator and
native-platform validator. There is no `--force`/approval bypass. Use a newly
reviewed exact inventory when any payload changes; no automatic hash refresh.
