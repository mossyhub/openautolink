"""Latest stable source discovery and intentional AAB publication contract.

Network callbacks must use bounded credential-free HTTPS requests. No fallback to
an older source or APK exists here. Metadata hashes bind release assets, not a
publisher signature; GitHub/repository ownership remains the trust root.
"""
import re
import hashlib
import json
from pathlib import Path

from .toolchain import BuilderError

API = 'https://api.github.com/repos/mossyhub/openautolink'
WEB = 'https://github.com/mossyhub/openautolink'
DESCRIPTOR_NAME = 'openautolink-personalization.json'
TEMPLATE_APPLICATION_ID = 'com.openautolink.template'
TRANSFORM_CONTRACT = 'oal-identity-v1'
MAX_TEMPLATE_SIZE = 512 * 1024**2
MAX_DESCRIPTOR_SIZE = 65536


class ReleaseError(BuilderError):
    """Invalid or unavailable latest release; retry rather than falling back."""


class TemplateUnavailable(ReleaseError):
    """Source exists, but the release has no intentional personalization AAB."""


def _matches(pattern, value):
    return isinstance(value, str) and re.fullmatch(pattern, value) is not None


def validate_tag(tag):
    if not _matches(r'v(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})', tag):
        raise ReleaseError('Expected a stable release tag vMAJOR.MINOR.PATCH; retry or contact the release publisher')
    return tag


def validate_commit(commit):
    if not _matches(r'[0-9a-f]{40}', commit):
        raise ReleaseError('Release tag did not resolve to an immutable 40-hex source commit; retry or contact the publisher')
    return commit


def _fetch(fetch_json, url):
    try:
        value = fetch_json(url)
    except Exception:
        raise ReleaseError('Could not read release metadata; check connectivity or GitHub rate limit and retry') from None
    if not isinstance(value, dict):
        raise ReleaseError('Malformed release metadata; retry or contact the publisher')
    return value


def resolve_latest_release(fetch_json):
    """Resolve latest stable source, independently of template availability.

    fetch_json(url) returns parsed JSON from a bounded credential-free GET.
    target_commitish is deliberately ignored: it may be a moving branch.
    """
    metadata = _fetch(fetch_json, API + '/releases/latest')
    tag = validate_tag(metadata.get('tag_name'))
    if metadata.get('draft') is not False or metadata.get('prerelease') is not False:
        raise ReleaseError('Latest release is not stable/public; retry or contact the publisher')
    if metadata.get('html_url') != WEB + '/releases/tag/' + tag:
        raise ReleaseError('Release URL does not match its tag and repository; retry or contact the publisher')
    ref = _fetch(fetch_json, API + '/git/ref/tags/' + tag)
    if ref.get('ref') != 'refs/tags/' + tag:
        raise ReleaseError('Release tag resolution mismatch; retry or contact the publisher')
    obj = ref.get('object')
    seen = set()
    for depth in range(9):
        if not isinstance(obj, dict):
            raise ReleaseError('Missing release tag object; retry or contact the publisher')
        sha = validate_commit(obj.get('sha'))
        if obj.get('type') == 'commit':
            break
        if obj.get('type') != 'tag' or sha in seen or depth == 8:
            raise ReleaseError('Invalid or excessive annotated release tag chain; contact the publisher')
        seen.add(sha)
        annotated = _fetch(fetch_json, API + '/git/tags/' + sha)
        if annotated.get('sha') != sha or (depth == 0 and annotated.get('tag') != tag):
            raise ReleaseError('Annotated release tag mismatch; contact the publisher')
        obj = annotated.get('object')
    assets = validate_assets(metadata.get('assets'), tag)
    return {'sourceRef': tag, 'sourceCommit': sha, 'versionName': tag[1:],
            'templateAvailable': DESCRIPTOR_NAME in assets and template_name(tag) in assets,
            'assets': assets}


def template_name(tag):
    return 'openautolink-template-' + validate_tag(tag) + '.aab'


def asset_url(tag, name):
    validate_tag(tag)
    if not _matches(r'[A-Za-z0-9][A-Za-z0-9._-]{0,199}', name):
        raise ReleaseError('Unsafe release asset name; contact the publisher')
    return WEB + '/releases/download/' + tag + '/' + name


def validate_assets(records, tag):
    if not isinstance(records, list) or len(records) > 100:
        raise ReleaseError('Missing or excessive release assets; retry or contact the publisher')
    assets = {}
    for record in records:
        if not isinstance(record, dict):
            raise ReleaseError('Malformed release asset; contact the publisher')
        name = record.get('name')
        url = asset_url(tag, name)
        size = record.get('size')
        digest = record.get('digest')
        if name in assets or record.get('browser_download_url') != url:
            raise ReleaseError('Duplicate or untrusted release asset URL; contact the publisher')
        if type(size) is not int or not 0 < size <= 4 * 1024**3:
            raise ReleaseError('Invalid release asset size; contact the publisher')
        if digest is not None and not _matches(r'sha256:[0-9a-f]{64}', digest):
            raise ReleaseError('Invalid release asset checksum; contact the publisher')
        assets[name] = {'name': name, 'size': size, 'browser_download_url': url, 'digest': digest}
    return assets


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('Duplicate JSON field')
        result[key] = value
    return result


def validate_descriptor(descriptor, release):
    """Validate a schema-1 descriptor against resolved GitHub source/assets.

    oal-identity-v1 changes only manifest install identity/version, signature
    permission, startup authority, cluster affinity and resource package name.
    DEX/native/implementation names stay unchanged. The transformer must inspect
    the real bundle and reject new unreviewed package-dependent fields.
    """
    error = 'Unsupported or inconsistent personalization descriptor; update the builder or contact the publisher for a supported template'
    if not isinstance(descriptor, dict) or set(descriptor) != {
        'schemaVersion', 'formatVersion', 'releaseTag', 'sourceCommit',
        'templateApplicationId', 'transformContract', 'templateAsset',
    }:
        raise ReleaseError(error)
    for key in ('schemaVersion', 'formatVersion'):
        if type(descriptor[key]) is not int or descriptor[key] != 1:
            raise ReleaseError(error)
    if (descriptor['releaseTag'] != release['sourceRef']
            or descriptor['sourceCommit'] != release['sourceCommit']
            or descriptor['templateApplicationId'] != TEMPLATE_APPLICATION_ID
            or descriptor['transformContract'] != TRANSFORM_CONTRACT):
        raise ReleaseError(error)
    item = descriptor['templateAsset']
    if not isinstance(item, dict) or set(item) != {'name', 'size', 'sha256'}:
        raise ReleaseError(error)
    name = template_name(release['sourceRef'])
    if (item['name'] != name or type(item['size']) is not int
            or not 0 < item['size'] <= MAX_TEMPLATE_SIZE
            or not _matches(r'[0-9a-f]{64}', item['sha256'])):
        raise ReleaseError(error)
    actual = release['assets'].get(name)
    if actual is None:
        raise TemplateUnavailable('The latest release template AAB is missing. Keep your work folder and retry after the publisher provides a supported template')
    if actual['size'] != item['size'] or actual.get('digest') not in (None, 'sha256:' + item['sha256']):
        raise ReleaseError('Template size/checksum disagrees with GitHub release metadata; retry or contact the publisher')
    return descriptor | {'url': asset_url(release['sourceRef'], name)}


def resolve_template(release, fetch_bytes):
    """Fetch <=64 KiB descriptor; return validated descriptor plus exact `url`.

    fetch_bytes(url) must bound its response and safely validate HTTPS redirects.
    Download the returned AAB URL with templateAsset size/sha256 constraints,
    then call verify_template_download before inspecting or transforming it.
    """
    record = release['assets'].get(DESCRIPTOR_NAME)
    if record is None:
        raise TemplateUnavailable('The latest release has no published personalization template. Keep your work folder and retry after the publisher provides a supported template')
    if not 0 < record['size'] <= MAX_DESCRIPTOR_SIZE:
        raise ReleaseError('Personalization descriptor exceeds size limit; contact the publisher')
    url = asset_url(release['sourceRef'], DESCRIPTOR_NAME)
    if record['browser_download_url'] != url:
        raise ReleaseError('Untrusted descriptor URL; contact the publisher')
    try:
        raw = fetch_bytes(url)
    except Exception:
        raise ReleaseError('Could not download personalization descriptor; check connectivity and retry') from None
    if not isinstance(raw, bytes) or len(raw) != record['size'] or len(raw) > MAX_DESCRIPTOR_SIZE:
        raise ReleaseError('Incomplete or oversized personalization descriptor; retry')
    if record.get('digest') not in (None, 'sha256:' + hashlib.sha256(raw).hexdigest()):
        raise ReleaseError('Personalization descriptor checksum mismatch; retry or contact the publisher')
    try:
        descriptor = json.loads(raw, object_pairs_hook=_unique_object)
    except (ValueError, UnicodeError, RecursionError):
        raise ReleaseError('Malformed personalization descriptor JSON; contact the publisher') from None
    return validate_descriptor(descriptor, release)


def verify_template_download(path, template):
    """Check exact downloaded bytes; structural/credential checks follow separately."""
    path = Path(path)
    item = template['templateAsset']
    try:
        if path.is_symlink() or not path.is_file() or path.stat().st_size != item['size']:
            raise ReleaseError('Template download size mismatch; discard the download and retry')
        with path.open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    except OSError:
        raise ReleaseError('Cannot read downloaded template; check workspace permissions and retry') from None
    if digest != item['sha256']:
        raise ReleaseError('Template download checksum mismatch; discard the download and retry')
    return path
