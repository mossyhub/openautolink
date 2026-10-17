"""Windows QWidget raster UI: omit optional Mesa OpenGL fallback.

Ordinary QWidget painting uses the raster backing store, not QOpenGLWidget.
No Qt Quick/OpenGL widgets are used by this application. Retain every other
stock PyInstaller hook behavior. Native frozen UI smoke remains mandatory.
"""
from pathlib import Path
import runpy
import PyInstaller
from PyInstaller import compat

_stock = runpy.run_path(str(Path(PyInstaller.__file__).parent / "hooks/hook-PySide6.py"))
hiddenimports = _stock.get("hiddenimports", [])
datas = _stock.get("datas", [])
binaries = _stock.get("binaries", [])
if compat.is_win:
    binaries = [(source, destination) for source, destination in binaries
                if Path(source).name.lower() != "opengl32sw.dll"]
