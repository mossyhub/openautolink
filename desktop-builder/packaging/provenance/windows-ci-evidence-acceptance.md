# Windows candidate evidence acceptance

This is the acceptance contract, **not a native result or release approval**.
`build.windows_candidate_coverage` collects probes from the private candidate,
then calls `accept_candidate_evidence` before materialization. Imported reports
are never used to skip collection. No source-build requirement is added to the
AAB-only community product or to the unchanged maintainer release process.

## Technical gates

Each native report is bound to the inner artifact SHA-256, embedded builder
source SHA-256, clean checkout/expected commit, Windows runner, run ID and attempt.
`run_candidate_probe` checks the original candidate before and after collection,
checks its actual embedded source bytes, and rejects conflicting child bindings.
Local/non-Windows reports cannot qualify this path.

The loader must match the exact pinned official PyInstaller wheel's member,
non-resource sections and critical headers under the established PE metadata
normalization. The actual inventory must match the runtime-free bootstrap policy;
standalone MSVC, UCRT and API-set DLLs have no acceptance route.

Only these fixed findings can be resolved by the new acceptance step:

| Finding | Required evidence |
| --- | --- |
| `WINDOWS_FROZEN_UI_UNTESTED` | Actual frozen read-only BuilderWindow on `windows` and `offscreen`, plus a separate `--terms-preview` rendering on `windows`; exact terms token and hash-pinned assent/entrypoint/test source. |
| `WINDOWS_RECOMBINATION_UNTESTED` | Exact supplier QtCore clone with a changed overlay, archived replacement hash, original coverage rejection, and Win32 runtime observation proving that modified bundled QtCore and the bundled qwindows plugin actually loaded with BuilderWindow. Original candidate remains unchanged. |
| `WINDOWS_INSTALLED_RUNTIME_UNTESTED` | Exact official NSIS stub code/imports, actual outer extraction matching all five staged members, and actual outer BuilderWindow smoke with `QT_QPA_PLATFORM=windows` using an already installed runtime. Resolves only this narrow gate. |
| `WINDOWS_EMBEDDED_MICROSOFT_TERMS_UNVERIFIED` | The separate terms preview/source proof permits splitting the specific CPython downstream grant from the independently scoped compiled-loader supplier route below. CPython's grant does not cover other suppliers. |

Missing evidence retains its finding. Failed, stale, mismatched or malformed
evidence is rejected. Unknown findings remain blockers. An explicit coverage
file cannot erase a live blocker or replace the collected evidence.

All reports retain `notQualification=true`. The technical probes do not prove
screenshot readability, a Qt source rebuild, arbitrary replacement compatibility,
or Microsoft's installer consent/cancellation/UAC/restart behavior on a recipient
machine. The Qt probe modifies a non-loaded overlay, not executable Qt code.
The installed-runtime bootstrap smoke never accepts or installs Microsoft code.

The historical combined `WINDOWS_BOOTSTRAP_UNVALIDATED` gate is **not cleared**.
During acceptance it becomes the separate clean-machine installation-path gate,
with explicit `acceptance`, `cancellation` and `failure` statuses all `not-tested`.
There is no imported-report or installed-runtime shortcut for this gate.
`test_native_powershell_control_flow_never_accepts_terms` runs native PowerShell
with fake external operations: even a passing Windows replay is unit/control-flow
evidence, not actual installer UI, UAC, cancellation, failure or restart integration.
Linux skips those native cases. A human clean-machine integration result remains
required; neither a fabricated assent nor removal of this gate is a ship route.

## Narrow Microsoft grant and unresolved items

The official `python-3.12.10-embed-amd64.zip` has SHA-256
`4acbed6dd1c744b0376e3b1cf57ce906f9dc9e95e68824584c8099a63025a3c3`.
Its exact Windows LICENSE has SHA-256
`e502c6b880ff58d614901495a9009c136539cd0b1e2a2abb8fc00b934c203419`.
Lines 283–320 expressly describe Microsoft code embedded by the linker in the
Windows binaries and permit compliant downstream redistribution. They require
protective distributor/external-end-user terms, not purchase of a publisher's
Visual Studio license or an invented entitlement certificate.

The shared validator retains the conditional CPython-specific Microsoft route:
`LicenseRef-Microsoft-CPython-3.12.10-Embedded-Code`, component
`microsoft-cpython-embedded`. It covers only the exact original supplier members
assigned to `cpython`, `cpython-libffi` and `cpython-openssl`. It retains the
complete existing supplier notices and Microsoft-specific conditions. All three
candidate-bound native probes, exact supplier mappings, original parent payloads,
static edges, scope and source evidence are required by the shared gate. No
standalone Microsoft DLL, different Python version or other supplier can use it.
The independent PSF, LGPL and other licenses are unchanged; this is not MIT
relabeling or a proprietary corresponding-source requirement.

## Derived bootstrap mapping correction

The original `windows-ci-reviewed.json` stays byte-for-byte historical evidence,
SHA-256 `e5b6ceba87c8d96afcb13d52a0032446b0f6212acd73ff750ab74762fbff8998`.
Only the derived runtime-free bootstrap review removes `microsoft-msvc` from
`qt-Core`, `qt-Gui`, `qt-Network`, `qt-Widgets`, `qt-plugins`, `pyside-shiboken`,
`cffi` and `cryptography`: **19 retained native item scopes in eight components**.
All their licenses, notices, corresponding sources, build instructions, supplier
member assignments and other static edges remain unchanged. The CPython grant
and the evidenced static PyInstaller-loader edge are not removed.

The official Qt/PySide/Shiboken grants are LGPL/GPL (the existing LGPL route is
retained), CFFI is MIT-0, and cryptography offers Apache/BSD (the existing BSD
choice is retained). MSVC compilation or dynamic runtime imports alone are not
evidence for a static Microsoft edge. Removing that unsupported mapping does not
assert universal absence of compiler code and does not impose a proof-of-absence
or a new license requirement on these suppliers.

The emitted `bootstrapMappingCorrection` binds the rationale to each component's
exact native payload, retained notice paths/digests, and supplier wheels:

| Exact wheel | SHA-256 |
| --- | --- |
| `PySide6_Essentials-6.8.3-cp39-abi3-win_amd64.whl` | `3c0fae5550aff69f2166f46476c36e0ef56ce73d84829eac4559770b0c034b07` |
| `shiboken6-6.8.3-cp39-abi3-win_amd64.whl` | `bca3a94513ce9242f7d4bbdca902072a1631888e0aa3a8711a52cc5dbe93588f` |
| `cffi-2.1.1-cp312-cp312-win_amd64.whl` | `f53e442b08449d42821fa4a4fba000095af9f62742a500f978a9f557ec44339a` |
| `cryptography-44.0.3-cp39-abi3-win_amd64.whl` | `5d186f32e52e66994dce4f766884bcb9c68b8da62d61d9d215bfe5fb56d21334` |

Retained grant/notice bundles include QtBase
`674045f873e29c29aa7402849fec82a3dd968be06464d9d0b3e8437409320d6a`,
PySide source `ac71dd412cdaf692176393339dbb8a6b3725cc68eda9486fcb699011e992b496`,
CFFI wheel `81eb43aaf74d27680b0e1c6d9b05651fb0b606c81cb145b586b469319eb76dd8`,
and cryptography wheel
`28eaacb86dfb3914bc4e16540da2e6759637e844298196d7686a53d2c531ebeb`.
All retained component notices are checked from local bytes; changed notices,
new/unknown suppliers, changed wheel/member hashes and changed component grants
reject this correction rather than inheriting it by filename or component label.

### Exact compiled-loader supplier distribution route

The remaining non-CPython static Microsoft scope is still recorded as
`pyinstaller-embedded-loader` / `@bootloader` → `microsoft-msvc`. It is not
removed, relabeled GPL, or covered by CPython's grant. The derived review now
uses the exact upstream **compiled-binary combination distribution grant** to
resolve `WINDOWS_EMBEDDED_MICROSOFT_SUPPLIER_GRANTS_UNPROVEN` for this one scope.

The [PyInstaller 6.13.0 COPYING](https://github.com/pyinstaller/pyinstaller/blob/v6.13.0/COPYING.txt)
expressly gives “unlimited permission to link or embed compiled bootloader and
related files into combinations with other programs, and to distribute those
combinations without any restriction coming from the use of those files.” It
also preserves GPL restrictions in other respects, including modification and
distribution outside a combined executable. This policy relies on the supplier's
published grant for its compiled binary; it is not a conclusion that Microsoft
code is GPL-licensed, a grant for unrelated suppliers, or a legal guarantee.

The route binds all of these exact bytes:

| Input | SHA-256 |
| --- | --- |
| `pyinstaller-6.13.0-py3-none-win_amd64.whl` | `8d4296236b85aae570379488c2da833b28828b17c57c2cc21fccd7e3811fe372` |
| `PyInstaller/bootloader/Windows-64bit-intel/runw.exe` | `7ed67be1a51d6d7c6d0d912b77e61c0950f8cdb7b9403f1de4da46c5506b337f` |
| Wheel `pyinstaller-6.13.0.dist-info/COPYING.txt` | `dcf75fdb959db1e3b41c0f8505069d2ece781b5ec6b3d0a4d30975cfc6580245` |
| Retained wheel notice bundle | `563755bbf4aaf6ffcb409adcaf1046cf69df26a669d6221de02bdb6cbf9ea70b` |

The notice bundle wraps the original COPYING bytes with a labeled header and
one final separator newline. The gate checks both the complete bundle and the
original member digest from those retained bytes. It retains every loader and
Microsoft notice, the loader GPL exception, `pyinstaller-static-zlib`, the
Microsoft static-runtime record/license, and all CPython-specific conditions.
The [supplier bootloader documentation](https://pyinstaller.org/en/v6.13.0/bootloader-building.html)
remains evidence for the static runtime; the grant does not erase that fact.

`microsoft-msvc.distribution_basis` records the exact supplier route, member,
COPYING/notice hashes, and actual candidate-prefix hash and hash scope. The
supplier-member hash and normalized candidate-prefix hash are distinct. Native
code equivalence, critical-header checks and candidate-bound source/identity
proof remain mandatory; adding this policy does not loosen the PE comparator.
The shared validator replays the native report contract and requires all three
candidate probes, the exact original suppliers, this sole parent and unchanged
static closure. New versions/suppliers, standalone loaders/DLLs, changed code,
notices, exception or scope do not inherit the route. Unknown scopes reject.

Only the supplier-grant finding is resolved by this route. Unknown findings and
missing technical proof remain blockers. In particular,
`WINDOWS_BOOTSTRAP_UNVALIDATED` remains the separate **unresolved clean-machine
installer gate**, even when every installed-runtime probe passes. No report
creates human assent and this change does not authorize release by itself.

## Assent and trust boundary

`pythonDownstream.sourceEvidence` in `windows-ci-bootstrap-policy.json` remains
hash-pinned. It is **not digitally signed**. Those hashes bind the reviewed
terms implementation, entrypoint and tests; native collection also checks those
bytes in the actual candidate source ZIP. The explicit Accept mechanism is
version/digest-bound and Microsoft-code-only, preserving LGPL replacement and
reverse-engineering for debugging such modifications. The separate preview has
Accept disabled. The reports explicitly record `assent=not-given`; neither CI
nor this gate represents that any publisher, distributor or user accepted terms.

Report validation is a consistency contract for trusted, in-process CI collection,
not cryptographic authentication of arbitrary JSON. Preserve the trusted checkout
and workflow/artifact provenance. The final second-pass package must still pass
existing exact-payload/source/compliance and final startup checks; a private
candidate report does not qualify a different final executable.
