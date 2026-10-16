"""Run with python -m oal_builder; packaged entry point uses the same code."""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import sys


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="OpenAutoLink desktop builder — local builds, no Play credentials.")
    parser.add_argument("--workspace", type=Path, help="Managed work folder (default: ~/OpenAutoLink Builder)")
    parser.add_argument("--smoke-test", action="store_true", help="Open and close the real UI without downloads or a build")
    parser.add_argument("--screenshot", type=Path, help="Save a PNG of the actual UI, then exit without building")
    parser.add_argument("--screenshot-page", choices=("identity", "options"), default="identity")
    args = parser.parse_args(argv)
    offline = args.smoke_test or args.screenshot is not None
    if offline and sys.platform.startswith("linux") and not (os.environ.get("DISPLAY") or os.environ.get("WAYLAND_DISPLAY")):
        os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

    from PySide6.QtCore import QTimer
    from PySide6.QtGui import QColor, QFontDatabase, QPalette
    from PySide6.QtWidgets import QApplication
    from oal_builder.ui import BuilderWindow

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
    window = BuilderWindow(workspace=args.workspace)
    window.show()
    if args.screenshot_page == "options" and offline:
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
