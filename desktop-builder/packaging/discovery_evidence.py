"""Allowlisted native discovery diagnostics. Never a license approval or download.

No binaries, source trees, environment dumps, pip direct_url credentials or raw
process output are copied. Supplier matches describe installed bytes, not a
claim about complete static dependencies or corresponding-source obligations.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path, PurePosixPath
import re


SHA256 = re.compile(r"[0-9a-f]{64}")


def relative_name(value):
    if (not isinstance(value, str) or not value or len(value) > 512
            or any(ord(c) < 32 for c in value) or "\\" in value or ":" in value
            or value.startswith("/") or ".." in PurePosixPath(value).parts):
        raise ValueError("Unsafe diagnostic member name")
    return value


def read_inventory(path):
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 8_000_000:
        raise ValueError("Missing or unsafe discovery inventory")
    data = json.loads(path.read_text(encoding="utf-8"))
    if set(data) != {"status", "artifactSha256", "builderSourceSha256", "payload"} or data["status"] != "unqualified":
        raise ValueError("Unexpected discovery inventory schema")
    for key in ("artifactSha256", "builderSourceSha256"):
        if not isinstance(data[key], str) or not SHA256.fullmatch(data[key]):
            raise ValueError("Invalid inventory digest")
    if not isinstance(data["payload"], list) or not data["payload"]:
        raise ValueError("Missing native payload")
    seen = set()
    for item in data["payload"]:
        if (not isinstance(item, dict) or not {"path", "sha256", "size", "kind"} <= item.keys()
                or item.keys() - {"path", "sha256", "size", "kind", "target", "hash_scope"}):
            raise ValueError("Unexpected payload fields")
        name = relative_name(item["path"])
        if name in seen or not isinstance(item["sha256"], str) or not SHA256.fullmatch(item["sha256"]):
            raise ValueError("Duplicate payload or invalid digest")
        seen.add(name)
        if type(item["size"]) is not int or item["size"] < 0:
            raise ValueError("Invalid payload size")
        if item["kind"] not in ("bootloader", "binary", "symlink"):
            raise ValueError("Unexpected payload kind")
        if "target" in item:
            # Framework links may go up within the frozen tree. Never read targets.
            target = item["target"]
            if not isinstance(target, str) or target.startswith("/") or ":" in target or "\\" in target or any(ord(c) < 32 for c in target):
                raise ValueError("Unsafe symlink description")
        if "hash_scope" in item and item["hash_scope"] not in (
                "prefix-bytes", "ELF64-prefix-with-e_shoff-zeroed",
                "PE-prefix-with-build-timestamps-and-checksum-zeroed"):
            raise ValueError("Unexpected bootloader hash scope")
    return data


PACKAGES = ("PySide6", "PySide6-Essentials", "PySide6-Addons", "shiboken6",
            "cryptography", "cffi", "pyinstaller", "pyinstaller-hooks-contrib")
NOTICE_NAME = re.compile(r"(?:licen[cs]e|copying|notice|copyright)(?:\.(?:txt|rst|md|apache|bsd|psf|gpl|lgpl|mit))?", re.I)


def notice(path, member):
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 2_000_000:
        raise ValueError("Unsafe supplier notice")
    blob = path.read_bytes()
    text = blob.decode("utf-8")
    if (not text.strip() or any(ord(c) < 32 and c not in "\r\n\t\f" for c in text)
            or re.search(r"-----BEGIN [^-]*PRIVATE KEY-----", text)):
        raise ValueError("Non-notice content rejected")
    return {"member": member, "sha256": hashlib.sha256(blob).hexdigest(), "text": text}


def file_digest(path):
    with path.open("rb") as reader:
        return hashlib.file_digest(reader, "sha256").hexdigest()


def wheel_evidence(payload, *, distributions=None):
    import importlib.metadata
    from email.parser import Parser
    if distributions is None:
        distributions = [importlib.metadata.distribution(name) for name in PACKAGES]
    by_hash = {}
    for item in payload:
        by_hash.setdefault(item["sha256"], []).append(item["path"])
    basenames = {PurePosixPath(item["path"]).name for item in payload}
    result = []
    for dist in distributions:
        root = Path(dist.locate_file("")).resolve()
        wheel = Parser().parsestr(dist.read_text("WHEEL") or "")
        entry = {"name": dist.metadata["Name"], "version": dist.version,
                 "licenseExpression": dist.metadata.get("License-Expression"),
                 "wheelTags": wheel.get_all("Tag", []), "notices": [], "nativeMatches": [], "nativeCandidates": []}
        for member in dist.files or ():
            # RECORD may include ../ scripts: never read them, even for hashing.
            try:
                name = relative_name(str(member))
            except ValueError:
                continue
            path = Path(dist.locate_file(member))
            if path.is_symlink() or not path.resolve().is_relative_to(root) or not path.is_file():
                continue
            parts = PurePosixPath(name).parts
            if parts[0].endswith(".dist-info") and NOTICE_NAME.fullmatch(parts[-1]):
                entry["notices"].append(notice(path, name))
            if parts[-1] in basenames and path.stat().st_size <= 256_000_000:
                candidate = installed_candidate(path, name, payload)
                entry["nativeCandidates"].append(candidate)
                digest = candidate["sha256"]
                entry["nativeMatches"].extend({"payload": target, "member": name, "sha256": digest}
                                              for target in by_hash.get(digest, []))
        result.append(entry)
    return result


def wheel_downloads(path):
    from urllib.parse import urlsplit
    allowed = {name.lower().replace("_", "-") for name in PACKAGES}
    result = []
    for entry in json.loads(path.read_text(encoding="utf-8"))["install"]:
        meta, download = entry["metadata"], entry.get("download_info", {})
        url = download.get("url", "")
        parsed = urlsplit(url)
        digest = download.get("archive_info", {}).get("hashes", {}).get("sha256", "")
        if (meta["name"].lower().replace("_", "-") in allowed and parsed.scheme == "https"
                and parsed.netloc == "files.pythonhosted.org" and not parsed.query and not parsed.fragment
                and re.fullmatch(r"/packages/[A-Za-z0-9/_.+-]+", parsed.path)
                and SHA256.fullmatch(digest)):
            result.append({"name": meta["name"], "version": meta["version"], "url": url, "sha256": digest})
    return result


def command(*args, cwd=None):
    import subprocess
    return subprocess.check_output(args, cwd=cwd, text=True, encoding="utf-8", stderr=subprocess.DEVNULL).strip()


def identity(expected_commit, runner, run_id, run_attempt):
    root = Path(__file__).resolve().parents[2]
    if not re.fullmatch(r"[0-9a-f]{40}", expected_commit):
        raise ValueError("Invalid expected commit")
    commit = command("git", "rev-parse", "HEAD", cwd=root)
    if commit != expected_commit:
        raise ValueError("Checkout differs from expected commit")
    if runner not in ("ubuntu-22.04", "windows-2022", "macos-15", "macos-15-intel", "local-diagnostic"):
        raise ValueError("Unexpected runner")
    if not re.fullmatch(r"[0-9]+", run_id) or not re.fullmatch(r"[0-9]+", run_attempt):
        raise ValueError("Invalid run identity")
    return {"checkoutCommit": commit, "expectedCommit": expected_commit,
            "trackedCheckoutDirty": bool(command("git", "status", "--porcelain", "--untracked-files=no", cwd=root)),
            "runner": runner, "runId": run_id, "runAttempt": run_attempt}


def windows_version(path):
    """Read numeric PE version resources only; never execute the native file."""
    import pefile  # PyInstaller's Windows dependency; not needed on other hosts.
    try:
        pe = pefile.PE(str(path), fast_load=True)
    except pefile.PEFormatError:
        return {}
    try:
        pe.parse_data_directories(directories=[2])
        fixed = getattr(pe, "VS_FIXEDFILEINFO", [])
        if not fixed:
            return {}
        result = {}
        for key, prefix in (("fileVersion", "FileVersion"), ("productVersion", "ProductVersion")):
            ms, ls = getattr(fixed[0], prefix + "MS"), getattr(fixed[0], prefix + "LS")
            result[key] = ".".join(str(value) for value in (ms >> 16, ms & 0xffff, ls >> 16, ls & 0xffff))
        return result
    finally:
        pe.close()


def installed_candidate(path, member, payload):
    import sys
    digest = file_digest(path)
    result = {"member": member, "sha256": digest, "size": path.stat().st_size,
              "matches": [item["path"] for item in payload if item["sha256"] == digest]}
    if sys.platform == "win32":
        result.update(windows_version(path))
    return result


def python_evidence(payload):
    import platform
    import ssl
    import sys
    import sysconfig
    import zlib
    base = Path(sys.base_prefix).resolve()
    stdlib = Path(sysconfig.get_path("stdlib")).resolve()
    result = {"version": platform.python_version(), "compiler": platform.python_compiler(),
              "build": list(platform.python_build()), "openssl": ssl.OPENSSL_VERSION,
              "zlibBuild": zlib.ZLIB_VERSION, "zlibRuntime": zlib.ZLIB_RUNTIME_VERSION,
              "notices": [], "nativeCandidates": []}
    for path in (base / "LICENSE.txt", stdlib / "LICENSE.txt"):
        if path.is_file() and not path.is_symlink() and path.resolve().is_relative_to(base):
            result["notices"].append(notice(path, path.relative_to(base).as_posix()))
    basenames = {PurePosixPath(item["path"]).name for item in payload}
    directories = (base, base / "DLLs", base / "lib", stdlib / "lib-dynload")
    seen = set()
    for directory in directories:
        if not directory.is_dir() or not directory.resolve().is_relative_to(base):
            continue
        for path in directory.iterdir():
            if (path.name not in basenames or not path.is_file() or not path.resolve().is_relative_to(base)
                    or path.stat().st_size > 256_000_000 or path in seen):
                continue
            seen.add(path)
            result["nativeCandidates"].append(installed_candidate(path, path.relative_to(base).as_posix(), payload))
    return result


def linux_evidence(payload):
    """Query owners of candidate system libraries, then hash; filenames aren't proof."""
    import subprocess
    result = []
    packages = {}
    basenames = sorted({PurePosixPath(item["path"]).name for item in payload})
    for name in basenames:
        if ".so" not in name or not re.fullmatch(r"[A-Za-z0-9._+-]+", name):
            continue
        try:
            owners = command("dpkg-query", "-S", "*/" + name)
        except subprocess.CalledProcessError:
            continue
        for line in owners.splitlines():
            if ": " not in line:
                continue
            package, filename = line.rsplit(": ", 1)
            if not re.fullmatch(r"[a-z0-9][a-z0-9+.-]*(?::[a-z0-9]+)?", package):
                continue
            path = Path(filename)
            if (not filename.startswith(("/usr/lib/", "/lib/", "/lib64/"))
                    or not str(path.resolve()).startswith(("/usr/lib/", "/lib/", "/lib64/"))
                    or not path.is_file() or path.stat().st_size > 256_000_000):
                continue
            if package not in packages:
                fields = command("dpkg-query", "-W", "-f=${binary:Package}\t${Version}\t${source:Package}\t${source:Version}\t${Architecture}", package).split("\t")
                if len(fields) != 5:
                    raise ValueError("Unexpected dpkg metadata")
                entry = dict(zip(("package", "version", "sourcePackage", "sourceVersion", "architecture"), fields))
                entry.update(notices=[], nativeCandidates=[])
                copyright_path = (Path("/usr/share/doc") / package.split(":")[0] / "copyright").resolve()
                if copyright_path.is_file() and copyright_path.is_relative_to(Path("/usr/share/doc")):
                    entry["notices"].append(notice(copyright_path, package + "/copyright"))
                packages[package] = entry
                result.append(entry)
            candidate = installed_candidate(path, filename.lstrip("/"), payload)
            if candidate not in packages[package]["nativeCandidates"]:
                packages[package]["nativeCandidates"].append(candidate)
    return result


def collect(args):
    import platform
    import subprocess
    data = read_inventory(args.inventory)  # No discovery -> no evidence artifact.
    result = {"schema": 1, "status": "UNQUALIFIED-DISCOVERY-ONLY",
              "identity": identity(args.expected_commit, args.runner, args.run_id, args.run_attempt),
              "system": platform.system(), "architecture": platform.machine(),
              "osVersion": platform.version(), "inventory": data,
              "limitations": ["Installed-file matches are observations, not license approval or proof of static dependency closure.",
                              "Missing supplier notices and unmatched payloads still require review; no executable is retained.",
                              "CPython/setup-python supplier build, Windows runtime rights and Mac signing changes need independent supplier evidence."]}
    result["collectionErrors"] = []
    for key, collector in (("wheelDownloads", lambda: wheel_downloads(args.pip_report)),
                           ("wheels", lambda: wheel_evidence(data["payload"])),
                           ("python", lambda: python_evidence(data["payload"])),
                           ("linuxPackages", lambda: linux_evidence(data["payload"]) if platform.system() == "Linux" else [])):
        try:
            result[key] = collector()
        except (OSError, ValueError, KeyError, subprocess.SubprocessError):
            # Keep the actual inventory even if supplementary supplier data is absent.
            # Fixed codes only: never upload exceptions, commands, paths or raw logs.
            result["collectionErrors"].append(key + "-unavailable")
    matches = {item["payload"] for wheel in result.get("wheels", []) for item in wheel["nativeMatches"]}
    candidates = list(result.get("python", {}).get("nativeCandidates", []))
    candidates += [item for package in result.get("linuxPackages", []) for item in package["nativeCandidates"]]
    matches.update(target for candidate in candidates for target in candidate["matches"])
    result["unmatchedPayload"] = sorted(item["path"] for item in data["payload"] if item["path"] not in matches)
    return result


def main(argv=None):
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inventory", type=Path, required=True)
    parser.add_argument("--pip-report", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--expected-commit", required=True)
    parser.add_argument("--runner", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--run-attempt", required=True)
    args = parser.parse_args(argv)
    result = collect(args)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    # Exclusive creation rejects stale artifacts and symlink outputs.
    with args.output.open("x", encoding="utf-8", newline="\n") as writer:
        json.dump(result, writer, indent=2, ensure_ascii=True)
        writer.write("\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
