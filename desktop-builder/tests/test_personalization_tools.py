"""Easy mode prepares only Java/bundletool, never the Android SDK/NDK."""
import threading
from pathlib import Path

import pytest

from oal_builder import toolchain as tc


@pytest.mark.parametrize('system,machine,suffix,home', [
    ('Linux', 'x86_64', '', ''),
    ('Windows', 'AMD64', '.exe', ''),
    ('Darwin', 'arm64', '', 'Contents/Home'),
    ('Darwin', 'x86_64', '', 'Contents/Home'),
])
def test_personalization_tools_need_no_android_sdk(tmp_path, monkeypatch, system, machine, suffix, home):
    monkeypatch.setattr(tc.platform, 'system', lambda: system)
    monkeypatch.setattr(tc.platform, 'machine', lambda: machine)
    manager = tc.Toolchain(tmp_path, lambda *a: None, threading.Event())
    downloads = []
    archive = tmp_path / 'jdk.archive'
    archive.write_bytes(b'fixture archive handled by fake installer')
    jar = tmp_path / 'bundletool.jar'
    jar.write_bytes(b'fixture bundletool download')
    def metadata(url):
        assert url.startswith('https://api.adoptium.net/')
        return [{'binary': {'package': {'link': 'https://github.com/adoptium/test.zip',
                                       'checksum': 'a' * 64, 'size': 45}}}]
    def download(url, digest=None, **kwargs):
        downloads.append(url)
        return jar if 'bundletool' in url else archive
    def install(source, target, digest, **kwargs):
        binaries = target / home / 'bin'
        binaries.mkdir(parents=True)
        for name in ('java', 'javac', 'jarsigner', 'keytool'):
            (binaries / (name + suffix)).write_bytes(b'fixture tool')
    monkeypatch.setattr(manager.downloader, 'json', metadata)
    monkeypatch.setattr(manager.downloader, 'download', download)
    monkeypatch.setattr(manager.downloader, 'metadata', lambda *a: pytest.fail('SDK metadata requested'))
    monkeypatch.setattr(manager, '_install_archive', install)
    tools = manager.install_personalization()
    assert set(('java', 'javac', 'keytool', 'jarsigner', 'bundletool', 'env')) <= tools.keys()
    assert Path(tools['javac']).name == 'javac' + suffix
    assert len(downloads) == 2
    assert not manager.sdk.exists()
    assert not any(k.startswith('ANDROID_') or k == 'GRADLE_USER_HOME' for k in tools['env'])
    assert str(manager.sdk) not in tools['env']['PATH']
    assert not (tmp_path / 'cache/gradle').exists()
    assert not (tmp_path / 'cache/home/.android').exists()


def test_personalization_tools_reject_unsupported_host_before_download(tmp_path, monkeypatch):
    monkeypatch.setattr(tc.platform, 'system', lambda: 'Linux')
    monkeypatch.setattr(tc.platform, 'machine', lambda: 'aarch64')
    manager = tc.Toolchain(tmp_path, lambda *a: None, threading.Event())
    monkeypatch.setattr(manager.downloader, 'json', lambda *a: pytest.fail('download before host validation'))
    with pytest.raises(tc.BuilderError, match='support'):
        manager.install_personalization()
