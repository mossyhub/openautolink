"""Widget-only desktop platform plugins; no QML/Quick/SVG/PDF/VirtualKeyboard.

Retain PyInstaller's link-time dependency discovery and translation handling.
Do not bundle optional image/input/theme/Wayland plugins merely because the
PySide wheel contains them. PNG support is built into QtGui. Linux uses XCB.
This reduces, but does not establish coverage of, Qt's static third-party code.
"""
from pathlib import Path
from PyInstaller.utils.hooks.qt import add_qt6_dependencies

hiddenimports, binaries, datas = add_qt6_dependencies(__file__)
_platform_plugins = {"libqxcb.so", "libqoffscreen.so", "qoffscreen.dll",
                     "libqoffscreen.dylib", "qwindows.dll", "libqcocoa.dylib"}
binaries = [(source, destination) for source, destination in binaries
            if Path(source).name in _platform_plugins
            and Path(destination).name == "platforms"]
