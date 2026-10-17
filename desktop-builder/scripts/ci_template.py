"""CI-only source snapshot and explicitly LOCAL template metadata adapter.

Only release/descriptor metadata is supplied locally. Builder, Toolchain,
Downloader cache verification, Java, protobuf transformation, signing and bundle
verification remain production code. Never import this adapter in the product.
"""
from __future__ import annotations

from contextlib import contextmanager
from copy import deepcopy
import hashlib
import json
from pathlib import Path
import re
import shutil
import threading

from oal_builder import engine, releases as r
from oal_builder.toolchain import BuilderError, Downloader


SELECTION = 'latest-stable-at-workflow-start'


# Explicit vocabulary, not a prefix/denylist that could echo arbitrary text.
_FAILURE_CODES = frozenset({
    'ci-template-source-contract-unsupported',
    'ci-template-aab-missing',
    'ci-template-source-mismatch',
    'ci-template-affinity-source-mismatch',
    'ci-template-input-invalid',
    'ci-template-descriptor-invalid',
    'ci-template-producer-time-budget-exceeded',
    'ci-expected-release-requires-published-mode',
    'ci-template-producer-path-not-fresh',
    'ci-template-linux-license-consent-required',
    'ci-source-snapshot-invalid',
    'ci-expected-published-release-mismatch',
})
_KNOWN_ERRORS = {
    'Download checksum mismatch': 'ci-template-download-checksum-mismatch',
    'Native-deps release does not match source aasdk gitlink': 'ci-template-native-source-mismatch',
    'Build cancelled': 'ci-template-cancelled',
    'Download failed; check connectivity or GitHub API rate limit and retry': 'ci-template-network-download-failed',
    'Incomplete download': 'ci-template-download-incomplete',
}
_TOOL_CATEGORIES = {
    'Dependency verification failed': 'ci-template-dependency-verification-failed',
    'Disk space exhausted': 'ci-template-disk-space-exhausted',
    'Network download failed': 'ci-template-network-download-failed',
    'Compiler failure': 'ci-template-compiler-failed',
}


def failure_details(exc):
    """Project fixed engine summaries to codes; never serialize raw errors.

    The engine already discards subprocess text. Recognize only its bounded
    summary prefix and fixed labels, and never inspect chained exceptions.
    """
    if isinstance(exc, r.TemplateUnavailable):
        return {'codes': ['ci-latest-published-template-unavailable']}
    if isinstance(exc, BuilderError) and len(exc.args) == 1 and isinstance(exc.args[0], str):
        text = exc.args[0]
        if len(text) > engine.MAX_DIAGNOSTIC_CHARS:
            return {'codes': ['ci-template-unclassified-failure']}
        if text in _FAILURE_CODES:
            return {'codes': [text]}
        if text in _KNOWN_ERRORS:
            return {'codes': [_KNOWN_ERRORS[text]]}
        for phase in ('build', 'template'):
            if text == phase + ': managed tool could not start (on Apple Silicon, legacy SDK tools may require Rosetta)':
                return {'codes': ['ci-template-tool-start-failed']}
            if text == phase + ': tool output did not close after exit; incomplete output discarded. Check for leftover tools before retrying.':
                return {'codes': ['ci-template-tool-output-incomplete']}
        match = re.match(r'^(?:build|tool): tool failed with exit code (-?[0-9]{1,10})\. ', text)
        if match:
            codes = [code for label, code in _TOOL_CATEGORIES.items()
                     if label + '. Recovery: ' in text]
            return dict(codes=codes or ['ci-template-tool-exit-unclassified'],
                        toolExitCode=int(match[1]))
    return {'codes': ['ci-template-unclassified-failure']}


def resolve_snapshot(fetch_json):
    return {'schema': 1, 'selection': SELECTION,
            'release': r.resolve_latest_release(fetch_json)}


def validate_release(release):
    try:
        if not isinstance(release, dict) or set(release) != {
                'sourceRef', 'sourceCommit', 'versionName', 'templateAvailable', 'assets'}:
            raise ValueError()
        tag = r.validate_tag(release['sourceRef'])
        r.validate_commit(release['sourceCommit'])
        assets = r.validate_assets(list(release['assets'].values()), tag)
        available = r.DESCRIPTOR_NAME in assets and r.template_name(tag) in assets
        if (release['versionName'] != tag[1:] or type(release['templateAvailable']) is not bool
                or release['templateAvailable'] != available or assets != release['assets']):
            raise ValueError()
    except (ValueError, TypeError, KeyError, AttributeError, BuilderError):
        raise BuilderError('ci-source-snapshot-invalid') from None
    return deepcopy(release)


def load_snapshot(path):
    path = Path(path)
    try:
        if path.is_symlink() or not path.is_file() or path.stat().st_size > 65536:
            raise ValueError()
        snapshot = json.loads(path.read_text('utf-8'), object_pairs_hook=r._unique_object)
        if (not isinstance(snapshot, dict) or set(snapshot) != {'schema', 'selection', 'release'}
                or type(snapshot['schema']) is not int or snapshot['schema'] != 1
                or snapshot['selection'] != SELECTION):
            raise ValueError()
        return validate_release(snapshot['release'])
    except (OSError, ValueError, TypeError, KeyError, RecursionError):
        raise BuilderError('ci-source-snapshot-invalid') from None


@contextmanager
def bound_inputs(release, descriptor_bytes=None):
    """Single-threaded CLI scope; snapshot is immutable across build operations.

    The local descriptor still traverses the real resolver/contract validator.
    No build result, download, tool invocation or verifier is substituted.
    """
    selected = validate_release(release)
    original_release, original_template = engine.resolve_latest_release, engine.resolve_template
    def resolved(_fetch):
        return deepcopy(selected)
    def template(actual, _fetch):
        if actual != selected:
            raise BuilderError('ci-source-snapshot-mismatch')
        return original_template(actual, lambda _url: descriptor_bytes)
    try:
        engine.resolve_latest_release = resolved
        if descriptor_bytes is not None:
            engine.resolve_template = template
        yield
    finally:
        engine.resolve_latest_release, engine.resolve_template = original_release, original_template


def local_metadata(release, template, descriptor_path):
    """Build LOCAL asset records, not evidence of a published/downloaded AAB."""
    selected = validate_release(release)
    descriptor_path = Path(descriptor_path)
    if descriptor_path.is_symlink() or descriptor_path.stat().st_size > r.MAX_DESCRIPTOR_SIZE:
        raise BuilderError('ci-template-descriptor-invalid')
    raw = descriptor_path.read_bytes()
    descriptor = json.loads(raw, object_pairs_hook=r._unique_object)
    r.verify_template_download(template, descriptor)
    selected['assets'] = {}
    for name, size, digest in (
            (r.DESCRIPTOR_NAME, len(raw), hashlib.sha256(raw).hexdigest()),
            (descriptor['templateAsset']['name'], descriptor['templateAsset']['size'],
             descriptor['templateAsset']['sha256'])):
        selected['assets'][name] = dict(name=name, size=size, digest='sha256:' + digest,
                                       browser_download_url=r.asset_url(selected['sourceRef'], name))
    selected['templateAvailable'] = True
    r.resolve_template(selected, lambda _url: raw)
    return selected, raw


def seed_template(workspace, template, descriptor):
    r.verify_template_download(template, descriptor)
    # Claim a new workspace before adding cache files; an unmarked nonempty
    # directory is correctly rejected by the production Builder.
    with engine.workspace_lock(workspace) as managed:
        cache = managed / 'cache/downloads'
        if cache.is_symlink():
            raise BuilderError('ci-template-cache-invalid')
        cache.mkdir(parents=True, exist_ok=True)
        target = cache / (descriptor['templateAsset']['sha256'] + '.archive')
        if target.is_symlink():
            raise BuilderError('ci-template-cache-invalid')
        shutil.copyfile(template, target)
        r.verify_template_download(target, descriptor)


def payload_digest(bundle):
    """Digest exact DEX/native members; no code bytes/paths enter reports."""
    import zipfile
    from oal_builder.personalize import _validate_zip
    records = []
    with zipfile.ZipFile(bundle) as archive:
        for info in _validate_zip(archive):
            if info.filename.startswith(('base/dex/', 'base/lib/')) and not info.is_dir():
                with archive.open(info) as stream:
                    records.append((info.filename, info.file_size, hashlib.file_digest(stream, 'sha256').hexdigest()))
    return hashlib.sha256(json.dumps(sorted(records), separators=(',', ':')).encode()).hexdigest()


def create_template(unsigned, result, release, destination, *, tools, run):
    """Transform the SAME source build's unsigned output, never rebuild Gradle.

    This is a CI-local intentional template, not release publication proof.
    The production transform fails closed if this source cannot be personalized.
    The production publication CLI then validates the generated contract in a
    clean interpreter. Neither operation accesses a signing identity.
    """
    import sys
    import zipfile
    from oal_builder.personalize import _signature, _validate_zip, personalize_bundle
    selected = validate_release(release)
    if any(result.get(k) != selected[k] for k in ('sourceRef', 'sourceCommit', 'versionName')):
        raise BuilderError('ci-template-source-mismatch')
    unsigned, destination = Path(unsigned), Path(destination)
    def member_hashes(archive):
        records = {}
        for info in _validate_zip(archive):
            if not info.is_dir() and not _signature(info.filename):
                with archive.open(info) as stream:
                    records[info.filename] = (info.file_size, hashlib.file_digest(stream, 'sha256').hexdigest())
        return records
    with zipfile.ZipFile(unsigned) as archive:
        if any(_signature(i.filename) for i in _validate_zip(archive)):
            raise BuilderError('ci-template-source-not-unsigned')
        unsigned_members = member_hashes(archive)
    # The engine signed a copy; bind all original members, not just DEX/native.
    with zipfile.ZipFile(result['artifact']) as archive:
        signed_members = member_hashes(archive)
    if unsigned_members != signed_members:
        raise BuilderError('ci-template-source-artifact-mismatch')
    destination.mkdir(parents=True, exist_ok=True)
    template = destination / r.template_name(selected['sourceRef'])
    java = Path(tools['java'])
    javac = tools.get('javac', str(java.with_name('javac.exe' if java.suffix == '.exe' else 'javac')))
    personalize_bundle(unsigned, template, original_app_id=result['applicationId'],
                       app_id=r.TEMPLATE_APPLICATION_ID, version_code=result['versionCode'],
                       java=java, javac=javac, bundletool=tools['bundletool'],
                       workdir=destination / 'transform', run=run)
    if payload_digest(unsigned) != payload_digest(template):
        raise BuilderError('ci-template-payload-mismatch')
    run([sys.executable, '-I', '-S', str(Path(__file__).with_name('publish_template.py')),
         '--aab', str(template), '--tag', selected['sourceRef'], '--commit', selected['sourceCommit'],
         '--version-code', str(result['versionCode']), '--output-dir', str(destination),
         '--java', str(java), '--javac', str(javac), '--bundletool', str(tools['bundletool'])], 'template')
    descriptor_path = destination / r.DESCRIPTOR_NAME
    selected, raw = local_metadata(selected, template, descriptor_path)
    return template, r.resolve_template(selected, lambda _: raw)


def prepare_template_affinity(source, release):
    """One reviewed CI-template-only fix; never widen the historical overlays.

    The released 528 manifest hardcodes the upstream cluster affinity even
    under -PappId. Its real unsigned template is rejected by the unchanged
    protobuf verifier. Apply the exact upstream placeholder edit already in
    the reviewed builder checkout, bound to tag, commit and entire file bytes.
    Other releases get no overlay and must pass publication on their own.
    """
    if (release['sourceRef'], release['sourceCommit']) != (
            'v0.1.528', '09c75422b5f0529b7b521c628eefffe9a75273d7'):
        return []
    manifest = Path(source) / 'app/src/main/AndroidManifest.xml'
    if manifest.is_symlink() or not manifest.is_file() or manifest.stat().st_size != 9900:
        raise BuilderError('ci-template-affinity-source-mismatch')
    raw = manifest.read_bytes()
    if hashlib.sha256(raw).hexdigest() != '93bdf196c22b08f84447dd6ca6010c215a4fc93060743b1322e2c048243d29aa':
        raise BuilderError('ci-template-affinity-source-mismatch')
    fixed = raw.replace(b'android:taskAffinity="com.openautolink.app.templates"',
                        b'android:taskAffinity="${applicationId}.templates"')
    if hashlib.sha256(fixed).hexdigest() != 'f05bad307b077980aa32cc6709fcd99d73f123abf5c6ce0b2314d71b9e5e4a49':
        raise BuilderError('ci-template-affinity-source-mismatch')
    manifest.write_bytes(fixed)
    return ['ci-template-v0.1.528-cluster-affinity']


def compile_template(workspace, release, destination, *, accept_ci_android_licenses=False,
                     diagnostic=None):
    """Maintainer CI producer only: one unsigned Linux build, no user identity.

    This intentionally does not call Builder.build or its community source mode.
    Strict dependency pins and immutable source preparation remain unchanged.
    """
    import os
    import platform
    import sys
    from oal_builder.toolchain import Toolchain
    diagnostic = {} if diagnostic is None else diagnostic
    diagnostic['phase'] = 'compile-preflight'
    selected = validate_release(release)
    if (os.environ.get('GITHUB_ACTIONS') != 'true' or platform.system() != 'Linux'
            or accept_ci_android_licenses is not True):
        raise BuilderError('ci-template-linux-license-consent-required')
    workspace, destination = Path(workspace), Path(destination)
    if workspace.exists() or destination.exists():
        raise BuilderError('ci-template-producer-path-not-fresh')
    cancel = threading.Event()
    timer = threading.Timer(9000, cancel.set)
    timer.daemon = True
    def progress(phase, _message):
        if phase in {'tools', 'source', 'download', 'build', 'template'}:
            print('Template producer: ' + phase, flush=True)
    timer.start()
    try:
        with engine.workspace_lock(workspace) as managed:
            diagnostic['phase'] = 'tools'
            manager = Toolchain(managed, progress, cancel)
            tools = manager.install(True)
            diagnostic['phase'] = 'source'
            actual = manager.prepare_source(selected)
            diagnostic['phase'] = 'source-contract'
            if any(actual.get(k) != selected[k] for k in ('sourceRef', 'sourceCommit', 'versionName')):
                raise BuilderError('ci-template-source-mismatch')
            source = managed / 'source'
            preparation = validate_source_preparation(
                actual.get('sourceOverlays', []), selected, 'local-linux-template')
            preparation += prepare_template_affinity(source, selected)
            preparation = validate_source_preparation(preparation, selected, 'local-linux-template')
            if preparation:
                diagnostic['sourcePreparation'] = preparation
                print('Template producer: ' + ','.join(preparation), flush=True)
            properties = (source / 'gradle/wrapper/gradle-wrapper.properties').read_text()
            script = (source / 'app/build.gradle.kts').read_text()
            if (not re.search(r'^distributionSha256Sum=[0-9a-f]{64}\s*$', properties, re.M)
                    or not (source / 'gradle/verification-metadata.xml').is_file()
                    or any(k not in script for k in ('oalBuilderUnsigned', 'oalVersionCode', 'oalVersionName', 'appId'))):
                raise BuilderError('ci-template-source-contract-unsupported')
            wrapper = source / 'gradlew'
            wrapper.chmod(0o755)
            # No personal keystore/authentication files are introduced into this source.
            argv = [str(wrapper), ':app:bundleRelease', '--no-daemon', '--no-build-cache',
                    '--no-configuration-cache', '--max-workers=2', '--dependency-verification=strict',
                    '-Dorg.gradle.parallel=false', '-Dorg.gradle.caching=false',
                    '-Dorg.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8',
                    '-Pkotlin.compiler.execution.strategy=in-process', '-PoalBuilderUnsigned=true',
                    '-PappId=' + r.TEMPLATE_APPLICATION_ID, '-PoalVersionCode=1',
                    '-PoalVersionName=' + selected['versionName']]
            diagnostic['phase'] = 'build'
            engine.run_process(argv, source, tools['env'], cancel, progress, 'build')
            diagnostic['phase'] = 'template-copy'
            unsigned = source / 'app/build/outputs/bundle/release/app-release.aab'
            if not unsigned.is_file():
                raise BuilderError('ci-template-aab-missing')
            destination.mkdir(parents=True)
            template = destination / r.template_name(selected['sourceRef'])
            shutil.copyfile(unsigned, template)
            # The clean publication CLI verifies credential absence, manifest,
            # protobuf resource IDs and the actual personalization contract.
            java = Path(tools['java'])
            diagnostic['phase'] = 'template-verify'
            engine.run_process([
                sys.executable, '-I', '-S', str(Path(__file__).with_name('publish_template.py')),
                '--aab', str(template), '--tag', selected['sourceRef'], '--commit', selected['sourceCommit'],
                '--version-code', '1', '--output-dir', str(destination), '--java', str(java),
                '--javac', str(java.with_name('javac')), '--bundletool', str(tools['bundletool'])],
                managed, tools['env'], cancel, progress, 'template')
            if cancel.is_set():
                raise BuilderError('ci-template-producer-time-budget-exceeded')
            return template, destination / r.DESCRIPTOR_NAME
    finally:
        timer.cancel()


def _bounded_json(path):
    path = Path(path)
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 65536:
        raise BuilderError('ci-template-input-invalid')
    return json.loads(path.read_bytes(), object_pairs_hook=r._unique_object)


def validate_source_preparation(preparation, release, provenance):
    """Bound effective CI source edits to their reviewed immutable release.

    These IDs disclose source transformations, not changes to the tag itself.
    Published descriptor schema 1 carries no overlay contract: do not infer a
    local CI repair merely because a published template has the same tag.
    """
    from oal_builder.native_verification import SOURCE_REF, SOURCE_COMMIT, OVERLAY_ID
    allowed = ({'ci-template-v0.1.528-cluster-affinity'}
               if (release['sourceRef'], release['sourceCommit']) == (
                   'v0.1.528', '09c75422b5f0529b7b521c628eefffe9a75273d7') else set())
    if (release['sourceRef'], release['sourceCommit']) == (SOURCE_REF, SOURCE_COMMIT):
        allowed = {'personal-cluster-task-affinity', OVERLAY_ID}
    if provenance == 'published-release-template':
        allowed = set()
    if (provenance not in {'local-linux-template', 'published-release-template'}
            or not isinstance(preparation, (list, tuple))
            or len(preparation) > len(allowed)
            or any(not isinstance(value, str) or value not in allowed for value in preparation)
            or len(set(preparation)) != len(preparation)):
        raise BuilderError('ci-template-input-invalid')
    return list(preparation)


def load_inputs(directory):
    """Revalidate the exact shared AAB/descriptor/source on every native host."""
    directory = Path(directory)
    try:
        if directory.is_symlink():
            raise ValueError()
        release = load_snapshot(directory / 'release.json')
        provenance = _bounded_json(directory / 'provenance.json')
        if (set(provenance) != {'schema', 'kind', 'descriptorSha256', 'sourcePreparation'}
                or type(provenance['schema']) is not int or provenance['schema'] != 2
                or provenance['kind'] not in {'local-linux-template', 'published-release-template'}):
            raise ValueError()
        preparation = validate_source_preparation(
            provenance['sourcePreparation'], release, provenance['kind'])
        descriptor_path = directory / r.DESCRIPTOR_NAME
        _bounded_json(descriptor_path)
        raw = descriptor_path.read_bytes()
        if hashlib.sha256(raw).hexdigest() != provenance['descriptorSha256']:
            raise ValueError()
        template = directory / 'template.aab'
        if provenance['kind'] == 'local-linux-template':
            selected, raw = local_metadata(release, template, descriptor_path)
        else:
            selected = release
        descriptor = r.resolve_template(selected, lambda _: raw)
        r.verify_template_download(template, descriptor)
        return dict(release=release, selected=selected, raw=raw, descriptor=descriptor,
                    template=template, provenance=provenance['kind'],
                    sourcePreparation=preparation)
    except (OSError, ValueError, TypeError, KeyError, RecursionError, BuilderError):
        raise BuilderError('ci-template-input-invalid') from None


def export_inputs(release, template, descriptor_path, destination, *, provenance,
                  source_preparation=()):
    """Export only credential-free public inputs; native jobs recheck all bytes."""
    release = validate_release(release)
    preparation = validate_source_preparation(source_preparation, release, provenance)
    destination = Path(destination)
    destination.mkdir(parents=True, exist_ok=False)
    shutil.copyfile(template, destination / 'template.aab')
    shutil.copyfile(descriptor_path, destination / r.DESCRIPTOR_NAME)
    engine.atomic_json(destination / 'release.json',
                       dict(schema=1, selection=SELECTION, release=release))
    engine.atomic_json(destination / 'provenance.json', dict(
        schema=2, kind=provenance, sourcePreparation=preparation,
        descriptorSha256=hashlib.sha256((destination / r.DESCRIPTOR_NAME).read_bytes()).hexdigest()))
    return load_inputs(destination)


def main(argv=None):
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--cache', type=Path, required=True)
    parser.add_argument('--workspace', type=Path, required=True)
    parser.add_argument('--template-mode', choices=['local', 'published'], required=True)
    parser.add_argument('--accept-ci-android-licenses', action='store_true')
    parser.add_argument('--expected-release-tag')
    parser.add_argument('--expected-source-commit')
    parser.add_argument('--failure-report', type=Path)
    args = parser.parse_args(argv)
    diagnostic = dict(schema=1, status='failed', templateMode=args.template_mode,
                      phase='preflight', source=None)
    try:
        if args.output.exists() or args.workspace.exists():
            raise BuilderError('ci-template-producer-path-not-fresh')
        expected = None
        if args.expected_release_tag is not None or args.expected_source_commit is not None:
            # A publication caller supplies BOTH immutable values. Never fall
            # back to another release, or reinterpret this as local integration.
            expected = (r.validate_tag(args.expected_release_tag),
                        r.validate_commit(args.expected_source_commit))
            if args.template_mode != 'published':
                raise BuilderError('ci-expected-release-requires-published-mode')
        downloader = Downloader(args.cache, lambda *_: None, threading.Event())
        diagnostic['phase'] = 'resolve'
        snapshot = resolve_snapshot(downloader.json)
        selected = validate_release(snapshot['release'])
        diagnostic['source'] = {k: selected[k] for k in ('sourceRef', 'sourceCommit', 'versionName')}
        if expected is not None and expected != (selected['sourceRef'], selected['sourceCommit']):
            raise BuilderError('ci-expected-published-release-mismatch')
        staging = args.workspace.with_name(args.workspace.name + '-template')
        if args.template_mode == 'published':
            diagnostic['phase'] = 'published-download'
            # Missing/newly incomplete publication fails, never an older tag or
            # automatic source compilation. Keep actual release asset records.
            raw = downloader.metadata(r.asset_url(selected['sourceRef'], r.DESCRIPTOR_NAME),
                                      max_bytes=r.MAX_DESCRIPTOR_SIZE) if selected['templateAvailable'] else None
            descriptor = r.resolve_template(selected, lambda _: raw)
            item = descriptor['templateAsset']
            template = downloader.download(descriptor['url'], item['sha256'], size=item['size'])
            r.verify_template_download(template, descriptor)
            staging.mkdir(parents=True, exist_ok=False)
            descriptor_path = staging / r.DESCRIPTOR_NAME
            descriptor_path.write_bytes(raw)
            provenance = 'published-release-template'
        else:
            diagnostic['phase'] = 'compile'
            template, descriptor_path = compile_template(args.workspace, selected, staging,
                accept_ci_android_licenses=args.accept_ci_android_licenses, diagnostic=diagnostic)
            provenance = 'local-linux-template'
        diagnostic['phase'] = 'export'
        export_inputs(selected, template, descriptor_path, args.output, provenance=provenance,
                      source_preparation=diagnostic.get('sourcePreparation', []))
    except Exception as exc:
        diagnostic.update(failure_details(exc))
        if args.failure_report is not None:
            try:
                engine.atomic_json(args.failure_report, diagnostic)
            except Exception:
                print('ci-template-failure-report-write-failed', flush=True)
        print(diagnostic['phase'] + ': ' + ','.join(diagnostic['codes']), flush=True)
        return 1
    print('Exact latest template inputs exported (' + provenance + '); native qualification still required.', flush=True)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
