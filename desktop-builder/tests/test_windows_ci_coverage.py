"""Windows review contracts; these are not native Windows execution tests."""
import importlib.util
from pathlib import Path
import unittest
import copy
import json
import tempfile
import platform
import subprocess
import sys
from unittest import mock


def load_module():
    spec = importlib.util.spec_from_file_location("windows_coverage", PACKAGING / "coverage_windows_ci.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

PACKAGING = Path(__file__).resolve().parents[1] / "packaging"


class WindowsCoverageTests(unittest.TestCase):
    @unittest.skipUnless(importlib.util.find_spec("pefile"), "PE parser dependency")
    def test_shared_inventory_normalizes_pe_metadata_not_loader_code(self):
        from PyInstaller.archive.writers import CArchiveWriter
        module = load_module()
        build = module._load("build")
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp)
            pkg = path / "test.pkg"
            CArchiveWriter(str(pkg), [], "python")
            executable = path / "test.exe"
            original = self.minimal_pe()
            executable.write_bytes(original + pkg.read_bytes())
            first = build.native_inventory(executable)
            self.assertEqual(first[0]["hash_scope"], module.PE_PREFIX_SCOPE)
            changed = bytearray(original)
            changed[136] = 1
            changed[152 + 64] = 2
            executable.write_bytes(changed + pkg.read_bytes())
            self.assertEqual(build.native_inventory(executable), first)
            changed[512] ^= 1
            executable.write_bytes(changed + pkg.read_bytes())
            self.assertNotEqual(build.native_inventory(executable), first)

    def test_windows_spec_filters_after_analysis_before_exe(self):
        module = load_module()
        build = module._load("build")
        with tempfile.TemporaryDirectory() as temp:
            work = Path(temp)
            with mock.patch("PyInstaller.building.makespec.is_darwin", False):
                spec = build.windows_spec(PACKAGING.parent, work, work / "builder-compliance.zip")
            text = spec.read_text()
            self.assertLess(text.index("a = Analysis("), text.index("a.binaries ="))
            self.assertLess(text.index("a.binaries ="), text.index("exe = EXE("))
            self.assertIn("windows10_runtime_binaries", text)
            rows = [("ucrtbase.dll", "os/ucrtbase.dll", "BINARY"),
                    ("VCRUNTIME140.dll", "python/VCRUNTIME140.dll", "BINARY")]
            analysis = mock.Mock(binaries=rows, pure=[], scripts=[], datas=[])
            exe = mock.Mock()
            exec(compile(text, str(spec), "exec"), {"Analysis": lambda *a, **kw: analysis,
                                                  "PYZ": mock.Mock(), "EXE": exe})
            self.assertEqual(analysis.binaries, rows[1:])
            self.assertIn(rows[1:], exe.call_args.args)

    @unittest.skipUnless(importlib.util.find_spec("pefile"), "PE parser dependency")
    def test_pe_probe_rejects_relocated_raw_code_layout(self):
        import struct
        module = load_module()
        original = self.minimal_pe()
        moved = bytearray(original + bytes(512))
        moved[1024:1536] = moved[512:1024]
        moved[512:1024] = bytes(512)
        struct.pack_into("<I", moved, 392 + 20, 1024)
        with self.assertRaisesRegex(ValueError, "layout"):
            module.compare_pe_loader(bytes(moved), original)

    def test_fetch_selects_required_sources_and_only_official_loader_wheel(self):
        module = load_module()
        review = json.loads(module.REVIEW.read_text())
        prepare = mock.Mock()
        prepare.prepare.return_value = {"status": "evidence-only"}
        with mock.patch.object(module, "_load", return_value=prepare):
            module.fetch_sources(review, Path("cache"))
        inputs = prepare.prepare.call_args.args[0]["inputs"]
        self.assertEqual(len(inputs), len(review["source_inputs"]) + 1)
        self.assertEqual([i["path"] for i in inputs if i["path"].endswith(".whl")],
                         ["pyinstaller-6.13.0-py3-none-win_amd64.whl"])

    def test_ci_identity_is_verified_and_dirty_checkout_rejected(self):
        module = load_module()
        helper = mock.Mock()
        identity = {"checkoutCommit": "a" * 40, "expectedCommit": "a" * 40,
                    "trackedCheckoutDirty": False, "runner": "windows-2022", "runId": "123", "runAttempt": "1"}
        helper.identity.return_value = identity
        env = {"GITHUB_ACTIONS": "true", "EXPECTED_COMMIT": "a" * 40,
               "GITHUB_RUN_ID": "123", "GITHUB_RUN_ATTEMPT": "1"}
        with mock.patch.dict("os.environ", env, clear=True), mock.patch.object(module, "_load", return_value=helper):
            self.assertEqual(module.ci_identity(), identity)
            helper.identity.assert_called_once_with("a" * 40, "windows-2022", "123", "1")
            identity["trackedCheckoutDirty"] = True
            with self.assertRaisesRegex(ValueError, "dirty"):
                module.ci_identity()
        with mock.patch.dict("os.environ", {}, clear=True):
            self.assertIsNone(module.ci_identity())

    def test_private_candidate_ui_probes_both_qpa_plugins_without_claiming_coverage(self):
        module = load_module()
        environments = []
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            candidate = root / "candidate.exe"
            candidate.write_bytes(b"API fixture, not a native executable")
            def child(argv, **kwargs):
                environments.append(kwargs["env"]["QT_QPA_PLATFORM"])
                target = Path(argv[-1])
                if argv[1] == "--runtime-info":
                    target.write_text(json.dumps({"frozen": True, "architecture": "x86_64", "system": "Windows"}))
                else:
                    self.assertIn("--workspace", argv)
                    target.write_bytes(b"\x89PNG\r\n\x1a\nUNIT TEST ONLY")
            with mock.patch("platform.system", return_value="Windows"), \
                    mock.patch("platform.machine", return_value="AMD64"), \
                    mock.patch("subprocess.run", side_effect=child):
                result = module.probe_candidate_ui(candidate, root)
            self.assertEqual(environments, ["windows", "windows", "offscreen"])
            self.assertEqual(result["status"], "evidence-only")
            self.assertTrue(result["notQualification"])
            self.assertEqual([p["platform"] for p in result["screenshots"]], ["windows", "offscreen"])
            self.assertFalse((root / "must-not-be-created").exists())

    def test_windows_hook_removes_mesa_but_preserves_other_extras(self):
        import runpy
        import PyInstaller.compat
        import PyInstaller.utils.hooks.qt as qt
        extras = [("C:/Qt/opengl32sw.dll", "PySide6"),
                  ("C:/Qt/icudt73.dll", "PySide6")]
        info = mock.Mock(version=[6, 8, 3])
        info.collect_extra_binaries.return_value = extras
        with mock.patch.object(PyInstaller.compat, "is_win", True), \
                mock.patch.object(qt, "pyside6_library_info", info):
            result = runpy.run_path(str(PACKAGING / "hook-PySide6.py"))
        self.assertEqual(result["binaries"], extras[1:])
        self.assertIn("shiboken6", result["hiddenimports"])

    def test_windows_network_uses_schannel_without_searching_path_openssl(self):
        import runpy
        import PyInstaller.compat
        import PyInstaller.utils.hooks.qt as qt
        binaries = [("C:/Qt/qopensslbackend.dll", "PySide6/plugins/tls"),
                    ("C:/Qt/qschannelbackend.dll", "PySide6/plugins/tls"),
                    ("C:/Qt/qnetworklistmanager.dll", "PySide6/plugins/networkinformation")]
        with mock.patch.object(PyInstaller.compat, "is_win", True), \
                mock.patch.object(qt, "add_qt6_dependencies", return_value=([], binaries, [])), \
                mock.patch.object(qt.pyside6_library_info, "collect_qtnetwork_files") as ssl_search:
            result = runpy.run_path(str(PACKAGING / "hook-PySide6.QtNetwork.py"))
        self.assertEqual(result["binaries"], binaries[1:])
        ssl_search.assert_not_called()

    @staticmethod
    def minimal_pe():
        """Non-runnable PE fixture for parser tests, never supplier evidence."""
        import struct
        image = bytearray(1024)
        image[:2] = b"MZ"
        struct.pack_into("<I", image, 60, 128)
        image[128:132] = b"PE\0\0"
        struct.pack_into("<HHIIIHH", image, 132, 0x8664, 1, 0, 0, 0, 240, 0x22)
        opt = 152
        struct.pack_into("<H", image, opt, 0x20b)
        struct.pack_into("<I", image, opt + 16, 4096)
        struct.pack_into("<Q", image, opt + 24, 0x140000000)
        struct.pack_into("<II", image, opt + 32, 4096, 512)
        struct.pack_into("<II", image, opt + 56, 8192, 512)
        struct.pack_into("<H", image, opt + 68, 2)
        struct.pack_into("<I", image, opt + 108, 16)
        struct.pack_into("<8sIIIIIIHHI", image, 392, b".text", 16, 4096, 512,
                         512, 0, 0, 0, 0, 0x60000020)
        image[512:528] = b"parser-test-only!"
        return bytes(image)

    @unittest.skipUnless(importlib.util.find_spec("pefile"), "pefile is a PyInstaller Windows dependency")
    def test_pe_loader_probe_rejects_changed_code_not_timestamp(self):
        module = load_module()
        self.assertTrue(hasattr(module, "compare_pe_loader"), "PE byte probe missing")
        original = self.minimal_pe()
        changed = bytearray(original)
        changed[136] = 1  # COFF timestamp changes during PyInstaller packaging.
        result = module.compare_pe_loader(bytes(changed), original)
        self.assertEqual(len(result["sections"]), 1)
        changed[512] ^= 1
        with self.assertRaisesRegex(ValueError, "section"):
            module.compare_pe_loader(bytes(changed), original)

    @unittest.skipUnless(importlib.util.find_spec("pefile"), "PE parser dependency")
    def test_actual_carchive_probe_binds_artifact_loader_and_wheel_api_replay(self):
        """Platform API replay with synthetic bytes, NOT native qualification."""
        import hashlib
        import zipfile
        from PyInstaller.archive.writers import CArchiveWriter
        module = load_module()
        build = module._load("build")
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            wheel = root / "pyinstaller-6.13.0-py3-none-win_amd64.whl"
            loader = self.minimal_pe()
            with zipfile.ZipFile(wheel, "w") as archive:
                archive.writestr("PyInstaller/bootloader/Windows-64bit-intel/runw.exe", loader)
            review = {"supplier_inputs": [{"path": wheel.name, "sha256": build.sha256(wheel)}]}
            pkg, exe = root / "payload.pkg", root / "candidate.exe"
            CArchiveWriter(str(pkg), [], "python312.dll")
            exe.write_bytes(loader + pkg.read_bytes())
            inventory = {"artifactSha256": build.sha256(exe), "builderSourceSha256": "a" * 64,
                         "payload": build.native_inventory(exe)}
            with mock.patch("platform.system", return_value="Windows"), \
                    mock.patch("platform.machine", return_value="AMD64"), \
                    mock.patch.object(module, "ci_identity", return_value=None):
                result = module.probe_native_loader(exe, inventory, review, root)
                self.assertEqual(result["artifactSha256"], inventory["artifactSha256"])
                self.assertEqual(result["loaderHashScope"], module.PE_PREFIX_SCOPE)
                self.assertTrue(result["notQualification"])
                for field in ("artifactSha256", "payload"):
                    changed = copy.deepcopy(inventory)
                    if field == "payload": changed["payload"][0]["sha256"] = "0" * 64
                    else: changed[field] = "0" * 64
                    with self.subTest(field=field), self.assertRaisesRegex(ValueError, "differs"):
                        module.probe_native_loader(exe, changed, review, root)
                changed = bytearray(loader)
                changed[512] ^= 1
                exe.write_bytes(changed + pkg.read_bytes())
                inventory.update(artifactSha256=build.sha256(exe), payload=build.native_inventory(exe))
                with self.assertRaisesRegex(ValueError, "section"):
                    module.probe_native_loader(exe, inventory, review, root)
                wheel.write_bytes(b"changed wheel")
                with self.assertRaisesRegex(ValueError, "changed"):
                    module.probe_native_loader(exe, inventory, review, root)

    def test_native_loader_probe_refuses_linux_without_reading_artifact(self):
        module = load_module()
        self.assertTrue(hasattr(module, "probe_native_loader"), "Native probe command missing")
        with mock.patch("platform.system", return_value="Linux"):
            with self.assertRaisesRegex(ValueError, "native Windows"):
                module.probe_native_loader(Path("absent.exe"), {}, {}, Path("absent-cache"))

    def test_probe_cli_is_available_but_not_platform_simulation(self):
        if platform.system() == "Windows":
            self.skipTest("Non-Windows guard test")
        with tempfile.TemporaryDirectory() as temp:
            inventory = Path(temp) / "discovery.json"
            inventory.write_text("{}")
            run = subprocess.run([sys.executable, str(PACKAGING / "coverage_windows_ci.py"),
                                  "probe-loader", "--inventory", str(inventory),
                                  "--executable", "absent.exe", "--cache", temp],
                                 capture_output=True, text=True)
            self.assertEqual(run.returncode, 2)
            self.assertIn("native Windows", run.stdout)

    def test_windows10_filter_drops_only_os_ucrt_and_api_set_forwarders(self):
        module = load_module()
        self.assertTrue(hasattr(module, "windows10_runtime_binaries"), "Analysis TOC filter missing")
        entries = [("ucrtbase.dll", "C:/Windows/System32/ucrtbase.dll", "BINARY"),
                   ("api-ms-win-core-file-l1-1-0.dll", "C:/SDK/api-ms-win-core-file-l1-1-0.dll", "BINARY"),
                   ("api-ms-win-crt-math-l1-1-0.dll", "C:/SDK/api-ms-win-crt-math-l1-1-0.dll", "BINARY"),
                   ("VCRUNTIME140.dll", "C:/Python/VCRUNTIME140.dll", "BINARY"),
                   ("foo.dll", "C:/project/foo.dll", "BINARY")]
        self.assertEqual(module.windows10_runtime_binaries(entries), entries[3:])

    @unittest.skipUnless(importlib.util.find_spec("pefile"), "pefile is a PyInstaller Windows dependency")
    def test_pe_probe_allows_new_nonexecuting_resource_not_overlap(self):
        import struct
        module = load_module()
        original = self.minimal_pe()
        changed = bytearray(original + bytes(512))
        struct.pack_into("<H", changed, 134, 2)
        struct.pack_into("<8sIIIIIIHHI", changed, 432, b".rsrc", 32, 8192, 512,
                         1024, 0, 0, 0, 0, 0x40000040)
        struct.pack_into("<II", changed, 152 + 112 + 16, 8192, 32)
        self.assertEqual(len(module.compare_pe_loader(bytes(changed), original)["resourceSections"]), 1)
        struct.pack_into("<I", changed, 432 + 12, 4096)
        struct.pack_into("<II", changed, 152 + 112 + 16, 4096, 32)
        with self.assertRaisesRegex(ValueError, "overlap"):
            module.compare_pe_loader(bytes(changed), original)

    @unittest.skipUnless(importlib.util.find_spec("pefile"), "pefile is a PyInstaller Windows dependency")
    def test_pe_probe_rejects_entrypoint_and_architecture_changes(self):
        module = load_module()
        original = self.minimal_pe()
        for offset in (152 + 16, 132):
            changed = bytearray(original)
            changed[offset] ^= 1
            with self.assertRaises(ValueError):
                module.compare_pe_loader(bytes(changed), original)

    @unittest.skipUnless(importlib.util.find_spec("pefile"), "pefile is a PyInstaller Windows dependency")
    def test_pe_probe_normalizes_only_pyinstaller_debug_timestamp(self):
        import struct
        module = load_module()
        image = bytearray(self.minimal_pe() + bytes(512))
        struct.pack_into("<H", image, 134, 2)
        struct.pack_into("<8sIIIIIIHHI", image, 432, b".rdata", 64, 8192, 512,
                         1024, 0, 0, 0, 0, 0x40000040)
        struct.pack_into("<II", image, 152 + 112 + 6 * 8, 8192, 28)
        struct.pack_into("<IIHHIIII", image, 1024, 0, 100, 0, 0, 2, 0, 0, 0)
        changed = bytearray(image)
        struct.pack_into("<I", changed, 1028, 200)
        self.assertEqual(len(module.compare_pe_loader(bytes(changed), bytes(image))["sections"]), 2)
        changed[1060] = 1
        with self.assertRaisesRegex(ValueError, "section"):
            module.compare_pe_loader(bytes(changed), bytes(image))

    def test_minimal_review_keeps_exact_native_and_non_native_obligations(self):
        module = load_module()
        review = json.loads(module.REVIEW.read_text())
        policy = json.loads((PACKAGING / "provenance/windows-ci-minimal-policy.json").read_text())
        loader = {"path": "@bootloader", "sha256": "a" * 64, "size": 1234,
                  "kind": "bootloader", "hash_scope": module.PE_PREFIX_SCOPE}
        inventory = {"artifactSha256": "b" * 64, "builderSourceSha256": "c" * 64,
                     "payload": [loader] + policy["expectedNonLoaderPayload"]}
        probe = {"schema": 1, "status": "evidence-only", "notQualification": True,
                 "system": "Windows", "architecture": "x86_64", "artifactSha256": "b" * 64,
                 "builderSourceSha256": "c" * 64, "loaderSha256": "a" * 64,
                 "loaderHashScope": module.PE_PREFIX_SCOPE,
                 "supplierWheelSha256": "8d4296236b85aae570379488c2da833b28828b17c57c2cc21fccd7e3811fe372",
                 "supplierMember": "PyInstaller/bootloader/Windows-64bit-intel/runw.exe",
                 "sections": [{"name": ".text", "sha256": "d" * 64}]}
        result = module.minimal_review(inventory, review, policy, probe)
        self.assertEqual(result["status"], "blocked")
        components = {c["id"]: c for c in result["components"]}
        self.assertEqual(len([p for c in components.values() for p in c["payload"]]), 47)
        self.assertEqual(components["qt-translations"]["payload"], [])
        self.assertEqual(len(components["qt-translations"]["historical_non_native_payload"]), 94)
        self.assertTrue(components["qt-translations"]["sources"])
        self.assertNotIn("mesa-llvmpipe", components)
        self.assertNotIn("microsoft-ucrt", components)
        self.assertEqual(len(components["microsoft-msvc"]["payload"]), 10)
        self.assertIn("MICROSOFT_REDISTRIBUTION_UNRESOLVED", [b["code"] for b in result["blockers"]])
        self.assertIn("WINDOWS_RECOMBINATION_UNTESTED", [b["code"] for b in result["blockers"]])
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / "coverage"
            with self.assertRaisesRegex(ValueError, "MICROSOFT_REDISTRIBUTION_UNRESOLVED"):
                module.materialize(inventory, result, PACKAGING, output, cache=Path(temp))
            self.assertFalse(output.exists())
        for mutation in ("missing", "extra", "hash", "probe", "source", "scope"):
            inv, evidence = copy.deepcopy(inventory), copy.deepcopy(probe)
            if mutation == "missing": inv["payload"].pop()
            if mutation == "extra": inv["payload"].append({**loader, "path": "unknown.dll"})
            if mutation == "hash": inv["payload"][1]["sha256"] = "0" * 64
            if mutation == "probe": evidence["artifactSha256"] = "0" * 64
            if mutation == "source": evidence["builderSourceSha256"] = "0" * 64
            if mutation == "scope": evidence["loaderHashScope"] = "prefix-bytes"
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                module.minimal_review(inv, review, policy, evidence)

    def test_exact_review_audit_rejects_duplicate_paths(self):
        path = PACKAGING / "coverage_windows_ci.py"
        self.assertTrue(path.is_file(), "Windows exact-payload audit is missing")
        spec = importlib.util.spec_from_file_location("windows_coverage", path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        payload = [{"path": "a.dll", "sha256": "a" * 64, "kind": "binary", "size": 1}]
        review = {"schema": 2, "system": "Windows", "architecture": "x86_64",
                  "status": "blocked", "blockers": [{"code": "TERMS_UNRESOLVED"}],
                  "observed_payload": payload,
                  "components": [{"id": "a", "payload": payload, "statically_linked": []}]}
        with self.assertRaisesRegex(ValueError, "duplicate"):
            module.audit({"system": "Windows", "architecture": "AMD64",
                          "inventory": {"payload": payload + payload}}, review)


    def test_blocked_materialization_never_creates_output(self):
        module = load_module()
        review = json.loads(module.REVIEW.read_text())
        inventory = {"payload": review["observed_payload"]}
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / "evidence"
            with self.assertRaisesRegex(ValueError, "MICROSOFT_REDISTRIBUTION_UNRESOLVED"):
                module.materialize(inventory, review, PACKAGING, output, cache=Path(temp))
            self.assertFalse(output.exists())


    def test_cli_audits_exact_observed_payload_and_reports_blockers(self):
        review = json.loads((PACKAGING / "provenance/windows-ci-reviewed.json").read_text())
        with tempfile.TemporaryDirectory() as temp:
            inventory = Path(temp) / "inventory.json"
            inventory.write_text(json.dumps({"payload": review["observed_payload"]}))
            run = subprocess.run([sys.executable, str(PACKAGING / "coverage_windows_ci.py"),
                                  "audit", "--inventory", str(inventory)], capture_output=True, text=True)
            self.assertEqual(run.returncode, 2, run.stderr)
            report = json.loads(run.stdout)
            self.assertEqual(report["status"], "blocked")
            self.assertEqual(report["payload_count"], 198)
            self.assertGreater(report["verified_assets"], 50)
            self.assertEqual(len(report["blockers"]), 7)


    def test_offline_fetch_verifies_pinned_source_cache(self):
        module = load_module()
        with tempfile.TemporaryDirectory() as temp:
            cache = Path(temp)
            source = cache / "source.tar.xz"
            source.write_bytes(b"test-only-source-input")
            digest = __import__("hashlib").sha256(source.read_bytes()).hexdigest()
            review = {"source_inputs": [{"path": source.name, "sha256": digest,
                       "url": "https://example.invalid/source.tar.xz"}]}
            report = module.fetch_sources(review, cache, offline=True)
            self.assertEqual(report["status"], "evidence-only")
            source.write_bytes(b"corrupted")
            with self.assertRaisesRegex(ValueError, "hash mismatch"):
                module.fetch_sources(review, cache, offline=True)


    def test_review_maps_every_observed_path_once(self):
        module = load_module()
        review = json.loads(module.REVIEW.read_text())
        report = module.audit({"payload": review["observed_payload"]}, review)
        self.assertEqual(report["payload_count"], 198)
        self.assertEqual(len({e["path"] for c in review["components"] for e in c["payload"]}), 198)

    def test_mutated_missing_and_new_payloads_are_rejected(self):
        module = load_module()
        review = json.loads(module.REVIEW.read_text())
        mutations = [lambda p: p.pop(), lambda p: p.append({**p[0], "path": "unknown.dll"}),
                     lambda p: p[0].update(sha256="0" * 64),
                     lambda p: p[0].update(size=p[0]["size"] + 1),
                     lambda p: p[0].update(kind="data")]
        for mutate in mutations:
            with self.subTest(mutation=mutate):
                payload = copy.deepcopy(review["observed_payload"])
                mutate(payload)
                with self.assertRaisesRegex(ValueError, "Unreviewed"):
                    module.audit({"payload": payload}, review)

    def test_wrong_platform_is_rejected(self):
        module = load_module()
        review = json.loads(module.REVIEW.read_text())
        for system, arch in [("Linux", "x86_64"), ("Windows", "arm64")]:
            with self.subTest(system=system, arch=arch), self.assertRaisesRegex(ValueError, "platform"):
                module.audit({"system": system, "architecture": arch,
                              "payload": review["observed_payload"]}, review)

    def test_case_alias_cannot_hide_duplicate_windows_path(self):
        module = load_module()
        review = json.loads(module.REVIEW.read_text())
        payload = copy.deepcopy(review["observed_payload"])
        payload.append({**payload[1], "path": payload[1]["path"].upper()})
        with self.assertRaisesRegex(ValueError, "duplicate"):
            module.audit({"payload": payload}, review)

    def test_unknown_static_record_is_rejected(self):
        module = load_module()
        review = json.loads(module.REVIEW.read_text())
        review["components"][0]["statically_linked"] = ["not-reviewed"]
        with self.assertRaisesRegex(ValueError, "static"):
            module.audit({"payload": review["observed_payload"]}, review)

    def test_proprietary_terms_do_not_claim_source_obligations(self):
        review = json.loads(load_module().REVIEW.read_text())
        byid = {c["id"]: c for c in review["components"]}
        for name in ["microsoft-msvc", "microsoft-ucrt"]:
            self.assertTrue(byid[name]["license"].startswith("LicenseRef-Microsoft-"))
            self.assertNotIn("sources", byid[name])
            self.assertNotIn("source_basis", byid[name])
        self.assertIn("microsoft-msvc", byid["cpython"]["statically_linked"])
        self.assertIn("qt-static-wintab", byid["qt-plugins"]["statically_linked"])
        self.assertIn("cffi-static-windows-libffi", byid["cffi"]["statically_linked"])
        self.assertIn("shiboken-python-headers", byid["pyside-shiboken"]["statically_linked"])
        self.assertIn("pyinstaller-static-zlib", byid["pyinstaller-embedded-loader"]["statically_linked"])

    def test_changed_notice_cannot_be_materialized(self):
        module = load_module()
        review = json.loads(module.REVIEW.read_text())
        review["components"][0]["notices"][0]["sha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "Missing or changed"):
            module.verify_assets(review, PACKAGING)

    def test_unsafe_asset_path_is_rejected(self):
        module = load_module()
        review = json.loads(module.REVIEW.read_text())
        review["components"][0]["notices"][0]["path"] = "../outside"
        with self.assertRaisesRegex(ValueError, "Unsafe"):
            module.verify_assets(review, PACKAGING)

    def test_reviewed_manifest_still_uses_shared_native_gate(self):
        if platform.system() == "Windows" and platform.machine().lower() in {"amd64", "x86_64"}:
            self.skipTest("This test checks cross-platform rejection, not native qualification")
        module = load_module()
        with tempfile.TemporaryDirectory() as temp:
            base = Path(temp)
            notice = base / "notice.txt"
            notice.write_bytes(b"test-only-notice")
            p = [{"path": "example.dll", "sha256": "a" * 64}]
            review = {"schema": 2, "system": "Windows", "architecture": "x86_64",
                      "status": "reviewed", "blockers": [], "observed_payload": p,
                      "components": [{"id": "example", "payload": p, "statically_linked": [],
                          "version": "test", "license": "MIT", "provenance": "test fixture",
                          "notices": [{"path": "notice.txt", "sha256": __import__("hashlib").sha256(notice.read_bytes()).hexdigest()}]}]}
            output = base / "output"
            with self.assertRaisesRegex(ValueError, "platform mismatch"):
                module.materialize({"payload": p}, review, base, output, cache=base)
            self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
