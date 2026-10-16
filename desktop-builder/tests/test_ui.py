"""Offscreen native-Qt behavior tests. The fake replaces only network/build I/O."""
import os
os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from dataclasses import dataclass
from pathlib import Path
from types import SimpleNamespace
import importlib
import threading
import time

import pytest


@pytest.fixture(scope="session")
def app():
    from PySide6.QtWidgets import QApplication
    return QApplication.instance() or QApplication([])


@dataclass
class Config:
    workspace: Path
    app_id: str
    carplay_key: Path | None = None
    carplay_certificate: Path | None = None
    accept_licenses: bool = False


@pytest.fixture
def backend():
    return SimpleNamespace(
        BuildConfig=Config,
        BuilderError=RuntimeError,
        suggest_app_id=lambda: "com.openautolink.user_a123456789",
        load_state=lambda workspace: None,
    )


@pytest.fixture
def window(request, backend, tmp_path):
    try:
        ui = importlib.import_module("oal_builder.ui")
    except ImportError as exc:
        pytest.fail(f"Native desktop UI is not implemented: {exc}")
    app = request.getfixturevalue("app")
    win = ui.BuilderWindow(workspace=tmp_path / "workspace", backend=backend)
    win.show()
    app.processEvents()
    yield win
    if getattr(win, "worker", None) and win.worker.isRunning():
        win.cancel_event.set()
        win.worker.wait(5000)
    app.processEvents()
    win.close()
    app.processEvents()


@pytest.fixture
def settings(tmp_path):
    from PySide6.QtCore import QSettings
    return QSettings(str(tmp_path / "builder-settings.ini"), QSettings.Format.IniFormat)


def test_custom_workspace_is_remembered_after_close_and_reopen(app, backend, tmp_path, settings, monkeypatch):
    from PySide6.QtCore import QSettings
    from PySide6.QtWidgets import QFileDialog
    from oal_builder import ui
    custom = tmp_path / "custom"
    custom.mkdir()
    backend.load_state = lambda path: {"applicationId": "com.example.saved"} if path == custom else None
    monkeypatch.setattr(QFileDialog, "getExistingDirectory", lambda *a: str(custom))
    first = ui.BuilderWindow(backend=backend, settings=settings)
    first.choose_workspace()
    assert first.workspace == custom
    first.close()
    reopened_settings = QSettings(settings.fileName(), QSettings.Format.IniFormat)
    reopened = ui.BuilderWindow(backend=backend, settings=reopened_settings)
    try:
        assert reopened.workspace == custom
        assert reopened.app_id.text() == "com.example.saved"
        assert reopened.app_id.isReadOnly()
    finally:
        reopened.close()


@pytest.mark.parametrize("workspace_kind", ["missing", "empty", "managed"])
def test_close_before_first_build_reopens_uninitialized_workspace(app, tmp_path, settings, monkeypatch, workspace_kind):
    from PySide6.QtCore import QSettings
    from oal_builder import engine, ui
    workspace = tmp_path / "not-created-yet"
    if workspace_kind == "empty":
        workspace.mkdir()
    elif workspace_kind == "managed":
        engine.managed_workspace(workspace)
    monkeypatch.setattr(ui, "DEFAULT_WORKSPACE", workspace)
    first = ui.BuilderWindow(backend=engine, settings=settings)
    assert first.next_button.isEnabled()
    assert workspace.exists() is (workspace_kind != "missing")
    assert settings.value("workspace/applicationId") == ""
    first.close()
    reopened = ui.BuilderWindow(backend=engine, settings=QSettings(settings.fileName(), QSettings.Format.IniFormat))
    try:
        assert reopened.workspace == workspace
        assert reopened.next_button.isEnabled()
        assert not reopened.app_id.isReadOnly()
        assert not reopened.error_label.text()
        reopened.advance()
        assert reopened.pages.currentIndex() == 1
        reopened.licenses.setChecked(True)
        assert reopened.build_config().workspace == workspace
        assert workspace.exists() is (workspace_kind != "missing")
        assert engine.load_state(workspace) is None
    finally:
        reopened.close()


def test_uninitialized_preference_does_not_accept_a_file_as_workspace(app, tmp_path, settings):
    from oal_builder import engine, ui
    workspace = tmp_path / "not-a-folder"
    workspace.write_text("keep")
    settings.setValue("workspace/path", str(workspace))
    settings.setValue("workspace/applicationId", "")
    win = ui.BuilderWindow(backend=engine, settings=settings)
    try:
        assert not win.next_button.isEnabled()
        assert win.error_label.text()
        assert workspace.read_text() == "keep"
    finally:
        win.close()


@pytest.mark.parametrize("problem", ["missing", "not_directory", "lost_identity", "changed_identity", "unavailable"])
def test_remembered_workspace_problem_blocks_new_identity(app, backend, tmp_path, settings, problem):
    from oal_builder import ui
    custom = tmp_path / "saved-work"
    custom.mkdir()
    backend.load_state = lambda _: {"applicationId": "com.example.saved"}
    first = ui.BuilderWindow(workspace=custom, backend=backend, settings=settings)
    first.close()
    if problem in ("missing", "not_directory"):
        custom.rmdir()
    if problem == "not_directory":
        custom.write_text("not a folder")
    def load_state(_):
        if problem == "unavailable":
            raise PermissionError("Work folder is unavailable")
        if problem == "changed_identity":
            return {"applicationId": "com.example.changed"}
        return None
    backend.load_state = load_state
    backend.suggest_app_id = lambda: pytest.fail("Recovery must not suggest a replacement identity")
    reopened = ui.BuilderWindow(backend=backend, settings=settings)
    try:
        assert reopened.workspace == custom
        assert not reopened.next_button.isEnabled()
        assert reopened.app_id.isReadOnly()
        assert "restore" in reopened.error_label.text().lower()
        assert "choose" in reopened.error_label.text().lower()
        reopened.advance()  # Not just a disabled visual control.
        assert reopened.pages.currentIndex() == 0
        reopened.licenses.setChecked(True)
        with pytest.raises(ValueError, match="(?i)restore"):
            reopened.build_config()
        assert settings.value("workspace/path") == str(custom)
        assert reopened.worker is None
    finally:
        reopened.close()


@pytest.mark.parametrize("confirm", [False, True])
def test_missing_workspace_requires_explicit_different_app_choice(app, backend, tmp_path, settings, monkeypatch, confirm):
    from PySide6.QtWidgets import QFileDialog, QMessageBox
    from oal_builder import ui
    missing = tmp_path / "missing"
    other = tmp_path / "another-app"
    other.mkdir()
    settings.setValue("workspace/path", str(missing))
    settings.setValue("workspace/applicationId", "com.example.original")
    win = ui.BuilderWindow(backend=backend, settings=settings)
    monkeypatch.setattr(QFileDialog, "getExistingDirectory", lambda *a: str(other))
    try:
        win.choose_workspace()
        assert not win.next_button.isEnabled()
        assert win.workspace == missing
        assert settings.value("workspace/path") == str(missing)
        questions = []
        def question(*args):
            questions.append(args)
            return QMessageBox.StandardButton.Yes if confirm else QMessageBox.StandardButton.No
        monkeypatch.setattr(QMessageBox, "question", question)
        win.new_app_button.click()
        assert len(questions) == 1
        assert questions[0][-1] == QMessageBox.StandardButton.No
        assert "updates" in questions[0][2].lower()
        assert win.next_button.isEnabled() is confirm
        assert win.workspace == (other if confirm else missing)
        assert settings.value("workspace/path") == str(other if confirm else missing)
        if confirm:
            assert not win.app_id.isReadOnly()
            win.advance()
            assert win.pages.currentIndex() == 1
    finally:
        win.close()


def test_saved_identity_disappearing_before_build_blocks_config(app, backend, tmp_path, settings):
    from oal_builder import ui
    custom = tmp_path / "custom"
    custom.mkdir()
    backend.load_state = lambda path: {"applicationId": "com.example.saved"} if path.is_dir() else None
    win = ui.BuilderWindow(workspace=custom, backend=backend, settings=settings)
    try:
        win.advance()
        custom.rmdir()
        win.licenses.setChecked(True)
        with pytest.raises(ValueError, match="(?i)restore"):
            win.build_config()
        assert not win.next_button.isEnabled()
        assert settings.value("workspace/applicationId") == "com.example.saved"
    finally:
        win.close()


@pytest.mark.parametrize("recovery", ["moved_folder", "restore_backup"])
def test_recovered_workspace_stays_saved_across_another_reopen(app, backend, tmp_path, settings, monkeypatch, recovery):
    import json
    from PySide6.QtCore import QSettings
    from PySide6.QtWidgets import QFileDialog, QInputDialog, QMessageBox
    from oal_builder import ui
    original = tmp_path / "original"
    original.mkdir()
    identity = {"applicationId": "com.example.original", "lastVersionCode": 8}
    (original / "test-state.json").write_text(json.dumps(identity))
    def load_state(path):
        state = path / "test-state.json"
        return json.loads(state.read_text()) if state.exists() else None
    backend.load_state = load_state
    first = ui.BuilderWindow(workspace=original, backend=backend, settings=settings)
    first.close()
    moved = tmp_path / "moved"
    original.rename(moved)
    win = ui.BuilderWindow(backend=backend, settings=settings)
    try:
        assert not win.next_button.isEnabled()
        if recovery == "moved_folder":
            monkeypatch.setattr(QFileDialog, "getExistingDirectory", lambda *a: str(moved))
            win.browse_workspace.click()
            expected = moved
        else:
            archive = tmp_path / "backup.oalidentity"
            monkeypatch.setattr(QFileDialog, "getOpenFileName", lambda *a: (str(archive), ""))
            monkeypatch.setattr(QInputDialog, "getText", lambda *a: ("test-backup-password", True))
            monkeypatch.setattr(QMessageBox, "information", lambda *a: None)
            def restore(path, source, password):
                assert source == archive
                path.mkdir()
                (path / "test-state.json").write_text(json.dumps(identity))
            backend.restore_identity = restore
            win.restore_button.click()
            expected = original
        assert win.next_button.isEnabled()
        assert win.workspace == expected
        assert win.app_id.text() == identity["applicationId"]
        assert win.app_id.isReadOnly()
        assert not win.error_label.text()
    finally:
        win.close()
    reopened = ui.BuilderWindow(backend=backend, settings=QSettings(settings.fileName(), QSettings.Format.IniFormat))
    try:
        assert reopened.workspace == expected
        assert reopened.state == identity
        assert reopened.next_button.isEnabled()
    finally:
        reopened.close()


def test_injected_workspace_never_reads_or_writes_machine_settings(app, backend, tmp_path, monkeypatch):
    from oal_builder import ui
    monkeypatch.setattr(ui, "QSettings", lambda *a: pytest.fail("Injected workspaces must not access machine settings"))
    explicit = tmp_path / "not-created"
    win = ui.BuilderWindow(workspace=explicit, backend=backend)
    try:
        assert win.workspace == explicit
        assert win.next_button.isEnabled()
        assert not win.app_id.isReadOnly()
        assert not explicit.exists()
    finally:
        win.close()


def test_unwritable_preferences_fail_visibly_instead_of_forgetting_workspace(app, backend, tmp_path):
    from PySide6.QtCore import QSettings
    from oal_builder import ui
    blocked = tmp_path / "not-a-directory"
    blocked.write_text("file")
    store = QSettings(str(blocked / "settings.ini"), QSettings.Format.IniFormat)
    custom = tmp_path / "custom"
    custom.mkdir()
    backend.load_state = lambda _: {"applicationId": "com.example.saved"}
    win = ui.BuilderWindow(workspace=custom, backend=backend, settings=store)
    try:
        assert not win.next_button.isEnabled()
        assert "settings" in win.error_label.text().lower()
        assert "remember" in win.error_label.text().lower()
    finally:
        win.close()


def test_new_build_has_editable_generated_identity_and_no_license_consent(window):
    assert window.app_id.text() == "com.openautolink.user_a123456789"
    assert not window.app_id.isReadOnly()
    assert not window.licenses.isChecked()
    assert window.workspace_input.text().endswith("workspace")
    assert window.pages.currentIndex() == 0
    assert window.windowTitle() == "OpenAutoLink Builder"


def test_saved_identity_is_locked_and_bad_state_blocks_build(window, backend):
    backend.load_state = lambda _: {"applicationId": "com.example.saved", "versionCode": 12}
    window.load_workspace(window.workspace)
    assert window.app_id.text() == "com.example.saved"
    assert window.app_id.isReadOnly()
    assert "update" in window.identity_status.text().lower()
    def broken(_):
        raise RuntimeError("Corrupt identity; restore a backup")
    backend.load_state = broken
    window.load_workspace(window.workspace)
    assert not window.next_button.isEnabled()
    assert "Corrupt identity" in window.error_label.text()


def test_application_id_is_validated_before_options(window):
    for invalid in ("bad", "com..oops", "com.1bad.app", "com.example-app", "com.class.app", " com.example.app ", "com.Example.app", "com.openautolink.app", "com." + "a" * 147):
        window.app_id.setText(invalid)
        window.next_button.click()
        assert window.pages.currentIndex() == 0
        assert window.error_label.text()
    window.app_id.setText("com.example.my_app")
    window.next_button.click()
    assert window.pages.currentIndex() == 1
    assert window.next_button.text() == "Build bundle"


def test_carplay_option_describes_authentication_and_authorization(window):
    from PySide6.QtWidgets import QLabel
    assert window.carplay.text() == 'Include CarPlay authentication files'
    text = ' '.join(w.text() for w in window.findChildren(QLabel))
    assert 'authorized to use and include' in text
    assert 'dedicated Android Automotive OS' in text


def test_license_consent_is_required_and_carplay_needs_pair(window, tmp_path):
    window.next_button.click()
    window.next_button.click()
    assert "license" in window.error_label.text().lower()
    assert window.worker is None
    window.licenses.setChecked(True)
    window.carplay.setChecked(True)
    assert not window.carplay_fields.isHidden()
    with pytest.raises(ValueError, match="Both"):
        window.build_config()
    key, cert = tmp_path / "identity.pk8", tmp_path / "identity.p7b"
    key.write_bytes(b"test-key")
    cert.write_bytes(b"test-cert")
    window.key_input.setText(str(key))
    window.cert_input.setText(str(cert))
    config = window.build_config()
    assert config.carplay_key == key
    assert config.carplay_certificate == cert
    assert config.accept_licenses is True
    window.carplay.setChecked(False)
    config = window.build_config()
    assert config.carplay_key is None and config.carplay_certificate is None


def wait_until(app, condition, seconds=5):
    deadline = time.monotonic() + seconds
    while not condition() and time.monotonic() < deadline:
        app.processEvents()
        time.sleep(0.01)
    app.processEvents()
    assert condition(), "Qt operation did not finish before the deadline"


def install_builder(backend, tmp_path, *, fail=None, block=False):
    calls = []
    entered = threading.Event()
    class FakeBuilder:
        def __init__(self, config, progress, cancel_event=None):
            self.config, self.progress, self.cancel = config, progress, cancel_event
        def build(self):
            calls.append((self.config, threading.current_thread().ident))
            entered.set()
            self.progress("compile", "Compiling the app")
            if block:
                assert self.cancel.wait(5)
                raise RuntimeError("Cancelled")
            if fail:
                raise RuntimeError(fail)
            artifact = tmp_path / "actual-bundle.aab"
            artifact.write_bytes(b"fake backend test artifact")
            backend.load_state = lambda _: {"applicationId": self.config.app_id, "lastVersionCode": 7}
            return {"artifact": str(artifact), "applicationId": self.config.app_id,
                    "versionCode": 7, "versionName": "0.7", "sourceCommit": "abc123"}
    backend.Builder = FakeBuilder
    return calls, entered


def begin_build(window):
    window.licenses.setChecked(True)
    window.next_button.click()
    window.next_button.click()


def test_build_runs_off_main_thread_and_shows_actual_bundle(window, backend, tmp_path, app):
    calls, _ = install_builder(backend, tmp_path)
    begin_build(window)
    wait_until(app, lambda: window.worker is None)
    assert calls[0][1] != threading.current_thread().ident
    assert calls[0][0].accept_licenses is True
    assert window.artifact_path.text() == str(tmp_path / "actual-bundle.aab")
    assert not window.result_card.isHidden()
    assert "abc123" in window.result_metadata.text()
    assert window.app_id.isReadOnly()
    assert window.next_button.text() == "Build update"
    window.next_button.click()
    assert window.pages.currentIndex() == 1


def test_build_error_is_visible_without_success_actions(window, backend, tmp_path, app):
    install_builder(backend, tmp_path, fail="Disk is full")
    begin_build(window)
    wait_until(app, lambda: window.worker is None)
    assert "Disk is full" in window.error_label.text()
    assert window.result_card.isHidden()
    assert window.next_button.isEnabled()
    assert not window.cancel_button.isVisible()


def test_cancel_signals_worker_and_never_displays_success(window, backend, tmp_path, app):
    _, entered = install_builder(backend, tmp_path, block=True)
    begin_build(window)
    assert entered.wait(2)
    window.cancel_button.click()
    assert window.cancel_event.is_set()
    wait_until(app, lambda: window.worker is None)
    assert "cancelled" in window.status_label.text().lower()
    assert window.result_card.isHidden()


def test_close_during_build_waits_for_worker(window, backend, tmp_path, app, monkeypatch):
    from PySide6.QtWidgets import QMessageBox
    _, entered = install_builder(backend, tmp_path, block=True)
    begin_build(window)
    assert entered.wait(2)
    monkeypatch.setattr(QMessageBox, "question", lambda *a, **kw: QMessageBox.StandardButton.Yes)
    window.close()
    assert window.cancel_event.is_set()
    wait_until(app, lambda: window.worker is None and not window.isVisible())


def test_restore_uses_masked_password_then_locks_restored_id(window, backend, monkeypatch, tmp_path):
    from PySide6.QtWidgets import QFileDialog, QInputDialog, QLineEdit, QMessageBox
    calls = []
    archive = tmp_path / "identity.oalidentity"
    monkeypatch.setattr(QFileDialog, "getOpenFileName", lambda *a, **k: (str(archive), ""))
    def password(*args, **kwargs):
        assert args[3] == QLineEdit.EchoMode.Password
        return "a-long-test-password", True
    monkeypatch.setattr(QInputDialog, "getText", password)
    monkeypatch.setattr(QMessageBox, "information", lambda *a: None)
    def restore(workspace, source, secret):
        calls.append((workspace, source, secret))
        backend.load_state = lambda _: {"applicationId": "com.example.restored", "lastVersionCode": 4}
    backend.restore_identity = restore
    window.restore_button.click()
    assert calls == [(window.workspace, archive, "a-long-test-password")]
    assert window.app_id.text() == "com.example.restored"
    assert window.app_id.isReadOnly()
    assert "a-long-test-password" not in window.log.toPlainText()


def test_backup_confirms_password_and_verifies_output(window, backend, monkeypatch, tmp_path):
    from PySide6.QtWidgets import QFileDialog, QInputDialog, QLineEdit, QMessageBox
    destination = tmp_path / "saved.oalidentity"
    messages = []
    monkeypatch.setattr(QFileDialog, "getSaveFileName", lambda *a, **k: (str(destination), ""))
    def password(*args, **kwargs):
        assert args[3] == QLineEdit.EchoMode.Password
        return "a-long-test-password", True
    monkeypatch.setattr(QInputDialog, "getText", password)
    monkeypatch.setattr(QMessageBox, "information", lambda *args: messages.append(args[-1]))
    def backup(workspace, target, secret):
        assert secret == "a-long-test-password"
        target.write_bytes(b"encrypted fake backend test identity")
    backend.backup_identity = backup
    window.backup_identity()
    assert destination.is_file()
    assert str(destination) in messages[0]


def test_backup_cancel_does_not_write(window, backend, monkeypatch, tmp_path):
    from PySide6.QtWidgets import QFileDialog, QInputDialog
    monkeypatch.setattr(QFileDialog, "getSaveFileName", lambda *a, **k: (str(tmp_path / "backup"), ""))
    monkeypatch.setattr(QInputDialog, "getText", lambda *a, **k: ("", False))
    backend.backup_identity = lambda *a: pytest.fail("Cancelled password must not write")
    window.backup_identity()


def test_missing_bundle_is_not_reported_as_success(window):
    window._on_success({"artifact": "/missing/result.aab"})
    assert window.result is None
    assert window.result_card.isHidden()
    assert "no readable" in window.error_label.text()


def test_packaging_cli_help_runs_without_building():
    import subprocess
    import sys
    root = Path(__file__).resolve().parents[1]
    result = subprocess.run([sys.executable, str(root / "packaging" / "build.py"), "--help"], capture_output=True, text=True)
    assert result.returncode == 0, result.stderr
    assert "--mac-arch" in result.stdout
    assert "--output" in result.stdout


def test_source_runner_help_is_available_without_gui():
    import subprocess
    import sys
    result = subprocess.run([sys.executable, "-m", "oal_builder", "--help"], capture_output=True, text=True)
    assert result.returncode == 0, result.stderr
    assert "--smoke-test" in result.stdout
    assert "--screenshot" in result.stdout


def test_offline_smoke_creates_screenshot_without_workspace(tmp_path):
    import subprocess
    import sys
    workspace = tmp_path / "must-not-be-created"
    screenshot = tmp_path / "wizard.png"
    result = subprocess.run([sys.executable, "-m", "oal_builder", "--smoke-test", "--workspace", str(workspace),
                             "--screenshot", str(screenshot)], capture_output=True, text=True, timeout=30)
    assert result.returncode == 0, result.stderr
    assert screenshot.read_bytes().startswith(b"\x89PNG")
    assert not workspace.exists()
