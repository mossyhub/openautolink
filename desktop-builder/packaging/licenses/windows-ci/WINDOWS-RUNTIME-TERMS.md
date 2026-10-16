# Windows Microsoft-runtime assent — implementation evidence, not release approval

## Supplier basis and exact scope

Reviewed retained supplier text (not rewritten by this change):

| Input | SHA-256 | Relevant provisions |
| --- | --- | --- |
| `CPython-3.12.10-Windows-LICENSE.txt` | `e502c6b880ff58d614901495a9009c136539cd0b1e2a2abb8fc00b934c203419` | Lines 283–320: embedded Microsoft Distributable Code, protective downstream agreement, notice/trademark/platform/malicious-program conditions, explicit preservation of Python's own licenses. |
| `Visual-Studio-2022-Community-License-EN.txt` | `0ff50aa7e32670b49af349ede7b464bed38cee0eb496767bd01c5f21223375a4` | Retained extraction of official terms: lines 38–49 (section 4 redistribution); 56–62 (Microsoft-code protections, subject to redistribution permission); 65 (exports); 67–77 (mandatory rights, warranty/damages). |
| `Visual-Studio-2022-Community-License-EN.docx` | `41a207b10c8ab91d0d2f10a854715f73dca54509581692d2fe179aa3ffcb8540` | Original supplier document underlying the text extraction. |

The runtime UI deliberately does **not** ask the end user to accept the entire
Visual Studio IDE license. It does not import IDE seat/enterprise rules,
telemetry consent, extension-development rules, or a paid-license assumption.
Its protective conditions cover **only Microsoft code**, whether separately
bundled or embedded. Python, Qt, PySide, Shiboken, builder, generated application,
and other component licenses stay independent. The LGPL modification,
replacement, recombination, and reverse-engineering-for-debugging rights are
explicitly preserved. The Excluded License restriction is limited to Microsoft
Distributable Code source, not a prohibition on using LGPL libraries.

## Coverage integration contract

`oal_builder/windows_terms.py` embeds the authoritative display text directly;
there is no optional data asset whose omission can silently bypass the gate.

* `TERMS_VERSION`: `1`.
* `acceptance_token()` returns version + `:` + SHA-256 of `TERMS_TEXT.encode("utf-8")`.
* Current token: `1:de66f4da4023f467b4471f3f3458c3a440e62d6af6fce0df88d4392f15296876`.
* Key: `windowsMicrosoftRuntimeTerms/acceptedVersionAndSha256`.
* Store: explicit `QSettings.NativeFormat`, `UserScope`, organization
  `OpenAutoLink`, application `OpenAutoLink Builder`. Organization/system
  fallbacks are disabled: machine-wide assent cannot stand in for this user.
* There is no acceptance CLI switch or environment-variable override. Ordinary
  Windows entry enters this gate **before importing/constructing BuilderWindow**.
* Accept is not the default button or initially focused. Decline is the safe
  default; closing, Escape, and initial Return reject. Acceptance requires an
  intentional activation of Accept. The dialog shows the complete scrollable
  text, not a checked checkbox or a link substituted for the terms.
* Only explicit acceptance is saved. Version or text changes trigger a fresh
  prompt. Settings access/status/write/sync/readback errors block normal use;
  errors do not leak raw settings exceptions or paths. Acceptance is local state,
  not a signed audit record or proof of user identity, and does not resist a user
  editing their own settings/source.
* Non-Windows launches do not consult or write this consent store.
* The frozen `packaging/runtime_hook.py` retains its early, display-independent
  `--export-compliance DESTINATION.zip` and `--runtime-info DESTINATION.json`
  paths. Both are reachable without assent, including after decline/settings
  failure. Those options remain frozen-only, not new source-mode options.
* `--smoke-test` and `--screenshot` render the real builder with disabled controls
  and read-only backend APIs; they do not consult/save assent or QSettings.
  `--terms-preview` separately renders the real terms dialog with Accept disabled
  and cannot be combined with `--smoke-test`. Neither automated path proves human
  agreement or an interactive post-assent build. Frozen native CI must exercise
  the builder and terms preview separately; a terms-only screenshot is not a
  successful builder smoke test.

A coverage integrator can bind this source module, entry point, tests, and the
text token to the exact packaged source snapshot. Do not substitute a statement
that CI or a user accepted for implementation evidence. Retain the supplier
notice files and applicable per-payload grant/origin mappings separately. This
change intentionally does not edit the packaging gate, coverage manifests, or
runtime hook and does not clear a distribution blocker by itself.

Recompute evidence from `desktop-builder` without Qt initialization:

```sh
python -c 'from oal_builder.windows_terms import acceptance_token; print(acceptance_token())'
python -m pytest tests/test_windows_terms.py -q
```

## Verification and limits

Local verification uses Python 3.12.15 in
`/hermes_work/oal-builder-verify-venv`, real PySide widgets offscreen, isolated
INI settings, and an explicit Windows-platform branch replay on Linux. It is
**not** native Windows registry, native Windows input, frozen-EXE, signing, or
release qualification. The hook test executes the real hook in a subprocess
with Qt/assent imports forbidden and a clearly marked fixture compliance ZIP;
that fixture is not a release artifact.

Local verification of the integrated implementation:

* Independent terms/UI/Windows-coverage review: **119 passed, zero skipped**,
  including the hash-pinned official-loader/native-diagnostic replay.
* Parent combined suite: **447 passed, 6 skipped**. Skips covered native Windows
  execution and optional supplier replays; the Windows supplier replay was then
  run separately with its real inputs (**38 passed**).
* Both the read-only builder and separate terms preview were rendered and
  visually inspected on Linux. These are scratch evidence, not native Windows
  captures, human assent, or shipped artifacts. The terms preview has disabled
  Accept; ordinary builder smoke renders the actual builder with disabled controls.

For that Linux verification, use child-process environment only:

```sh
export LC_ALL=C.UTF-8
export FONTCONFIG_FILE=/home/hermeswebui/.hermes/profiles/dev/cache/scratch/oal-fonts.conf
export LD_LIBRARY_PATH=/home/hermeswebui/.hermes/profiles/dev/cache/scratch/oal-qt-libs/extracted/usr/lib/x86_64-linux-gnu:/home/hermeswebui/.hermes/profiles/dev/cache/scratch/oal-qt-libs/root/usr/lib/x86_64-linux-gnu
/hermes_work/oal-builder-verify-venv/bin/python -m pytest tests/ -q --tb=short
```

The focused test file covers decline, explicit accept and reopened acceptance,
changed text/version, non-Windows bypass, settings status/exception/readback
failures, an actual unwritable store, per-user native store selection and disabled
fallbacks, real dialog Accept/Decline/close/Escape/Return, automation without
assent or settings mutation, separate real-builder/terms-preview construction,
entry-point ordering, both diagnostic routes,
and Microsoft-only/LGPL wording safeguards.

Native release qualification must additionally exercise first-launch decline,
accept and reopen with an isolated Windows user/settings hive, changed-token
re-prompt, inaccessible settings, both diagnostic exports, and the actual frozen
UI after human assent. Never seed production acceptance to make smoke pass.

## Remaining distribution/legal review questions

1. Confirm that this scoped wording provides the required “at least as much”
   protection under the **actual applicable grant for each shipped supplier**,
   including warranty/damages and mandatory-law/open-source exceptions. The
   CPython Windows notice expressly describes compliant downstream distribution;
   do not replace that with an invented paid publisher-license requirement.
2. Independently establish the grant and permitted unmodified release-code
   provenance for Microsoft bytes supplied by PySide/Shiboken or other suppliers.
   The assent mechanism cannot supply a missing redistribution right.
3. Confirm how distributors who redistribute without running the GUI assent to
   and pass through the applicable terms. First launch addresses external end
   users; it is not evidence that every downstream distributor agreed. The text
   requires downstream pass-through, but no download/distributor agreement flow
   is implemented here.
4. Review whether first-use assent is sufficient for the selected distribution
   channel, recognizing that Python/Qt/Microsoft startup code necessarily runs to
   display the dialog and diagnostics. This is an application-use gate, not an
   installer/pre-execution license gate or a change to statutory rights.
5. Complete exact frozen-payload coverage, notices/source/recombination duties,
   native qualification, and other release gates independently. No overall
   Windows distribution approval or claim of Microsoft endorsement is made.
