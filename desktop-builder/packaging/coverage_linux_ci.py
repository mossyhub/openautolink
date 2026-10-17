"""Materialize the reviewed Ubuntu 22.04 x86_64 CI payload, fail closed on drift.

This module does not approve new discovery or infer coverage from filenames.
"""
from __future__ import annotations

import argparse
import importlib.util
import json
from pathlib import Path

BASE = Path(__file__).resolve().parent


def check_payload(inventory, review):
    """Accept a diagnostic discovery or build inventory; compare exact records."""
    if 'inventory' in inventory:
        if (inventory.get('system') != 'Linux' or inventory.get('architecture') != 'x86_64'
                or inventory.get('collectionErrors')):
            raise ValueError('Unsupported or incomplete discovery payload')
        inventory = inventory['inventory']
    if (review.get('schema') != 2 or review.get('system') != 'Linux'
            or review.get('architecture') != 'x86_64'):
        raise ValueError('Unsupported reviewed payload platform/schema')
    payload = inventory['payload']
    approved = [entry for c in review['components'] for entry in c.get('payload', [])]
    by_path = {entry['path']: entry for entry in payload}
    expected = {entry['path']: entry for entry in approved}
    if (not payload or len(by_path) != len(payload) or len(expected) != len(approved)
            or by_path != expected):
        raise ValueError('Unreviewed, duplicate, missing or changed native payload')
    return payload


def _load(name):
    spec = importlib.util.spec_from_file_location('linux_ci_' + name, BASE / (name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def materialize(inventory, review, base, cache, output, lock, *, offline=False):
    """Verify the exact payload first, acquire pinned sources, run the generic gate.

    Neither evidence acquisition nor an installed-byte match grants approval.
    An unsupported policy, missing static edge, changed asset or changed payload
    is still rejected by generate_native/build without an emitted manifest.
    """
    payload = check_payload(inventory, review)
    if output.exists():
        raise ValueError('Use a new evidence output directory')
    _load('prepare_native').prepare(lock, cache, payload, offline=offline)
    return _load('generate_native').generate(payload, review, base, output, cache=cache)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--inventory', type=Path,
                        help='Build inventory or native discovery; default is the committed exact payload')
    parser.add_argument('--review', type=Path, default=BASE / 'provenance/linux-ci-reviewed.json')
    parser.add_argument('--base', type=Path, default=BASE)
    parser.add_argument('--lock', type=Path, default=BASE / 'provenance/linux-ci/ubuntu-sources.lock.json')
    parser.add_argument('--cache', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--offline', action='store_true')
    args = parser.parse_args(argv)
    review = json.loads(args.review.read_text(encoding='utf-8'))
    inventory = (json.loads(args.inventory.read_text(encoding='utf-8')) if args.inventory else
                 {'payload': [e for c in review['components'] for e in c.get('payload', [])]})
    result = materialize(inventory, review, args.base, args.cache, args.output,
                         json.loads(args.lock.read_text(encoding='utf-8')), offline=args.offline)
    print(result)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
