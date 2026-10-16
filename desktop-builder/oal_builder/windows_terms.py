"""Windows-only protective assent for Microsoft code, not an app-wide EULA.

The text is embedded in Python so source and frozen entry points show identical
terms without an optional data-file dependency. See packaging/licenses/windows-ci/
WINDOWS-RUNTIME-TERMS.md for the supplier basis and coverage integration limits.
"""
from __future__ import annotations

import hashlib
import sys

TERMS_VERSION = "1"
TERMS_TEXT = """Microsoft runtime code — protective terms (version 1)

SCOPE AND YOUR OTHER LICENSES
These terms apply ONLY to Microsoft Distributable Code included in this Windows
build, including Microsoft runtime code embedded in Python binaries and any
separately bundled Microsoft runtime DLLs (the “Microsoft code”). They do not
relicense Python, Qt, PySide, Shiboken, OpenAutoLink, this builder, your generated
applications, or any other non-Microsoft code. Their own licenses continue to
apply. Merely being in the same executable does not make code Microsoft code.

Nothing here restricts rights granted under those other licenses, including LGPL
rights to modify or replace Qt/PySide/Shiboken libraries, relink or recombine the
application, and reverse engineer the application for debugging modifications to
those libraries. Nothing here removes rights that applicable law does not allow
to be waived, or exceptions required by third-party open-source license terms.

PROTECTION OF MICROSOFT CODE ONLY
By choosing Accept, you agree to the following solely for the Microsoft code:

1. The Microsoft code is licensed, not sold. Microsoft and its suppliers retain
   their rights. Use it only as permitted by its applicable licenses, subject to
   their technical limitations. Do not work around those limitations or reverse
   engineer, decompile, disassemble, or otherwise attempt to derive its source,
   except as permitted by applicable law despite this limitation or as required
   by third-party licensing terms governing included open-source components.
2. Do not remove, minimize, block, or modify Microsoft or supplier copyright,
   trademark, patent, or other notices in the Microsoft code. Do not use
   Microsoft's trademarks in a name or manner suggesting Microsoft origin or
   endorsement of an application containing the Microsoft code.
3. Do not distribute the Microsoft code to run on platforms other than Microsoft
   operating systems, run-time technologies, or application platforms. Do not
   include it in malicious, deceptive, or unlawful programs or use it unlawfully.
   Comply with domestic and international export laws applicable to that code,
   including destination, end-user, and end-use restrictions.
4. Redistribute Microsoft runtime code only where its applicable supplier grant
   permits, as unmodified, permitted release object code forming part of an
   application with significant primary functionality, not as a stand-alone
   offering. Do not redistribute Microsoft runtime components marked Preview,
   Pre-release, Beta, or non-redistributable debug components. Do not rent or lease
   the Microsoft code independently of a permitted application distribution.
5. If you redistribute the Microsoft code as part of an application, require
   your distributors and external end users to agree to terms protecting that
   code at least as much as these terms and the applicable Microsoft grant.
   Preserve and provide its applicable notices and license terms. Acceptance
   here is not permission to redistribute code outside that grant.
6. Do not modify or distribute Microsoft Distributable Code source so that any
   part becomes subject to an “Excluded License”: one requiring, as a condition
   of use, modification, or distribution, disclosure/distribution of that code
   in source form or rights for others to modify it. This restriction does NOT
   apply to independently licensed Python, Qt, PySide, Shiboken, builder, or
   application code and does not replace or narrow their licenses.
7. The Microsoft code is provided “as is”. To the extent permitted by applicable
   law, Microsoft gives no express warranties, guarantees, or conditions and
   excludes implied warranties of merchantability, fitness for a particular
   purpose, and non-infringement. To that same extent, recovery from Microsoft
   and its suppliers for that code is limited to direct damages up to US $5.00,
   excluding consequential, lost-profits, special, indirect, and incidental
   damages, including contract and tort claims, even if warned of the possibility.
   Mandatory consumer rights and statutory remedies are unaffected.

YOUR CHOICE
Accept records agreement to these Microsoft-code-only terms for this user on
this computer. Decline (or closing this dialog) exits without opening the builder.
No Microsoft account, paid Visual Studio license, or paid publisher license is
asserted or requested here. This agreement does not establish this distributor's
rights to every bundled runtime; that remains a separate distribution review.

You can inspect/export the frozen application's notices and corresponding source
without accepting: --export-compliance DESTINATION.zip. Runtime diagnostics are
also available without accepting: --runtime-info DESTINATION.json. Automated UI
smoke tests and screenshots do not count as agreement and never save acceptance.
"""


ACCEPTANCE_KEY = "windowsMicrosoftRuntimeTerms/acceptedVersionAndSha256"


def acceptance_token() -> str:
    """Bind assent to both the reviewed version and exact UTF-8 display text."""
    return TERMS_VERSION + ":" + hashlib.sha256(TERMS_TEXT.encode("utf-8")).hexdigest()


class TermsSettingsError(RuntimeError):
    """Acceptance could not be reliably read or persisted; normal use is blocked."""


_SETTINGS_ERROR = (
    "Cannot verify or save Microsoft runtime terms acceptance in your user settings. "
    "The builder will not open. Check settings permissions and try again. "
    "--export-compliance and --runtime-info remain available without acceptance."
)


def ensure_windows_terms(*, platform=None, settings=None, prompt=None) -> bool:
    """Return whether normal use may proceed; only an explicit True is assent.

    No environment/CLI acceptance override. Callers must not call this in bounded
    read-only builder smoke mode or the separate non-accepting --terms-preview.
    Neither automation mode grants normal builder use or records legal assent.
    """
    if (sys.platform if platform is None else platform) != "win32":
        return True
    from PySide6.QtCore import QSettings

    token = acceptance_token()
    try:
        # Disable organization/system fallbacks: another user's or machine-wide
        # value is not this user's assent. Explicit NativeFormat/UserScope also
        # avoids relying on application-global QSettings defaults.
        if settings is None:
            settings = QSettings(QSettings.Format.NativeFormat, QSettings.Scope.UserScope,
                                 "OpenAutoLink", "OpenAutoLink Builder")
        settings.setFallbacksEnabled(False)
        settings.sync()
        stored = settings.value(ACCEPTANCE_KEY)
        if settings.status() != QSettings.Status.NoError:
            raise TermsSettingsError(_SETTINGS_ERROR)
        if stored == token:
            return True
    except Exception:
        raise TermsSettingsError(_SETTINGS_ERROR) from None

    if prompt is None:
        from PySide6.QtWidgets import QDialog
        dialog = create_terms_dialog()
        accepted = dialog.exec() == QDialog.DialogCode.Accepted
    else:
        accepted = prompt(TERMS_TEXT) is True
    if not accepted:
        return False
    try:
        settings.setValue(ACCEPTANCE_KEY, token)
        settings.sync()
        stored = settings.value(ACCEPTANCE_KEY)
        if settings.status() != QSettings.Status.NoError or stored != token:
            raise TermsSettingsError(_SETTINGS_ERROR)
    except Exception:
        raise TermsSettingsError(_SETTINGS_ERROR) from None
    return True


def create_terms_dialog(*, automated=False):
    """Build the real first-launch UI; automated preview cannot record assent."""
    from PySide6.QtWidgets import (
        QDialog, QHBoxLayout, QLabel, QPushButton, QTextBrowser, QVBoxLayout,
    )

    dialog = QDialog()
    dialog.setWindowTitle("Microsoft runtime terms — OpenAutoLink Builder")
    dialog.resize(760, 640)
    layout = QVBoxLayout(dialog)
    heading = QLabel("Microsoft code only — your open-source license rights are unchanged.")
    heading.setWordWrap(True)
    layout.addWidget(heading)
    browser = QTextBrowser()
    browser.setPlainText(TERMS_TEXT.rstrip("\n"))
    browser.setOpenExternalLinks(False)
    layout.addWidget(browser)
    if automated:
        label = QLabel("Automated preview — no acceptance is recorded; the builder is not opened.")
        label.setWordWrap(True)
        layout.addWidget(label)
    buttons = QHBoxLayout()
    buttons.addStretch()
    decline = QPushButton("Decline")
    decline.setObjectName("declineMicrosoftTerms")
    decline.setDefault(True)
    decline.clicked.connect(dialog.reject)
    accept = QPushButton("Accept")
    accept.setObjectName("acceptMicrosoftTerms")
    accept.setAutoDefault(False)
    accept.setDefault(False)
    accept.setEnabled(not automated)
    if not automated:
        accept.clicked.connect(dialog.accept)
    buttons.addWidget(decline)
    buttons.addWidget(accept)
    layout.addLayout(buttons)
    decline.setFocus()
    return dialog
