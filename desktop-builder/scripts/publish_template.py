#!/usr/bin/env python3
"""Publish only an intentional unsigned, credential-free personalization AAB.

The caller must bind --commit to git rev-parse HEAD and the release tag. This
producer validates the actual AAB with bundletool and exercises the production
transformer (discarding its unsigned probe). It never reads a signing key.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from oal_builder.personalize import personalize_bundle, _validate_zip, _signature
from oal_builder.toolchain import BuilderError
from oal_builder.releases import (DESCRIPTOR_NAME, MAX_TEMPLATE_SIZE, ReleaseError,
                                  TEMPLATE_APPLICATION_ID, TRANSFORM_CONTRACT,
                                  template_name, validate_commit, validate_tag)

ANDROID = '{http://schemas.android.com/apk/res/android}'


def _run(argv, phase='publication', cwd=None):
    try:
        result = subprocess.run([str(a) for a in argv], cwd=cwd, timeout=180,
                                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, check=True)
    except (OSError, subprocess.SubprocessError):
        raise ReleaseError('Template validation tool failed; inspect the release build locally before publishing') from None
    if len(result.stdout) > 4 * 1024**2:
        raise ReleaseError('Template validation output exceeded its limit; contact the builder maintainer')
    return result.stdout


def produce_descriptor(aab, *, tag, commit, version_code, output_dir, java, javac, bundletool):
    """Return the descriptor Path after validation; no writes on validation failure."""
    validate_tag(tag)
    validate_commit(commit)
    aab, output_dir = Path(aab), Path(output_dir)
    if (aab.is_symlink() or not aab.is_file() or aab.name != template_name(tag)
            or not 0 < aab.stat().st_size <= MAX_TEMPLATE_SIZE):
        raise ReleaseError('Expected the exact bounded template AAB filename for this release; rebuild the template')
    if type(version_code) is not int or not 1 <= version_code <= 2100000000:
        raise ReleaseError('Invalid release version code; fix the release version derivation')
    try:
        with zipfile.ZipFile(aab) as archive:
            infos = _validate_zip(archive)
            if any(_signature(info.filename) for info in infos):
                raise ReleaseError('Template must be unsigned; rebuild with -PoalBuilderUnsigned=true')
    except (zipfile.BadZipFile, BuilderError):
        raise ReleaseError('Template must be an unsigned credential-free base-only AAB with safe unique members; rebuild before publishing') from None
    with aab.open('rb') as stream:
        before = hashlib.file_digest(stream, 'sha256').hexdigest()
    _run([java, '-Xmx512m', '-jar', bundletool, 'validate', '--bundle=' + str(aab)])
    xml = _run([java, '-Xmx512m', '-jar', bundletool, 'dump', 'manifest', '--bundle=' + str(aab), '--module=base'])
    try:
        root = ET.fromstring(xml)
    except ET.ParseError:
        raise ReleaseError('Template manifest could not be read; rebuild the template') from None
    if (root.tag != 'manifest' or root.get('package') != TEMPLATE_APPLICATION_ID
            or root.get(ANDROID + 'versionName') != tag[1:]
            or root.get(ANDROID + 'versionCode') != str(version_code)):
        raise ReleaseError('Template package/version disagrees with the release; rebuild from the same checkout and version')
    with tempfile.TemporaryDirectory(prefix='oal-publication-', dir=aab.parent) as temporary:
        probe = Path(temporary)
        # Real helper checks resource/manifest identity agreement and the exact
        # six reviewed edits, preserves payload, and rejects unknown identity use.
        try:
            personalize_bundle(aab, probe / 'probe.aab', original_app_id=TEMPLATE_APPLICATION_ID,
                               app_id='com.openautolink.publication.probe', version_code=version_code,
                               java=java, javac=javac, bundletool=bundletool, workdir=probe / 'work', run=_run)
        except BuilderError:
            raise ReleaseError('Template violates the supported transform contract; review the new release with the builder maintainer before publishing') from None
    with aab.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    if digest != before:
        raise ReleaseError('Template changed during verification; rebuild and retry publication')
    descriptor = {'schemaVersion': 1, 'formatVersion': 1, 'releaseTag': tag,
                  'sourceCommit': commit, 'templateApplicationId': TEMPLATE_APPLICATION_ID,
                  'transformContract': TRANSFORM_CONTRACT,
                  'templateAsset': {'name': aab.name, 'size': aab.stat().st_size, 'sha256': digest}}
    output_dir.mkdir(parents=True, exist_ok=True)
    result = output_dir / DESCRIPTOR_NAME
    result.write_text(json.dumps(descriptor, indent=2) + '\n', encoding='utf-8')
    (output_dir / (aab.name + '.sha256')).write_text(digest + '  ' + aab.name + '\n', encoding='ascii')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('aab', 'tag', 'commit', 'output-dir', 'java', 'javac', 'bundletool'):
        parser.add_argument('--' + name, required=True)
    parser.add_argument('--version-code', type=int, required=True)
    args = vars(parser.parse_args())
    try:
        result = produce_descriptor(**args)
    except (ReleaseError, OSError) as exc:
        print(str(exc) if isinstance(exc, ReleaseError) else 'Publication filesystem error; check workspace permissions', file=sys.stderr)
        return 1
    print(result.name)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
