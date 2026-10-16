"""Scoped runtime assent; Qt tests use a private settings file, never user consent."""
import importlib
import os

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

import pytest


@pytest.fixture(scope="session")
def app():
    from PySide6.QtWidgets import QApplication
    return QApplication.instance() or QApplication([])


@pytest.fixture
def terms():
    try:
        return importlib.import_module("oal_builder.windows_terms")
    except ImportError:
        pytest.fail("Windows runtime assent gate is not implemented")


@pytest.fixture
def settings(tmp_path):
    from PySide6.QtCore import QSettings
    return QSettings(str(tmp_path / "consent.ini"), QSettings.Format.IniFormat)


def test_decline_blocks_without_saving_assent(terms, settings):
    seen = []
    assert not terms.ensure_windows_terms(
        platform="win32", settings=settings,
        prompt=lambda text: seen.append(text) or False)
    assert seen == [terms.TERMS_TEXT]
    assert not settings.allKeys()


def test_explicit_accept_survives_reopen(terms, settings):
    from PySide6.QtCore import QSettings
    assert terms.ensure_windows_terms(platform="win32", settings=settings, prompt=lambda text: True)
    reopened = QSettings(settings.fileName(), QSettings.Format.IniFormat)
    assert terms.ensure_windows_terms(platform="win32", settings=reopened,
                                      prompt=lambda text: pytest.fail("Already accepted current terms"))
    assert reopened.value(terms.ACCEPTANCE_KEY) == terms.acceptance_token()


@pytest.mark.parametrize("phase", ["initial-sync", "read", "write", "final-sync", "readback"])
def test_settings_failures_block_even_when_value_looks_accepted(terms, phase):
    from PySide6.QtCore import QSettings

    class BrokenSettings:
        syncs = 0
        stored = None
        broken = False
        def setFallbacksEnabled(self, enabled):
            assert enabled is False
        def sync(self):
            self.syncs += 1
            if (phase == "initial-sync" and self.syncs == 1) or (phase == "final-sync" and self.syncs == 2):
                self.broken = True
        def status(self):
            return QSettings.Status.AccessError if self.broken else QSettings.Status.NoError
        def value(self, key):
            if phase == "read":
                self.broken = True
                return terms.acceptance_token()
            if phase == "readback":
                return None
            return self.stored
        def setValue(self, key, value):
            if phase == "write":
                raise OSError("private path must not appear in error")
            self.stored = value

    with pytest.raises(terms.TermsSettingsError, match="Cannot verify or save"):
        terms.ensure_windows_terms(platform="win32", settings=BrokenSettings(), prompt=lambda text: True)


def test_unwritable_real_settings_fail_closed(terms, tmp_path):
    from PySide6.QtCore import QSettings
    blocker = tmp_path / "not-a-directory"
    blocker.write_text("blocked")
    settings = QSettings(str(blocker / "consent.ini"), QSettings.Format.IniFormat)
    with pytest.raises(terms.TermsSettingsError):
        terms.ensure_windows_terms(platform="win32", settings=settings, prompt=lambda text: True)


@pytest.mark.parametrize("changed", ["TERMS_TEXT", "TERMS_VERSION"])
def test_changed_terms_require_new_explicit_acceptance(terms, settings, monkeypatch, changed):
    assert terms.ensure_windows_terms(platform="win32", settings=settings, prompt=lambda text: True)
    prior = settings.value(terms.ACCEPTANCE_KEY)
    monkeypatch.setattr(terms, changed, getattr(terms, changed) + " revised")
    assert not terms.ensure_windows_terms(platform="win32", settings=settings, prompt=lambda text: False)
    assert settings.value(terms.ACCEPTANCE_KEY) == prior
    assert terms.ensure_windows_terms(platform="win32", settings=settings, prompt=lambda text: True)
    assert settings.value(terms.ACCEPTANCE_KEY) != prior


@pytest.mark.parametrize("platform", ["linux", "darwin"])
def test_non_windows_bypass_does_not_access_settings_or_prompt(terms, platform):
    assert terms.ensure_windows_terms(platform=platform, settings=object(),
                                      prompt=lambda text: pytest.fail("No Windows terms on this OS"))


@pytest.mark.parametrize("answer", [None, 1, "accepted", False])
def test_only_explicit_true_counts_as_acceptance(terms, settings, answer):
    assert not terms.ensure_windows_terms(platform="win32", settings=settings, prompt=lambda text: answer)
    assert not settings.allKeys()


@pytest.mark.parametrize("action,accepted", [("accept", True), ("decline", False), ("close", False), ("escape", False), ("enter", False)])
def test_real_dialog_requires_explicit_accept_click(app, terms, action, accepted):
    from PySide6.QtCore import Qt
    from PySide6.QtTest import QTest
    from PySide6.QtWidgets import QDialog, QPushButton, QTextBrowser
    dialog = terms.create_terms_dialog()
    accept = dialog.findChild(QPushButton, "acceptMicrosoftTerms")
    decline = dialog.findChild(QPushButton, "declineMicrosoftTerms")
    assert accept.text() == "Accept"
    assert decline.text() == "Decline"
    assert not accept.isDefault() and not accept.autoDefault()
    assert dialog.findChild(QTextBrowser).toPlainText() == terms.TERMS_TEXT.rstrip("\n")
    dialog.show()
    app.processEvents()
    assert not accept.hasFocus()
    if action == "accept":
        QTest.mouseClick(accept, Qt.MouseButton.LeftButton)
    elif action == "decline":
        QTest.mouseClick(decline, Qt.MouseButton.LeftButton)
    elif action == "close":
        dialog.close()
    else:
        QTest.keyClick(dialog, Qt.Key.Key_Escape if action == "escape" else Qt.Key.Key_Return)
    assert (dialog.result() == QDialog.DialogCode.Accepted) is accepted
    dialog.close()


def test_terms_preview_dialog_cannot_accept(app, terms):
    from PySide6.QtWidgets import QPushButton, QLabel
    dialog = terms.create_terms_dialog(automated=True)
    assert not dialog.findChild(QPushButton, "acceptMicrosoftTerms").isEnabled()
    assert "no acceptance" in " ".join(label.text() for label in dialog.findChildren(QLabel)).lower()
    dialog.close()


@pytest.mark.parametrize("decision", ["decline", "settings-error"])
def test_entrypoint_blocks_before_builder_construction(app, terms, monkeypatch, tmp_path, decision):
    from types import SimpleNamespace
    from PySide6.QtWidgets import QMessageBox
    from oal_builder import __main__ as entry, ui
    monkeypatch.setattr(entry, "sys", SimpleNamespace(platform="win32", argv=["builder"], stderr=None))
    calls = []
    def gate(**kwargs):
        calls.append(kwargs)
        if decision == "settings-error":
            raise terms.TermsSettingsError("Cannot verify or save acceptance")
        return False
    monkeypatch.setattr(terms, "ensure_windows_terms", gate)
    monkeypatch.setattr(ui, "BuilderWindow", lambda **kw: pytest.fail("Builder constructed without assent"))
    monkeypatch.setattr(QMessageBox, "critical", lambda *a: None)
    assert entry.main(["--workspace", str(tmp_path / "workspace")]) != 0
    assert len(calls) == 1
    assert not (tmp_path / "workspace").exists()


@pytest.mark.parametrize("platform", ["win32", "linux", "darwin"])
@pytest.mark.parametrize("explicit_workspace", [False, True])
@pytest.mark.parametrize("flag", ["--smoke-test", "--screenshot"])
def test_automated_entrypoint_exercises_real_readonly_builder(
        app, terms, monkeypatch, tmp_path, platform, explicit_workspace, flag):
    """Platform branch replay with real Qt widgets, not native Windows proof."""
    import socket
    from types import SimpleNamespace
    from PySide6.QtCore import QSettings
    from PySide6.QtWidgets import QAbstractButton, QLineEdit
    from oal_builder import __main__ as entry, engine, ui

    monkeypatch.setattr(entry, "sys", SimpleNamespace(platform=platform, argv=["builder"], stderr=None))
    workspace = tmp_path / "workspace"
    monkeypatch.setattr(ui, "DEFAULT_WORKSPACE", workspace)
    def forbidden(*args, **kwargs):
        pytest.fail("Read-only UI automation must not use settings, network, or mutation APIs")
    monkeypatch.setattr(terms, "ensure_windows_terms", forbidden)
    monkeypatch.setattr(terms, "create_terms_dialog", forbidden)
    # Preserve QSettings enums while detecting even an attempted settings write.
    monkeypatch.setattr(QSettings, "setValue", forbidden)
    monkeypatch.setattr(ui, "QSettings", forbidden)
    monkeypatch.setattr(socket, "create_connection", forbidden)
    for name in ("Builder", "backup_identity", "restore_identity"):
        monkeypatch.setattr(engine, name, forbidden)

    shown = []
    original_show = ui.BuilderWindow.show
    def show(window):
        assert type(window) is ui.BuilderWindow
        assert window.pages.count() == 3
        assert window.settings is None
        assert window.worker is None
        assert not window.centralWidget().isEnabled()
        assert all(not child.isEnabled() for child in window.findChildren(QAbstractButton))
        assert all(not child.isEnabled() for child in window.findChildren(QLineEdit))
        assert not window.licenses.isChecked()
        assert not any(hasattr(window.backend, name) for name in
                       ("Builder", "BuildConfig", "backup_identity", "restore_identity"))
        shown.append(window)
        original_show(window)
    monkeypatch.setattr(ui.BuilderWindow, "show", show)
    args = [flag] if flag == "--smoke-test" else [flag, str(tmp_path / "builder.png"), "--screenshot-page", "options"]
    if explicit_workspace:
        args += ["--workspace", str(workspace)]
    assert entry.main(args) == 0
    assert len(shown) == 1
    assert shown[0].pages.currentIndex() == (1 if flag == "--screenshot" else 0)
    assert not workspace.exists()
    if flag == "--screenshot":
        assert (tmp_path / "builder.png").read_bytes().startswith(b"\x89PNG")


def test_windows_smoke_cannot_hide_broken_builder(app, terms, monkeypatch):
    from types import SimpleNamespace
    from oal_builder import __main__ as entry, ui
    monkeypatch.setattr(entry, "sys", SimpleNamespace(platform="win32", argv=["builder"], stderr=None))
    def broken_builder(*args, **kwargs):
        raise RuntimeError("builder construction regression")
    monkeypatch.setattr(ui, "BuilderWindow", broken_builder)
    monkeypatch.setattr(terms, "ensure_windows_terms", lambda **kw: pytest.fail("Smoke is not assent"))
    with pytest.raises(RuntimeError, match="builder construction regression"):
        entry.main(["--smoke-test"])


@pytest.mark.parametrize("mode", ["--smoke-test", "--terms-preview"])
def test_automated_subprocess_leaves_existing_workspace_and_settings_unchanged(tmp_path, mode):
    import subprocess
    import sys
    workspace = tmp_path / "workspace"
    workspace.mkdir()
    (workspace / "keep.txt").write_bytes(b"existing workspace contents")
    config = tmp_path / "settings"
    config.mkdir()
    (config / "keep.ini").write_bytes(b"existing settings contents")
    before = {str(p.relative_to(tmp_path)): p.read_bytes() for p in tmp_path.rglob("*") if p.is_file()}
    env = dict(os.environ, XDG_CONFIG_HOME=str(config), HOME=str(tmp_path))
    result = subprocess.run([sys.executable, "-m", "oal_builder", mode, "--workspace", str(workspace)],
                            env=env, capture_output=True, text=True, timeout=30)
    assert result.returncode == 0, result.stderr
    after = {str(p.relative_to(tmp_path)): p.read_bytes() for p in tmp_path.rglob("*") if p.is_file()}
    assert after == before


@pytest.mark.parametrize("screenshot", [False, True])
def test_explicit_terms_preview_exercises_only_nonaccepting_terms(
        app, terms, monkeypatch, tmp_path, screenshot):
    from types import SimpleNamespace
    from PySide6 import QtCore
    from PySide6.QtWidgets import QPushButton
    from oal_builder import __main__ as entry, ui
    monkeypatch.setattr(entry, "sys", SimpleNamespace(platform="win32", argv=["builder"], stderr=None))
    def forbidden(*args, **kwargs):
        pytest.fail("Terms preview must not construct builder or access consent/settings")
    monkeypatch.setattr(terms, "ensure_windows_terms", forbidden)
    monkeypatch.setattr(ui, "BuilderWindow", forbidden)
    monkeypatch.setattr(QtCore, "QSettings", forbidden)
    shown = []
    original_dialog = terms.create_terms_dialog
    def dialog(**kwargs):
        assert kwargs == {"automated": True}
        window = original_dialog(**kwargs)
        assert not window.findChild(QPushButton, "acceptMicrosoftTerms").isEnabled()
        shown.append(window)
        return window
    monkeypatch.setattr(terms, "create_terms_dialog", dialog)
    args = ["--terms-preview", "--workspace", str(tmp_path / "workspace")]
    if screenshot:
        args += ["--screenshot", str(tmp_path / "terms.png")]
    assert entry.main(args) == 0
    assert len(shown) == 1
    assert not (tmp_path / "workspace").exists()
    if screenshot:
        assert (tmp_path / "terms.png").read_bytes().startswith(b"\x89PNG")


def test_smoke_and_terms_preview_are_not_interchangeable():
    from oal_builder import __main__ as entry
    with pytest.raises(SystemExit) as error:
        entry.main(["--smoke-test", "--terms-preview"])
    assert error.value.code == 2


def test_default_prompt_records_only_explicit_button_click(app, terms, settings):
    from PySide6.QtCore import QTimer
    from PySide6.QtWidgets import QPushButton
    def click_accept():
        app.activeModalWidget().findChild(QPushButton, "acceptMicrosoftTerms").click()
    QTimer.singleShot(0, click_accept)
    assert terms.ensure_windows_terms(platform="win32", settings=settings)
    assert settings.value(terms.ACCEPTANCE_KEY) == terms.acceptance_token()


def test_default_store_is_native_per_user_without_fallbacks(terms, monkeypatch, settings):
    from PySide6 import QtCore
    real_qsettings = QtCore.QSettings
    calls = []
    def factory(*args):
        calls.append(args)
        return settings
    factory.Format = real_qsettings.Format
    factory.Scope = real_qsettings.Scope
    factory.Status = real_qsettings.Status
    monkeypatch.setattr(QtCore, "QSettings", factory)
    assert terms.ensure_windows_terms(platform="win32", prompt=lambda text: True)
    assert calls == [(real_qsettings.Format.NativeFormat, real_qsettings.Scope.UserScope,
                      "OpenAutoLink", "OpenAutoLink Builder")]
    assert not settings.fallbacksEnabled()


def test_entrypoint_opens_builder_after_acceptance(app, terms, monkeypatch, tmp_path):
    from types import SimpleNamespace
    from PySide6.QtCore import QTimer
    from PySide6.QtWidgets import QWidget
    from oal_builder import __main__ as entry, ui
    monkeypatch.setattr(entry, "sys", SimpleNamespace(platform="win32", argv=["builder"], stderr=None))
    calls = []
    def gate(**kwargs):
        calls.append("assent")
        return True
    class Window(QWidget):
        def __init__(self, **kwargs):
            super().__init__()
            calls.append("builder")
        def show(self):
            super().show()
            QTimer.singleShot(0, self.close)
    monkeypatch.setattr(terms, "ensure_windows_terms", gate)
    monkeypatch.setattr(ui, "BuilderWindow", Window)
    assert entry.main(["--workspace", str(tmp_path / "workspace")]) == 0
    assert calls == ["assent", "builder"]


@pytest.mark.parametrize("command", ["--runtime-info", "--export-compliance"])
def test_frozen_diagnostics_exit_before_qt_or_assent(tmp_path, command):
    """Execute the existing frozen hook in isolation, not a pretend frozen EXE."""
    import json
    from pathlib import Path
    import subprocess
    import sys
    import zipfile
    hook = Path(__file__).resolve().parents[1] / "packaging" / "runtime_hook.py"
    with zipfile.ZipFile(tmp_path / "builder-compliance.zip", "w") as archive:
        archive.writestr("test-notice.txt", "Diagnostic export test fixture, not release evidence")
    target = tmp_path / ("output.json" if command == "--runtime-info" else "output.zip")
    script = '''import runpy, sys
class NoQtOrTerms:
    def find_spec(self, fullname, *args):
        if fullname.startswith("PySide6") or fullname in ("oal_builder.windows_terms", "oal_builder.__main__"):
            raise AssertionError("Diagnostics must not load GUI or assent")
sys.meta_path.insert(0, NoQtOrTerms())
sys.frozen = True
sys._MEIPASS = sys.argv[2]
sys.argv = ["builder", sys.argv[3], sys.argv[4]]
runpy.run_path(HOOK)
'''.replace("HOOK", repr(str(hook)))
    result = subprocess.run([sys.executable, "-c", script, "unused", str(tmp_path), command, str(target)],
                            capture_output=True, text=True, timeout=30)
    assert result.returncode == 0, result.stderr
    if command == "--runtime-info":
        assert json.loads(target.read_text())["frozen"] is True
    else:
        assert target.read_bytes() == (tmp_path / "builder-compliance.zip").read_bytes()


def test_terms_keep_restrictions_scoped_and_preserve_lgpl_rights(terms):
    import hashlib
    assert "ONLY to Microsoft Distributable Code" in terms.TERMS_TEXT
    assert "Their own licenses continue to" in terms.TERMS_TEXT
    assert "reverse engineer the application for debugging modifications" in terms.TERMS_TEXT
    assert "rights to modify or replace Qt/PySide/Shiboken libraries" in terms.TERMS_TEXT
    assert "This restriction does NOT" in terms.TERMS_TEXT
    assert "applicable law" in terms.TERMS_TEXT
    assert "at least as much as these terms" in terms.TERMS_TEXT
    assert terms.acceptance_token() == terms.TERMS_VERSION + ":" + hashlib.sha256(terms.TERMS_TEXT.encode("utf-8")).hexdigest()
