#!/usr/bin/env python3
"""Public compiled-runtime + immutable published-template deployment preparation.

No website source, build execution, credential retrieval, or Azure provisioning.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'desktop-builder'))
from oal_builder import releases as r

ORIGIN = 'https://oal.mossyg.com'
API = r.API
DESCRIPTOR = r.DESCRIPTOR_NAME
MAX_SHELL = 32 * 1024**2
MAX_EXPANDED = 64 * 1024**2


def version(tag):
    return tuple(map(int, r.validate_tag(tag)[1:].split('.')))


def select_release(records):
    eligible = []
    for release in records:
        if release.get('draft') is not False or release.get('prerelease') is not False:
            continue
        try:
            tag = r.validate_tag(release.get('tag_name'))
        except r.ReleaseError:
            continue
        names = [a.get('name') for a in release.get('assets', [])]
        if DESCRIPTOR in names and r.template_name(tag) in names:
            eligible.append(release)
    if not eligible:
        raise ValueError('No complete stable personalization release')
    return max(eligible, key=lambda item: version(item['tag_name']))


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def shell_path(name):
    return isinstance(name, str) and (name in {'index.html', 'staticwebapp.config.json', 'brand/oal-logo.png'} or re.fullmatch(r'assets/[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)*\.(js|css|wasm|png|svg|woff2)', name) is not None)


def validate_config(config):
    if not isinstance(config, dict) or set(config) != {'schemaVersion', 'url', 'sha256', 'size', 'files'}:
        raise ValueError('Invalid runtime registry schema')
    if (type(config['schemaVersion']) is not int or config['schemaVersion'] != 1
            or not re.fullmatch(r'https://github\.com/mossyhub/openautolink/releases/download/web-builder-runtime/[A-Za-z0-9_-]+\.zip', str(config['url']))
            or not re.fullmatch(r'[0-9a-f]{64}', str(config['sha256']))
            or type(config['size']) is not int or not 0 < config['size'] <= MAX_SHELL):
        raise ValueError('Runtime archive must have exact public URL, size and SHA-256 pins')
    files = config['files']
    if not isinstance(files, dict) or not 2 <= len(files) <= 100 or not {'index.html', 'staticwebapp.config.json'} <= files.keys():
        raise ValueError('Runtime must contain index and SWA configuration')
    for name, item in files.items():
        if (not shell_path(name) or not isinstance(item, dict) or set(item) != {'sha256', 'size'}
                or not re.fullmatch(r'[0-9a-f]{64}', str(item['sha256']))
                or type(item['size']) is not int or not 0 < item['size'] <= MAX_SHELL):
            raise ValueError('Invalid runtime file registry')
    if sum(i['size'] for i in files.values()) > MAX_EXPANDED:
        raise ValueError('Runtime expanded size exceeds bound')
    return config


def extract_shell(archive, destination, registry):
    destination = Path(destination)
    if destination.exists():
        raise ValueError('Output must be fresh')
    with zipfile.ZipFile(archive) as z:
        infos = z.infolist()
        if len(infos) != len(registry) or {i.filename for i in infos} != set(registry):
            raise ValueError('Archive members disagree with exact allowlist')
        contents = {}
        for info in infos:
            mode = info.external_attr >> 16
            item = registry[info.filename]
            if (info.orig_filename != info.filename or not shell_path(info.filename) or info.is_dir() or stat.S_ISLNK(mode)
                    or stat.S_IFMT(mode) not in (0, stat.S_IFREG)
                    or info.flag_bits & 1 or info.compress_type not in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED)
                    or info.file_size != item['size'] or not 0 < info.file_size <= MAX_SHELL
                    or info.file_size > max(1, info.compress_size) * 250):
                raise ValueError('Unsafe runtime archive entry')
            with z.open(info) as stream:
                raw = stream.read(item['size'] + 1)
            if len(raw) != item['size'] or sha(raw) != item['sha256']:
                raise ValueError('Runtime member checksum mismatch')
            if any(marker in raw for marker in (b'-----BEGIN PRIVATE KEY', b'/hermes_work/', b'/home/hermes')):
                raise ValueError('Private-source marker in public runtime')
            contents[info.filename] = raw
        if sum(map(len, contents.values())) > MAX_EXPANDED:
            raise ValueError('Runtime expanded size exceeds bound')
    destination.mkdir(parents=True)
    for name, raw in contents.items():
        target = destination / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(raw)


def emit_manifest(release):
    tag = release['sourceRef']
    return validate_manifest(dict(schemaVersion=1, sourceRef=tag,
        sourceCommit=release['sourceCommit'], assets=[dict(release['assets'][name])
        for name in (DESCRIPTOR, r.template_name(tag))]))


def validate_manifest(manifest):
    if not isinstance(manifest, dict) or set(manifest) != {'schemaVersion', 'sourceRef', 'sourceCommit', 'assets'}:
        raise ValueError('Invalid site manifest')
    if type(manifest['schemaVersion']) is not int or manifest['schemaVersion'] != 1:
        raise ValueError('Invalid manifest version')
    tag = r.validate_tag(manifest['sourceRef'])
    r.validate_commit(manifest['sourceCommit'])
    records = manifest['assets']
    if not isinstance(records, list) or len(records) != 2:
        raise ValueError('Manifest must contain exactly two assets')
    for item in records:
        if (not isinstance(item, dict) or set(item) != {'name', 'size', 'digest', 'browser_download_url'}
                or not re.fullmatch(r'sha256:[0-9a-f]{64}', str(item.get('digest')))):
            raise ValueError('Manifest requires strict asset digest records')
    assets = r.validate_assets(records, tag)
    if set(assets) != {DESCRIPTOR, r.template_name(tag)} or assets[DESCRIPTOR]['size'] > r.MAX_DESCRIPTOR_SIZE or assets[r.template_name(tag)]['size'] > r.MAX_TEMPLATE_SIZE:
        raise ValueError('Invalid manifest asset set or size')
    return manifest


def guard_served(candidate, served):
    if version(candidate['sourceRef']) < version(served['sourceRef']):
        raise ValueError('Refusing rollback over newer served release')
    if candidate['sourceRef'] == served['sourceRef'] and candidate != served:
        raise ValueError('Same-tag immutable proof conflicts with served snapshot')


def allowed_url(url, *, asset=False):
    try:
        parsed = urllib.parse.urlsplit(url)
        if parsed.scheme != 'https' or parsed.username or parsed.password or parsed.port not in (None, 443) or parsed.fragment:
            return False
        if asset:
            return parsed.hostname in {'github.com', 'release-assets.githubusercontent.com', 'objects.githubusercontent.com'}
        return (url.startswith(API + '/') and parsed.hostname == 'api.github.com') or (parsed.netloc == 'oal.mossyg.com' and parsed.path.startswith('/'))
    except ValueError:
        return False


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Client:
    def __init__(self, token=None):
        self.token = token
        self.opener = urllib.request.build_opener(NoRedirect)

    def get(self, url, limit, *, asset=False, size=None, digest=None, output=None):
        initial = url
        deadline = time.monotonic() + 180
        for depth in range(6):
            if not allowed_url(url, asset=asset):
                raise ValueError('Untrusted HTTPS request')
            if asset and urllib.parse.urlsplit(url).hostname == 'github.com' and url != initial:
                raise ValueError('Unexpected GitHub asset redirect')
            headers = {'Accept-Encoding': 'identity', 'Cache-Control': 'no-cache', 'User-Agent': 'OAL-site-release'}
            if url.startswith(API + '/'):
                headers['Accept'] = 'application/vnd.github+json'
                headers['X-GitHub-Api-Version'] = '2022-11-28'
                if self.token:
                    headers['Authorization'] = 'Bearer ' + self.token
            try:
                response = self.opener.open(urllib.request.Request(url, headers=headers), timeout=30)
            except urllib.error.HTTPError as exc:
                if asset and exc.code in (301, 302, 303, 307, 308) and depth < 5:
                    url = urllib.parse.urljoin(url, exc.headers.get('Location', ''))
                    exc.close()
                    continue
                raise ValueError('HTTP request failed') from None
            with response:
                if response.status != 200 or response.headers.get('Content-Encoding', 'identity') != 'identity':
                    raise ValueError('Unexpected HTTP response')
                length = response.headers.get('Content-Length')
                if length is not None and (not length.isdecimal() or int(length) > limit or (size is not None and int(length) != size)):
                    raise ValueError('Invalid response length')
                sink = Path(output).open('xb') if output else None
                raw, count, hasher = bytearray(), 0, hashlib.sha256()
                try:
                    while True:
                        if time.monotonic() > deadline:
                            raise ValueError('Download deadline exceeded')
                        chunk = response.read(min(1024**2, limit - count + 1))
                        if not chunk:
                            break
                        count += len(chunk)
                        if count > limit:
                            raise ValueError('Download exceeds bound')
                        hasher.update(chunk)
                        if sink:
                            sink.write(chunk)
                        else:
                            raw.extend(chunk)
                finally:
                    if sink:
                        sink.close()
                if ((length is not None and count != int(length)) or (size is not None and count != size)
                        or (digest is not None and hasher.hexdigest() != digest)):
                    raise ValueError('Downloaded size/checksum mismatch')
                return bytes(raw) if not output else Path(output)
        raise ValueError('Excessive redirects')

    def json(self, url):
        return strict_json(self.get(url, 4 * 1024**2))


def strict_json(raw):
    if len(raw) > 4 * 1024**2:
        raise ValueError('JSON too large')
    value = json.loads(raw.decode('utf-8'), object_pairs_hook=r._unique_object,
                       parse_constant=lambda _: (_ for _ in ()).throw(ValueError('Nonfinite JSON')))
    pending = [(value, 0)]
    while pending:
        current, depth = pending.pop()
        if depth > 64:
            raise ValueError('JSON nesting exceeds bound')
        if isinstance(current, (dict, list)):
            pending.extend((child, depth + 1) for child in (current.values() if isinstance(current, dict) else current))
    return value


def resolve(client):
    records = []
    for page in range(1, 21):
        batch = client.json(API + '/releases?per_page=100&page=' + str(page))
        if not isinstance(batch, list) or len(batch) > 100:
            raise ValueError('Invalid release listing')
        records.extend(batch)
        if len(batch) < 100:
            break
    else:
        raise ValueError('Release listing exceeds safe pagination bound')
    metadata = select_release(records)
    tag = metadata['tag_name']
    # Reuse the established strict tag peeling and descriptor binding logic.
    release = r.resolve_latest_release(lambda url: metadata if url == API + '/releases/latest' else client.json(url))
    if metadata.get('html_url') != r.WEB + '/releases/tag/' + tag or type(metadata.get('id')) is not int:
        raise ValueError('Invalid release identity')
    selected = {name: release['assets'][name] for name in (DESCRIPTOR, r.template_name(tag))}
    for name, item in selected.items():
        bound = r.MAX_DESCRIPTOR_SIZE if name == DESCRIPTOR else r.MAX_TEMPLATE_SIZE
        if item['size'] > bound or not re.fullmatch(r'sha256:[0-9a-f]{64}', str(item['digest'])):
            raise ValueError('Both release assets require bounded size and published SHA-256')
    release['assets'] = selected
    asset_proof = {}
    for record in metadata['assets']:
        if record['name'] in selected:
            if type(record.get('id')) is not int or record['id'] <= 0:
                raise ValueError('Missing immutable asset identity')
            asset_proof[record['name']] = dict(id=record['id'], digest=record['digest'], size=record['size'])
    return dict(schemaVersion=1, releaseId=metadata['id'], assetProof=asset_proof, release=release)


def inventory(root):
    root = Path(root)
    result = {}
    if root.is_symlink() or not root.is_dir():
        raise ValueError('Invalid snapshot root')
    for target in sorted(root.rglob('*')):
        if target.is_symlink() or not (target.is_file() or target.is_dir()):
            raise ValueError('Nonregular snapshot member')
        if target.is_file():
            with target.open('rb') as stream:
                result[target.relative_to(root).as_posix()] = dict(size=target.stat().st_size,
                    sha256=hashlib.file_digest(stream, 'sha256').hexdigest())
    return result


def write_json(path, value):
    Path(path).write_text(json.dumps(value, indent=2) + '\n', encoding='utf-8')


def runtime_proof(client, config):
    metadata = client.json(API + '/releases/tags/web-builder-runtime')
    if metadata.get('tag_name') != 'web-builder-runtime' or metadata.get('draft') is not False or metadata.get('prerelease') is not True:
        raise ValueError('Runtime registry release must be a public prerelease, never Android latest')
    expected = config['url'].rsplit('/', 1)[1]
    matches = [a for a in metadata.get('assets', []) if a.get('name') == expected]
    if len(matches) != 1:
        raise ValueError('Pinned runtime asset missing or duplicated')
    item = matches[0]
    if (item.get('browser_download_url') != config['url'] or item.get('size') != config['size']
            or item.get('digest') != 'sha256:' + config['sha256'] or type(item.get('id')) is not int):
        raise ValueError('Runtime registry metadata disagrees with checked-in pin')
    return dict(releaseId=metadata['id'], assetId=item['id'], sha256=config['sha256'])


def prepare(client, config, work, validate_template):
    config = validate_config(config)
    work = Path(work)
    if work.exists() or work.is_symlink():
        raise ValueError('Preparation workspace must be fresh')
    proof = resolve(client)
    runtime = runtime_proof(client, config)
    work.mkdir(parents=True)
    release = proof['release']
    tag = release['sourceRef']
    record = release['assets'][DESCRIPTOR]
    raw = client.get(record['browser_download_url'], r.MAX_DESCRIPTOR_SIZE, asset=True,
                     size=record['size'], digest=record['digest'][7:])
    descriptor = r.resolve_template(release, lambda _: raw)
    item = descriptor['templateAsset']
    template = work / item['name']
    client.get(descriptor['url'], r.MAX_TEMPLATE_SIZE, asset=True, output=template,
               size=item['size'], digest=item['sha256'])
    r.verify_template_download(template, descriptor)
    validate_template(template)
    shell = work / 'runtime.zip'
    client.get(config['url'], MAX_SHELL, asset=True, output=shell,
               size=config['size'], digest=config['sha256'])
    dist = work / 'dist'
    extract_shell(shell, dist, config['files'])
    tagdir = dist / 'templates' / tag
    tagdir.mkdir(parents=True)
    (tagdir / DESCRIPTOR).write_bytes(raw)
    template.rename(tagdir / item['name'])
    manifest = emit_manifest(release)
    write_json(dist / 'templates/current.json', manifest)
    # Validate staged originals again rather than trusting copies or emission.
    r.resolve_template(release, lambda _: (tagdir / DESCRIPTOR).read_bytes())
    r.verify_template_download(tagdir / item['name'], descriptor)
    if strict_json((dist / 'templates/current.json').read_bytes()) != manifest:
        raise ValueError('Staged manifest drift')
    report = dict(schemaVersion=1, origin=ORIGIN, proof=proof, runtime=runtime,
                  configSha256=sha(json.dumps(config, sort_keys=True).encode()), files=inventory(dist))
    write_json(work / 'preparation.json', report)
    return report


def load_report(work):
    work = Path(work)
    report = strict_json((work / 'preparation.json').read_bytes())
    if report.get('origin') != ORIGIN or report.get('schemaVersion') != 1 or inventory(work / 'dist') != report.get('files'):
        raise ValueError('Prepared snapshot hash inventory changed')
    return report


def recheck(client, config, work):
    report = load_report(work)
    if (resolve(client) != report['proof'] or runtime_proof(client, config) != report['runtime']
            or sha(json.dumps(config, sort_keys=True).encode()) != report['configSha256']):
        raise ValueError('Latest complete release or immutable runtime proof changed; prepare afresh')
    served = validate_manifest(strict_json(client.get(ORIGIN + '/templates/current.json', 1024**2)))
    candidate = emit_manifest(report['proof']['release'])
    guard_served(candidate, served)
    return report


def readback(client, work, attempts=12):
    report = load_report(work)
    for attempt in range(attempts):
        try:
            for name, item in report['files'].items():
                if name == 'staticwebapp.config.json':
                    continue  # Azure consumes this file; not HTTP-readable.
                client.get(ORIGIN + '/' + name, item['size'], size=item['size'], digest=item['sha256'])
            manifest = validate_manifest(strict_json(client.get(ORIGIN + '/templates/current.json', 1024**2)))
            if manifest != emit_manifest(report['proof']['release']):
                raise ValueError('Served metadata differs from verified snapshot')
            result = dict(status='verified', origin=ORIGIN, proof=report['proof'], files=report['files'],
                          configuration='staticwebapp.config.json included-in-upload-not-http-readable')
            write_json(Path(work) / 'deployment-readback.json', result)
            return result
        except (ValueError, OSError):
            if attempt + 1 == attempts:
                raise ValueError('Public readback failed; deployment may already have changed, no rollback attempted') from None
            time.sleep(10)


def pin_runtime(archive):
    """Create pins from a reviewed compiled-only ZIP; never build private source."""
    import tempfile
    archive = Path(archive)
    if archive.is_symlink() or not archive.is_file() or not 0 < archive.stat().st_size <= MAX_SHELL:
        raise ValueError('Invalid runtime ZIP')
    if not re.fullmatch(r'[A-Za-z0-9_-]+\.zip', archive.name):
        raise ValueError('Use a versioned runtime ZIP filename')
    registry = {}
    with zipfile.ZipFile(archive) as z:
        for info in z.infolist():
            if not shell_path(info.filename) or info.filename in registry or not 0 < info.file_size <= MAX_SHELL:
                raise ValueError('Runtime archive is not compiled-only or has duplicate entries')
            with z.open(info) as stream:
                raw = stream.read(info.file_size + 1)
            registry[info.filename] = dict(size=len(raw), sha256=sha(raw))
    config = validate_config(dict(schemaVersion=1,
        url=r.WEB + '/releases/download/web-builder-runtime/' + archive.name,
        size=archive.stat().st_size, sha256=sha(archive.read_bytes()), files=registry))
    with tempfile.TemporaryDirectory(prefix='pin-runtime-') as tmp:
        extract_shell(archive, Path(tmp) / 'verified', registry)
    return config


def template_validator(java, javac, bundletool):
    from oal_builder.personalize import personalize_bundle, _validate_zip, _signature
    import subprocess
    import tempfile
    def run(argv, phase='validation', cwd=None):
        subprocess.run([str(v) for v in argv], cwd=cwd, check=True, timeout=180,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    def validate(template):
        with zipfile.ZipFile(template) as source:
            if any(_signature(info.filename) for info in _validate_zip(source)):
                raise ValueError('Template must be unsigned')
        with tempfile.TemporaryDirectory(prefix='probe-', dir=template.parent) as tmp:
            root = Path(tmp)
            run([java, '-Xmx512m', '-jar', bundletool, 'validate', '--bundle=' + str(template)])
            personalize_bundle(template, root / 'probe.aab', original_app_id=r.TEMPLATE_APPLICATION_ID,
                app_id='com.openautolink.refresh.probe', version_code=1, java=java, javac=javac,
                bundletool=bundletool, workdir=root / 'work', run=run)
    return validate


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['prepare', 'recheck', 'readback', 'pin-runtime'])
    parser.add_argument('--archive', type=Path)
    parser.add_argument('--config', type=Path, default=Path(__file__).with_name('runtime.json'))
    parser.add_argument('--work', type=Path)
    parser.add_argument('--java')
    parser.add_argument('--javac')
    parser.add_argument('--bundletool')
    args = parser.parse_args()
    client = Client(os.environ.get('GH_TOKEN'))
    try:
        if args.command == 'pin-runtime':
            if not args.archive:
                raise ValueError('Runtime ZIP required')
            write_json(args.config, pin_runtime(args.archive))
            print('Compiled runtime registry pinned; no publication or deployment performed')
            return 0
        if not args.work:
            raise ValueError('Workspace required')
        config = validate_config(strict_json(args.config.read_bytes()))
        if args.command == 'prepare':
            if not all((args.java, args.javac, args.bundletool)):
                raise ValueError('Preparation requires pinned bundletool and JDK validation tools')
            result = prepare(client, config, args.work, template_validator(args.java, args.javac, args.bundletool))
        elif args.command == 'recheck':
            result = recheck(client, config, args.work)
        else:
            result = readback(client, args.work)
    except Exception:
        # Do not echo request URLs, token values, response bodies or tool output.
        print('Website refresh failed closed during ' + args.command + '; inspect validated configuration and upstream availability', file=sys.stderr)
        return 1
    print('Website refresh ' + args.command + ' verified: ' + result['proof']['release']['sourceRef'])
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
