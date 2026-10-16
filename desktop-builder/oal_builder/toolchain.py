"""Managed native tools and authenticated release downloads (no host Git)."""

import hashlib
import json
import platform
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
import os
import re
import shutil
import stat
import tarfile
import zipfile
from pathlib import Path, PurePosixPath


class BuilderError(RuntimeError):
    """Actionable, credential-free failure presented to the desktop UI."""


def archive_path(name):
    if not name or '\x00' in name or '\\' in name or ':' in name or name.startswith('/') or '..' in PurePosixPath(name).parts:
        raise BuilderError('Unsafe archive path')
    return PurePosixPath(name)


def safe_extract(archive, destination, *, strip_root=False, allowed=None, allow_links=False):
    """Extract bounded regular members; tools may use contained relative links.

    Validate the complete member list before writing. Never traverse preexisting
    symlinks. No tar extraction APIs, ownership restoration, devices or FIFOs.
    """
    destination = Path(destination)
    destination.mkdir(parents=True, exist_ok=True)
    records = []
    is_zip = zipfile.is_zipfile(archive)
    with (zipfile.ZipFile(archive) if is_zip else tarfile.open(archive, 'r:*')) as container:
        members = container.infolist() if is_zip else container.getmembers()
        if len(members) > 300000:
            raise BuilderError('Oversized archive')
        roots = set()
        total = 0
        for member in members:
            # ZipInfo.filename has already normalized Windows backslashes.
            # Validate the original name, before host-dependent normalization
            # can conceal a forbidden archive path.
            name = member.orig_filename if is_zip else member.name
            path = archive_path(name)
            roots.add(path.parts[0])
            mode = (member.external_attr >> 16) if is_zip else member.mode
            directory = member.is_dir() if is_zip else member.isdir()
            link = stat.S_ISLNK(mode) if is_zip else member.issym() or member.islnk()
            if not is_zip and not (member.isfile() or directory or link):
                raise BuilderError('Unsupported archive member')
            if link and not allow_links:
                raise BuilderError('Links forbidden in archive')
            size = member.file_size if is_zip else member.size
            total += size
            if size > 4 * 1024**3 or total > 16 * 1024**3:
                raise BuilderError('Oversized archive')
            relative = Path(*path.parts[1:]) if strip_root else Path(*path.parts)
            if not relative.parts:
                if not directory:
                    raise BuilderError('Invalid archive root')
                continue
            if allowed and not allowed(relative.as_posix(), directory):
                raise BuilderError('Unexpected archive member')
            target = destination / relative
            for parent in [target, *target.parents]:
                if parent.is_symlink():
                    raise BuilderError('Symlink in archive destination')
                if parent == destination:
                    break
            records.append((member, target, mode, directory, link))
        if strip_root and len(roots) != 1:
            raise BuilderError('Archive must contain one root directory')
        links = []
        for member, target, mode, directory, link in records:
            if directory:
                target.mkdir(parents=True, exist_ok=True)
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            if link:
                value = container.read(member).decode('utf-8') if is_zip else member.linkname
                if not value or '\\' in value or ':' in value or value.startswith('/'):
                    raise BuilderError('Unsafe archive link')
                base = target.parent if is_zip or member.issym() else destination
                resolved = (base / value).resolve()
                if not resolved.is_relative_to(destination.resolve()):
                    raise BuilderError('Escaping archive link')
                links.append((target, resolved))
                continue
            stream = container.open(member) if is_zip else container.extractfile(member)
            with stream, target.open('wb') as output:
                shutil.copyfileobj(stream, output, 1024 * 1024)
            target.chmod(0o755 if mode & 0o111 else 0o644)
        # Copy links after regular files: works without Windows symlink privileges.
        pending = links
        while pending:
            unresolved = []
            for target, resolved in pending:
                if resolved.is_file():
                    shutil.copy2(resolved, target)
                else:
                    unresolved.append((target, resolved))
            if len(unresolved) == len(pending):
                raise BuilderError('Unresolved archive link')
            pending = unresolved


REPOSITORY = 'https://dl.google.com/android/repository/'
GITHUB = 'https://api.github.com/repos/mossyhub/openautolink'
BUNDLETOOL_SHA256 = 'a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29'
SDK_PACKAGES = ('platforms;android-36', 'build-tools;34.0.0', 'ndk;28.2.13676358', 'cmake;3.22.1')


def check_url(url):
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme != 'https' or parsed.username or parsed.password:
        raise BuilderError('Downloads require authenticated HTTPS')
    host = parsed.hostname or ''
    if host not in {'dl.google.com', 'api.adoptium.net', 'api.github.com', 'github.com', 'codeload.github.com', 'objects.githubusercontent.com', 'release-assets.githubusercontent.com'}:
        raise BuilderError('Untrusted download host')


def file_digest(path, algorithm='sha256'):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, algorithm).hexdigest()


class Downloader:
    def __init__(self, cache, progress, cancel_event):
        self.cache = Path(cache)
        self.cache.mkdir(parents=True, exist_ok=True)
        self.progress = progress
        self.cancel_event = cancel_event

    def check_cancel(self):
        if self.cancel_event.is_set():
            raise BuilderError('Build cancelled')

    def _open(self, url):
        check_url(url)
        self.check_cancel()
        request = urllib.request.Request(url, headers={'User-Agent': 'OpenAutoLink-Desktop-Builder/1', 'Accept': 'application/vnd.github+json' if url.startswith('https://api.github.com/') else '*/*'})
        try:
            response = urllib.request.urlopen(request, timeout=60)
            check_url(response.geturl())
            return response
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            # Never expose URLs containing credentials or response content.
            raise BuilderError('Download failed; check connectivity or GitHub API rate limit and retry') from exc

    def metadata(self, url):
        with self._open(url) as response:
            data = response.read(16 * 1024**2 + 1)
        if len(data) > 16 * 1024**2:
            raise BuilderError('Metadata response too large')
        return data

    def json(self, url):
        try:
            return json.loads(self.metadata(url))
        except (ValueError, UnicodeError) as exc:
            raise BuilderError('Invalid release metadata') from exc

    def download(self, url, digest=None, algorithm='sha256', size=None):
        check_url(url)
        self.check_cancel()
        if digest is not None and (algorithm not in {'sha1', 'sha256'} or not re.fullmatch('[0-9a-f]{' + str(40 if algorithm == 'sha1' else 64) + '}', digest)):
            raise BuilderError('Missing or invalid archive checksum')
        # Unhashed source archives are fetched afresh from immutable commit URLs.
        name = digest or hashlib.sha256(url.encode()).hexdigest()
        target = self.cache / (name + '.archive')
        if digest and target.is_file() and file_digest(target, algorithm) == digest and (size is None or target.stat().st_size == size):
            return target
        target.unlink(missing_ok=True)
        temp = target.with_suffix('.part')
        self.progress('download', 'Downloading verified build inputs')
        try:
            with self._open(url) as response, temp.open('wb') as output:
                count = 0
                while chunk := response.read(1024**2):
                    self.check_cancel()
                    count += len(chunk)
                    if count > (size if size is not None else 4 * 1024**3):
                        raise BuilderError('Download exceeds expected size')
                    output.write(chunk)
            if size is not None and count != size:
                raise BuilderError('Incomplete download')
            if digest and file_digest(temp, algorithm) != digest:
                raise BuilderError('Download checksum mismatch')
            os.replace(temp, target)
            return target
        finally:
            temp.unlink(missing_ok=True)


def sdk_package(xml, package_id, host):
    root = ET.fromstring(xml)
    # Google's document uses qualified top-level names and unqualified children.
    for node in root.iter():
        node.tag = node.tag.rsplit('}', 1)[-1]
    for package in root.findall('remotePackage'):
        if package.get('path') != package_id:
            continue
        for archive in package.findall('archives/archive'):
            if archive.findtext('host-os') not in (None, host):
                continue
            complete = archive.find('complete')
            checksum = complete.find('checksum')
            license_id = package.find('uses-license').get('ref')
            license_node = next((p for p in root.findall('license') if p.get('id') == license_id), None)
            if license_node is None:
                raise BuilderError('SDK license metadata missing')
            relative = complete.findtext('url')
            archive_path(relative)
            return {'url': REPOSITORY + relative, 'digest': checksum.text, 'algorithm': checksum.get('type'), 'size': int(complete.findtext('size')), 'license': (license_id, ''.join(license_node.itertext()).strip())}
    raise BuilderError('Pinned SDK package not available for this operating system: ' + package_id)


def remove_managed(path):
    """Never follow a replaced managed directory symlink during cleanup."""
    path = Path(path)
    if path.is_symlink():
        raise BuilderError('Managed directory must not be a symlink')
    if path.exists():
        shutil.rmtree(path)


def commit_sha(value):
    if not isinstance(value, str) or not re.fullmatch('[0-9a-f]{40}', value):
        raise BuilderError('Release did not resolve to an exact source commit')
    return value


def personalize_cluster_affinity(manifest):
    """Narrow compatibility overlay until released upstream uses the placeholder."""
    text = Path(manifest).read_text('utf-8')
    changed = False
    def replace(match):
        nonlocal changed
        tag = match.group(0)
        if 'android:name="androidx.car.app.activity.CarAppActivity"' in tag and 'android:taskAffinity="com.openautolink.app.templates"' in tag:
            changed = True
            return tag.replace('android:taskAffinity="com.openautolink.app.templates"', 'android:taskAffinity="${applicationId}.templates"', 1)
        return tag
    updated = re.sub(r'<activity\b[^>]*>', replace, text)
    if changed:
        Path(manifest).write_text(updated, encoding='utf-8')
    return changed


def apply_native_verification_overlay(source, tag, commit):
    """Add only reviewed native classifier hashes to one immutable release.

    Validate every target before writing the staged source. Existing bytes,
    verification policy and all other artifact checksums remain unchanged.
    Future releases must supply their own verification metadata (fail closed
    under Gradle strict mode); there is no runtime checksum generation.
    """
    from .native_verification import COMPONENTS, OVERLAY_ID, SOURCE_REF, SOURCE_COMMIT
    if tag != SOURCE_REF:
        return []
    error = 'Native dependency verification overlay does not match the released source; update the desktop builder. Do not disable strict verification.'
    if commit != SOURCE_COMMIT:
        raise BuilderError(error)
    metadata = Path(source) / 'gradle/verification-metadata.xml'
    try:
        if metadata.is_symlink() or metadata.stat().st_size > 4 * 1024**2:
            raise ValueError()
        original = metadata.read_bytes()
        text = original.decode('utf-8')
        root = ET.fromstring(text)
        ns = {'v': 'https://schema.gradle.org/dependency-verification'}
        components = root.findall('v:components/v:component', ns)
        for group, name, version, repository, artifacts in COMPONENTS:
            targets = [c for c in components if c.get('group') == group and c.get('name') == name and c.get('version') == version]
            if len(targets) != 1:
                raise ValueError()
            additions = ''
            for artifact, digest, _sha1 in artifacts:
                if not re.fullmatch('[0-9a-f]{64}', digest):
                    raise ValueError()
                existing = [a for a in targets[0].findall('v:artifact', ns) if a.get('name') == artifact]
                if existing:
                    if len(existing) != 1 or len(existing[0]) != 1 or existing[0][0].tag != '{' + ns['v'] + '}sha256' or existing[0][0].get('value') != digest or len(existing[0][0]):
                        raise ValueError()
                    continue
                additions += (f'         <artifact name="{artifact}">\n'
                              f'            <sha256 value="{digest}" origin="{repository}{artifact}"/>\n'
                              '         </artifact>\n')
            if additions:
                opening = f'<component group="{group}" name="{name}" version="{version}">'
                text, count = re.subn('(' + re.escape(opening) + r'.*?)(      </component>)',
                                     lambda m: m[1] + additions + m[2], text, flags=re.S)
                if count != 1:
                    raise ValueError()
        updated = text.encode('utf-8')
        if updated == original:
            return []
        metadata.write_bytes(updated)
    except (OSError, ValueError, ET.ParseError) as exc:
        raise BuilderError(error) from exc
    return [OVERLAY_ID]


class Toolchain:
    def __init__(self, workspace, progress, cancel_event):
        self.workspace = Path(workspace).resolve()
        self.tools = self.workspace / 'tools'
        self.sdk = self.tools / 'android-sdk'
        self.progress = progress
        self.downloader = Downloader(self.workspace / 'cache/downloads', progress, cancel_event)

    def environment(self, java_home):
        # Do not inherit signing secrets, Gradle init options or Java agents.
        allowed = {'SystemRoot', 'SYSTEMROOT', 'WINDIR', 'COMSPEC', 'PATHEXT', 'LANG', 'LC_ALL', 'TZ', 'HTTP_PROXY', 'HTTPS_PROXY', 'NO_PROXY', 'http_proxy', 'https_proxy', 'no_proxy'}
        env = {k: v for k, v in os.environ.items() if k in allowed}
        home = self.workspace / 'cache/home'
        temp = self.workspace / 'cache/tmp'
        for p in (home, temp, self.workspace / 'cache/gradle', home / '.android'):
            p.mkdir(parents=True, exist_ok=True)
        env.update({'JAVA_HOME': str(java_home), 'ANDROID_HOME': str(self.sdk), 'ANDROID_SDK_ROOT': str(self.sdk), 'ANDROID_NDK_HOME': str(self.sdk / 'ndk/28.2.13676358'), 'GRADLE_USER_HOME': str(self.workspace / 'cache/gradle'), 'HOME': str(home), 'USERPROFILE': str(home), 'ANDROID_USER_HOME': str(home / '.android'), 'TMPDIR': str(temp), 'TMP': str(temp), 'TEMP': str(temp), 'CMAKE_BUILD_PARALLEL_LEVEL': '2'})
        system_path = os.defpath if os.name != 'nt' else str(Path(env.get('SystemRoot', 'C:/Windows')) / 'System32')
        env['PATH'] = os.pathsep.join((str(Path(java_home) / 'bin'), str(self.sdk / 'cmake/3.22.1/bin'), system_path))
        return env

    def _install_archive(self, archive, target, digest, *, strip_root):
        marker = target / '.oal-archive-digest'
        if target.is_symlink():
            raise BuilderError('Managed tool directory must not be a symlink')
        if marker.is_file() and marker.read_text() == digest:
            return
        target.parent.mkdir(parents=True, exist_ok=True)
        stage = target.with_name(target.name + '.installing')
        remove_managed(stage)
        try:
            safe_extract(archive, stage, strip_root=strip_root, allow_links=True)
            (stage / '.oal-archive-digest').write_text(digest)
            remove_managed(target)
            os.replace(stage, target)
        finally:
            remove_managed(stage)

    def install(self, accept_licenses):
        if not accept_licenses:
            raise BuilderError('Android SDK license acceptance is required before downloading tools')
        host = {'Linux': 'linux', 'Windows': 'windows', 'Darwin': 'macosx'}.get(platform.system())
        machine = platform.machine().lower()
        if host is None or machine not in {'x86_64', 'amd64', 'arm64', 'aarch64'} or (host != 'macosx' and machine not in {'x86_64', 'amd64'}):
            raise BuilderError('Pinned Android native tools support Windows/Linux x64 and macOS only')
        self.tools.mkdir(parents=True, exist_ok=True)
        jdk_os = 'mac' if host == 'macosx' else host
        arch = 'aarch64' if machine in {'arm64', 'aarch64'} else 'x64'
        releases = self.downloader.json('https://api.adoptium.net/v3/assets/latest/17/hotspot?' + urllib.parse.urlencode({'architecture': arch, 'image_type': 'jdk', 'os': jdk_os, 'vendor': 'eclipse'}))
        if not releases:
            raise BuilderError('Official JDK 17 unavailable for this host')
        package = releases[0]['binary']['package']
        archive = self.downloader.download(package['link'], package['checksum'], size=package['size'])
        jdk = self.tools / 'jdk-17'
        self._install_archive(archive, jdk, package['checksum'], strip_root=True)
        java_home = jdk / 'Contents/Home' if host == 'macosx' else jdk
        xml = self.downloader.metadata(REPOSITORY + 'repository2-3.xml')
        for package_id in SDK_PACKAGES:
            self.downloader.check_cancel()
            self.progress('tools', 'Preparing ' + package_id)
            package = sdk_package(xml, package_id, host)
            archive = self.downloader.download(package['url'], package['digest'], algorithm=package['algorithm'], size=package['size'])
            target = self.sdk.joinpath(*package_id.split(';'))
            self._install_archive(archive, target, package['digest'], strip_root=not package_id.startswith('cmake;'))
            license_id, text = package['license']
            if not re.fullmatch('[a-zA-Z0-9_-]+', license_id):
                raise BuilderError('Invalid SDK license identifier')
            licenses = self.sdk / 'licenses'
            licenses.mkdir(exist_ok=True)
            (licenses / license_id).write_text(hashlib.sha1(text.encode()).hexdigest() + '\n')
        bundletool = self.downloader.download('https://github.com/google/bundletool/releases/download/1.18.3/bundletool-all-1.18.3.jar', BUNDLETOOL_SHA256)
        jar = self.tools / 'bundletool-1.18.3.jar'
        shutil.copyfile(bundletool, jar)
        env = self.environment(java_home)
        suffix = '.exe' if host == 'windows' else ''
        executables = {name: str(java_home / 'bin' / (name + suffix)) for name in ('java', 'jarsigner', 'keytool')}
        for executable in executables.values():
            if not Path(executable).is_file():
                raise BuilderError('Managed JDK installation is incomplete')
        return {**executables, 'bundletool': str(jar), 'env': env}

    def prepare_source(self):
        """Resolve the bundled qualified pin, never a moving latest release."""
        from .native_verification import SOURCE_REF, SOURCE_COMMIT
        release = self.downloader.json(GITHUB + '/releases/tags/' + urllib.parse.quote(SOURCE_REF, safe=''))
        if (not isinstance(release, dict) or release.get('tag_name') != SOURCE_REF
                or release.get('draft') is not False or release.get('prerelease') is not False):
            raise BuilderError('Qualified source release is not the expected stable public release; update the desktop builder')
        tag = SOURCE_REF
        commit = commit_sha(self.downloader.json(GITHUB + '/commits/' + urllib.parse.quote(tag, safe=''))['sha'])
        if commit != SOURCE_COMMIT:
            raise BuilderError('Qualified source tag no longer matches its reviewed commit; update the desktop builder')
        tree = self.downloader.json(GITHUB + '/git/trees/' + commit + '?recursive=1')
        if tree.get('truncated'):
            raise BuilderError('GitHub source tree was truncated; cannot verify gitlink')
        links = [entry for entry in tree['tree'] if entry['path'] == 'external/opencardev-aasdk' and entry.get('mode') == '160000']
        if len(links) != 1:
            raise BuilderError('Source aasdk gitlink missing')
        gitlink = commit_sha(links[0]['sha'])
        native = self.downloader.json(GITHUB + '/releases/tags/native-deps')
        hashes = re.findall(r'(?<![0-9a-f])[0-9a-f]{7,40}(?![0-9a-f])', native.get('body') or '')
        if not any(gitlink.startswith(value) for value in hashes):
            raise BuilderError('Native-deps release does not match source aasdk gitlink')
        # Validate all assets before touching a previous source tree.
        assets = []
        for abi in ('arm64-v8a', 'x86_64'):
            matches = [a for a in native['assets'] if a['name'] == 'native-deps-' + abi + '.tar.gz']
            if len(matches) != 1 or not re.fullmatch(r'sha256:[0-9a-f]{64}', matches[0].get('digest') or ''):
                raise BuilderError('Native-deps release requires a GitHub SHA-256 digest for each ABI')
            assets.append((abi, matches[0]))
        stage = self.workspace / 'source.preparing'
        source = self.workspace / 'source'
        remove_managed(stage)
        try:
            self.progress('source', 'Preparing upstream release ' + tag + ' at ' + commit[:12])
            archive = self.downloader.download('https://codeload.github.com/mossyhub/openautolink/zip/' + commit)
            safe_extract(archive, stage, strip_root=True)
            manifest = stage / 'app/src/main/AndroidManifest.xml'
            overlays = []
            if manifest.is_file() and personalize_cluster_affinity(manifest):
                overlays.append('personal-cluster-task-affinity')
            overlays.extend(apply_native_verification_overlay(stage, tag, commit))
            cmake = (stage / 'app/src/main/cpp/CMakeLists.txt').read_text()
            if 'AASDK_SOURCE_DIR' in cmake or 'external/opencardev-aasdk' in cmake:
                headers = self.downloader.download('https://codeload.github.com/mossyhub/aasdk/zip/' + gitlink)
                safe_extract(headers, stage / 'external/opencardev-aasdk', strip_root=True)
            for abi, asset in assets:
                self.downloader.check_cancel()
                archive = self.downloader.download(asset['browser_download_url'], asset['digest'][7:])
                prefixes = ('third_party/openssl/' + abi + '/', 'third_party/aasdk/' + abi + '/', 'third_party/boost/include/')
                def allowed(name, directory):
                    value = name.rstrip('/') + ('/' if directory else '')
                    return any(value.startswith(p) or (directory and p.startswith(value)) for p in prefixes)
                safe_extract(archive, stage / 'app/src/main/cpp', allowed=allowed)
            remove_managed(source)
            os.replace(stage, source)
        finally:
            remove_managed(stage)
        return {'sourceCommit': commit, 'versionName': tag.removeprefix('v'), 'sourceRef': tag, 'sourceOverlays': overlays}
