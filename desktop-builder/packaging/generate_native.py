"""Materialize a reviewed, exact-byte schema-2 manifest; never infer approval.

The review is an explicit path/hash/component map. A new OS, dependency, version,
or byte needs a new review. The cache supplies only hash-pinned source assets.
"""
from __future__ import annotations

import argparse
import importlib.util
import json
from pathlib import Path
import shutil
import tempfile

_spec = importlib.util.spec_from_file_location("native_build_gate", Path(__file__).with_name("build.py"))
build = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(build)


def generate(payload, review, base, output, *, cache=None):
    if review.get("schema") != 2:
        raise ValueError("Reviewed schema 2 required")
    actual = {(e["path"], e["sha256"]) for e in payload}
    approved = [(e["path"], e["sha256"]) for c in review["components"] for e in c.get("payload", [])]
    if len(actual) != len(payload) or len(approved) != len(set(approved)) or set(approved) != actual:
        raise ValueError("Unreviewed, duplicate, missing or changed native payload")
    if output.exists():
        raise ValueError("Use a new evidence output directory")
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="native-stage-", dir=output.parent) as temp:
        stage = Path(temp)
        copied = set()
        for component in review["components"]:
            assets = component["notices"] + component.get("sources", [])
            if component.get("build_instructions"):
                assets += [component["build_instructions"]]
            for asset in assets:
                if asset["path"] in copied:
                    continue
                source_base, source_asset = base, asset
                if cache is not None and asset["path"].startswith("sources/"):
                    # The destination still passes the full safe-path check below.
                    source_base = cache
                    source_asset = {**asset, "path": asset["path"].removeprefix("sources/")}
                source = build.coverage_asset(source_base, source_asset)
                target = stage / asset["path"]
                if not target.resolve().is_relative_to(stage.resolve()):
                    raise ValueError("Unsafe evidence destination")
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(source, target)
                build.coverage_asset(stage, asset)
                copied.add(asset["path"])
        build.validate_native_coverage(payload, review, stage)
        (stage / "coverage.json").write_text(json.dumps(review, indent=2) + "\n", encoding="utf-8")
        stage.rename(output)
    return output / "coverage.json"


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inventory", type=Path, required=True)
    parser.add_argument("--review", type=Path, default=Path(__file__).parent / "provenance/linux-reviewed.json")
    parser.add_argument("--base", type=Path, default=Path(__file__).parent)
    parser.add_argument("--cache", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    result = generate(json.loads(args.inventory.read_text())["payload"],
                      json.loads(args.review.read_text()), args.base, args.output, cache=args.cache)
    print(result)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
