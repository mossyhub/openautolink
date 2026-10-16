"""Build native distributables on the target OS; never upload or publish them."""
from __future__ import annotations

import argparse
import hashlib
import importlib.metadata
import json
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import tarfile
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = ROOT.parent
NAME = "OpenAutoLink-Builder"


def coverage_asset(base: Path, asset: dict) -> Path:
    """Only explicit, hash-bound, nonempty local evidence; no network at build time."""
    from pathlib import PurePosixPath
    import re

    name = asset.get("path", "")
    path = PurePosixPath(name)
    if (not name or path.is_absolute() or ".." in path.parts or "\\" in name
            or ":" in name or not re.fullmatch(r"[0-9a-f]{64}", asset.get("sha256", ""))):
        raise ValueError(f"Unsafe native evidence path/hash: {name}")
    target = base / path
    if (not target.resolve().is_relative_to(base.resolve()) or target.is_symlink()
            or not target.is_file() or not target.stat().st_size or sha256(target) != asset["sha256"]):
        raise ValueError(f"Missing or changed native evidence: {name}")
    return target


def validate_native_coverage(payload: list[dict], coverage: dict, base: Path):
    """Mechanical evidence gate, not a legal opinion or a source-equivalence proof.

    A reviewer must establish supplier/source correspondence and all static code.
    No filename glob, distro version guess, or generic LGPL text grants coverage.
    """
    import re

    components = coverage.get("components", [])
    covered = [(entry["path"], entry["sha256"])
               for component in components for entry in component.get("payload", [])]
    actual = {(entry["path"], entry["sha256"]) for entry in payload}
    missing = [entry["path"] for entry in payload if (entry["path"], entry["sha256"]) not in covered]
    if missing:
        raise ValueError("Uncovered native payload: " + ", ".join(missing))
    if not payload or len(covered) != len(set(covered)) or set(covered) != actual:
        raise ValueError("Empty, duplicate or stale native coverage")
    if (coverage.get("schema") not in (1, 2) or coverage.get("system") != platform.system()
            or coverage.get("architecture") != platform.machine().lower().replace("amd64", "x86_64")):
        raise ValueError("Native coverage schema/platform mismatch")
    ids = [component.get("id", "") for component in components]
    if len(ids) != len(set(ids)) or any(not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", name) for name in ids):
        raise ValueError("Invalid/duplicate native component IDs")
    for component in components:
        # Schema 1 retains its legacy all-source contract. Schema 2 distinguishes
        # notice-only licenses from copyleft; never infer policy from a substring
        # ("MIT OR Proprietary" is not automatically MIT).
        notice_terms = {
            "MIT", "MIT-0", "BSD-2-Clause", "BSD-3-Clause", "Apache-2.0", "PSF-2.0",
            "ISC", "Zlib", "BSL-1.0", "Unicode-3.0", "ICU", "libpng-2.0",
            "bzip2-1.0.6", "FTL", "X11", "0BSD", "CC0-1.0", "Imlib2", "HPND",
            "MIT-open-group", "Apache-2.0 WITH LLVM-exception",
            "LicenseRef-ICC-License", "LicenseRef-SHA1-Public-Domain",
            "LicenseRef-libselinux-Public-Domain",
            "LicenseRef-BSD-3-Clause-with-PCRE2-Binary-Like-Packages-Exception",
        }
        # Only conjunctions of individually reviewed notice routes are allowed.
        # OR, parentheses, unknown LicenseRefs and invented exceptions fail closed.
        notice_only = coverage.get("schema") == 2 and all(
            term in notice_terms for term in component.get("license", "").split(" AND "))
        # These are composite grants, NOT aliases for MIT. The full exact
        # supplier notice and a library-specific scope are mandatory; see
        # LINUX-REVIEW.md for selections/exclusions and additional conditions.
        scoped_notices = {
            "LicenseRef-Kerberos-1.21.3": "936728f4181718f42951b881c1e8f1386bf6b2723c4fbc533c374d6f42c71816",
            "LicenseRef-libX11-1.8.12": "0b380a7fd5b2228f26e9585e56f14812efd3350f3df307507d2bc055dfd8de3e",
            "LicenseRef-com-err-1.47.2": "2041b5febdc110151ca3372d01b16b85dda1888e33f3f60fce0f59940eed7d19",
        }
        if coverage.get("schema") == 2 and component.get("license") in scoped_notices:
            if (scoped_notices[component["license"]] not in {a.get("sha256") for a in component.get("notices", [])}
                    or not isinstance(component.get("license_scope"), str) or not component["license_scope"].strip()):
                raise ValueError("Composite terms require exact reviewed notice and library scope")
            notice_only = True
        if coverage.get("schema") == 2 and component.get("license") == "GPL-3.0-or-later WITH GCC-exception-3.1":
            if not isinstance(component.get("exception_basis"), str) or not component["exception_basis"].strip():
                raise ValueError("GCC exception requires runtime/eligible-compilation basis")
            # The exception permits independent applications; it does not waive
            # corresponding source for the standalone runtime libraries we bundle.
        if coverage.get("schema") == 2 and component.get("license") == "GPL-2.0-or-later WITH Bootloader-exception":
            # Upstream COPYING explicitly covers embedding the unmodified loader,
            # not standalone distribution or arbitrary GPL libraries.
            if ([entry["path"] for entry in component.get("payload", [])] != ["@bootloader"]
                    or not isinstance(component.get("exception_basis"), str)
                    or not component["exception_basis"].strip()):
                raise ValueError("Bootloader exception requires embedded-loader scope and provenance basis")
            notice_only = True
        if coverage.get("schema") == 2 and not notice_only and component.get("license") not in {
            "LGPL-2.0-only", "LGPL-2.0-or-later", "LGPL-2.1-only", "LGPL-2.1-or-later",
            "LGPL-3.0-only", "LGPL-3.0-or-later", "GPL-2.0-only", "GPL-2.0-or-later",
            "GPL-3.0-only", "GPL-3.0-or-later", "MPL-2.0", "AFL-2.1",
            "GPL-3.0-or-later WITH GCC-exception-3.1",
        }:
            raise ValueError(f"Unsupported native license: {component.get('license')}")
        fields = ("version", "license", "provenance")
        if not notice_only:
            fields += ("source_basis",)
        for field in fields:
            if not isinstance(component.get(field), str) or not component[field].strip():
                raise ValueError(f"Missing {field} for native component {component['id']}")
        static = component.get("statically_linked")
        if (not isinstance(static, list) or any(name not in ids or name == component["id"] for name in static)):
            raise ValueError(f"Undocumented static dependencies for {component['id']}")
        for kind in ("notices", "sources"):
            if kind == "sources" and notice_only and not component.get(kind):
                continue
            if not component.get(kind):
                raise ValueError(f"Missing component-specific {kind}: {component['id']}")
            for asset in component[kind]:
                target = coverage_asset(base, asset)
                if kind == "sources":
                    if not asset.get("url", "").startswith("https://"):
                        raise ValueError("Exact source provenance URL required")
                    # A LICENSE, directory listing, or HTML download page is not source.
                    if zipfile.is_zipfile(target):
                        with zipfile.ZipFile(target) as sources:
                            members = [(item.filename, item.file_size) for item in sources.infolist() if not item.is_dir()]
                    elif tarfile.is_tarfile(target):
                        with tarfile.open(target) as sources:
                            members = [(item.name, item.size) for item in sources if item.isfile()]
                    else:
                        raise ValueError(f"Corresponding source must be a local source archive: {target.name}")
                    # Reject obvious license-only bundles; this is NOT proof of
                    # completeness or binary/source correspondence (reviewed above).
                    source_suffixes = {".c", ".cc", ".cpp", ".h", ".hpp", ".py", ".rs", ".s", ".asm",
                                       ".m", ".mm", ".go", ".js", ".ts", ".patch", ".diff", ".cmake", ".sh"}
                    if not any(size > 0 and (Path(name).suffix.lower() in source_suffixes
                                            or Path(name).name in {"Makefile", "CMakeLists.txt", "configure", "rules",
                                                                   "UnicodeData.txt"})
                               for name, size in members):
                        raise ValueError(f"No source material found in {target.name}; notices alone do not suffice")
        needs_recipe = not notice_only and not (coverage.get("schema") == 2 and component["license"] == "MPL-2.0")
        if needs_recipe or component.get("build_instructions"):
            coverage_asset(base, component.get("build_instructions", {}))
    return coverage


def native_inventory(executable: Path) -> list[dict]:
    """Hash actual frozen native bytes, not installed packages or filename guesses.

    CArchive binaries AND native-magic data entries are checked. Onedir includes
    all native files and symlinks under _internal (including Mac frameworks).
    The bootloader prefix is separate from its self-referential appended archive.
    """
    from PyInstaller.archive.readers import CArchiveReader
    import os

    magic = (b"\x7fELF", b"MZ", b"\xfe\xed\xfa\xce", b"\xce\xfa\xed\xfe",
             b"\xfe\xed\xfa\xcf", b"\xcf\xfa\xed\xfe", b"\xca\xfe\xba\xbe",
             b"\xbe\xba\xfe\xca", b"\xca\xfe\xba\xbf", b"\xbf\xba\xfe\xca")
    reader = CArchiveReader(str(executable))
    result = {}
    def add(name, data, kind, **metadata):
        name = name.replace("\\", "/")
        if name in result:
            raise ValueError(f"Duplicate frozen native path: {name}")
        result[name] = {"path": name, "sha256": hashlib.sha256(data).hexdigest(),
                        "size": len(data), "kind": kind, **metadata}
    with executable.open("rb") as stream:
        prefix = stream.read(reader._start_offset)
    if not prefix.startswith(magic):
        raise ValueError("Missing native bootloader")
    loader_scope = "prefix-bytes"
    if len(prefix) >= 64 and prefix[:6] == b"\x7fELF\x02\x01":
        # objcopy moves ELF section headers behind the appended CArchive. Their
        # file offset is not code and depends on this inventory's own ZIP size.
        # Normalize ONLY ELF64 e_shoff; every other prefix byte remains bound.
        prefix = prefix[:40] + b"\0" * 8 + prefix[48:]
        loader_scope = "ELF64-prefix-with-e_shoff-zeroed"
    add("@bootloader", prefix, "bootloader", hash_scope=loader_scope)
    for name, entry in sorted(reader.toc.items()):
        if entry[-1] == "d":
            raise ValueError("External CArchive dependencies are not supported")
        data = reader.extract(name)
        if entry[-1] == "n":
            add(name, data, "symlink", target=data.rstrip(b"\0").decode("utf-8").replace("\\", "/"))
        elif entry[-1] == "b" or data.startswith(magic):
            add(name, data, "binary")
    internal = executable.parent / "_internal"
    if internal.is_dir():
        for path in sorted(internal.rglob("*")):
            name = path.relative_to(internal).as_posix()
            if path.is_symlink():
                if not path.resolve().is_relative_to(internal.resolve()):
                    raise ValueError(f"External runtime symlink: {name}")
                target = os.readlink(path)
                add(name, target.encode("utf-8") + b"\0", "symlink", target=target)
            elif path.is_file():
                with path.open("rb") as stream:
                    header = stream.read(4)
                if header.startswith(magic):
                    add(name, path.read_bytes(), "binary")
    return sorted(result.values(), key=lambda entry: entry["path"])


def add_native_compliance(archive_path: Path, payload: list[dict], coverage_file: Path | None):
    """Attach license-specific evidence; unknown native payloads fail closed.

    Source archives are mandatory for the implemented copyleft route, not for
    schema-2 permissive components. This is not an automatic legal approval.
    """
    coverage = json.loads(coverage_file.read_text(encoding="utf-8")) if coverage_file else {"components": []}
    base = coverage_file.parent if coverage_file else archive_path.parent
    validate_native_coverage(payload, coverage, base)
    with zipfile.ZipFile(archive_path, "a", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("native/payload.json", json.dumps(payload, indent=2) + "\n")
        archive.writestr("native/coverage.json", json.dumps(coverage, indent=2) + "\n")
        copied = set()
        for component in coverage["components"]:
            assets = component["notices"] + component.get("sources", [])
            if component.get("build_instructions"):
                assets += [component["build_instructions"]]
            for asset in assets:
                if asset["path"] not in copied:
                    target = coverage_asset(base, asset)
                    archive.write(target, "native/files/" + asset["path"])
                    copied.add(asset["path"])
    # Revalidate embedded bytes, not just source files which could change mid-copy.
    verify_native_compliance(None, archive_path, expected_payload=payload)


def verify_native_compliance(executable: Path | None, archive_path: Path, *, expected_payload=None):
    """Read back evidence and compare every native byte of the final artifact."""
    from pathlib import PurePosixPath

    with zipfile.ZipFile(archive_path) as archive, tempfile.TemporaryDirectory(prefix="oal-native-evidence-") as temp:
        if len(archive.namelist()) != len(set(archive.namelist())):
            raise ValueError("Duplicate compliance archive member")
        try:
            payload = json.loads(archive.read("native/payload.json"))
            coverage = json.loads(archive.read("native/coverage.json"))
        except KeyError as exc:
            raise ValueError("Missing native payload coverage in compliance archive") from exc
        actual = native_inventory(executable) if executable is not None else expected_payload
        if actual is None or payload != actual:
            raise ValueError("Final native payload differs from embedded inventory")
        base = Path(temp)
        for name in archive.namelist():
            if not name.startswith("native/files/"):
                continue
            relative = name.removeprefix("native/files/")
            path = PurePosixPath(relative)
            if not relative or path.is_absolute() or ".." in path.parts or "\\" in relative or ":" in relative:
                raise ValueError("Unsafe native evidence archive path")
            target = base / path
            target.parent.mkdir(parents=True, exist_ok=True)
            with archive.open(name) as reader, target.open("xb") as writer:
                shutil.copyfileobj(reader, writer)
        validate_native_coverage(payload, coverage, base)


def run(*args):
    subprocess.run([str(arg) for arg in args], check=True, cwd=ROOT)


def collect_licenses(destination: Path):
    destination.mkdir(parents=True, exist_ok=True)
    for name in ("LICENSE", "THIRD_PARTY_LICENSES.md"):
        shutil.copy2(REPOSITORY / name, destination / name)
    shutil.copytree(ROOT / "packaging" / "licenses", destination / "runtime", dirs_exist_ok=True)
    shutil.copy2(ROOT / "packaging" / "README.md", destination / "RUNNING.md")
    guide = REPOSITORY / "docs" / "install-gm.md"
    if guide.is_file():
        shutil.copy2(guide, destination / "install-gm.md")
    inventory = []
    for name in ("PySide6", "PySide6-Essentials", "PySide6-Addons", "shiboken6", "cryptography", "cffi", "pyinstaller"):
        dist = importlib.metadata.distribution(name)
        inventory.append({"name": name, "version": dist.version})
        for entry in dist.files or ():
            if any(part.lower().startswith(("license", "copying", "notice")) for part in entry.parts):
                source = Path(dist.locate_file(entry))
                if source.is_file():
                    target = destination / name / Path(str(entry))
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(source, target)
    (destination / "runtime-versions.json").write_text(json.dumps(inventory, indent=2) + "\n", encoding="utf-8")


def source_archive(target: Path):
    """Explicit source allowlist: no work folders, credentials, venvs or build output."""
    sources = [REPOSITORY / "LICENSE", REPOSITORY / "THIRD_PARTY_LICENSES.md"]
    for pattern in ("oal_builder/*.py", "tests/test_*.py", "requirements*.txt", "packaging/*.py", "packaging/*.md", "packaging/provenance/*.json", "packaging/licenses/**/*"):
        sources.extend(ROOT.glob(pattern))
    workflow = REPOSITORY / ".github/workflows/build-desktop-builder.yml"
    if workflow.is_file():
        sources.append(workflow)
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as archive:
        for source in sorted(set(sources)):
            if source.is_file():
                # Fixed metadata makes identical checkouts compare across native CI jobs.
                entry = zipfile.ZipInfo(source.relative_to(REPOSITORY).as_posix())
                entry.create_system = 3
                entry.external_attr = 0o100644 << 16
                # Store source bytes verbatim so zlib versions cannot change this hash.
                archive.writestr(entry, source.read_bytes(), compress_type=zipfile.ZIP_STORED)


def compliance_archive(work: Path) -> Path:
    """Prepare builder notices/source. Native coverage is mandatory after discovery."""
    legal = work / "licenses"
    collect_licenses(legal)
    source = work / "builder-source.zip"
    source_archive(source)
    target = work / "builder-compliance.zip"
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.write(source, source.name)
        for item in sorted(legal.rglob("*")):
            if item.is_file():
                archive.write(item, item.relative_to(work))
    return target


def extract_runtime(archive_path: Path, destination: Path):
    """Accept only an isolated onedir tree; preserve safe framework symlinks."""
    from pathlib import PurePosixPath
    import posixpath

    destination.mkdir(parents=True, exist_ok=False)
    with tarfile.open(archive_path, "r:gz") as archive:
        members = archive.getmembers()
        seen = set()
        for member in members:
            path = PurePosixPath(member.name)
            if (path.is_absolute() or ".." in path.parts or "\\" in member.name
                    or not path.parts or path.parts[0] != NAME or member.name in seen
                    or not (member.isfile() or member.isdir() or member.issym())):
                raise ValueError(f"Unsafe runtime archive member: {member.name}")
            seen.add(member.name)
            if member.issym():
                link = PurePosixPath(member.linkname)
                resolved = PurePosixPath(posixpath.normpath(str(path.parent / link)))
                if (link.is_absolute() or "\\" in member.linkname
                        or not resolved.parts or resolved.parts[0] != NAME):
                    raise ValueError(f"Unsafe runtime archive link: {member.name}")
        try:
            archive.extractall(destination, members=members, filter="data")
        except tarfile.FilterError as exc:
            raise ValueError(f"Unsafe runtime archive: {exc}") from exc


def assemble_mac_app(runtimes: dict[str, Path], stage: Path) -> Path:
    """One visible application, two complete native onedir trees; NOT universal2."""
    import plistlib

    if set(runtimes) != {"arm64", "x86_64"}:
        raise ValueError("Both arm64 and x86_64 runtimes are required")
    app = stage / f"{NAME}.app"
    contents = app / "Contents"
    (contents / "MacOS").mkdir(parents=True)
    for architecture, runtime in runtimes.items():
        if not (runtime / NAME).is_file():
            raise ValueError(f"Missing {architecture} runtime executable")
        shutil.copytree(runtime, contents / "Resources/runtimes" / architecture / NAME, symlinks=True)
    launcher = contents / "MacOS" / NAME
    launcher.write_text('''#!/bin/sh
set -eu
HERE=$(CDPATH= cd -- "$(/usr/bin/dirname -- "$0")" && pwd -P)
case "$(/usr/bin/uname -m)" in
    arm64) exec "$HERE/../Resources/runtimes/arm64/OpenAutoLink-Builder/OpenAutoLink-Builder" "$@" ;;
    x86_64) exec "$HERE/../Resources/runtimes/x86_64/OpenAutoLink-Builder/OpenAutoLink-Builder" "$@" ;;
    *) printf '%s\\n' 'Unsupported Mac architecture' >&2; exit 1 ;;
esac
''', encoding="utf-8")
    launcher.chmod(0o755)
    with (contents / "Info.plist").open("wb") as writer:
        plistlib.dump({"CFBundleExecutable": NAME, "CFBundleName": "OpenAutoLink Builder",
                       "CFBundleDisplayName": "OpenAutoLink Builder", "CFBundlePackageType": "APPL",
                       "CFBundleIdentifier": "org.openautolink.builder", "CFBundleVersion": "1",
                       "CFBundleShortVersionString": "1.0", "NSHighResolutionCapable": True,
                       "LSMinimumSystemVersion": "13.0"}, writer)
    return app


def sha256(path: Path) -> str:
    with path.open("rb") as reader:
        return hashlib.file_digest(reader, "sha256").hexdigest()


def write_manifest(output: Path, stem: str, artifact: Path, files: list[Path], **metadata):
    manifest = {"artifact": artifact.name, "sha256": sha256(artifact),
                "files": {p.name: sha256(p) for p in files},
                "signing": "not developer-signed or notarized", **metadata}
    (output / f"{stem}.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(manifest, indent=2))
    return manifest


def inventory_target(executable: Path) -> Path:
    # The outer Mac launcher is a shell script, not a PyInstaller CArchive.
    # runtime-info has already established the actual selected architecture.
    if platform.system() == "Darwin" and executable.parent.name == "MacOS" and executable.parents[1].name == "Contents":
        return executable.parents[1] / "Resources/runtimes" / platform.machine() / NAME / NAME
    return executable


def smoke_artifact(executable: Path, screenshot: Path, source_sha256: str):
    """Probe the frozen runtime's embedded bytes and real Qt UI, without a build."""
    with tempfile.TemporaryDirectory(prefix="oal-smoke-") as temp:
        work = Path(temp)
        info_path = work / "runtime.json"
        subprocess.run([str(executable), "--runtime-info", str(info_path)], check=True, timeout=120)
        info = json.loads(info_path.read_text(encoding="utf-8"))
        if (info.get("architecture") != platform.machine().lower().replace("amd64", "x86_64")
                or info.get("system") != platform.system() or info.get("frozen") is not True):
            raise ValueError("Wrong frozen runtime architecture or operating system")
        exported = work / "compliance.zip"
        subprocess.run([str(executable), "--export-compliance", str(exported)], check=True, timeout=120)
        verify_native_compliance(inventory_target(executable), exported)
        with zipfile.ZipFile(exported) as archive:
            source = archive.read("builder-source.zip")
            if hashlib.sha256(source).hexdigest() != source_sha256:
                raise ValueError("Frozen builder source does not match the manifest")
            if not archive.read("licenses/LICENSE") or not archive.read("licenses/runtime-versions.json"):
                raise ValueError("Frozen compliance archive is incomplete")
        workspace = work / "must-not-be-created"
        screenshot.unlink(missing_ok=True)
        subprocess.run([str(executable), "--smoke-test", "--workspace", str(workspace),
                        "--screenshot", str(screenshot)], check=True, timeout=120)
        if not screenshot.read_bytes().startswith(b"\x89PNG\r\n\x1a\n"):
            raise ValueError("Frozen UI did not produce a PNG screenshot")
        if workspace.exists():
            raise ValueError("Offline smoke test unexpectedly created a workspace")


def read_manifest(path: Path) -> dict:
    """Validate exact file names and bytes before interpreting downloaded artifacts."""
    import re

    manifest = json.loads(path.read_text(encoding="utf-8"))
    files = manifest.get("files", {})
    if not files or manifest.get("artifact") not in files:
        raise ValueError("Manifest must hash its artifact")
    for name, digest in files.items():
        if (not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", name)
                or not re.fullmatch(r"[0-9a-f]{64}", digest)):
            raise ValueError("Unsafe manifest file name or hash")
        target = path.parent / name
        if target.is_symlink() or not target.is_file() or sha256(target) != digest:
            raise ValueError(f"Artifact hash mismatch: {name}")
    if manifest.get("sha256") != files[manifest["artifact"]]:
        raise ValueError("Inconsistent artifact hash")
    return manifest


def combine_mac(incoming: Path, output: Path):
    if platform.system() != "Darwin":
        raise ValueError("DMG assembly requires macOS")
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="oal-mac-combine-") as temp:
        work = Path(temp)
        source_archive(work / "builder-source.zip")
        source_sha = sha256(work / "builder-source.zip")
        runtimes, stages = {}, {}
        for architecture in ("arm64", "x86_64"):
            stem = f"{NAME}-macos-{architecture}"
            folder = incoming / architecture
            manifest = read_manifest(folder / f"{stem}.json")
            if (manifest.get("architecture") != architecture or manifest.get("system") != "Darwin"
                    or manifest.get("kind") != "internal-runtime-stage"
                    or manifest.get("frozenSmokeTest") != "passed"
                    or manifest.get("builderSourceSha256") != source_sha
                    or manifest["artifact"] != f"{stem}.runtime.tar.gz"):
                raise ValueError(f"Mismatched or untested {architecture} runtime stage")
            extract_runtime(folder / manifest["artifact"], work / architecture)
            runtime = work / architecture / NAME
            run("lipo", "-verify_arch", architecture, runtime / NAME)
            runtimes[architecture], stages[architecture] = runtime, manifest
        stage = work / "distribution"
        assemble_mac_app(runtimes, stage)
        (stage / "Applications").symlink_to("/Applications", target_is_directory=True)
        package = output / f"{NAME}.dmg"
        run("hdiutil", "create", "-volname", "OpenAutoLink Builder", "-srcfolder", stage,
            "-format", "UDZO", "-ov", package)
        run("hdiutil", "verify", package)
        write_manifest(output, f"{NAME}-macos", package, [package], system="Darwin",
                       architecture="dual-runtime-arm64-x86_64", kind="dual-runtime-dmg",
                       builderSourceSha256=source_sha, runtimeStages=stages,
                       frozenSmokeTest="pending-combined-native-gates")


def smoke_mac(package_dir: Path, output: Path, expected_arch: str):
    if platform.system() != "Darwin" or platform.machine() != expected_arch or expected_arch not in ("arm64", "x86_64"):
        raise ValueError("Final Mac smoke requires the expected native Mac runner")
    manifest = read_manifest(package_dir / f"{NAME}-macos.json")
    if manifest.get("kind") != "dual-runtime-dmg" or manifest["artifact"] != f"{NAME}.dmg":
        raise ValueError("Expected the combined Mac DMG")
    output.mkdir(parents=True, exist_ok=True)
    screenshot = output / f"smoke-macos-{expected_arch}.png"
    report = output / f"smoke-macos-{expected_arch}.json"
    report.unlink(missing_ok=True)
    with tempfile.TemporaryDirectory(prefix="oal-final-mac-") as temp:
        mount = Path(temp) / "mounted"
        mount.mkdir()
        run("hdiutil", "verify", package_dir / manifest["artifact"])
        run("hdiutil", "attach", "-readonly", "-nobrowse", "-mountpoint", mount, package_dir / manifest["artifact"])
        try:
            apps = list(mount.glob("*.app"))
            if len(apps) != 1 or apps[0].name != f"{NAME}.app":
                raise ValueError("DMG must expose exactly one application")
            # Crucially launch the outer selector, not a hand-selected inner runtime.
            smoke_artifact(apps[0] / "Contents/MacOS" / NAME, screenshot, manifest["builderSourceSha256"])
        finally:
            run("hdiutil", "detach", mount)
    write_manifest(output, report.stem, screenshot, [screenshot], architecture=expected_arch,
                   packageSha256=manifest["sha256"], builderSourceSha256=manifest["builderSourceSha256"],
                   frozenSmokeTest="passed", scope="combined-dmg-launcher-native-runtime-and-ui")


def qualify_mac(package_dir: Path, evidence: Path):
    manifest_path = package_dir / f"{NAME}-macos.json"
    manifest = read_manifest(manifest_path)
    if manifest.get("kind") != "dual-runtime-dmg" or manifest["artifact"] != f"{NAME}.dmg":
        raise ValueError("Expected combined DMG for qualification")
    reports = {}
    for architecture in ("arm64", "x86_64"):
        report = read_manifest(evidence / architecture / f"smoke-macos-{architecture}.json")
        if (report.get("architecture") != architecture or report.get("frozenSmokeTest") != "passed"
                or report.get("scope") != "combined-dmg-launcher-native-runtime-and-ui"
                or report.get("packageSha256") != manifest["sha256"]
                or report.get("builderSourceSha256") != manifest["builderSourceSha256"]
                or report["artifact"] != f"smoke-macos-{architecture}.png"):
            raise ValueError(f"Missing matching native combined-DMG evidence for {architecture}")
        reports[architecture] = report
    # Only qualify once BOTH reports have been validated against the exact DMG bytes.
    for architecture, report in reports.items():
        screenshot = report["artifact"]
        shutil.copy2(evidence / architecture / screenshot, package_dir / screenshot)
        manifest["files"][screenshot] = sha256(package_dir / screenshot)
    manifest["nativeSmokeTests"] = reports
    manifest["frozenSmokeTest"] = "passed-on-arm64-and-x86_64"
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")


def smoke_linux_xcb(package_dir: Path):
    import os

    if platform.system() != "Linux" or os.environ.get("QT_QPA_PLATFORM") != "xcb" or not os.environ.get("DISPLAY"):
        raise ValueError("Linux native smoke requires QT_QPA_PLATFORM=xcb and a real/Xvfb DISPLAY")
    path = package_dir / f"{NAME}-linux-x86_64.json"
    manifest = read_manifest(path)
    if manifest.get("system") != "Linux" or manifest.get("kind") != "direct-executable" or manifest["artifact"] != NAME:
        raise ValueError("Expected the direct Linux executable")
    screenshot = package_dir / "smoke-linux-xcb.png"
    smoke_artifact(package_dir / NAME, screenshot, manifest["builderSourceSha256"])
    manifest["nativePlatformSmoke"] = "xcb-display-passed"
    manifest["files"][screenshot.name] = sha256(screenshot)
    path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "dist", help="Directory for native packages")
    parser.add_argument("--native-coverage", type=Path, help="License-specific exact-native-byte coverage JSON and notice/source assets (required to offer a package)")
    parser.add_argument("--audit-native", type=Path, help="Read a frozen executable into an UNQUALIFIED inventory; do not build or approve it")
    parser.add_argument("--mac-arch", choices=("native", "x86_64", "arm64"), default="native",
                        help="Native Mac runtime stage (not a standalone Mac download)")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--smoke-linux-xcb", type=Path, metavar="PACKAGE_DIR", help="Verify the direct Linux executable under a real/Xvfb XCB display")
    mode.add_argument("--combine-mac", type=Path, metavar="STAGES", help="Combine arm64/ and x86_64/ stages into one DMG (macOS only)")
    mode.add_argument("--smoke-mac", type=Path, metavar="PACKAGE_DIR", help="Mount and smoke the final combined DMG (macOS only)")
    mode.add_argument("--qualify-mac", type=Path, metavar="PACKAGE_DIR", help="Attach both native smoke reports to the exact DMG manifest")
    parser.add_argument("--expected-arch", choices=("arm64", "x86_64"), help="Required native architecture for --smoke-mac")
    parser.add_argument("--evidence", type=Path, help="arm64/ and x86_64/ evidence directories for --qualify-mac")
    args = parser.parse_args(argv)
    output = args.output.resolve()
    if args.audit_native:
        output.mkdir(parents=True, exist_ok=True)
        executable = args.audit_native.resolve()
        report = {"status": "unqualified", "artifactSha256": sha256(executable),
                  "payload": native_inventory(executable)}
        (output / "native-payload-review.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        return 0
    if args.smoke_linux_xcb:
        smoke_linux_xcb(args.smoke_linux_xcb.resolve())
        return 0
    if args.combine_mac:
        combine_mac(args.combine_mac.resolve(), output)
        return 0
    if args.smoke_mac:
        if not args.expected_arch:
            parser.error("--smoke-mac requires --expected-arch")
        smoke_mac(args.smoke_mac.resolve(), output, args.expected_arch)
        return 0
    if args.qualify_mac:
        if not args.evidence:
            parser.error("--qualify-mac requires --evidence")
        qualify_mac(args.qualify_mac.resolve(), args.evidence.resolve())
        return 0
    output.mkdir(parents=True, exist_ok=True)
    work_root = ROOT / "build"
    if any(output.iterdir()):
        raise ValueError("Use an empty output directory; stale artifacts must not survive a failed qualification")
    work_root.mkdir(exist_ok=True)
    system = platform.system()
    if system not in ("Linux", "Windows", "Darwin"):
        raise SystemExit(f"Unsupported packaging OS: {system}")
    architecture = platform.machine().lower().replace("amd64", "x86_64")
    if system == "Darwin" and args.mac_arch not in ("native", architecture):
        raise SystemExit("Build and smoke each Mac runtime on its own native architecture")
    if architecture not in (("arm64", "x86_64") if system == "Darwin" else ("x86_64",)):
        raise SystemExit(f"Unsupported packaging architecture: {architecture}")
    with tempfile.TemporaryDirectory(prefix="package-", dir=work_root) as temp:
        work = Path(temp)
        compliance = compliance_archive(work)
        source_sha = sha256(work / "builder-source.zip")
        # Freeze the same bytes embedded in the executable, even if a local editor
        # changes the checkout while PyInstaller is running. This ZIP was just
        # generated by source_archive's explicit relative-path allowlist.
        with zipfile.ZipFile(work / "builder-source.zip") as snapshot:
            snapshot.extractall(work / "source-snapshot")
        frozen_root = work / "source-snapshot" / ROOT.name
        stage = work / "distribution"
        stage.mkdir()
        command = [sys.executable, "-m", "PyInstaller", "--noconfirm", "--clean", "--noupx",
                   "--name", NAME, "--distpath", str(stage), "--workpath", str(work / "pyinstaller"),
                   "--specpath", str(work), "--paths", str(frozen_root), "--hidden-import", "oal_builder.engine",
                   "--add-data", f"{compliance}:.", "--runtime-hook", str(frozen_root / "packaging/runtime_hook.py"),
                   "--additional-hooks-dir", str(frozen_root / "packaging"),
                   "--exclude-module", "tkinter"]
        if system == "Darwin":
            # Console onedir avoids nested visible .apps; the outer .app launches it without a terminal.
            command += ["--onedir", "--target-arch", architecture]
        else:
            command += ["--onefile"]
            if system == "Windows":
                command += ["--windowed"]
        command.append(str(frozen_root / "oal_builder" / "__main__.py"))
        run(*command)
        filename = f"{NAME}.exe" if system == "Windows" else NAME
        candidate = stage / NAME / NAME if system == "Darwin" else stage / filename
        payload = native_inventory(candidate)
        report_path = output / "native-payload-review.json"
        report_path.write_text(json.dumps({"status": "unqualified", "artifactSha256": sha256(candidate),
                                          "builderSourceSha256": source_sha, "payload": payload}, indent=2) + "\n", encoding="utf-8")
        add_native_compliance(compliance, payload, args.native_coverage.resolve() if args.native_coverage else None)
        # Freeze again from the SAME source snapshot with the now-complete evidence.
        # The final native payload must remain byte-identical; drift fails closed.
        run(*command)
        verify_native_compliance(candidate, compliance)
        report_path.unlink()
        os_name = "macos" if system == "Darwin" else system.lower()
        stem = f"{NAME}-{os_name}-{architecture}"
        screenshot = output / f"smoke-{os_name}-{architecture}.png"
        if system == "Darwin":
            executable = stage / NAME / NAME
            run("lipo", "-verify_arch", architecture, executable)
            smoke_artifact(executable, screenshot, source_sha)
            package = output / f"{stem}.runtime.tar.gz"
            with tarfile.open(package, "w:gz") as archive:
                archive.add(stage / NAME, arcname=NAME)
        else:
            filename = f"{NAME}.exe" if system == "Windows" else NAME
            package = output / filename
            shutil.copy2(stage / filename, package)
            if system == "Linux":
                package.chmod(package.stat().st_mode | 0o111)
            smoke_artifact(package, screenshot, source_sha)
        write_manifest(output, stem, package, [package, screenshot], system=system,
                       architecture=architecture, python=platform.python_version(),
                       builderSourceSha256=source_sha, frozenSmokeTest="passed",
                       nativeCoverage="exact-payload-evidence-verified",
                       kind="internal-runtime-stage" if system == "Darwin" else "direct-executable")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
