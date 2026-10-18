"""Public workflow contracts; stdlib-only, including the actual APK gate."""
import os
from pathlib import Path
import re
import subprocess
import tempfile
import textwrap
import unittest
from zipfile import ZipFile

WORKFLOWS = Path(__file__).resolve().parents[1]
CI = (WORKFLOWS / "ci.yml").read_text()
RELEASE = (WORKFLOWS / "release-apk.yml").read_text()


def run_block(source, step):
    """Extract a literal run block without a YAML dependency on the runner."""
    body = source.split(f"      - name: {step}\n", 1)[1]
    body = re.split(r"\n      - (?:name:|uses:)", body, maxsplit=1)[0]
    match = re.search(r"        run: \|\n((?:          .*\n|\n)+)", body + "\n")
    if not match:
        raise AssertionError(f"No literal run block for {step}")
    return textwrap.dedent(match.group(1))


class PublicWorkflowContracts(unittest.TestCase):
    def test_ci_covers_lab_and_workflow_changes(self):
        for trigger in ("carplay-lab/**", ".github/workflows/release-apk.yml",
                        ".github/workflows/tests/**", "gradle.properties", "external/**"):
            self.assertIn(f"- '{trigger}'", CI)
        tasks = run_block(CI, "Run unit tests")
        for module in ("app", "companion", "carplay-lab"):
            self.assertIn(f":{module}:testDebugUnitTest", tasks)
            self.assertIn(f"{module}/build/reports/tests/testDebugUnitTest/", CI)
            self.assertIn(f"{module}/build/test-results/testDebugUnitTest/", CI)
        self.assertIn("if: always()", CI)
        self.assertIn("python3 -m unittest discover -s .github/workflows/tests -v", CI)

    def test_public_builds_do_not_inject_private_assets(self):
        for source in (CI, RELEASE):
            executable = "\n".join(line for line in source.splitlines()
                                   if not line.lstrip().startswith("#"))
            self.assertNotRegex(executable, r"-P\s*oalCarPlayMfiAssetsDir")
            self.assertNotIn("ORG_GRADLE_PROJECT_oalCarPlayMfiAssetsDir", executable)
            self.assertNotRegex(executable, r"secrets\.[^ }]*(?:MFI|CARPLAY|IDENTITY)")
        self.assertNotIn("bundleRelease", CI)
        self.assertNotIn("bundleRelease", RELEASE)
        self.assertNotIn("personalization", RELEASE)
        self.assertEqual(set(re.findall(r"secrets\.([A-Z0-9_]+)", RELEASE)), {
            "UPLOAD_KEYSTORE_BASE64", "UPLOAD_KEY_ALIAS", "UPLOAD_KEY_PASSWORD",
            "UPLOAD_STORE_PASSWORD",
        })
        self.assertNotIn("secrets.", CI)

    def test_toolchain_and_native_paths(self):
        for source in (CI, RELEASE):
            self.assertIn("java-version: 17", source)
            for package in ("platforms;android-36", "build-tools;34.0.0", "ndk;28.2.13676358", "cmake;3.22.1"):
                self.assertIn(package, source)
            native = run_block(source, "Download native deps")
            self.assertIn('--repo "$GITHUB_REPOSITORY"', native)
            self.assertIn("-C app/src/main/cpp/", native)
            self.assertIn("third_party/boost/include/boost/asio.hpp", native)
            self.assertIn("third_party/openssl/${ABI}/lib/libssl.a", native)
            self.assertIn("third_party/aasdk/${ABI}/lib/libaasdk.a", native)

    def test_archive_gate_precedes_exact_upload(self):
        gate = RELEASE.index("- name: Verify public APKs contain no private CarPlay identity")
        self.assertLess(RELEASE.index("- name: Rename APKs"), gate)
        self.assertLess(gate, RELEASE.index("- name: Upload APKs to release"))
        self.assertIn("fail_on_unmatched_files: true", RELEASE)
        self.assertNotIn("continue-on-error:", RELEASE)
        self.assertNotIn("-*.apk", RELEASE)
        for label, module in (("car", "app"), ("companion", "companion")):
            self.assertIn(f"{module}/build/outputs/apk/release/openautolink-{label}-${{{{ steps.tag.outputs.value }}}}.apk", RELEASE)
        self.assertIn("app-release.apk", RELEASE)
        self.assertIn("companion-release.apk", RELEASE)
        # Existing release signing is required; companion has no unsigned override.
        for step in ("Build car app release APK", "Build companion release APK"):
            self.assertNotIn("-PoalBuilderUnsigned", run_block(RELEASE, step))
        self.assertNotIn("rm -f secrets/upload-key.jks", RELEASE)
        self.assertNotIn("qualify-desktop-aab", RELEASE)

    def apk_gate(self, entries=None, module="app", missing=False, corrupt=False):
        entries = entries or ["classes.dex", "assets/public-config.json"]
        gate = run_block(RELEASE, "Verify public APKs contain no private CarPlay identity")
        self.assertEqual(gate.count('/apksigner" verify'), 2)
        # APK fixtures are deliberately unsigned ZIPs; execute only the real
        # credential scan. Android signature validation requires the SDK.
        script = "python3 - <<'PY'\n" + gate.split("python3 - <<'PY'\n", 1)[1]
        with tempfile.TemporaryDirectory() as tmp:
            for current, label in (("app", "car"), ("companion", "companion")):
                apk = Path(tmp) / current / "build/outputs/apk/release" / f"openautolink-{label}-v0.1.400.apk"
                apk.parent.mkdir(parents=True)
                if current == module and missing:
                    continue
                if current == module and corrupt:
                    apk.write_bytes(b"not an APK")
                    continue
                with ZipFile(apk, "w") as archive:
                    for entry in entries if current == module else ["classes.dex"]:
                        archive.writestr(entry, b"synthetic test fixture")
            return subprocess.run(["bash", "-e", "-o", "pipefail", "-c", script],
                                  cwd=tmp, env={**os.environ, "RELEASE_TAG": "v0.1.400"},
                                  text=True, capture_output=True)

    def test_archive_gate_accepts_clean_apks(self):
        result = self.apk_gate()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.count("Verified credential-free public APK:"), 2)

    def test_archive_gate_rejects_identity_in_either_apk(self):
        for module in ("app", "companion"):
            for entry in ("assets/offline-mfi/identity.pk8", "assets/offline-mfi/certificate.p7b",
                          "assets/offline-mfi/", "assets/OFFLINE-MFI/other.bin",
                          "assets\\offline-mfi\\identity.pk8", "assets/identity.pk8",
                          "assets/certificate.p7b"):
                with self.subTest(module=module, entry=entry):
                    result = self.apk_gate([entry], module)
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn("refusing public upload", result.stderr)
                    self.assertNotIn(entry, result.stderr)  # Do not enumerate private files.

    def test_archive_gate_rejects_missing_or_corrupt_apks(self):
        for module in ("app", "companion"):
            for kwargs in ({"missing": True}, {"corrupt": True}):
                with self.subTest(module=module, **kwargs):
                    self.assertNotEqual(self.apk_gate(module=module, **kwargs).returncode, 0)

    def tag_step(self, step, tag):
        script = run_block(RELEASE, step)
        self.assertNotIn("${{", script)
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "output"
            result = subprocess.run(["bash", "-e", "-o", "pipefail", "-c", script],
                                    cwd=tmp, env={**os.environ, "RELEASE_TAG": tag,
                                                  "GITHUB_OUTPUT": str(output)},
                                    text=True, capture_output=True)
            return result, output.read_text() if output.exists() else ""

    def test_tag_input_is_validated_not_evaluated(self):
        result, output = self.tag_step("Resolve release tag", "v0.1.400")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(output, "value=v0.1.400\n")
        for tag in ("", "v0.1.0", "v0.1.0400", "v0.2.1", "v0.1.400\nvalue=other",
                    'v0.1.400\"; exit 0; #', "$(touch injected)"):
            with self.subTest(tag=tag):
                result, output = self.tag_step("Resolve release tag", tag)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(output, "")

    def test_separate_template_publication_contract(self):
        path = WORKFLOWS / 'publish-personalization-template.yml'
        self.assertTrue(path.is_file(), 'Missing independent template workflow')
        source = path.read_text()
        self.assertIn('types: [published]', source)
        self.assertIn('workflow_dispatch:', source)
        self.assertIn('cancel-in-progress: false', source)
        self.assertIn('github.event.release.tag_name || inputs.tag', source)
        self.assertNotIn('secrets.', source)
        self.assertNotIn('assembleRelease', source)
        self.assertNotIn('UPLOAD_KEY', source)
        self.assertNotIn('source_preparation', source)
        self.assertIn('ref: refs/tags/${{ steps.tag.outputs.value }}', source)
        self.assertIn('persist-credentials: false', source)
        producer, uploader = source.split('\n  upload:', 1)
        self.assertIn('contents: read', producer)
        self.assertNotIn('contents: write', producer)
        self.assertIn('contents: write', uploader)
        self.assertIn('needs: produce', uploader)
        self.assertIn('needs: [produce, upload]', uploader)
        self.assertIn('template_mode: published', uploader)
        self.assertIn('expected_release_tag: ${{ needs.produce.outputs.release_tag }}', uploader)
        self.assertIn('expected_source_commit: ${{ needs.produce.outputs.source_commit }}', uploader)
        self.assertIn('builder_commit: ${{ needs.produce.outputs.source_commit }}', uploader)
        self.assertIn('--release-id "$RELEASE_ID"', uploader)
        build = run_block(source, 'Build unsigned credential-free personalization AAB')
        self.assertIn('-PappId=com.openautolink.template -PoalBuilderUnsigned=true', build)
        self.assertNotIn('GH_TOKEN', build)
        self.assertIn("python-version: '3.12'", source)
        self.assertIn('java-version: 17', source)
        self.assertIn('a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29', source)
        self.assertEqual(run_block(source, 'Download native deps'), run_block(RELEASE, 'Download native deps'))
        self.assertLess(source.index('Preflight clean tag and required publisher tree'), source.index('Set up Android SDK'))
        self.assertIn('python -I -S desktop-builder/scripts/publish_template.py', source)
        self.assertIn('python -I -S desktop-builder/scripts/upload_template.py', source)

    def test_historical_tag_without_publisher_fails_explicit_preflight(self):
        path = WORKFLOWS / 'publish-personalization-template.yml'
        self.assertTrue(path.is_file(), 'Missing independent template workflow')
        script = run_block(path.read_text(), 'Preflight clean tag and required publisher tree')
        with tempfile.TemporaryDirectory() as tmp:
            result = subprocess.run(['bash', '-e', '-o', 'pipefail', '-c', script], cwd=tmp,
                                    env={**os.environ, 'RELEASE_TAG': 'v0.1.530'}, text=True, capture_output=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('publisher tree', result.stderr)
        self.assertIn('new release after merge', result.stderr)

    def test_version_code_limit_and_tag_derivation(self):
        for tag, expected in (("v0.1.400", 400), ("v0.1.2100000000", 2100000000)):
            result, output = self.tag_step("Derive version from release tag", tag)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn(f"code={expected}\n", output)
            self.assertIn(f"name={tag[1:]}\n", output)
        for tag in ("v0.1.2100000001", "v0.1.999999999999999999999999", "v0.2.1"):
            result, output = self.tag_step("Derive version from release tag", tag)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(output, "")


if __name__ == "__main__":
    unittest.main()
