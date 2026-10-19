import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

SCRIPT = Path(__file__).resolve().parents[1] / 'refresh.py'
spec = importlib.util.spec_from_file_location('refresh', SCRIPT)
m = importlib.util.module_from_spec(spec)
if SCRIPT.exists():
    spec.loader.exec_module(m)


class SelectionTests(unittest.TestCase):
    def test_selects_newest_complete_stable_not_runtime(self):
        records = [dict(tag_name='web-builder-runtime', prerelease=True, draft=False, assets=[]),
                   dict(tag_name='v0.1.534', prerelease=False, draft=False, assets=[]),
                   dict(tag_name='v0.1.533', prerelease=False, draft=False, assets=[
                       {'name': 'openautolink-personalization.json'},
                       {'name': 'openautolink-template-v0.1.533.aab'}])]
        self.assertTrue(hasattr(m, 'select_release'), 'missing complete stable selector')
        self.assertEqual(m.select_release(records)['tag_name'], 'v0.1.533')


class ShellTests(unittest.TestCase):
    def test_extracts_only_exact_registry_and_rejects_malicious_members(self):
        self.assertTrue(hasattr(m, 'extract_shell'), 'missing bounded shell extractor')
        import hashlib
        raw = b'<!doctype html>'
        registry = {'index.html': {'size': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()}}
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            archive = root / 'shell.zip'
            with zipfile.ZipFile(archive, 'w') as z:
                z.writestr('index.html', raw)
            m.extract_shell(archive, root / 'good', registry)
            self.assertEqual((root / 'good/index.html').read_bytes(), raw)
            for name in ['../secret', '/absolute', 'assets/source.js.map', 'private.ts', 'index.html']:
                with self.subTest(name=name):
                    import warnings
                    with warnings.catch_warnings():
                        warnings.simplefilter('ignore', UserWarning)
                        with zipfile.ZipFile(archive, 'w') as z:
                            z.writestr('index.html', raw)
                            z.writestr(name, b'extra')
                    with self.assertRaises(ValueError):
                        m.extract_shell(archive, root / 'bad', registry)
                    self.assertFalse((root / 'bad').exists())

    def test_shell_rejects_nul_truncated_member_names(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            archive = root / 'shell.zip'
            with zipfile.ZipFile(archive, 'w') as z:
                z.writestr('index.htmlX', b'compiled')
            archive.write_bytes(archive.read_bytes().replace(b'index.htmlX', b'index.html\0'))
            registry = {'index.html': {'size': 8, 'sha256': m.sha(b'compiled')}}
            with self.assertRaises(ValueError):
                m.extract_shell(archive, root / 'bad', registry)

    def test_actual_compiled_runtime_paths_are_allowed(self):
        self.assertTrue(m.shell_path('brand/oal-logo.png'))
        self.assertTrue(m.shell_path('assets/build.worker-BP3iWVmz.js'))
        for path in ['assets/source.js.map', 'assets/../private.js', 'core/releases.mjs', 'index.html/child']:
            self.assertFalse(m.shell_path(path))

    def test_registry_rejects_source_and_unpinned_hashes(self):
        self.assertTrue(hasattr(m, 'validate_config'), 'missing runtime registry validator')
        for config in [{}, {'schemaVersion': 1}]:
            with self.assertRaises(ValueError):
                m.validate_config(config)


class SnapshotTests(unittest.TestCase):
    def test_manifest_matches_authoritative_browser_contract(self):
        self.assertTrue(hasattr(m, 'emit_manifest'), 'missing same-origin manifest emitter')
        tag = 'v0.1.533'
        assets = {name: dict(name=name, size=10, digest='sha256:' + 'b' * 64,
                            browser_download_url=m.r.asset_url(tag, name))
                  for name in [m.DESCRIPTOR, m.r.template_name(tag)]}
        release = dict(sourceRef=tag, sourceCommit='a' * 40, assets=assets)
        manifest = m.emit_manifest(release)
        self.assertEqual(set(manifest), {'schemaVersion', 'sourceRef', 'sourceCommit', 'assets'})
        self.assertEqual(len(manifest['assets']), 2)
        self.assertEqual(manifest['assets'], list(assets.values()))
        self.assertEqual(m.validate_manifest(manifest), manifest)
        manifest['assets'][0]['digest'] = None
        with self.assertRaises(ValueError):
            m.validate_manifest(manifest)

    def test_guard_rejects_rollback_or_same_tag_changed_proof(self):
        self.assertTrue(hasattr(m, 'guard_served'), 'missing anti-rollback guard')
        old = {'sourceRef': 'v0.1.533', 'sourceCommit': 'a' * 40, 'assets': []}
        for candidate in [old | {'sourceRef': 'v0.1.532'}, old | {'sourceCommit': 'b' * 40}]:
            with self.assertRaises(ValueError):
                m.guard_served(candidate, old)
        m.guard_served(old, old)
        m.guard_served(old | {'sourceRef': 'v0.1.534'}, old)

    def test_redirect_policy_never_forwards_api_auth(self):
        self.assertTrue(hasattr(m, 'allowed_url'), 'missing bounded HTTPS policy')
        self.assertTrue(m.allowed_url('https://release-assets.githubusercontent.com/x?sig=x', asset=True))
        for url in ['http://github.com/x', 'https://evil.com/x', 'https://github.com:444/x',
                    'https://user@github.com/x', 'https://api.github.com/repos/other/repo']:
            self.assertFalse(m.allowed_url(url, asset=False))


class PrepareTests(unittest.TestCase):
    def test_resolves_real_tag_and_requires_both_published_digests(self):
        self.assertTrue(hasattr(m, 'resolve'), 'missing immutable release resolver')
        tag = 'v0.1.533'
        records = [dict(id=42, tag_name=tag, html_url=m.r.WEB + '/releases/tag/' + tag,
                        draft=False, prerelease=False, assets=[
                            dict(id=1 if name == m.DESCRIPTOR else 2, name=name, size=10, digest='sha256:' + 'b' * 64,
                                 browser_download_url=m.r.asset_url(tag, name))
                            for name in (m.DESCRIPTOR, m.r.template_name(tag))])]
        class Fake:
            def json(self, url):
                if '/releases?' in url:
                    return records
                if '/git/ref/' in url:
                    return dict(ref='refs/tags/' + tag, object=dict(type='commit', sha='a' * 40))
                raise AssertionError(url)
        proof = m.resolve(Fake())
        self.assertEqual(proof['release']['sourceCommit'], 'a' * 40)
        self.assertEqual(proof['releaseId'], 42)
        self.assertEqual(set(proof['assetProof']), {m.DESCRIPTOR, m.r.template_name(tag)})
        records[0]['assets'][0]['digest'] = None
        with self.assertRaises(ValueError):
            m.resolve(Fake())

    def test_inventory_detects_added_or_changed_files(self):
        self.assertTrue(hasattr(m, 'inventory'), 'missing exact snapshot inventory')
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'index.html').write_bytes(b'compiled')
            records = m.inventory(root)
            self.assertEqual(records['index.html']['sha256'], m.sha(b'compiled'))
            (root / 'index.html').write_bytes(b'changed')
            self.assertNotEqual(m.inventory(root), records)
            (root / 'link').symlink_to(root / 'index.html')
            with self.assertRaises(ValueError):
                m.inventory(root)


class EndToEndTests(unittest.TestCase):
    def test_prepare_recheck_and_hash_readback_synthetic_snapshot(self):
        self.assertTrue(hasattr(m, 'prepare'), 'missing snapshot preparation transaction')
        import hashlib
        tag = 'v0.1.533'
        template = b'synthetic-template-not-public-proof'
        descriptor = dict(schemaVersion=1, formatVersion=1, releaseTag=tag, sourceCommit='a' * 40,
                          templateApplicationId=m.r.TEMPLATE_APPLICATION_ID,
                          transformContract=m.r.TRANSFORM_CONTRACT,
                          templateAsset=dict(name=m.r.template_name(tag), size=len(template), sha256=m.sha(template)))
        raw = json.dumps(descriptor).encode()
        shell_bytes = io.BytesIO()
        files = {'index.html': b'compiled synthetic runtime', 'staticwebapp.config.json': b'{}'}
        with zipfile.ZipFile(shell_bytes, 'w') as z:
            for name, content in files.items():
                z.writestr(name, content)
        shell = shell_bytes.getvalue()
        config = dict(schemaVersion=1, url=m.r.WEB + '/releases/download/web-builder-runtime/shell.zip',
                      size=len(shell), sha256=m.sha(shell), files={name: dict(size=len(content), sha256=m.sha(content)) for name, content in files.items()})
        assets = [dict(id=1 if name == m.DESCRIPTOR else 2, name=name, size=len(content), digest='sha256:' + m.sha(content),
                       browser_download_url=m.r.asset_url(tag, name)) for name, content in [(m.DESCRIPTOR, raw), (m.r.template_name(tag), template)]]
        release = dict(id=42, tag_name=tag, html_url=m.r.WEB + '/releases/tag/' + tag, draft=False, prerelease=False, assets=assets)
        runtime = dict(id=43, tag_name='web-builder-runtime', draft=False, prerelease=True, assets=[dict(id=90, name='shell.zip', browser_download_url=config['url'], size=len(shell), digest='sha256:' + m.sha(shell))])
        class Fake:
            served = {}
            def json(self, url):
                if '/releases?' in url:
                    return [runtime, release]
                if url.endswith('/releases/tags/web-builder-runtime'):
                    return runtime
                if '/git/ref/' in url:
                    return dict(ref='refs/tags/' + tag, object=dict(type='commit', sha='a' * 40))
                raise AssertionError(url)
            def get(self, url, limit, *, output=None, size=None, digest=None, asset=False):
                content = ({config['url']: shell, m.r.asset_url(tag, m.DESCRIPTOR): raw,
                            m.r.asset_url(tag, m.r.template_name(tag)): template} | self.served)[url]
                assert len(content) <= limit
                if size is not None:
                    assert len(content) == size
                if digest is not None:
                    assert m.sha(content) == digest
                if output:
                    Path(output).write_bytes(content)
                    return Path(output)
                return content
        client = Fake()
        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp) / 'snapshot'
            checked = []
            report = m.prepare(client, config, work, lambda p: checked.append(p.read_bytes()))
            self.assertEqual(checked, [template])
            manifest = json.loads((work / 'dist/templates/current.json').read_bytes())
            self.assertEqual(manifest['sourceRef'], tag)
            self.assertEqual(set(report['files']), {'index.html', 'staticwebapp.config.json', 'templates/current.json',
                'templates/' + tag + '/' + m.DESCRIPTOR, 'templates/' + tag + '/' + m.r.template_name(tag)})
            client.served = {m.ORIGIN + '/' + name: (work / 'dist' / name).read_bytes() for name in report['files']}
            m.recheck(client, config, work)
            result = m.readback(client, work, attempts=1)
            self.assertEqual(result['status'], 'verified')
            client.served[m.ORIGIN + '/index.html'] = b'wrong'
            with self.assertRaises((ValueError, AssertionError)):
                m.readback(client, work, attempts=1)
            release['id'] = 999
            with self.assertRaises(ValueError):
                m.recheck(client, config, work)


class RegistryTests(unittest.TestCase):
    def test_pins_only_compiled_shell_archive(self):
        self.assertTrue(hasattr(m, 'pin_runtime'), 'missing compiled artifact registry writer')
        with tempfile.TemporaryDirectory() as tmp:
            archive = Path(tmp) / 'runtime-reviewed.zip'
            with zipfile.ZipFile(archive, 'w') as z:
                z.writestr('index.html', b'compiled fixture')
                z.writestr('staticwebapp.config.json', b'{}')
            config = m.pin_runtime(archive)
            self.assertEqual(config['sha256'], m.sha(archive.read_bytes()))
            self.assertEqual(m.validate_config(config), config)
            with zipfile.ZipFile(archive, 'a') as z:
                z.writestr('core/private-source.mjs', b'private')
            with self.assertRaises(ValueError):
                m.pin_runtime(archive)

    def test_validator_rejects_signed_template_before_external_tool(self):
        from oal_builder.toolchain import BuilderError
        with tempfile.TemporaryDirectory() as tmp:
            template = Path(tmp) / 'signed.aab'
            with zipfile.ZipFile(template, 'w') as z:
                z.writestr('BundleConfig.pb', b'config')
                z.writestr('base/manifest/AndroidManifest.xml', b'manifest')
                z.writestr('base/resources.pb', b'resources')
                z.writestr('base/dex/classes.dex', b'dex')
                z.writestr('META-INF/SIGNER.RSA', b'signature')
            with self.assertRaisesRegex((ValueError, BuilderError), 'unsigned'):
                m.template_validator('/nonexistent-java', '/nonexistent-javac', '/nonexistent-jar')(template)


class TransportTests(unittest.TestCase):
    def test_real_client_hashes_stream_and_never_sends_token_to_assets_or_site(self):
        class Response(io.BytesIO):
            status = 200
            headers = {'Content-Length': '4'}
        class Opener:
            requests = []
            def open(self, request, timeout):
                self.requests.append(request)
                return Response(b'data')
        client = m.Client('synthetic-token-never-real')
        client.opener = Opener()
        self.assertEqual(client.get(m.API + '/releases', 4, digest=m.sha(b'data')), b'data')
        self.assertEqual(client.opener.requests[-1].get_header('Authorization'), 'Bearer synthetic-token-never-real')
        client.get(m.r.asset_url('v0.1.533', m.DESCRIPTOR), 4, asset=True)
        self.assertIsNone(client.opener.requests[-1].get_header('Authorization'))
        client.get(m.ORIGIN + '/templates/current.json', 4)
        self.assertIsNone(client.opener.requests[-1].get_header('Authorization'))
        for kwargs in [dict(limit=3), dict(limit=4, size=3), dict(limit=4, digest='0' * 64)]:
            with self.assertRaises(ValueError):
                client.get(m.ORIGIN + '/index.html', **kwargs)

    def test_strict_json_rejects_duplicates_nonfinite_and_deep_input(self):
        for raw in [b'{"a":1,"a":2}', b'{"a":NaN}', b'[' * 70 + b'0' + b']' * 70]:
            with self.subTest(raw=raw[:30]), self.assertRaises(ValueError):
                m.strict_json(raw)


if __name__ == '__main__':
    unittest.main()
