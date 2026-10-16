"""Fetch pinned supplier evidence, preserve notices, and match frozen bytes.

This deliberately produces evidence, NOT a coverage approval. Run on each native
inventory; source archives and notice supersets do not prove static-code closure.
No archives are extracted and no supplier code is executed.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import tarfile
import tempfile
from urllib.parse import urlparse
from urllib.request import urlopen
import zipfile


def digest(path):
    with path.open("rb") as reader:
        return hashlib.file_digest(reader, "sha256").hexdigest()


def prepare(lock: dict, destination: Path, payload: list[dict], *, offline=False):
    if lock.get("schema") != 1:
        raise ValueError("Unknown evidence lock schema")
    destination.mkdir(parents=True, exist_ok=True)
    report = {"status": "evidence-only", "inputs": [], "notices": [], "matches": []}
    by_hash = {}
    for item in payload:
        by_hash.setdefault(item["sha256"], []).append(item["path"])
    seen = set()
    for entry in lock["inputs"]:
        name, expected, url = entry["path"], entry["sha256"], entry["url"]
        if (not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+~%-]*", name)
                or name in seen or not re.fullmatch(r"[0-9a-f]{64}", expected)
                or urlparse(url).scheme != "https" or not urlparse(url).netloc):
            raise ValueError("Unsafe or duplicate evidence lock entry")
        seen.add(name)
        target = destination / name
        if target.is_symlink():
            raise ValueError("Symlink evidence cache entry")
        if not target.exists() and entry.get("container"):
            container = entry["container"]
            if container not in {item["path"] for item in report["inputs"]}:
                raise ValueError("Nested source container must be verified first")
            # Read one explicitly named regular member, never extract a directory.
            with tarfile.open(destination / container) as archive:
                members = [m for m in archive if m.name == entry.get("archive_member")]
                if len(members) != 1 or not members[0].isfile():
                    raise ValueError("Missing/unsafe nested source member")
                with tempfile.NamedTemporaryFile(dir=destination, delete=False) as writer:
                    temporary = Path(writer.name)
                    try:
                        with archive.extractfile(members[0]) as reader:
                            shutil.copyfileobj(reader, writer)
                        writer.close()
                        if digest(temporary) != expected:
                            raise ValueError("Nested source member hash mismatch")
                        temporary.replace(target)
                    finally:
                        writer.close()
                        temporary.unlink(missing_ok=True)
        if not target.exists():
            if offline:
                raise ValueError(f"Missing cached evidence: {name}")
            with tempfile.NamedTemporaryFile(dir=destination, delete=False) as writer:
                temporary = Path(writer.name)
                try:
                    with urlopen(url, timeout=180) as reader:
                        if urlparse(reader.url).scheme != "https":
                            raise ValueError("Insecure evidence redirect")
                        shutil.copyfileobj(reader, writer)
                    writer.flush()
                    writer.close()  # Windows cannot rename an open NamedTemporaryFile.
                    if digest(temporary) != expected:
                        raise ValueError(f"Supplier evidence hash mismatch: {name}")
                    temporary.replace(target)
                finally:
                    writer.close()
                    temporary.unlink(missing_ok=True)
        if digest(target) != expected:
            raise ValueError(f"Cached evidence hash mismatch: {name}")
        report["inputs"].append({"path": name, "sha256": expected, "url": url})
        requested = entry.get("notice_members", [])
        notices = {}
        def consume(member, blob):
            if member in requested:
                notices[member] = blob
            for frozen_path in by_hash.get(hashlib.sha256(blob).hexdigest(), []):
                report["matches"].append({"payload": frozen_path, "input": name, "member": member,
                                          "sha256": hashlib.sha256(blob).hexdigest()})
        if zipfile.is_zipfile(target):
            with zipfile.ZipFile(target) as archive:
                if len(archive.namelist()) != len(set(archive.namelist())):
                    raise ValueError("Duplicate supplier ZIP member")
                for item in archive.infolist():
                    if not item.is_dir() and (name.endswith(".whl") or item.filename in requested):
                        consume(item.filename, archive.read(item))
        elif tarfile.is_tarfile(target):
            with tarfile.open(target) as archive:
                for item in archive:
                    if item.isfile() and item.name in requested:
                        if item.name in notices:
                            raise ValueError("Duplicate supplier tar member")
                        consume(item.name, archive.extractfile(item).read())
        elif requested == ["@file"]:
            consume("@file", target.read_bytes())
        if set(notices) != set(requested) or any(not blob for blob in notices.values()):
            raise ValueError(f"Missing or empty supplier notice members: {name}")
        if notices:
            notice_path = destination / (name + ".notices.txt")
            if notice_path.is_symlink():
                raise ValueError("Symlink notice output")
            with notice_path.open("wb") as writer:
                writer.write(f"Supplier: {url}\nSHA-256: {expected}\nNotice superset; not an assertion all components are shipped.\n".encode())
                for member, blob in sorted(notices.items()):
                    writer.write(f"\n===== {member} =====\n".encode())
                    writer.write(blob)
                    writer.write(b"\n")
            report["notices"].append({"path": notice_path.name, "sha256": digest(notice_path), "input": name})
    matched = {item["payload"] for item in report["matches"]}
    report["unmatched"] = sorted(item["path"] for item in payload if item["path"] not in matched)
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--lock", type=Path, default=Path(__file__).parent / "provenance/source-inputs.lock.json")
    parser.add_argument("--inventory", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--offline", action="store_true")
    args = parser.parse_args(argv)
    report = prepare(json.loads(args.lock.read_text()), args.output,
                     json.loads(args.inventory.read_text())["payload"], offline=args.offline)
    (args.output / "evidence-report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({"status": report["status"], "inputs": len(report["inputs"]),
                      "noticeBundles": len(report["notices"]), "matchedPaths": len({m['payload'] for m in report['matches']}),
                      "unmatchedPaths": len(report["unmatched"])}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
