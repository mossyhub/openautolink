"""Native Qt wizard. All build/network work belongs to the injected engine."""
from __future__ import annotations

from pathlib import Path
import importlib
import re
import threading

from PySide6.QtCore import QSettings, QThread, QUrl, Signal, Slot, Qt
from PySide6.QtGui import QDesktopServices, QCloseEvent
from PySide6.QtWidgets import (
    QCheckBox, QFileDialog, QFrame, QHBoxLayout, QInputDialog, QLabel,
    QLineEdit, QMainWindow, QMessageBox, QPlainTextEdit, QProgressBar,
    QPushButton, QScrollArea, QStackedWidget, QVBoxLayout, QWidget,
)

GUIDE_URL = "https://github.com/mossyhub/openautolink/blob/main/docs/install-gm.md"
SDK_LICENSE_URL = "https://developer.android.com/studio/terms"
DEFAULT_WORKSPACE = Path.home() / "OpenAutoLink Builder"

# Linear-inspired luminance hierarchy, translated into native Qt widgets.
# Use installed system fonts: no remote fonts, web view, or local HTTP server.
STYLE = """
QWidget { background: #101213; color: #f3f5f4; font-size: 14px; }
QMainWindow { background: #101213; }
QLabel { background: transparent; border: none; }
QLabel#brand { font-size: 17px; font-weight: 600; }
QLabel#title { font-size: 28px; font-weight: 600; }
QLabel#subtitle, QLabel#muted { color: #aeb7b4; }
QLabel#eyebrow { color: #8eaaa0; font-size: 12px; font-weight: 600; }
QLabel#cardTitle { font-size: 16px; font-weight: 600; }
QLabel#step { color: #8e9793; padding: 12px; border-radius: 6px; }
QLabel#step[active="true"] { color: #b7e8d2; background: #1b2b24; }
QFrame#card { background: #191c1d; border: 1px solid #2d3232; border-radius: 10px; }
QFrame#card QWidget { background: transparent; }
QLineEdit, QPlainTextEdit { background: #111415; border: 1px solid #3a4240;
    border-radius: 6px; padding: 10px 12px; selection-background-color: #32694f; }
QLineEdit:focus, QPushButton:focus { border: 1px solid #87c9a8; }
QLineEdit:read-only { color: #bec8c2; }
QLineEdit:disabled, QPushButton:disabled { color: #78817d; background: #1a1e1c; border-color: #2a302c; }
QPushButton { background: #222827; border: 1px solid #3a4340; border-radius: 6px;
    padding: 10px 16px; min-height: 18px; }
QPushButton:hover { background: #2b3430; border-color: #66776e; }
QPushButton#primary { background: #99d8b6; border-color: #99d8b6; color: #101c15; font-weight: 600; }
QPushButton#primary:hover { background: #b1e5ca; }
QPushButton#primary:disabled { background: #2d4437; border-color: #2d4437; color: #8eaaa0; }
QCheckBox { spacing: 10px; }
QCheckBox::indicator { width: 18px; height: 18px; border: 1px solid #66776e; border-radius: 4px; background: #111415; }
QCheckBox::indicator:checked { background: #99d8b6; border: 4px solid #32694f; }
QProgressBar { border: none; border-radius: 3px; background: #2b3430; min-height: 6px; max-height: 6px; }
QProgressBar::chunk { background: #99d8b6; border-radius: 3px; }
QScrollArea { border: none; }
QScrollBar:vertical { background: #101213; width: 10px; }
QScrollBar::handle:vertical { background: #414b46; border-radius: 4px; min-height: 24px; }
QScrollBar::add-line:vertical, QScrollBar::sub-line:vertical { height: 0px; }
QToolTip { color: #f3f5f4; background: #252c28; border: 1px solid #59685e; }
"""


def label(text: str, name: str = "", wrap: bool = True) -> QLabel:
    widget = QLabel(text)
    widget.setTextFormat(Qt.TextFormat.PlainText)
    widget.setWordWrap(wrap)
    widget.setObjectName(name)
    return widget


def card(title: str, description: str = "") -> tuple[QFrame, QVBoxLayout]:
    frame = QFrame()
    frame.setObjectName("card")
    layout = QVBoxLayout(frame)
    layout.setContentsMargins(20, 20, 20, 20)
    layout.setSpacing(12)
    layout.addWidget(label(title, "cardTitle"))
    if description:
        layout.addWidget(label(description, "muted"))
    return frame, layout


def button(text: str, callback=None, primary: bool = False) -> QPushButton:
    widget = QPushButton(text)
    if primary:
        widget.setObjectName("primary")
    if callback:
        widget.clicked.connect(callback)
    return widget


def row(*widgets: QWidget) -> QHBoxLayout:
    layout = QHBoxLayout()
    layout.setSpacing(12)
    for widget in widgets:
        layout.addWidget(widget)
    return layout


class BuildWorker(QThread):
    progress_changed = Signal(str, str)
    succeeded = Signal(dict)
    failed = Signal(str)
    cancelled = Signal()

    def __init__(self, backend, config, cancel_event, parent=None):
        super().__init__(parent)
        self.backend, self.config, self.cancel_event = backend, config, cancel_event

    def run(self):
        try:
            result = self.backend.Builder(self.config, self.progress_changed.emit, self.cancel_event).build()
            if self.cancel_event.is_set():
                self.cancelled.emit()
            else:
                self.succeeded.emit(result)
        except Exception as exc:
            if self.cancel_event.is_set():
                self.cancelled.emit()
            else:
                self.failed.emit(str(exc) or type(exc).__name__)


class BuilderWindow(QMainWindow):
    def __init__(self, workspace: Path | None = None, backend=None, *, settings=None):
        super().__init__()
        self.backend = backend if backend is not None else importlib.import_module("oal_builder.engine")
        # Explicit workspaces (including smoke/tests) never read or write machine
        # preferences unless a settings store is explicitly supplied as well.
        self.settings = settings if settings is not None else (
            QSettings(QSettings.Format.NativeFormat, QSettings.Scope.UserScope,
                      "OpenAutoLink", "Builder") if workspace is None else None)
        saved = self.settings.value("workspace/path", "") if self.settings is not None and workspace is None else ""
        self.workspace = Path(workspace or saved or DEFAULT_WORKSPACE).expanduser()
        self._remembered_workspace = self.workspace if saved else None
        self._identity_workspace = self._remembered_workspace
        self._expected_app_id = self.settings.value("workspace/applicationId", "") if saved else ""
        self._workspace_error = ""
        self.state = None
        self.worker = None
        self.cancel_event = threading.Event()
        self.result = None
        self._close_after_cancel = False
        self.setWindowTitle("OpenAutoLink Builder")
        self.resize(1000, 780)
        self.setMinimumSize(800, 620)
        self.setStyleSheet(STYLE)
        root = QWidget()
        self.setCentralWidget(root)
        outer = QHBoxLayout(root)
        outer.setContentsMargins(24, 24, 24, 24)
        outer.setSpacing(24)

        sidebar = QVBoxLayout()
        sidebar.setSpacing(12)
        sidebar.addWidget(label("OPENAUTOLINK", "eyebrow"))
        sidebar.addWidget(label("Builder", "brand"))
        sidebar.addSpacing(24)
        self.steps = []
        for text in ("01   Your app", "02   Build options", "03   Your bundle"):
            step = label(text, "step")
            step.setFixedWidth(180)
            sidebar.addWidget(step)
            self.steps.append(step)
        sidebar.addStretch()
        sidebar.addWidget(label("LOCAL BY DESIGN", "eyebrow"))
        sidebar.addWidget(label("Your signing identity stays\non your computer.", "muted"))
        outer.addLayout(sidebar)

        content = QVBoxLayout()
        content.setSpacing(16)
        self.pages = QStackedWidget()
        content.addWidget(self.pages, 1)
        outer.addLayout(content, 1)
        self._make_identity_page()
        self._make_options_page()
        self._make_build_page()
        self.error_label = label("", "error")
        self.error_label.setStyleSheet("color: #f0b9a8;")
        self.error_label.setAccessibleName("Validation message")
        content.addWidget(self.error_label)
        self.back_button = button("Back")
        self.next_button = button("Continue", primary=True)
        self.cancel_button = button("Cancel build")
        self.cancel_button.hide()
        footer = QHBoxLayout()
        footer.setSpacing(12)
        footer.addWidget(self.back_button)
        footer.addStretch()
        footer.addWidget(self.cancel_button)
        footer.addWidget(self.next_button)
        content.addLayout(footer)
        self.pages.currentChanged.connect(self._page_changed)
        self._page_changed(0)
        self.browse_workspace.clicked.connect(self.choose_workspace)
        self.new_app_button.clicked.connect(self.create_different_app)
        self.carplay.toggled.connect(self.carplay_fields.setVisible)
        self.key_button.clicked.connect(lambda: self.choose_carplay_file(self.key_input, "Private key (*.pk8)"))
        self.cert_button.clicked.connect(lambda: self.choose_carplay_file(self.cert_input, "Certificate (*.p7b)"))
        self.license_link.clicked.connect(lambda: QDesktopServices.openUrl(QUrl(SDK_LICENSE_URL)))
        self.next_button.clicked.connect(self.advance)
        self.cancel_button.clicked.connect(self.cancel_build)
        self.restore_button.clicked.connect(self.restore_identity)
        self.backup_button.clicked.connect(self.backup_identity)
        self.log_toggle.clicked.connect(self.toggle_log)
        self.show_folder_button.clicked.connect(self.show_artifact_folder)
        self.guide_button.clicked.connect(lambda: QDesktopServices.openUrl(QUrl(GUIDE_URL)))
        self.back_button.clicked.connect(lambda: self.pages.setCurrentIndex(max(0, self.pages.currentIndex() - 1)))
        self.load_workspace(self.workspace)

    def _page(self, title: str, subtitle: str):
        area = QScrollArea()
        area.setWidgetResizable(True)
        body = QWidget()
        area.setWidget(body)
        layout = QVBoxLayout(body)
        layout.setContentsMargins(0, 0, 12, 0)
        layout.setSpacing(16)
        layout.addWidget(label(title, "title"))
        layout.addWidget(label(subtitle, "subtitle"))
        self.pages.addWidget(area)
        return layout

    def _make_identity_page(self):
        layout = self._page("An app that's yours.", "Build a signed Android App Bundle for your own Google Play Internal Test.")
        frame, fields = card("One folder. Everything together.", "Source, build tools, signing identity and finished bundles live here. Keep this folder for future updates.")
        self.workspace_input = QLineEdit(str(self.workspace))
        self.workspace_input.setReadOnly(True)
        self.workspace_input.setAccessibleName("Managed work folder")
        self.browse_workspace = button("Choose existing…")
        fields.addLayout(row(self.workspace_input, self.browse_workspace))
        self.new_app_button = button("Create a different app…")
        fields.addWidget(self.new_app_button, alignment=Qt.AlignmentFlag.AlignLeft)
        layout.addWidget(frame)

        frame, fields = card("Your app identity", "This unique application ID identifies your app in Google Play. You can edit it before your first build. Updates keep the same ID and signing key.")
        identity_label = label("Application ID")
        self.app_id = QLineEdit()
        self.app_id.setAccessibleName("Application ID")
        identity_label.setBuddy(self.app_id)
        fields.addWidget(identity_label)
        fields.addWidget(self.app_id)
        self.identity_status = label("New identity • a signing key will be created locally", "muted")
        fields.addWidget(self.identity_status)
        self.restore_button = button("Restore encrypted identity…")
        fields.addWidget(self.restore_button, alignment=Qt.AlignmentFlag.AlignLeft)
        layout.addWidget(frame)
        layout.addWidget(label("No Play password, service account or API access required. You upload the finished bundle yourself.", "muted"))
        layout.addStretch()

    def _make_options_page(self):
        layout = self._page("Choose what to include.", "Android Auto is ready to build. CarPlay requires your authentication files.")
        frame, fields = card("CarPlay", "Select compatible files you are authorized to use and include. These are separate from your Android signing key.")
        self.carplay = QCheckBox("Include CarPlay authentication files")
        fields.addWidget(self.carplay)
        self.carplay_fields = QWidget()
        inputs = QVBoxLayout(self.carplay_fields)
        inputs.setContentsMargins(0, 0, 0, 0)
        inputs.setSpacing(12)
        self.key_input = QLineEdit()
        self.key_input.setPlaceholderText("Private key (.pk8)")
        self.key_input.setReadOnly(True)
        self.key_input.setAccessibleName("CarPlay private key file")
        self.cert_input = QLineEdit()
        self.cert_input.setPlaceholderText("Certificate (.p7b)")
        self.cert_input.setReadOnly(True)
        self.cert_input.setAccessibleName("CarPlay certificate file")
        self.key_button = button("Choose .pk8…")
        self.cert_button = button("Choose .p7b…")
        inputs.addLayout(row(self.key_input, self.key_button))
        inputs.addLayout(row(self.cert_input, self.cert_button))
        inputs.addWidget(label("Both files are required. Files embedded in an app can be extracted by anyone who receives it. Do not treat the built app as a secure vault.", "muted"))
        self.carplay_fields.hide()
        fields.addWidget(self.carplay_fields)
        layout.addWidget(frame)

        frame, fields = card("Build tools & SDK licenses", "The first build downloads source and build tools, including Java and the Android SDK. Allow several GB of free space and time for downloads.")
        self.license_link = button("Read Android SDK terms ↗")
        fields.addWidget(self.license_link, alignment=Qt.AlignmentFlag.AlignLeft)
        self.licenses = QCheckBox("I accept the Android SDK license terms")
        self.licenses.setAccessibleName("Accept Android SDK license terms")
        fields.addWidget(self.licenses)
        fields.addWidget(label("Required before installation. Nothing is accepted on your behalf until you choose this option.", "muted"))
        layout.addWidget(frame)
        layout.addStretch()

    def _make_build_page(self):
        layout = self._page("From source to your bundle.", "The build runs locally. You can keep this window open while it works.")
        frame, fields = card("Build activity")
        self.status_label = label("Ready to build", "cardTitle")
        self.detail_label = label("", "muted")
        self.progress = QProgressBar()
        self.progress.setTextVisible(False)
        self.progress.setRange(0, 100)
        self.progress.setValue(0)
        fields.addWidget(self.status_label)
        fields.addWidget(self.detail_label)
        fields.addWidget(self.progress)
        self.log_toggle = button("Show build details")
        fields.addWidget(self.log_toggle, alignment=Qt.AlignmentFlag.AlignLeft)
        self.log = QPlainTextEdit()
        self.log.setReadOnly(True)
        self.log.setMaximumBlockCount(2000)
        self.log.setMinimumHeight(140)
        self.log.setAccessibleName("Build log")
        self.log.hide()
        fields.addWidget(self.log)
        layout.addWidget(frame)
        self.result_card, results = card("Your bundle is ready", "Upload this .aab to Internal testing on the dedicated Android Automotive OS track. The builder never uploads anything.")
        self.artifact_path = QLineEdit()
        self.artifact_path.setReadOnly(True)
        self.artifact_path.setAccessibleName("Built Android App Bundle path")
        results.addWidget(self.artifact_path)
        self.result_metadata = label("", "muted")
        self.result_metadata.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
        results.addWidget(self.result_metadata)
        self.show_folder_button = button("Show in folder")
        self.guide_button = button("Open Internal Test guide ↗")
        results.addLayout(row(self.show_folder_button, self.guide_button))
        results.addWidget(label("Protect your update identity. Keep an encrypted backup somewhere separate from this work folder.", "muted"))
        self.backup_button = button("Back up identity…")
        results.addWidget(self.backup_button, alignment=Qt.AlignmentFlag.AlignLeft)
        self.result_card.hide()
        layout.addWidget(self.result_card)
        layout.addStretch()

    def _page_changed(self, index: int):
        for i, step in enumerate(self.steps):
            step.setProperty("active", i == index)
            step.style().unpolish(step)
            step.style().polish(step)
        self.back_button.setVisible(index > 0)
        self.next_button.setText("Build bundle" if index == 1 else "Continue")
        self.error_label.clear()

    def load_workspace(self, workspace: Path, *, keep_new_id: bool = False):
        self.workspace = Path(workspace).expanduser()
        self.workspace_input.setText(str(self.workspace))
        self.error_label.clear()
        try:
            remembered = self.workspace == self._remembered_workspace
            # A path preference is saved when the wizard opens, before a work
            # folder or signing identity exists. Only an established identity
            # makes a missing folder a recovery event; an existing non-folder
            # remains invalid even for an uninitialized preference.
            if remembered and not self.workspace.is_dir() and (
                    self._expected_app_id or self.workspace.exists()):
                raise ValueError("The saved work folder is missing or unavailable.")
            state = self.backend.load_state(self.workspace)
            if self.workspace == self._identity_workspace and self._expected_app_id and (
                    state is None or state["applicationId"] != self._expected_app_id):
                raise ValueError("The saved app identity is missing or has changed.")
            self.state = state
            if state is not None:
                self._identity_workspace = self.workspace
                self._expected_app_id = state["applicationId"]
                self.app_id.setText(state["applicationId"])
                self.app_id.setReadOnly(True)
                self.identity_status.setText("Saved identity • updates keep your application ID and signing key")
            else:
                if not keep_new_id:
                    self.app_id.setText(self.backend.suggest_app_id())
                self.app_id.setReadOnly(False)
                self.identity_status.setText("New identity • a signing key will be created locally")
            self.next_button.setEnabled(True)
            self._workspace_error = ""
            if self.settings is not None:
                self.settings.setValue("workspace/path", str(self.workspace.absolute()))
                self.settings.setValue("workspace/applicationId", state["applicationId"] if state else "")
                self.settings.sync()
                if self.settings.status() != QSettings.Status.NoError:
                    raise OSError("Cannot save app settings to remember this work folder. Check settings file permissions.")
        except Exception as exc:
            self.state = None
            self.app_id.clear()
            self.app_id.setReadOnly(True)
            self.identity_status.setText("Identity needs attention • do not replace your signing key")
            self._workspace_error = (f"{exc} Restore your encrypted identity backup or choose an existing work folder. "
                                     "Do not create a different app unless you intend to start over.")
            self.error_label.setText(self._workspace_error)
            self.next_button.setEnabled(False)

    def choose_workspace(self):
        if self.worker is not None:
            return
        selected = QFileDialog.getExistingDirectory(self, "Choose an existing app's work folder", str(self.workspace))
        if selected:
            try:
                target = Path(selected)
                if not target.is_dir() or self.backend.load_state(target) is None:
                    raise ValueError("Choose a folder containing a saved app identity, or restore your encrypted backup. "
                                     "Use ‘Create a different app’ only to intentionally start over.")
            except Exception as exc:
                self.error_label.setText(str(exc))
                return
            self.result = None
            self.result_card.hide()
            self._remembered_workspace = None
            self._expected_app_id = ""
            self.load_workspace(target)

    def create_different_app(self):
        if self.worker is not None:
            return
        selected = QFileDialog.getExistingDirectory(self, "Choose an empty folder for a different app", str(self.workspace.parent))
        if not selected:
            return
        target = Path(selected)
        try:
            if not target.is_dir() or any(target.iterdir()):
                raise ValueError("Choose an empty folder. Existing app identities must not be replaced.")
            answer = QMessageBox.question(
                self, "Create a different app?",
                "This creates a new application ID and signing key, not updates to your previous app. "
                "Keep your old work folder or encrypted backup for updates. Continue with a different app?",
                QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
                QMessageBox.StandardButton.No)
            if answer != QMessageBox.StandardButton.Yes:
                return
            self._remembered_workspace = None
            self._expected_app_id = ""
            self.result = None
            self.result_card.hide()
            self.load_workspace(target)
        except Exception as exc:
            self.error_label.setText(str(exc))

    def choose_carplay_file(self, field: QLineEdit, file_filter: str):
        selected, _ = QFileDialog.getOpenFileName(self, "Choose CarPlay file", str(Path.home()), file_filter)
        if selected:
            field.setText(selected)

    def validate_identity(self):
        if self.state is not None:
            # Recheck on Continue/Build: a removable work folder may disappear
            # while this window is open. Never turn that into a fresh identity.
            self.load_workspace(self.workspace, keep_new_id=True)
        if self._workspace_error:
            raise ValueError(self._workspace_error)
        app_id = self.app_id.text()
        reserved = set("abstract assert boolean break byte case catch char class const continue default do double else enum extends final finally float for goto if implements import instanceof int interface long native new package private protected public return short static strictfp super switch synchronized this throw throws transient try void volatile while true false null".split())
        if (len(app_id) > 150 or app_id == "com.openautolink.app"
                or not re.fullmatch(r"[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)+", app_id)
                or any(part in reserved for part in app_id.split("."))):
            raise ValueError("Use a unique lowercase application ID, for example com.example.my_app (150 characters maximum). Each part must start with a letter; no spaces, Java keywords, or com.openautolink.app.")
        if self.state and app_id != self.state["applicationId"]:
            raise ValueError("The application ID is locked to this saved identity.")
        return app_id

    def build_config(self):
        app_id = self.validate_identity()
        if not self.licenses.isChecked():
            raise ValueError("Read and accept the Android SDK license terms before building.")
        key = certificate = None
        if self.carplay.isChecked():
            if not self.key_input.text() or not self.cert_input.text():
                raise ValueError("Both CarPlay files are required: a .pk8 private key and a .p7b certificate.")
            key, certificate = Path(self.key_input.text()), Path(self.cert_input.text())
            if key.suffix.lower() != ".pk8" or certificate.suffix.lower() != ".p7b" or not key.is_file() or not certificate.is_file():
                raise ValueError("Choose existing .pk8 and .p7b files for CarPlay.")
        return self.backend.BuildConfig(workspace=self.workspace, app_id=app_id, carplay_key=key,
                                        carplay_certificate=certificate, accept_licenses=True)

    def advance(self):
        if self.worker is not None:
            return
        self.error_label.clear()
        try:
            index = self.pages.currentIndex()
            if index == 0:
                self.validate_identity()
                self.pages.setCurrentIndex(1)
            elif index == 1:
                self.start_build(self.build_config())
            else:
                self.pages.setCurrentIndex(1)
        except (ValueError, self.backend.BuilderError) as exc:
            self.error_label.setText(str(exc))

    def start_build(self, config):
        if self.worker is not None:
            return
        self.result = None
        self.result_card.hide()
        self.log.clear()
        self.pages.setCurrentIndex(2)
        self.next_button.hide()
        self.back_button.hide()
        self.cancel_button.show()
        self.cancel_button.setEnabled(True)
        self.cancel_button.setText("Cancel build")
        self.status_label.setText("Starting your build")
        self.detail_label.setText("Preparing your managed work folder…")
        self.progress.setRange(0, 0)
        self.cancel_event = threading.Event()
        self.worker = BuildWorker(self.backend, config, self.cancel_event, self)
        self.worker.progress_changed.connect(self._on_progress)
        self.worker.succeeded.connect(self._on_success)
        self.worker.failed.connect(self._on_failure)
        self.worker.cancelled.connect(self._on_cancelled)
        self.worker.finished.connect(self._worker_finished)
        self.worker.start()

    @Slot(str, str)
    def _on_progress(self, stage: str, message: str):
        if not self.cancel_event.is_set():
            self.status_label.setText(stage.replace("_", " ").replace("-", " ").capitalize())
            self.detail_label.setText(message)
        self.log.appendPlainText(f"[{stage}] {message}")

    @Slot(dict)
    def _on_success(self, result: dict):
        try:
            artifact = Path(result["artifact"]).resolve()
            if artifact.suffix.lower() != ".aab" or not artifact.is_file():
                raise ValueError("The build returned no readable .aab file. Review the build details.")
            metadata = (f"{result['applicationId']}\nVersion {result['versionName']} • code {result['versionCode']}\n"
                        f"Source commit: {result['sourceCommit']}")
        except (KeyError, TypeError, ValueError, OSError) as exc:
            self._on_failure(str(exc))
            return
        self.result = result
        self.artifact_path.setText(str(artifact))
        self.artifact_path.setCursorPosition(0)
        self.result_metadata.setText(metadata)
        self.result_card.show()
        self.status_label.setText("Build complete")
        self.detail_label.setText("Your signed Android App Bundle is ready for Internal testing.")
        self.progress.setRange(0, 100)
        self.progress.setValue(100)

    @Slot(str)
    def _on_failure(self, message: str):
        self.status_label.setText("Build needs attention")
        self.detail_label.setText("Your work folder is preserved. Review the message, then try again.")
        self.error_label.setText(message)
        self.log.appendPlainText(f"Build stopped: {message}")
        self.progress.setRange(0, 100)
        self.progress.setValue(0)

    @Slot()
    def _on_cancelled(self):
        self.status_label.setText("Build cancelled")
        self.detail_label.setText("Your saved identity is kept. You can build again when you're ready.")
        self.progress.setRange(0, 100)
        self.progress.setValue(0)

    @Slot()
    def _worker_finished(self):
        worker = self.worker
        self.worker = None
        if worker:
            worker.deleteLater()
        error = self.error_label.text()
        self.load_workspace(self.workspace, keep_new_id=True)
        if error and not self.error_label.text():
            self.error_label.setText(error)
        self.cancel_button.hide()
        self.next_button.show()
        self.next_button.setText("Build update" if self.result else "Review settings")
        self.back_button.show()
        if self._close_after_cancel:
            self.close()

    def cancel_build(self):
        if self.worker is not None:
            self.cancel_event.set()
            self.cancel_button.setEnabled(False)
            self.cancel_button.setText("Cancelling…")
            self.status_label.setText("Stopping safely")
            self.detail_label.setText("Waiting for the current operation to stop. Your signing identity will be kept.")

    def toggle_log(self):
        visible = self.log.isHidden()
        self.log.setVisible(visible)
        self.log_toggle.setText("Hide build details" if visible else "Show build details")

    def show_artifact_folder(self):
        if self.result:
            QDesktopServices.openUrl(QUrl.fromLocalFile(str(Path(self.result["artifact"]).resolve().parent)))

    def restore_identity(self):
        if self.worker is not None:
            return
        archive, _ = QFileDialog.getOpenFileName(self, "Restore encrypted identity", str(Path.home()), "Encrypted identity (*.oalidentity);;All files (*)")
        if not archive:
            return
        password, accepted = QInputDialog.getText(self, "Unlock identity backup", "Backup password", QLineEdit.EchoMode.Password)
        if not accepted:
            return
        try:
            self.backend.restore_identity(self.workspace, Path(archive), password)
            self.load_workspace(self.workspace)
            if self.state is None:
                raise ValueError(self.error_label.text() or "Restore returned without a saved identity.")
            self.carplay.setChecked(False)
            self.key_input.clear()
            self.cert_input.clear()
            QMessageBox.information(self, "Identity restored", "Your application ID and signing key are ready for updates. CarPlay files are not included in identity backups; select them again if needed.")
        except Exception as exc:
            self.error_label.setText(str(exc))
        finally:
            password = None

    def backup_identity(self):
        if self.worker is not None:
            return
        destination, _ = QFileDialog.getSaveFileName(self, "Save encrypted identity backup", str(Path.home() / "OpenAutoLink-identity.oalidentity"), "Encrypted identity (*.oalidentity)")
        if not destination:
            return
        password, accepted = QInputDialog.getText(self, "Protect your identity backup", "Choose a password (at least 12 characters). Store it separately; it cannot be recovered.", QLineEdit.EchoMode.Password)
        if not accepted:
            return
        confirmation = None
        try:
            if len(password) < 12:
                raise ValueError("Use a backup password of at least 12 characters.")
            confirmation, accepted = QInputDialog.getText(self, "Confirm backup password", "Enter the same password again", QLineEdit.EchoMode.Password)
            if not accepted:
                return
            if password != confirmation:
                raise ValueError("The passwords do not match. No backup was written.")
            target = Path(destination)
            self.backend.backup_identity(self.workspace, target, password)
            if not target.is_file() or target.stat().st_size == 0:
                raise ValueError("The backup could not be verified on disk.")
            QMessageBox.information(self, "Encrypted identity saved", f"Saved to:\n{target}\n\nKeep this backup and its password safe. CarPlay files are excluded.")
        except Exception as exc:
            self.error_label.setText(str(exc))
        finally:
            password = confirmation = None

    def closeEvent(self, event: QCloseEvent):
        if self.worker is not None:
            if not self._close_after_cancel:
                answer = QMessageBox.question(self, "A build is still running", "Cancel the build and close when it has stopped safely?",
                                              QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
                                              QMessageBox.StandardButton.No)
                if answer == QMessageBox.StandardButton.Yes:
                    self._close_after_cancel = True
                    self.cancel_build()
            event.ignore()
            return
        event.accept()
