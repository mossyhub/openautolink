"""Frozen-only compliance export: runs before the GUI and needs no Qt display."""
from pathlib import Path
import shutil
import sys

if len(sys.argv) > 1 and sys.argv[1] == "--runtime-info":
    import json
    import platform

    if len(sys.argv) != 3:
        raise SystemExit("Usage: OpenAutoLink-Builder --runtime-info DESTINATION.json")
    with Path(sys.argv[2]).open("x", encoding="utf-8") as writer:
        json.dump({"architecture": platform.machine().lower().replace("amd64", "x86_64"),
                   "system": platform.system(), "frozen": bool(getattr(sys, "frozen", False))}, writer)
    raise SystemExit(0)

if len(sys.argv) > 1 and sys.argv[1] == "--export-compliance":
    if len(sys.argv) != 3:
        raise SystemExit("Usage: OpenAutoLink-Builder --export-compliance DESTINATION.zip")
    source = Path(sys._MEIPASS) / "builder-compliance.zip"
    target = Path(sys.argv[2]).resolve()
    target.parent.mkdir(parents=True, exist_ok=True)
    # Refuse replacement: exporting notices must not overwrite a user's file.
    with source.open("rb") as reader, target.open("xb") as writer:
        shutil.copyfileobj(reader, writer)
    raise SystemExit(0)
