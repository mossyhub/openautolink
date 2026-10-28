"""Synthetic fixtures; optional real-tool qualification never uses real credentials."""
import hashlib
import importlib
import os
from pathlib import Path
import shutil
import subprocess
import zipfile

import pytest

MANIFEST = 'base/manifest/AndroidManifest.xml'
RESOURCES = 'base/resources.pb'
OLD = 'com.openautolink.template'
NEW = 'com.example.personal'
ANDROID = 'http://schemas.android.com/apk/res/android'
UNKNOWN = b'\xa0\x06\x07'  # unknown varint field 100, deliberately retained


def varint(n):
    out = bytearray()
    while n > 127:
        out.append((n & 127) | 128)
        n >>= 7
    return bytes(out) + bytes([n])


def field(n, value):
    if isinstance(value, str):
        value = value.encode()
    if isinstance(value, int):
        return varint(n << 3) + varint(value)
    return varint((n << 3) | 2) + varint(len(value)) + value


# Android SDK public attr IDs, not inferred from textual attribute names.
ATTRIBUTE_IDS = {
    'name': 0x01010003, 'versionCode': 0x0101021b,
    'protectionLevel': 0x01010009, 'taskAffinity': 0x01010012,
    'authorities': 0x01010018, 'value': 0x01010024,
    'sharedUserId': 0x0101000b,
}


def attr(name, value, namespace=ANDROID, compiled=None, resource_id=None):
    if resource_id is None:
        resource_id = ATTRIBUTE_IDS.get(name, 0) if namespace == ANDROID else 0
    return ((field(1, namespace) if namespace else b'') + field(2, name) + field(3, value)
            + (field(5, resource_id) if resource_id else b'')
            + (field(6, compiled) if compiled else b'') + UNKNOWN)


def proto_fields(data):
    """Small fixture-only wire reader; preserve each field and its order."""
    def take_varint(offset):
        value, shift = 0, 0
        while True:
            byte = data[offset]
            offset += 1
            value |= (byte & 127) << shift
            if not byte & 128:
                return value, offset
            shift += 7
    offset = 0
    while offset < len(data):
        key, offset = take_varint(offset)
        if key & 7 == 0:
            value, offset = take_varint(offset)
        else:
            assert key & 7 == 2
            length, offset = take_varint(offset)
            value = data[offset:offset + length]
            offset += length
        yield key >> 3, value


def mutate_manifest_attribute(data, name, *, resource_id=None, namespace=None, alias=None):
    """Mutate every matching attribute while rebuilding all protobuf lengths."""
    def attribute(data):
        fields = list(proto_fields(data))
        if dict(fields).get(2) != name.encode():
            return data
        replacements = {}
        if resource_id is not None:
            replacements[5] = resource_id
        if namespace is not None:
            replacements[1] = namespace
        if alias is not None:
            replacements[2] = alias
        return b''.join(field(k, replacements.pop(k, v)) for k, v in fields) + b''.join(
            field(k, v) for k, v in replacements.items())
    def element(data):
        return b''.join(field(k, attribute(v) if k == 4 else xml_node(v) if k == 5 else v)
                        for k, v in proto_fields(data))
    def xml_node(data):
        return b''.join(field(k, element(v) if k == 1 else v) for k, v in proto_fields(data))
    return xml_node(data)


def node(name, attrs=(), children=()):
    return field(1, field(3, name) + b''.join(field(4, a) for a in attrs) + b''.join(field(5, c) for c in children) + UNKNOWN) + UNKNOWN


def manifest(old=OLD, version=1, extra=(), app_attrs=()):
    return node('manifest', [attr('package', old, ''), attr('versionCode', str(version), compiled=field(7, field(6, version)))], [
        node('permission', [attr('name', old + '.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'), attr('protectionLevel', '0x00000002', compiled=field(7, field(7, 2)))]),
        node('uses-permission', [attr('name', old + '.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION')]),
        node('application', [attr('name', 'com.openautolink.app.OalApplication'), *app_attrs], [
            node('activity', [attr('name', 'androidx.car.app.activity.CarAppActivity'), attr('taskAffinity', old + '.templates')]),
            node('provider', [attr('name', 'androidx.startup.InitializationProvider'), attr('authorities', old + '.androidx-startup')]),
            node('receiver', [attr('name', 'com.openautolink.app.diagnostics.SettingsReceiver')], [node('intent-filter', children=[node('action', [attr('name', 'com.openautolink.app.SET_PREF')])])]),
            *extra,
        ]),
    ])


def resources(old=OLD):
    return field(2, field(1, field(1, 127)) + field(2, old) + UNKNOWN) + UNKNOWN


def entries():
    return {MANIFEST: manifest(), RESOURCES: resources(), 'BundleConfig.pb': b'',
            'base/dex/classes.dex': b'dex\nsynthetic-com/openautolink/app',
            'base/lib/arm64-v8a/libtest.so': b'ELF-synthetic-Java_com_openautolink_app',
            'META-INF/UPLOAD.SF': b'old signature', 'META-INF/UPLOAD.RSA': b'old signature',
            'META-INF/MANIFEST.MF': b'old signature', 'META-INF/license.txt': b'keep license',
            'base/root/META-INF/OTHER.SF': b'keep nested signature'}


def bundle(path, data=None):
    with zipfile.ZipFile(path, 'w', compression=zipfile.ZIP_DEFLATED) as z:
        for name, value in (entries() if data is None else data).items():
            z.writestr(name, value)
    return path


@pytest.fixture
def p():
    return importlib.import_module('oal_builder.personalize')


@pytest.fixture
def tools():
    root = Path(os.environ.get('OAL_PERSONALIZE_TOOLS', '/hermes_work/oal-builder-clean-qualification/tools'))
    paths = dict(java=root / 'jdk-17/bin/java', javac=root / 'jdk-17/bin/javac', bundletool=root / 'bundletool-1.18.3.jar')
    if not all(v.is_file() for v in paths.values()):
        pytest.skip('Set OAL_PERSONALIZE_TOOLS to a managed JDK17/bundletool tool directory')
    return paths


def runner(argv, phase, cwd=None):
    # Test commands have bounded lifetime, no output accumulation or secret logs.
    subprocess.run([str(a) for a in argv], cwd=cwd, check=True, timeout=120,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def transform(p, tmp_path, tools, data=None, **kwargs):
    source = bundle(tmp_path / 'input.aab', data)
    output = tmp_path / 'output.aab'
    result = p.personalize_bundle(source, output, original_app_id=OLD, app_id=NEW,
                                 version_code=42, workdir=tmp_path / 'work', run=runner,
                                 **tools, **kwargs)
    return source, output, result


def test_unsigned_semantic_transformation_preserves_payload_and_unknown_fields(p, tools, tmp_path):
    source, output, result = transform(p, tmp_path, tools)
    with zipfile.ZipFile(source) as before, zipfile.ZipFile(output) as after:
        assert after.read(MANIFEST) == manifest(NEW, 42)
        assert after.read(RESOURCES) == resources(NEW)
        for name in before.namelist():
            if name in (MANIFEST, RESOURCES) or name in ('META-INF/UPLOAD.SF', 'META-INF/UPLOAD.RSA', 'META-INF/MANIFEST.MF'):
                continue
            assert before.read(name) == after.read(name)
        assert 'META-INF/UPLOAD.SF' not in after.namelist()
    assert result['applicationId'] == NEW
    assert result['versionCode'] == 42
    assert result['templateSha256'] == hashlib.sha256(source.read_bytes()).hexdigest()
    assert result['unsignedSha256'] == hashlib.sha256(output.read_bytes()).hexdigest()
    assert result['changedMembers'] == [MANIFEST, RESOURCES]
    assert result['removedSignatureCount'] == 3
    assert not list((tmp_path / 'work').iterdir())


@pytest.mark.parametrize('name', ['../bad', '/absolute', 'C:/drive', 'base/../bad', 'base//bad',
    'base/./bad', 'base/bad\\name', 'base/bad\x00tail', 'BASE/resources.pb', 'base/resources.pb',
    'base/assets/offline-mfi/identity.pk8', 'base/assets/private.P12', 'feature/assets/file',
    'base/unknown.pb', 'base/assets/space. ', 'base/assets/CON', 'base/assets/bad:stream'])
def test_unsafe_zip_rejected_before_any_writes_or_processes(p, tmp_path, name):
    import warnings
    source = bundle(tmp_path / 'input.aab')
    with warnings.catch_warnings():
        warnings.simplefilter('ignore', UserWarning)
        with zipfile.ZipFile(source, 'a') as z:
            info = zipfile.ZipInfo('placeholder')
            info.filename = name  # preserve hostile names even on Windows
            z.writestr(info, b'synthetic')
    output, work = tmp_path / 'output.aab', tmp_path / 'work'
    def forbidden(*args, **kwargs):
        pytest.fail('process started for unsafe ZIP')
    with pytest.raises(p.BuilderError, match='OAL_PERSONALIZE_'):
        p.personalize_bundle(source, output, original_app_id=OLD, app_id=NEW, version_code=42,
            java='unused', javac='unused', bundletool='unused', workdir=work, run=forbidden)
    assert not output.exists()
    assert not work.exists()


@pytest.mark.parametrize('violation', ['symlink', 'encrypted', 'count', 'expanded', 'member', 'ratio'])
def test_zip_structural_limits_before_writes(p, tmp_path, monkeypatch, violation):
    source = bundle(tmp_path / 'input.aab')
    if violation == 'symlink':
        with zipfile.ZipFile(source, 'a') as z:
            info = zipfile.ZipInfo('base/assets/link')
            info.create_system = 3
            info.external_attr = 0o120777 << 16
            z.writestr(info, 'target')
    elif violation == 'encrypted':
        raw = bytearray(source.read_bytes())
        # Mark first central directory entry as encrypted; no read should occur.
        pos = raw.index(b'PK\x01\x02')
        raw[pos + 8] |= 1
        source.write_bytes(raw)
    else:
        monkeypatch.setattr(p, {'count': 'MAX_MEMBERS', 'expanded': 'MAX_EXPANDED_BYTES',
            'member': 'MAX_MEMBER_BYTES', 'ratio': 'MAX_COMPRESSION_RATIO'}[violation], 1)
    def forbidden(*args, **kwargs):
        pytest.fail('process started for invalid ZIP')
    with pytest.raises(p.BuilderError, match='OAL_PERSONALIZE_'):
        p.personalize_bundle(source, tmp_path / 'output.aab', original_app_id=OLD,
            app_id=NEW, version_code=42, java='unused', javac='unused', bundletool='unused',
            workdir=tmp_path / 'work', run=forbidden)
    assert not (tmp_path / 'work').exists()


@pytest.mark.parametrize('mutation', ['unknown_attribute', 'unknown_compiled', 'relative_class',
    'wrong_affinity_owner', 'wrong_provider_owner', 'non_signature_permission', 'raw_compiled_mismatch',
    'compiled_version_mismatch', 'duplicate_attribute', 'unknown_element', 'resource_dependency',
    'resource_package_id', 'shared_uid', 'extra_authority', 'missing_application', 'class_resource_reference'])
def test_fail_closed_unsupported_protobuf_shapes(p, tools, tmp_path, mutation):
    data = entries()
    if mutation == 'unknown_attribute':
        data[MANIFEST] = manifest(extra=[node('meta-data', [attr('name', 'unreviewed'), attr('value', OLD + '.custom')])])
    elif mutation == 'unknown_compiled':
        data[MANIFEST] = manifest(extra=[node('meta-data', [attr('name', 'unreviewed'), attr('value', 'benign', compiled=field(2, field(1, OLD)))])])
    elif mutation == 'relative_class':
        data[MANIFEST] = manifest(extra=[node('service', [attr('name', '.RelativeService')])])
    elif mutation == 'wrong_affinity_owner':
        data[MANIFEST] = manifest().replace(b'androidx.car.app.activity.CarAppActivity', b'androidx.car.app.activity.BADAppActivity')
    elif mutation == 'wrong_provider_owner':
        data[MANIFEST] = manifest().replace(b'androidx.startup.InitializationProvider', b'androidx.startup.BADtializationProvider')
    elif mutation == 'non_signature_permission':
        data[MANIFEST] = manifest().replace(b'0x00000002', b'0x00000001')
    elif mutation == 'raw_compiled_mismatch':
        data[MANIFEST] = manifest(extra=[node('service', [attr('name', 'com.example.Service', compiled=field(2, field(1, '.RelativeService')))])])
    elif mutation == 'class_resource_reference':
        data[MANIFEST] = manifest(extra=[node('service', [attr('name', 'com.example.Service', compiled=field(1, field(2, 0x7f010001)))])])
    elif mutation == 'compiled_version_mismatch':
        data[MANIFEST] = manifest().replace(field(7, field(6, 1)), field(7, field(6, 2)))
    elif mutation == 'duplicate_attribute':
        data[MANIFEST] = manifest(extra=[node('service', [attr('name', 'com.example.Service'), attr('name', 'com.example.Other')])])
    elif mutation == 'unknown_element':
        data[MANIFEST] = manifest(extra=[node('unknown-future-component')])
    elif mutation == 'resource_dependency':
        data[RESOURCES] += field(101, OLD)
    elif mutation == 'resource_package_id':
        data[RESOURCES] = resources().replace(field(1, field(1, 127)), field(1, field(1, 126)))
    elif mutation == 'shared_uid':
        data[MANIFEST] = node('manifest', [attr('package', OLD, ''), attr('versionCode', '1', compiled=field(7, field(6, 1))), attr('sharedUserId', 'com.foreign.shared')])
    elif mutation == 'extra_authority':
        data[MANIFEST] = manifest(extra=[node('provider', [attr('name', 'com.example.Provider'), attr('authorities', 'com.foreign.shared')])])
    else:
        data[MANIFEST] = manifest().replace(b'com.openautolink.app.OalApplication', b'com.openautolink.app.BadApplication')
    with pytest.raises(p.BuilderError, match='OAL_PERSONALIZE_'):
        transform(p, tmp_path, tools, data)
    assert not (tmp_path / 'output.aab').exists()
    assert not list((tmp_path / 'work').iterdir())


@pytest.mark.parametrize('value,valid', [
    ('com.example.personal', True), ('com.openautolink.template', True),
    ('com.example.u_123', True), ('a.' + 'b' * 148, True),
    (None, False), (42, False), ('', False), ('single', False),
    ('com.openautolink.app', False), ('com.example.class', False),
    ('com.example.true', False), ('com._invalid.foo', False),
    ('com.example..app', False), ('Com.example.app', False),
    ('com.example.3app', False), ('com.example.app;bad', False),
    ('com.example.app\n', False), ('a.' + 'b' * 149, False),
])
def test_lightweight_app_id_rules_match_build_boundary(value, valid):
    from oal_builder.app_identity import validate_app_id
    from oal_builder.engine import validate_app_id as engine_validate
    from oal_builder.toolchain import BuilderError
    for validate in (validate_app_id, engine_validate):
        if valid:
            assert validate(value) is None
        else:
            with pytest.raises(BuilderError):
                validate(value)


@pytest.mark.parametrize('value', [
    'com.openautolink', 'com.openautolink.template',
    'com.openautolink.template.user', 'org.com.openautolink.templatecopy',
])
def test_personal_identity_rules_match_transformer_overlap_guard(p, tmp_path, value):
    from oal_builder.app_identity import validate_app_id, validate_personal_app_id
    # Generic rules intentionally allow publication's template/original ID.
    assert validate_app_id(value) is None
    with pytest.raises(p.BuilderError, match='incompatible with AAB personalization'):
        validate_personal_app_id(value)
    with pytest.raises(p.BuilderError, match='OAL_PERSONALIZE_IDENTITY'):
        p._check_options(tmp_path / 'input', tmp_path / 'output', tmp_path / 'work', OLD, value, 1)
    assert not list(tmp_path.iterdir())


def test_suggested_personal_id_is_compatible_with_both_modes():
    from oal_builder.app_identity import validate_personal_app_id
    from oal_builder.engine import suggest_app_id
    for _ in range(20):
        assert validate_personal_app_id(suggest_app_id()) is None


@pytest.mark.parametrize('case', ['same_output', 'existing_output', 'hardlinked_output', 'symlink_output',
    'symlink_workdir', 'invalid_appid', 'implementation_appid', 'same_appid', 'bool_version',
    'zero_version', 'overflow_version', 'bad_zip', 'baked_dex', 'baked_resource', 'baked_utf16'])
def test_preflight_preserves_inputs_and_rejects_unsafe_options(p, tmp_path, case):
    data = entries()
    if case.startswith('baked_'):
        name = 'base/dex/classes.dex' if case == 'baked_dex' else 'base/res/xml/config.xml'
        data[name] = OLD.encode('utf-16le' if case == 'baked_utf16' else 'utf-8')
    source = bundle(tmp_path / 'input.aab', data)
    output, work = tmp_path / 'output.aab', tmp_path / 'work'
    appid, version = NEW, 42
    if case == 'same_output': output = source
    elif case == 'existing_output': output.write_bytes(b'preserve output')
    elif case == 'hardlinked_output': os.link(source, output)
    elif case == 'symlink_output': output.symlink_to(source)
    elif case == 'symlink_workdir': work.symlink_to(tmp_path, target_is_directory=True)
    elif case == 'invalid_appid': appid = 'bad;argument'
    elif case == 'implementation_appid': appid = 'com.openautolink.app'
    elif case == 'same_appid': appid = OLD
    elif case == 'bool_version': version = True
    elif case == 'zero_version': version = 0
    elif case == 'overflow_version': version = 2100000001
    elif case == 'bad_zip': source.write_bytes(b'not a ZIP')
    before = source.read_bytes()
    existing = output.read_bytes() if output.exists() else None
    def forbidden(*args, **kwargs):
        pytest.fail('process started before invalid input was rejected')
    with pytest.raises(p.BuilderError, match='OAL_PERSONALIZE_'):
        p.personalize_bundle(source, output, original_app_id=OLD, app_id=appid, version_code=version,
            java='unused', javac='unused', bundletool='unused', workdir=work, run=forbidden)
    assert source.read_bytes() == before
    if existing is not None:
        assert output.read_bytes() == existing
    else:
        assert not output.exists()


def test_runner_failure_is_sanitized_and_scratch_removed(p, tmp_path, tools):
    source = bundle(tmp_path / 'input.aab')
    def failing(*args, **kwargs):
        raise RuntimeError('DO_NOT_LEAK_PRIVATE_MATERIAL')
    with pytest.raises(p.BuilderError, match='^OAL_PERSONALIZE_HELPER_FAILED$') as error:
        p.personalize_bundle(source, tmp_path / 'output.aab', original_app_id=OLD, app_id=NEW,
            version_code=42, workdir=tmp_path / 'work', run=failing, **tools)
    import traceback
    assert 'DO_NOT_LEAK_PRIVATE_MATERIAL' not in ''.join(traceback.format_exception(error.value))
    assert not (tmp_path / 'output.aab').exists()
    assert not list((tmp_path / 'work').iterdir())


@pytest.fixture
def synthetic_pair():
    import datetime as dt
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.x509.oid import NameOID
    key = ec.generate_private_key(ec.SECP256R1())
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, 'Synthetic test only')])
    now = dt.datetime.now(dt.timezone.utc)
    cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
        .serial_number(1).not_valid_before(now).not_valid_after(now + dt.timedelta(days=1))
        .sign(key, hashes.SHA256()))
    return (key.private_bytes(serialization.Encoding.DER, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()),
            cert.public_bytes(serialization.Encoding.DER))


@pytest.mark.parametrize('contract', ['tuple', 'engine_staged_path'])
def test_synthetic_carplay_pair_injected_only_at_exact_asset_paths(p, tools, tmp_path, synthetic_pair, contract):
    import json
    pair = synthetic_pair
    if contract == 'engine_staged_path':
        staged = tmp_path / 'private/carplay'
        assets = staged / 'offline-mfi'
        assets.mkdir(parents=True)
        for name, data in zip(('identity.pk8', 'certificate.p7b'), pair):
            (assets / name).write_bytes(data)
        carplay = staged
    else:
        carplay = pair
    source, output, metadata = transform(p, tmp_path, tools, carplay=carplay)
    with zipfile.ZipFile(source) as before, zipfile.ZipFile(output) as after:
        added = set(after.namelist()) - set(before.namelist())
        assert added == {'base/assets/offline-mfi/identity.pk8', 'base/assets/offline-mfi/certificate.p7b'}
        for name, data in zip(('identity.pk8', 'certificate.p7b'), pair):
            assert after.read('base/assets/offline-mfi/' + name) == data
            assert data.hex() not in json.dumps(metadata)
            assert hashlib.sha256(data).hexdigest() not in json.dumps(metadata)
        assert metadata['addedMembers'] == sorted(added)
        assert metadata['carplayIncluded'] is True
    assert not list((tmp_path / 'work').iterdir())


def test_credential_bearing_template_rejected_even_with_replacement_pair(p, tools, tmp_path, synthetic_pair):
    data = entries()
    data['base/assets/offline-mfi/certificate.p7b'] = b'synthetic prior certificate'
    with pytest.raises(p.BuilderError, match='OAL_PERSONALIZE_CREDENTIAL_INPUT'):
        transform(p, tmp_path, tools, data, carplay=synthetic_pair)
    assert not (tmp_path / 'work').exists()


@pytest.mark.parametrize('pair', [(b'a',), (b'', b'cert'), (b'a' * 16385, b'cert'), ('string', b'cert'), [b'key', b'cert']])
def test_invalid_carplay_contract_rejected_before_writes(p, tools, tmp_path, pair):
    with pytest.raises(p.BuilderError, match='OAL_PERSONALIZE_CARPLAY_INPUT'):
        transform(p, tmp_path, tools, carplay=pair)
    assert not (tmp_path / 'work').exists()


@pytest.mark.parametrize('include_carplay', [False, True])
def test_real_copied_bundle_validates_and_builds_apks(p, tools, tmp_path, synthetic_pair, include_carplay):
    """Opt-in qualification against the retained old credential-free spike AAB."""
    import io
    import json
    import xml.etree.ElementTree as ET
    configured = os.environ.get('OAL_PERSONALIZE_REAL_AAB')
    if not configured:
        pytest.skip('Set OAL_PERSONALIZE_REAL_AAB to the retained credential-free spike input')
    original = Path(configured)
    pinned = 'f273d25e575197ade9e24b119c98d3a5b2c82312aa674ea38c9ba5c798fba89e'
    assert p.file_digest(original) == pinned
    source = tmp_path / 'copy.aab'
    shutil.copyfile(original, source)
    output = tmp_path / 'personalized.aab'
    metadata = p.personalize_bundle(source, output,
        original_app_id='com.openautolink.personal.qualification', app_id=NEW,
        version_code=42, workdir=tmp_path / 'work', run=runner,
        carplay=synthetic_pair if include_carplay else None, **tools)
    java = [str(tools['java']), '-Xmx512m', '-Djava.io.tmpdir=' + str(tmp_path),
        '-Duser.home=' + str(tmp_path), '-jar', str(tools['bundletool'])]
    runner(java + ['validate', '--bundle=' + str(output)], 'validate', tmp_path)
    # Bounded semantic dump, not a production runner or unbounded subprocess log.
    dump = subprocess.run(java + ['dump', 'manifest', '--bundle=' + str(output)], check=True,
        timeout=120, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL).stdout
    assert len(dump) < 1024 * 1024
    root = ET.fromstring(dump)
    assert root.get('package') == NEW
    assert root.get('{' + ANDROID + '}versionCode') == '42'
    assert root.find('application').get('{' + ANDROID + '}name') == 'com.openautolink.app.OalApplication'
    baseline = subprocess.run(java + ['dump', 'manifest', '--bundle=' + str(source)], check=True,
        timeout=120, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL).stdout
    expected_manifest = ET.fromstring(baseline)
    android = '{' + ANDROID + '}'
    expected_manifest.set('package', NEW)
    expected_manifest.set(android + 'versionCode', '42')
    old_id = 'com.openautolink.personal.qualification'
    edits = 0
    for element in expected_manifest.iter():
        for key, value in list(element.attrib.items()):
            if value.startswith(old_id + '.'):
                assert (element.tag, key) in {
                    ('permission', android + 'name'), ('uses-permission', android + 'name'),
                    ('activity', android + 'taskAffinity'), ('provider', android + 'authorities')}
                element.set(key, NEW + value[len(old_id):])
                edits += 1
    assert edits == 4
    def semantic(element):
        return element.tag, sorted(element.attrib.items()), [semantic(child) for child in element]
    assert semantic(root) == semantic(expected_manifest)
    apks = tmp_path / 'default.apks'
    runner(java + ['build-apks', '--bundle=' + str(output), '--output=' + str(apks),
        '--mode=default', '--max-threads=2'], 'build-apks', tmp_path)
    with zipfile.ZipFile(source) as before, zipfile.ZipFile(output) as after:
        common = set(before.namelist()) & set(after.namelist())
        changed = sorted(n for n in common if before.read(n) != after.read(n))
        assert changed == [MANIFEST, RESOURCES]
        assert set(before.namelist()) - set(after.namelist()) == {'META-INF/MANIFEST.MF', 'META-INF/UPLOAD.SF', 'META-INF/UPLOAD.RSA'}
        assert set(after.namelist()) - set(before.namelist()) == set(metadata['addedMembers'])
        payloads = {n.removeprefix('base/dex/') if n.startswith('base/dex/') else n.removeprefix('base/'): before.read(n)
            for n in before.namelist() if n.startswith(('base/dex/', 'base/lib/'))}
        seen, seen_assets = set(), set()
        with zipfile.ZipFile(apks) as generated:
            apk_names = [n for n in generated.namelist() if n.endswith('.apk')]
            assert len(apk_names) == 84
            for name in apk_names:
                with zipfile.ZipFile(io.BytesIO(generated.read(name))) as apk:
                    for n in apk.namelist():
                        if n in payloads:
                            assert apk.read(n) == payloads[n]
                            seen.add(n)
                        if n.startswith('assets/offline-mfi/'):
                            assert include_carplay
                            index = ('identity.pk8', 'certificate.p7b').index(n.split('/')[-1])
                            assert apk.read(n) == synthetic_pair[index]
                            seen_assets.add(n)
        assert seen == set(payloads)
        assert len(seen_assets) == (2 if include_carplay else 0)
    assert p.file_digest(original) == p.file_digest(source) == pinned
    metadata.update({'bundletoolValidate': True, 'generatedSplitApks': len(apk_names), 'codeNativeUnchanged': True})
    (tmp_path / 'qualification.json').write_text(json.dumps(metadata, indent=2))


@pytest.mark.parametrize('case', ['directory_case_alias', 'double_trailing_slash', 'extra_manifest', 'dex_shape', 'native_shape', 'local_header_alias'])
def test_additional_archive_shape_and_raw_name_guards(p, tmp_path, case):
    data = entries()
    if case == 'directory_case_alias':
        data.update({'base/assets/Folder/a': b'a', 'base/assets/folder/b': b'b'})
    elif case == 'double_trailing_slash': data['base/assets/folder//'] = b''
    elif case == 'extra_manifest': data['base/manifest/Other.xml'] = b'x'
    elif case == 'dex_shape': data['base/dex/arbitrary.jar'] = b'x'
    elif case == 'native_shape': data['base/lib/unknown-abi/libtest.so'] = b'x'
    source = bundle(tmp_path / 'input.aab', data)
    if case == 'local_header_alias':
        raw = bytearray(source.read_bytes())
        at = raw.index(b'base/manifest/AndroidManifest.xml')
        raw[at:at + 4] = b'BASE'
        source.write_bytes(raw)
    def forbidden(*args, **kwargs):
        pytest.fail('process invoked for malformed ZIP')
    with pytest.raises(p.BuilderError, match='OAL_PERSONALIZE_'):
        p.personalize_bundle(source, tmp_path / 'output.aab', original_app_id=OLD, app_id=NEW,
            version_code=42, java='unused', javac='unused', bundletool='unused', workdir=tmp_path / 'work', run=forbidden)
    assert not (tmp_path / 'work').exists()


@pytest.mark.parametrize('name,resource_id,value', [
    ('name', 0x01010003, 'com.example.Service'),
    ('appComponentFactory', 0x0101057a, 'com.example.Factory'),
    ('permission', 0x01010006, 'com.example.PERMISSION'),
    ('process', 0x01010011, ':isolated'),
    ('exported', 0x01010010, 'false'),
    ('enabled', 0x0101000e, 'true'),
    ('directBootAware', 0x01010505, 'false'),
    ('allowBackup', 0x01010280, 'false'),
    ('minSdkVersion', 0x0101020c, '29'),
    ('targetSdkVersion', 0x01010270, '36'),
])
@pytest.mark.parametrize('mutation', ['valid', 'wrong_id', 'missing_id', 'no_namespace', 'foreign_namespace', 'alias'])
def test_android_attribute_semantics_cannot_be_aliased(p, tools, tmp_path, name, resource_id, value, mutation):
    compiled = None
    if value in ('true', 'false'):
        compiled = field(7, field(8, int(value == 'true')))
    elif name in ('minSdkVersion', 'targetSdkVersion'):
        compiled = field(7, field(6, int(value)))
    a = attr(name, value, resource_id=resource_id, compiled=compiled)
    data = entries()
    data[MANIFEST] = manifest(extra=[node('service', [a])])
    if mutation == 'valid':
        transform(p, tmp_path, tools, data)
        assert (tmp_path / 'output.aab').is_file()
        return
    change = {
        'wrong_id': {'resource_id': 0x01010009},
        'missing_id': {'resource_id': 0},
        'no_namespace': {'namespace': ''},
        'foreign_namespace': {'namespace': 'urn:unreviewed'},
        'alias': {'alias': 'unreviewedAlias'},
    }[mutation]
    data[MANIFEST] = mutate_manifest_attribute(data[MANIFEST], name, **change)
    with pytest.raises(p.BuilderError, match='OAL_PERSONALIZE_HELPER_FAILED'):
        transform(p, tmp_path, tools, data)
    assert not (tmp_path / 'output.aab').exists()


@pytest.mark.parametrize('name', ['package', 'versionCode', 'taskAffinity', 'authorities', 'protectionLevel'])
def test_identity_and_protection_attribute_ids_are_exact(p, tools, tmp_path, name):
    data = entries()
    data[MANIFEST] = mutate_manifest_attribute(data[MANIFEST], name, resource_id=0x01010011)
    with pytest.raises(p.BuilderError, match='OAL_PERSONALIZE_HELPER_FAILED'):
        transform(p, tmp_path, tools, data)
    assert not (tmp_path / 'output.aab').exists()


@pytest.mark.parametrize('name,value,resource_id,compiled', [
    ('exported', 'false', 0x01010010, field(7, field(8, 1))),
    ('enabled', 'false', 0x0101000e, field(7, field(8, 1))),
    ('permission', 'com.example.PERMISSION', 0x01010006, field(1, field(2, 0x7f010001))),
    ('process', ':isolated', 0x01010011, field(1, field(2, 0x7f010001))),
    ('minSdkVersion', '29', 0x0101020c, field(7, field(6, 1))),
    ('targetSdkVersion', '36', 0x01010270, field(7, field(6, 1))),
])
def test_security_attributes_reject_disagreeing_compiled_values(p, tools, tmp_path, name, value, resource_id, compiled):
    data = entries()
    data[MANIFEST] = manifest(extra=[node('service', [
        attr(name, value, resource_id=resource_id, compiled=compiled)])])
    with pytest.raises(p.BuilderError, match='OAL_PERSONALIZE_HELPER_FAILED'):
        transform(p, tmp_path, tools, data)
    assert not (tmp_path / 'output.aab').exists()


def test_template_with_split_identity_is_rejected(p, tools, tmp_path):
    original = manifest()
    # Insert an extra root attribute with the normal fixture constructor.
    extra = field(4, attr('split', 'feature', ''))
    # Reuse our encoder's length-delimited outer node without binary byte replacement.
    def decode_varint(data, offset):
        value, shift = 0, 0
        while True:
            byte = data[offset]
            offset += 1
            value |= (byte & 127) << shift
            if not byte & 128: return value, offset
            shift += 7
    length, start = decode_varint(original, 1)
    data = entries()
    data[MANIFEST] = field(1, original[start:start + length] + extra) + UNKNOWN
    with pytest.raises(p.BuilderError, match='OAL_PERSONALIZE_'):
        transform(p, tmp_path, tools, data)
