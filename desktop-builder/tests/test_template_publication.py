"""Publication producer tests; real local bundle/tools where available."""
import importlib.util
import json
import hashlib
import os
from pathlib import Path
import subprocess
import shutil
import sys
import zipfile

import pytest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'desktop-builder/scripts/publish_template.py'
TAG = 'v0.1.526'
COMMIT = '580a68d772afc97e444019504178f1678731feb6'


def producer():
    spec = importlib.util.spec_from_file_location('publish_template', SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@pytest.fixture(scope='module')
def real_template(tmp_path_factory):
    from oal_builder.personalize import personalize_bundle
    source = Path('/hermes_work/oal-builder-clean-qualification/output/openautolink-1-580a68d772af.aab')
    tools_root = Path(os.environ.get('OAL_PERSONALIZE_TOOLS', '/hermes_work/oal-builder-clean-qualification/tools'))
    tools = dict(java=tools_root / 'jdk-17/bin/java', javac=tools_root / 'jdk-17/bin/javac', bundletool=tools_root / 'bundletool-1.18.3.jar')
    if not source.is_file() or not all(p.is_file() for p in tools.values()):
        pytest.skip('Real local AAB/JDK/bundletool required for publication integration')
    root = tmp_path_factory.mktemp('publication')
    output = root / ('openautolink-template-' + TAG + '.aab')
    def run(argv, phase, cwd=None):
        subprocess.run(argv, cwd=cwd, check=True, timeout=120, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    personalize_bundle(source, output, original_app_id='com.openautolink.personal.qualification',
                       app_id='com.openautolink.template', version_code=526,
                       workdir=root / 'transform', run=run, **tools)
    return output, tools


def test_producer_binds_real_bundle_identity_version_and_digest(real_template, tmp_path):
    p = producer()
    aab, tools = real_template
    result = p.produce_descriptor(aab, tag=TAG, commit=COMMIT, version_code=526,
                                  output_dir=tmp_path, **tools)
    value = json.loads(result.read_bytes())
    assert value == {'schemaVersion': 1, 'formatVersion': 1, 'releaseTag': TAG,
                     'sourceCommit': COMMIT, 'templateApplicationId': 'com.openautolink.template',
                     'transformContract': 'oal-identity-v1',
                     'templateAsset': {'name': aab.name, 'size': aab.stat().st_size,
                                       'sha256': hashlib.sha256(aab.read_bytes()).hexdigest()}}
    assert (tmp_path / (aab.name + '.sha256')).read_text() == value['templateAsset']['sha256'] + '  ' + aab.name + '\n'


@pytest.mark.parametrize('member', ['base/assets/offline-mfi/identity.pk8',
    'base/assets/certificate.p7b', 'base/assets/upload-key.jks', 'META-INF/UPLOAD.RSA',
    'META-INF/MANIFEST.MF', '../escape', 'base/resources.pb'])
def test_publication_rejects_credentials_signatures_and_duplicate_members_before_tools(tmp_path, monkeypatch, member):
    import warnings
    p = producer()
    aab = tmp_path / ('openautolink-template-' + TAG + '.aab')
    with warnings.catch_warnings():
        warnings.simplefilter('ignore', UserWarning)
        with zipfile.ZipFile(aab, 'w') as archive:
            archive.writestr('BundleConfig.pb', b'')
            archive.writestr('base/manifest/AndroidManifest.xml', b'fake')
            archive.writestr('base/resources.pb', b'fake')
            archive.writestr('base/dex/classes.dex', b'fake')
            archive.writestr(member, b'not a real credential')
    monkeypatch.setattr(p, '_run', lambda *a, **k: pytest.fail('unsafe archive reached tools'))
    with pytest.raises(p.ReleaseError):
        p.produce_descriptor(aab, tag=TAG, commit=COMMIT, version_code=526,
                             output_dir=tmp_path / 'out', java='java', javac='javac', bundletool='bundletool')
    assert not (tmp_path / 'out').exists()


def test_real_compiled_attribute_id_bypass_rejected_by_both_boundaries(real_template, tmp_path):
    """bundletool accepts this real AAB, but its taskAffinity actually means process."""
    from test_personalize import MANIFEST, mutate_manifest_attribute
    from oal_builder.personalize import personalize_bundle, BuilderError
    p = producer()
    original, tools = real_template
    aab = tmp_path / original.name
    with zipfile.ZipFile(original) as source, zipfile.ZipFile(aab, 'w') as target:
        for info in source.infolist():
            data = source.read(info)
            if info.filename == MANIFEST:
                changed = mutate_manifest_attribute(data, 'taskAffinity', resource_id=0x01010011)
                assert changed != data
                data = changed
            target.writestr(info, data)
    p._run([tools['java'], '-jar', tools['bundletool'], 'validate', '--bundle=' + str(aab)])
    before = hashlib.sha256(aab.read_bytes()).hexdigest()
    with pytest.raises(p.ReleaseError, match='transform contract'):
        p.produce_descriptor(aab, tag=TAG, commit=COMMIT, version_code=526,
                             output_dir=tmp_path / 'published', **tools)
    assert not (tmp_path / 'published').exists()
    with pytest.raises(BuilderError, match='OAL_PERSONALIZE_HELPER_FAILED'):
        personalize_bundle(aab, tmp_path / 'personal.aab', original_app_id='com.openautolink.template',
                           app_id='com.example.personal', version_code=42, **tools,
                           workdir=tmp_path / 'work', run=p._run)
    assert not (tmp_path / 'personal.aab').exists()
    assert hashlib.sha256(aab.read_bytes()).hexdigest() == before


def test_release_version_mismatch_does_not_publish(real_template, tmp_path):
    p = producer()
    aab, tools = real_template
    with pytest.raises(p.ReleaseError, match='package/version'):
        p.produce_descriptor(aab, tag=TAG, commit=COMMIT, version_code=527,
                             output_dir=tmp_path / 'out', **tools)
    assert not (tmp_path / 'out').exists()


@pytest.mark.parametrize('clean_python', [False, True])
def test_producer_cli_uses_relative_paths_without_changing_source(real_template, tmp_path, clean_python):
    aab, tools = real_template
    shutil.copyfile(aab, tmp_path / aab.name)
    command = [sys.executable, *(['-I', '-S'] if clean_python else []), str(SCRIPT), '--aab', aab.name, '--tag', TAG, '--commit', COMMIT,
               '--version-code', '526', '--output-dir', 'published']
    for key, value in tools.items():
        command += ['--' + key, str(value)]
    result = subprocess.run(command, cwd=tmp_path, capture_output=True, text=True, timeout=120)
    assert result.returncode == 0, result.stderr
    assert result.stdout.strip() == 'openautolink-personalization.json'
    digest = hashlib.sha256(aab.read_bytes()).hexdigest()
    descriptor = json.loads((tmp_path / 'published/openautolink-personalization.json').read_text())
    assert descriptor['templateApplicationId'] == 'com.openautolink.template'
    assert descriptor['templateAsset'] == {'name': aab.name, 'size': aab.stat().st_size, 'sha256': digest}
    assert (tmp_path / 'published' / (aab.name + '.sha256')).read_text() == digest + '  ' + aab.name + '\n'
    assert (tmp_path / aab.name).read_bytes() == aab.read_bytes()


def test_transform_failure_is_actionable_and_sanitized(real_template, tmp_path, monkeypatch):
    p = producer()
    aab, tools = real_template
    def fail(*args, **kwargs):
        raise p.BuilderError('private path or secret')
    monkeypatch.setattr(p, 'personalize_bundle', fail)
    with pytest.raises(p.ReleaseError, match='transform contract') as error:
        p.produce_descriptor(aab, tag=TAG, commit=COMMIT, version_code=526,
                             output_dir=tmp_path / 'out', **tools)
    assert 'private path' not in str(error.value)
    assert not (tmp_path / 'out').exists()


def test_template_workflow_is_unsigned_same_checkout_and_preserves_apk_outputs():
    text = (ROOT / '.github/workflows/publish-personalization-template.yml').read_text()
    apk = (ROOT / '.github/workflows/release-apk.yml').read_text()
    assert ':app:bundleRelease' in text
    assert '-PappId=com.openautolink.template' in text
    assert '-PoalBuilderUnsigned=true' in text
    assert ':app:bundleRelease' not in apk and 'personalization' not in apk
    assert 'rm -f secrets/upload-key.jks' not in apk
    assert 'secrets.' not in text and 'assembleRelease' not in text
    assert 'ref: refs/tags/${{ steps.tag.outputs.value }}' in text
    assert text.index('Preflight clean tag and required publisher tree') < text.index(':app:bundleRelease')
    assert 'python -I -S .publication-tooling/desktop-builder/scripts/publish_template.py' in text
    assert 'git rev-parse HEAD' in text
    assert 'git rev-parse "refs/tags/${RELEASE_TAG}^{commit}"' in text
    assert 'sha256sum --check' in text
    assert 'personalization-release/openautolink-template-${{ steps.tag.outputs.value }}.aab' in text
    assert 'personalization-release/openautolink-personalization.json' in text
    assert 'personalization-release/*' not in text
