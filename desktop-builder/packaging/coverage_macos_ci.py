"""Exact macOS CI coverage. Never strip signatures or normalize native code.

The payload hashes cover the observed thinned, loader-rewritten, ad-hoc-signed
bytes, NOT the universal2 wheel members. A changed signature is a changed file.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path, PurePosixPath
import re
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parent


def _load(name):
    spec = importlib.util.spec_from_file_location("macos_" + name, ROOT / (name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def validate_payload(payload, review):
    """Require a bijection of paths and all native identity metadata."""
    if (review.get("schema") != 2 or review.get("system") != "Darwin"
            or review.get("architecture") not in {"arm64", "x86_64"}):
        raise ValueError("Mac coverage schema/platform mismatch")
    expected = [entry for component in review["components"] for entry in component.get("payload", [])]
    def indexed(entries):
        result = {}
        for entry in entries:
            name = entry.get("path", "")
            path = PurePosixPath(name)
            if (not name or name in result or path.is_absolute() or ".." in path.parts
                    or "\\" in name or "\x00" in name or path.as_posix() != name
                    or not re.fullmatch(r"[0-9a-f]{64}", entry.get("sha256", ""))
                    or type(entry.get("size")) is not int or entry["size"] <= 0
                    or entry.get("kind") not in {"binary", "symlink", "bootloader"}):
                raise ValueError("Invalid or duplicate native payload")
            if entry["kind"] == "symlink":
                target = entry.get("target", "")
                if (not isinstance(target, str) or not target or "\x00" in target
                        or "\\" in target or PurePosixPath(target).is_absolute()
                        or hashlib.sha256(target.encode() + b"\0").hexdigest() != entry["sha256"]
                        or len(target.encode()) + 1 != entry["size"]):
                    raise ValueError("Invalid symlink payload")
                depth = len(path.parent.parts)
                for part in PurePosixPath(target).parts:
                    depth += -1 if part == ".." else (0 if part == "." else 1)
                    if depth < 0:
                        raise ValueError("Escaping symlink payload")
            result[name] = entry
        return result
    if not payload or indexed(payload) != indexed(expected):
        raise ValueError("Unreviewed, missing or changed native payload")
    return payload


XZ_LICENSE = "LicenseRef-XZ-5.2.3-Public-Domain"
XZ_NOTICE_SHA256 = "fea1a2d89f381c4b392c7cef6ace8318649863b309ddea9cfd81d0a504ca83fb"
XZ_SCOPE = "Only embedded liblzma in _lzma; not xz tools, getopt_long, scripts, build tools or documentation."


def validate_xz_component(component, base, asset_gate):
    """Additive, library-only policy; do not turn XZ tools into public domain."""
    if (component.get("id") != "cpython-static-xz" or component.get("version") != "5.2.3"
            or component.get("license") != XZ_LICENSE or component.get("license_scope") != XZ_SCOPE
            or component.get("payload") != [] or component.get("statically_linked") != []
            or component.get("sources") or not isinstance(component.get("provenance"), str)
            or not component["provenance"].strip()
            or XZ_NOTICE_SHA256 not in {a.get("sha256") for a in component.get("notices", [])}):
        raise ValueError("XZ requires exact liblzma-only scope and supplier notice")
    for asset in component["notices"]:
        asset_gate(base, asset)


def _with_xz_policy(payload, review, base, generic_gate, asset_gate):
    # Validate the extension independently, then run the unchanged generic gate
    # over all other components. The output retains the original complete model;
    # there is no license relabelling or automatic final-build policy override.
    extension = [c for c in review["components"] if c.get("license") == XZ_LICENSE]
    if not extension:
        return generic_gate(payload, review, base)
    ids = [c.get("id") for c in review["components"]]
    if len(ids) != len(set(ids)):
        raise ValueError("Duplicate component IDs")
    for component in review["components"]:
        static = component.get("statically_linked")
        if (not isinstance(static, list) or any(s not in ids or s == component["id"] for s in static)):
            raise ValueError("Undocumented static dependencies")
    for component in extension:
        validate_xz_component(component, base, asset_gate)
    extension_ids = {c["id"] for c in extension}
    ordinary = [{**c, "statically_linked": [s for s in c.get("statically_linked", []) if s not in extension_ids]}
                for c in review["components"] if c not in extension]
    generic_gate(payload, {**review, "components": ordinary}, base)
    return review


def materialize(document, review, base, output, *, cache):
    """Cross-materialize assets, not a cross-build or native execution check.

    Use an isolated build-module instance with the declared target platform;
    never mutate the process-wide platform module or weaken final build gates.
    """
    if "inventory" in document:
        inventory = document["inventory"]
        if (document.get("schema") != 1 or document.get("status") != "UNQUALIFIED-DISCOVERY-ONLY"
                or document.get("system") != "Darwin" or document.get("architecture") != review["architecture"]
                or document.get("identity") != review.get("discovery_identity")
                or document.get("collectionErrors") != []
                or inventory.get("artifactSha256") != review.get("discovery_artifact_sha256")
                or inventory.get("builderSourceSha256") != review.get("discovery_builder_source_sha256")):
            raise ValueError("Unreviewed or misattributed discovery identity")
    payload = document.get("inventory", document)["payload"]
    validate_payload(payload, review)
    generator = _load("generate_native")
    generator.build.platform = SimpleNamespace(system=lambda: "Darwin", machine=lambda: review["architecture"])
    generic_gate = generator.build.validate_native_coverage
    generator.build.validate_native_coverage = lambda p, r, b: _with_xz_policy(
        p, r, b, generic_gate, generator.build.coverage_asset)
    return generator.generate(payload, review, Path(base), Path(output), cache=Path(cache))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inventory", type=Path, required=True, help="Discovery JSON or raw native inventory")
    parser.add_argument("--architecture", choices=("arm64", "x86_64"), required=True)
    parser.add_argument("--review", type=Path)
    parser.add_argument("--base", type=Path, default=ROOT)
    parser.add_argument("--cache", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--fetch", action="store_true", help="Acquire and verify pinned supplier inputs")
    parser.add_argument("--offline", action="store_true", help="With --fetch, verify cache without network")
    args = parser.parse_args(argv)
    review_path = args.review or ROOT / "provenance" / f"macos-{args.architecture}-ci-reviewed.json"
    review = json.loads(review_path.read_text(encoding="utf-8"))
    if review.get("architecture") != args.architecture:
        raise ValueError("Requested architecture does not match review")
    raw = args.inventory.read_bytes()
    document = json.loads(raw)
    if "inventory" in document and hashlib.sha256(raw).hexdigest() != review.get("discovery_sha256"):
        raise ValueError("Changed discovery document")
    # Reject changed payload BEFORE fetching or producing any manifest.
    validate_payload(document.get("inventory", document)["payload"], review)
    if args.fetch:
        lock = json.loads((ROOT / "provenance/macos-ci/supplier-inputs.lock.json").read_text(encoding="utf-8"))
        _load("prepare_native").prepare(lock, args.cache, [], offline=args.offline)
    result = materialize(document, review, args.base, args.output, cache=args.cache)
    print(json.dumps({"status": "materialized-not-native-qualified", "architecture": args.architecture,
                      "coverage": str(result), "payloadPaths": sum(len(c["payload"]) for c in review["components"]),
                      "parentIntegration": "Scoped XZ notice policy and exact final native freeze/recombination required"}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
