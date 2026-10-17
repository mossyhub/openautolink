"""Attach exactly three qualified downloads to an existing published release.

No release/tag creation, workflow dispatch, clobber, latest lookup or PR writes.
The native jobs are the execution gates; this verifier binds their reports and
transported bytes to the immutable release checkout before any write.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
from typing import NamedTuple
import urllib.parse
import urllib.request

_spec = importlib.util.spec_from_file_location('desktop_package_build', Path(__file__).with_name('build.py'))
build = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(build)
NAMES = ('OpenAutoLink-Builder.exe', 'OpenAutoLink-Builder', 'OpenAutoLink-Builder.dmg')


class VerifiedDownload(NamedTuple):
    path: Path
    sha256: str
    size: int


def require(condition, message):
    if not condition:
        raise ValueError(message)


def verify_windows_bootstrap(manifest):
    """Require the native producer's outer-executable evidence, not an inner EXE."""
    lock = json.loads((Path(__file__).parent / 'provenance/windows-setup.lock.json').read_text())['nsis']
    require(manifest.get('bootstrap') == 'official-vc-runtime-bootstrap-v1'
            and manifest.get('bootstrapExtraction') == 'exact-payload-verified'
            and manifest.get('runtimeInstaller') == 'recipient-download-only-interactive',
            'Windows bootstrap qualification missing')
    payload = manifest.get('embeddedPayload')
    names = {'builder.exe', 'bootstrap.ps1', 'setup-manifest.json', 'NSIS-COPYING.txt', 'BOOTSTRAP-NOTICE.txt'}
    require(isinstance(payload, dict) and set(payload) == names
            and all(isinstance(v, str) and re.fullmatch(r'[0-9a-f]{64}', v) for v in payload.values())
            and manifest.get('innerSha256') == payload.get('builder.exe'),
            'Windows bootstrap payload evidence invalid')
    loader = manifest.get('bootstrapLoader')
    require(isinstance(loader, dict), 'Windows bootstrap loader missing')
    require(loader.get('status') == 'exact-nonresource-sections'
            and loader.get('supplierStubSha256') == lock['stubSha256']
            and loader.get('supplierArchiveSha256') == lock['sha256']
            and loader.get('imports') == sorted(['advapi32.dll', 'shell32.dll', 'ole32.dll',
                'comctl32.dll', 'user32.dll', 'gdi32.dll', 'kernel32.dll'])
            and isinstance(loader.get('sections'), list) and bool(loader['sections']),
            'Windows bootstrap supplier evidence invalid')


def verify_downloads(root, source_sha):
    require(re.fullmatch(r'[0-9a-f]{64}', source_sha), 'Invalid source identity')
    result = []
    for folder, suffix, name, system, smoke in [
        ('windows', 'windows-x86_64', NAMES[0], 'Windows', 'qwindows-passed'),
        ('linux', 'linux-x86_64', NAMES[1], 'Linux', 'xcb-display-passed'),
        ('macos', 'macos', NAMES[2], 'Darwin', None)]:
        path = root / folder
        m = build.read_manifest(path / f'OpenAutoLink-Builder-{suffix}.json')
        require(m.get('builderSourceSha256') == source_sha and m.get('system') == system
                and m['artifact'] == name, 'Artifact source, name or platform mismatch')
        if folder != 'macos':
            require(m.get('kind') == 'direct-executable' and m.get('architecture') == 'x86_64'
                    and m.get('nativeCoverage') == 'exact-payload-evidence-verified'
                    and m.get('frozenSmokeTest') == 'passed' and m.get('nativePlatformSmoke') == smoke,
                    'Direct executable native gate missing')
            if folder == 'windows':
                verify_windows_bootstrap(m)
        else:
            require(m.get('kind') == 'dual-runtime-dmg' and m.get('architecture') == 'dual-runtime-arm64-x86_64'
                    and m.get('frozenSmokeTest') == 'passed-on-arm64-and-x86_64', 'Combined Mac gate missing')
            reports, stages = m.get('nativeSmokeTests', {}), m.get('runtimeStages', {})
            require(set(reports) == set(stages) == {'arm64', 'x86_64'}, 'Both Mac architectures required')
            for arch in ('arm64', 'x86_64'):
                r, s = reports[arch], stages[arch]
                png = f'smoke-macos-{arch}.png'
                require(r.get('architecture') == arch and r.get('builderSourceSha256') == source_sha
                        and r.get('packageSha256') == m['sha256'] and r.get('frozenSmokeTest') == 'passed'
                        and r.get('nativePlatformSmoke') == 'cocoa-passed'
                        and r.get('scope') == 'combined-dmg-launcher-native-runtime-and-ui'
                        and r.get('artifact') == png and r.get('sha256') == m['files'].get(png)
                        and r.get('files') == {png: m['files'].get(png)}, 'Mac smoke report mismatch')
                require(s.get('architecture') == arch and s.get('system') == 'Darwin'
                        and s.get('builderSourceSha256') == source_sha and s.get('kind') == 'internal-runtime-stage'
                        and s.get('nativeCoverage') == 'exact-payload-evidence-verified'
                        and s.get('frozenSmokeTest') == 'passed', 'Mac runtime coverage missing')
        result.append(VerifiedDownload(path / name, m['sha256'], (path / name).stat().st_size))
    return result


def release_target(api, event, repository, commit):
    require(re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository), 'Invalid repository')
    require(re.fullmatch(r'[0-9a-f]{40}', commit), 'Invalid immutable commit')
    require(event.get('action') == 'published' and event.get('repository', {}).get('full_name') == repository
            and event['repository'].get('private') is False, 'Not a public release publication event')
    expected = event.get('release', {})
    release_id, tag = expected.get('id'), expected.get('tag_name')
    require(type(release_id) is int and release_id > 0 and isinstance(tag, str)
            and re.fullmatch(r'v[0-9]+\.[0-9]+\.[0-9]+', tag), 'Invalid intended release')
    for release in (expected, api.get(f'releases/{release_id}'), api.get('releases/tags/' + tag)):
        require(release.get('id') == release_id and release.get('tag_name') == tag
                and release.get('draft') is False and release.get('prerelease') is False,
                'Release identity or publication status changed')
    obj = api.get('git/ref/tags/' + tag)['object']
    for _ in range(5):
        require(re.fullmatch(r'[0-9a-f]{40}', obj.get('sha', '')), 'Invalid tag object')
        if obj.get('type') == 'commit':
            require(obj['sha'] == commit, 'Release tag moved or wrong checkout')
            return release_id
        require(obj.get('type') == 'tag', 'Invalid tag target')
        obj = api.get('git/tags/' + obj['sha'])['object']
    raise ValueError('Tag nesting limit exceeded')


def list_assets(api, release_id):
    result = []
    for page in range(1, 21):
        batch = api.get(f'releases/{release_id}/assets?per_page=100&page={page}')
        require(isinstance(batch, list), 'Invalid assets response')
        result.extend(batch)
        if len(batch) < 100:
            return result
    raise ValueError('Asset pagination limit exceeded')


def publish(api, event, repository, commit, assets):
    require(tuple(a.path.name for a in assets) == NAMES, 'Only the exact three downloads may be published')
    release_id = release_target(api, event, repository, commit)
    require(not any(a.get('name') in NAMES for a in list_assets(api, release_id)),
            'Desktop assets already exist; refusing overwrite or ambiguous partial rerun')
    hashes = {a.path.name: (a.sha256, a.size) for a in assets}
    for asset in assets:
        require(not asset.path.is_symlink() and (build.sha256(asset.path), asset.path.stat().st_size)
                == (asset.sha256, asset.size), 'Payload changed after verification')
    for asset in assets:
        path = asset.path
        release_target(api, event, repository, commit)
        require(not path.is_symlink() and (build.sha256(path), path.stat().st_size) == hashes[path.name],
                'Payload changed during publication')
        api.upload(release_id, path)
    release_target(api, event, repository, commit)
    remote = list_assets(api, release_id)
    for name, (sha, size) in hashes.items():
        matches = [a for a in remote if a.get('name') == name]
        require(len(matches) == 1, 'Uploaded asset missing or duplicated')
        asset = matches[0]
        require(asset.get('size') == size and asset.get('state') == 'uploaded', 'Uploaded asset incomplete')
        data = api.download(asset)
        require(len(data) == size and hashlib.sha256(data).hexdigest() == sha, 'Uploaded asset readback mismatch')
    release_target(api, event, repository, commit)


class GitHub:
    def __init__(self, repository, token):
        require(re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository), 'Invalid repository')
        self.repository, self.token = repository, token

    def request(self, url, data=None, content_type='application/vnd.github+json'):
        req = urllib.request.Request(url, data=data, headers={
            'Authorization': 'Bearer ' + self.token, 'Accept': 'application/vnd.github+json',
            'Content-Type': content_type, 'X-GitHub-Api-Version': '2022-11-28'})
        with urllib.request.urlopen(req, timeout=300) as response:
            return json.load(response)

    def get(self, path):
        return self.request(f'https://api.github.com/repos/{self.repository}/{path}')

    def upload(self, release_id, path):
        return self.request(f'https://uploads.github.com/repos/{self.repository}/releases/{release_id}/assets?'
                            + urllib.parse.urlencode({'name': path.name}), path.read_bytes(), 'application/octet-stream')

    def download(self, asset):
        # Public releases only. Do not forward API authorization across redirects.
        url = asset.get('browser_download_url', '')
        parsed = urllib.parse.urlsplit(url)
        require(parsed.scheme == 'https' and parsed.netloc == 'github.com'
                and parsed.path.startswith(f'/{self.repository}/releases/download/'), 'Invalid public download URL')
        size = asset['size']
        require(type(size) is int and 0 < size <= 2 * 1024**3, 'Invalid public asset size')
        with urllib.request.urlopen(url, timeout=300) as response:
            return response.read(size + 1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--downloads', required=True, type=Path)
    parser.add_argument('--verify-only', action='store_true', help='Offline native-report/byte checks; never publishes')
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='desktop-release-verify-') as temporary:
        source = Path(temporary) / 'source.zip'
        build.source_archive(source)
        assets = verify_downloads(args.downloads, build.sha256(source))
    if args.verify_only:
        print('Verified three exact downloads against this source snapshot (no publication)')
        return
    require(os.environ.get('GITHUB_EVENT_NAME') == 'release', 'Publication is release-event-only')
    event = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text())
    commit = os.environ['GITHUB_SHA']
    checkout = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=build.REPOSITORY, text=True).strip()
    require(checkout == commit, 'Checkout is not the immutable event commit')
    publish(GitHub(os.environ['GITHUB_REPOSITORY'], os.environ['GH_TOKEN']), event,
            os.environ['GITHUB_REPOSITORY'], commit, assets)
    print('Verified three exact desktop assets on the intended release')


if __name__ == '__main__':
    main()
