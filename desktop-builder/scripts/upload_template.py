#!/usr/bin/env python3
"""Attach only a verified template, checksum and descriptor to an existing release.

Never creates releases, overwrites/deletes assets, dispatches workflows or signs.
The producer performs structural AAB qualification; this transaction binds bytes.
"""
from __future__ import annotations

import hashlib
import json
import argparse
import os
import re
import sys
import urllib.parse
import urllib.request
from pathlib import Path

DESCRIPTOR = 'openautolink-personalization.json'


def require(condition, message):
    if not condition:
        raise ValueError(message)


def release_target(api, *, release_id, tag, commit):
    require(type(release_id) is int and release_id > 0, 'Invalid release ID')
    require(isinstance(tag, str) and re.fullmatch(r'v0\.1\.[1-9][0-9]{0,9}', tag)
            and int(tag.split('.')[-1]) <= 2100000000, 'Invalid release tag/version')
    require(isinstance(commit, str) and re.fullmatch(r'[0-9a-f]{40}', commit), 'Invalid release commit')
    for path in (f'releases/{release_id}', 'releases/tags/' + tag):
        release = api.get(path)
        require(release.get('id') == release_id and release.get('tag_name') == tag
                and release.get('draft') is False and release.get('prerelease') is False,
                'Release ID/tag/publication status mismatch')
    ref = api.get('git/ref/tags/' + tag)
    require(ref.get('ref') == 'refs/tags/' + tag, 'Tag ref mismatch')
    obj = ref.get('object', {})
    seen = set()
    for depth in range(9):
        sha = obj.get('sha', '')
        require(isinstance(sha, str) and re.fullmatch(r'[0-9a-f]{40}', sha), 'Invalid tag object')
        if obj.get('type') == 'commit':
            require(sha == commit, 'Tag moved or wrong checkout')
            return
        require(obj.get('type') == 'tag' and sha not in seen and depth < 8, 'Invalid tag chain')
        seen.add(sha)
        annotated = api.get('git/tags/' + sha)
        require(annotated.get('sha') == sha and (depth != 0 or annotated.get('tag') == tag), 'Annotated tag mismatch')
        obj = annotated.get('object', {})


def list_assets(api, release_id):
    result = {}
    for page in range(1, 21):
        batch = api.get(f'releases/{release_id}/assets?per_page=100&page={page}')
        require(isinstance(batch, list), 'Invalid asset listing')
        for asset in batch:
            name = asset['name']
            require(name not in result, 'Duplicate release asset')
            result[name] = asset
        if len(batch) < 100:
            return result
    raise ValueError('Asset pagination limit exceeded')


def snapshot_apks(api, assets):
    return {name: (asset['id'], asset['size'], asset.get('digest'), api.fingerprint(asset))
            for name, asset in assets.items() if name.casefold().endswith('.apk')}


def fingerprint(path):
    require(not path.is_symlink() and path.is_file(), 'Payload must be regular files')
    with path.open('rb') as stream:
        return path.stat().st_size, hashlib.file_digest(stream, 'sha256').hexdigest()


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, 'Duplicate descriptor field')
        result[key] = value
    return result


def verify_payload(directory, tag, commit):
    names = [f'openautolink-template-{tag}.aab', f'openautolink-template-{tag}.aab.sha256', DESCRIPTOR]
    directory = Path(directory)
    require(not directory.is_symlink() and {p.name for p in directory.iterdir()} == set(names),
            'Only exact template/checksum/descriptor files are allowed')
    paths = [directory / name for name in names]
    for path, limit in zip(paths, (512 * 1024**2, 1024, 65536)):
        require(not path.is_symlink() and path.is_file() and 0 < path.stat().st_size <= limit, 'Invalid payload file')
    expected = {p.name: fingerprint(p) for p in paths}
    size, sha = expected[names[0]]
    descriptor = json.loads(paths[2].read_bytes(), object_pairs_hook=unique_object)
    require(descriptor == {
        'schemaVersion': 1, 'formatVersion': 1, 'releaseTag': tag, 'sourceCommit': commit,
        'templateApplicationId': 'com.openautolink.template', 'transformContract': 'oal-identity-v1',
        'templateAsset': {'name': names[0], 'size': size, 'sha256': sha}}, 'Descriptor binding mismatch')
    require(type(descriptor['schemaVersion']) is int and type(descriptor['formatVersion']) is int
            and type(descriptor['templateAsset']['size']) is int, 'Invalid descriptor types')
    require(paths[1].read_bytes() == (sha + '  ' + names[0] + '\n').encode('ascii'), 'Checksum binding mismatch')
    return paths, expected


def verify_remote(api, asset, expected):
    require(asset.get('state') == 'uploaded' and asset.get('size') == expected[0]
            and type(asset.get('id')) is int and asset['id'] > 0
            and asset.get('digest') in (None, 'sha256:' + expected[1]), 'Existing asset conflict or incomplete upload')
    require(api.fingerprint(asset) == expected, 'Existing asset conflict/readback mismatch')


def publish(api, directory, *, release_id, tag, commit):
    target = dict(release_id=release_id, tag=tag, commit=commit)
    release_target(api, **target)
    paths, expected = verify_payload(directory, tag, commit)
    existing = list_assets(api, release_id)
    before = snapshot_apks(api, existing)
    ids = {name: asset['id'] for name, asset in existing.items() if name in expected}
    try:
        for name in expected:
            if name in existing:
                verify_remote(api, existing[name], expected[name])
        for path in paths:
            release_target(api, **target)
            require(fingerprint(path) == expected[path.name], 'Payload changed during publication')
            if path.name == DESCRIPTOR:
                # Descriptor is the completion marker. Recheck dependencies and
                # the independent APK set immediately before making it visible.
                current = list_assets(api, release_id)
                for previous in paths[:2]:
                    require(previous.name in current and current[previous.name]['id'] == ids[previous.name],
                            'Template asset ID changed')
                    verify_remote(api, current[previous.name], expected[previous.name])
                require(snapshot_apks(api, current) == before, 'Maintainer APK assets changed during publication')
            if path.name not in existing:
                uploaded = api.upload(release_id, path)
                ids[path.name] = uploaded['id']
            asset = list_assets(api, release_id)[path.name]
            require(asset['id'] == ids[path.name], 'Template asset ID changed')
            verify_remote(api, asset, expected[path.name])
        final = list_assets(api, release_id)
        for name in expected:
            require(name in final, 'Asset missing on final readback')
            require(final[name]['id'] == ids[name], 'Template asset ID changed')
            verify_remote(api, final[name], expected[name])
        release_target(api, **target)
    finally:
        # Even a lost response may have created a partial asset. Audit without
        # rolling back/deleting anything; a retry verifies bytes before resuming.
        after = snapshot_apks(api, list_assets(api, release_id))
        require(before == after, 'Maintainer APK assets changed during publication')


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError('Authenticated API redirect refused')


class PublicRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        parsed = urllib.parse.urlsplit(newurl)
        require(parsed.scheme == 'https' and parsed.netloc in {
            'github.com', 'release-assets.githubusercontent.com', 'objects.githubusercontent.com'},
            'Unsafe public asset redirect')
        # Build fresh: no Authorization, cookies or API headers cross origins.
        return urllib.request.Request(newurl)


class GitHub:
    def __init__(self, repository, token):
        require(isinstance(repository, str) and re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository),
                'Invalid repository')
        require(isinstance(token, str) and token and '\n' not in token and '\r' not in token, 'Missing/invalid GH_TOKEN')
        self.repository, self.token = repository, token

    def request(self, url, data=None):
        request = urllib.request.Request(url, data=data, headers={
            'Authorization': 'Bearer ' + self.token, 'Accept': 'application/vnd.github+json',
            'Content-Type': 'application/octet-stream' if data is not None else 'application/json',
            'X-GitHub-Api-Version': '2022-11-28'})
        with urllib.request.build_opener(NoRedirect()).open(request, timeout=300) as response:
            raw = response.read(4 * 1024**2 + 1)
        require(len(raw) <= 4 * 1024**2, 'API response exceeds limit')
        return json.loads(raw)

    def get(self, path):
        return self.request(f'https://api.github.com/repos/{self.repository}/{path}')

    def upload(self, release_id, path):
        require(type(release_id) is int and release_id > 0, 'Invalid release ID')
        require(path.name == DESCRIPTOR or re.fullmatch(r'openautolink-template-v0\.1\.[1-9][0-9]{0,9}\.aab(?:\.sha256)?', path.name),
                'Only template assets may be uploaded')
        require(not path.is_symlink() and path.is_file() and 0 < path.stat().st_size <= 512 * 1024**2, 'Invalid upload file')
        return self.request(f'https://uploads.github.com/repos/{self.repository}/releases/{release_id}/assets?'
                            + urllib.parse.urlencode({'name': path.name}), path.read_bytes())

    def fingerprint(self, asset):
        url = asset.get('browser_download_url', '')
        parsed = urllib.parse.urlsplit(url)
        prefix = f'/{self.repository}/releases/download/'
        require(parsed.scheme == 'https' and parsed.netloc == 'github.com'
                and parsed.path.startswith(prefix) and not parsed.query and not parsed.fragment
                and parsed.path[len(prefix):].count('/') == 1
                and parsed.path.rsplit('/', 1)[-1] == asset.get('name'), 'Invalid public asset URL')
        size = asset.get('size')
        require(type(size) is int and 0 < size <= 2 * 1024**3, 'Invalid asset size')
        digest, total = hashlib.sha256(), 0
        # Deliberately unauthenticated: public downloads must be public.
        with urllib.request.build_opener(PublicRedirect()).open(urllib.request.Request(url), timeout=300) as response:
            while True:
                block = response.read(min(1024**2, size - total + 1))
                if not block:
                    break
                total += len(block)
                require(total <= size, 'Asset download exceeds size')
                digest.update(block)
        require(total == size, 'Asset download size mismatch')
        return total, digest.hexdigest()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path)
    parser.add_argument('--release-id', type=int, required=True)
    parser.add_argument('--tag', required=True)
    parser.add_argument('--commit', required=True)
    parser.add_argument('--check-only', action='store_true', help='Read-only release tuple preflight')
    args = parser.parse_args(argv)
    try:
        api = GitHub(os.environ.get('GITHUB_REPOSITORY'), os.environ.get('GH_TOKEN'))
        target = dict(release_id=args.release_id, tag=args.tag, commit=args.commit)
        if args.check_only:
            release_target(api, **target)
        else:
            require(args.directory is not None, 'Payload directory required')
            publish(api, args.directory, **target)
        print('Release tuple verified' if args.check_only else 'Three template assets read back; maintainer APKs unchanged')
        return 0
    except (ValueError, OSError, KeyError, TypeError):
        # Never echo server bodies, request headers, URLs or token-bearing errors.
        print('Template publication refused: release/asset conflict, invalid payload or transport failure; no overwrite attempted', file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
