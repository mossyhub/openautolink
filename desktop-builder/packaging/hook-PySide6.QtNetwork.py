"""Use Windows' native Schannel Qt TLS backend, not PATH-sourced OpenSSL.

Python HTTPS continues using its official CPython OpenSSL DLLs. Non-Windows
platforms retain the stock hook unmodified. Native TLS/startup tests required.
"""
from pathlib import Path
import runpy
import PyInstaller
from PyInstaller import compat
from PyInstaller.utils.hooks.qt import add_qt6_dependencies

if compat.is_win:
    hiddenimports, binaries, datas = add_qt6_dependencies(__file__)
    binaries = [(source, destination) for source, destination in binaries
                if Path(source).name.lower() != "qopensslbackend.dll"]
    # Deliberately do not call collect_qtnetwork_files(): its Windows collector
    # searches arbitrary PATH locations for a second set of OpenSSL libraries.
else:
    _stock = runpy.run_path(str(Path(PyInstaller.__file__).parent / "hooks/hook-PySide6.QtNetwork.py"))
    hiddenimports = _stock.get("hiddenimports", [])
    binaries = _stock.get("binaries", [])
    datas = _stock.get("datas", [])
