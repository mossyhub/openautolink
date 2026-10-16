"""Run with python -m oal_builder; packaged entry point uses the same code."""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import sys


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="OpenAutoLink desktop builder — local builds, no Play credentials.")
    parser.add_argument("--workspace", type=Path, help="Managed work folder (default: ~/OpenAutoLink Builder)")
    preview = parser.add_mutually_exclusive_group()
    preview.add_argument("--smoke-test", action="store_true", help="Open and close the real builder read-only: no downloads, builds, settings changes, or assent")
    preview.add_argument("--terms-preview", action="store_true", help="Preview Microsoft runtime terms separately, with Accept disabled; not a builder smoke test or assent")
    parser.add_argument("--screenshot", type=Path, help="Save a PNG of the read-only builder (or --terms-preview), then exit without accepting terms")
    parser.add_argument("--screenshot-page", choices=("identity", "options"), default="identity")
    args = parser.parse_args(argv)
    offline = args.smoke_test or args.terms_preview or args.screenshot is not None
    if offline and sys.platform.startswith("linux") and not (os.environ.get("DISPLAY") or os.environ.get("WAYLAND_DISPLAY")):
        os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

    from PySide6.QtCore import QTimer
    from PySide6.QtGui import QColor, QFontDatabase, QPalette
    from PySide6.QtWidgets import QApplication, QMessageBox

    app = QApplication.instance() or QApplication([sys.argv[0]])
    app.setApplicationName("OpenAutoLink Builder")
    app.setOrganizationName("OpenAutoLink")
    app.setStyle("Fusion")
    app.setFont(QFontDatabase.systemFont(QFontDatabase.SystemFont.GeneralFont))
    palette = QPalette()
    for role, color in ((QPalette.ColorRole.Window, "#101213"), (QPalette.ColorRole.WindowText, "#f3f5f4"),
                        (QPalette.ColorRole.Base, "#111415"), (QPalette.ColorRole.Text, "#f3f5f4"),
                        (QPalette.ColorRole.Button, "#222827"), (QPalette.ColorRole.ButtonText, "#f3f5f4"),
                        (QPalette.ColorRole.Highlight, "#32694f"), (QPalette.ColorRole.HighlightedText, "#f3f5f4")):
        palette.setColor(role, QColor(color))
    app.setPalette(palette)
    if sys.platform == "win32" and not offline:
        from oal_builder.windows_terms import TermsSettingsError, ensure_windows_terms
        # Frozen runtime_hook.py handles compliance export/runtime-info before
        # this entry point (and before Qt), regardless of the assent state.
        try:
            if not ensure_windows_terms(platform=sys.platform):
                return 1
        except TermsSettingsError as exc:
            QMessageBox.critical(None, "Microsoft runtime terms — settings error", str(exc))
            return 1

    if args.terms_preview:
        from oal_builder.windows_terms import create_terms_dialog
        window = create_terms_dialog(automated=True)
    elif offline:
        from oal_builder.ui import BuilderWindow, DEFAULT_WORKSPACE
        from types import SimpleNamespace
        from oal_builder import engine
        # Exercise the real builder, not a substitute dialog. Only its read-only
        # initialization API is available; build/backup/restore are absent.
        backend = SimpleNamespace(load_state=engine.load_state,
                                  suggest_app_id=engine.suggest_app_id,
                                  BuilderError=engine.BuilderError)
        # An explicit path prevents BuilderWindow from opening QSettings, even
        # when the command did not specify --workspace. No consent is implied.
        window = BuilderWindow(workspace=args.workspace or DEFAULT_WORKSPACE, backend=backend)
        window.centralWidget().setEnabled(False)
    else:
        from oal_builder.ui import BuilderWindow
        window = BuilderWindow(workspace=args.workspace)
    window.show()
    if args.screenshot_page == "options" and offline and not args.terms_preview:
        window.pages.setCurrentIndex(1)
    exit_status = 0

    def finish():
        nonlocal exit_status
        if args.screenshot:
            try:
                args.screenshot.parent.mkdir(parents=True, exist_ok=True)
                if not window.grab().save(str(args.screenshot), "PNG"):
                    raise OSError("Qt could not save the screenshot")
            except OSError as exc:
                if sys.stderr:
                    print(f"Screenshot failed: {exc}", file=sys.stderr)
                exit_status = 1
        window.close()
        app.exit(exit_status)

    if offline:
        QTimer.singleShot(350, finish)
    return app.exec()


if __name__ == "__main__":
    raise SystemExit(main())
