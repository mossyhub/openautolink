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
            self.assertEqual(analysis.binaries, [])
            self.assertIn(analysis.binaries, exe.call_args.args)

    @staticmethod
    def resource_relocation_pair():
        """Non-runnable structural fixture, not Windows resource API evidence."""
        import struct
        original = bytearray(WindowsCoverageTests.minimal_pe() + bytes(512))
        struct.pack_into("<H", original, 134, 2)
        struct.pack_into("<I", original, 152 + 8, 512)
        struct.pack_into("<I", original, 152 + 56, 12288)
        struct.pack_into("<8sIIIIIIHHI", original, 432, b".reloc", 32, 8192, 512,
                         1024, 0, 0, 0, 0, 0x42000040)
        struct.pack_into("<II", original, 152 + 112 + 5 * 8, 8200, 16)
        original[1024:1056] = bytes(range(32))
        changed = bytearray(original + bytes(512))
        changed[1536:2048] = original[1024:1536]
        changed[1024:1536] = bytes(512)
        struct.pack_into("<H", changed, 134, 3)
        struct.pack_into("<I", changed, 152 + 8, 1024)
        struct.pack_into("<I", changed, 152 + 56, 16384)
        struct.pack_into("<I", changed, 432 + 12, 12288)
        struct.pack_into("<I", changed, 432 + 20, 1536)
        struct.pack_into("<8sIIIIIIHHI", changed, 472, b".rsrc", 64, 8192, 512,
                         1024, 0, 0, 0, 0, 0x40000040)
        struct.pack_into("<II", changed, 152 + 112 + 2 * 8, 8192, 64)
        struct.pack_into("<II", changed, 152 + 112 + 5 * 8, 12296, 16)
        return bytes(changed), bytes(original)

    @unittest.skipUnless(importlib.util.find_spec("pefile"), "PE parser dependency")
    def test_resource_insertion_moves_only_identical_relocation_section(self):
        module = load_module()
        changed, original = self.resource_relocation_pair()
        result = module.compare_pe_loader(changed, original)
        self.assertEqual([s["name"] for s in result["sections"]], [".text", ".reloc"])
        self.assertEqual(result["sections"][1]["rva"], 12288)
        self.assertEqual(result["sections"][1]["raw_offset"], 1536)
        self.assertEqual(len(result["resourceSections"]), 1)

    def assert_resource_movement_negative_controls(self, module, changed, supplier):
        """Keep section bytes intact when testing movement, not just corruption."""
        import pefile
        import struct
        with pefile.PE(data=changed, fast_load=True) as pe:
            sections = {s.Name.rstrip(b"\0").decode(): s for s in pe.sections}
            reloc, resource, code = sections[".reloc"], sections[".rsrc"], sections[".text"]
            directory = pe.OPTIONAL_HEADER.DATA_DIRECTORY[5].get_file_offset()
            mutations = {
                "changed-reloc-byte": (reloc.PointerToRawData, None),
                "changed-code-byte": (code.PointerToRawData, None),
                "moved-code-rva": (code.get_field_absolute_offset("VirtualAddress"), 0x100000),
                "changed-reloc-attributes": (reloc.get_field_absolute_offset("Characteristics"), reloc.Characteristics ^ 0x80000000),
                "changed-reloc-virtual-size": (reloc.get_field_absolute_offset("Misc_VirtualSize"), reloc.Misc_VirtualSize + 1),
                "changed-reloc-raw-size": (reloc.get_field_absolute_offset("SizeOfRawData"), reloc.SizeOfRawData - 1),
                "wrong-reloc-directory-offset": (directory, pe.OPTIONAL_HEADER.DATA_DIRECTORY[5].VirtualAddress + 1),
                "wrong-reloc-directory-size": (directory + 4, pe.OPTIONAL_HEADER.DATA_DIRECTORY[5].Size + 1),
                "reloc-directory-outside": (directory, reloc.VirtualAddress + reloc.SizeOfRawData),
                "reloc-directory-left-behind": (directory, resource.VirtualAddress),
                "empty-reloc-directory": (directory + 4, 0),
                "overlap-reloc-raw": (reloc.get_field_absolute_offset("PointerToRawData"), resource.PointerToRawData),
                "overlap-reloc-rva": (reloc.get_field_absolute_offset("VirtualAddress"), resource.VirtualAddress),
                "overlap-resource-raw": (resource.get_field_absolute_offset("PointerToRawData"), code.PointerToRawData),
                "overlap-resource-rva": (resource.get_field_absolute_offset("VirtualAddress"), code.VirtualAddress),
                "executable-resource": (resource.get_field_absolute_offset("Characteristics"), resource.Characteristics | 0x20000000),
                "empty-resource-directory": (pe.OPTIONAL_HEADER.DATA_DIRECTORY[2].get_file_offset() + 4, 0),
                "bad-file-alignment": (pe.OPTIONAL_HEADER.get_field_absolute_offset("FileAlignment"), 0),
                "bad-section-alignment": (pe.OPTIONAL_HEADER.get_field_absolute_offset("SectionAlignment"), 513),
                "changed-entrypoint": (pe.OPTIONAL_HEADER.get_field_absolute_offset("AddressOfEntryPoint"), 1),
                "changed-import-directory": (pe.OPTIONAL_HEADER.DATA_DIRECTORY[1].get_file_offset(), 1),
                "wrong-image-size": (pe.OPTIONAL_HEADER.get_field_absolute_offset("SizeOfImage"), pe.OPTIONAL_HEADER.SizeOfImage + 4096),
                "wrong-initialized-size": (pe.OPTIONAL_HEADER.get_field_absolute_offset("SizeOfInitializedData"), pe.OPTIONAL_HEADER.SizeOfInitializedData + 512),
                "moved-resource-not-at-old-reloc": (resource.get_field_absolute_offset("VirtualAddress"), resource.VirtualAddress - 4096),
            }
            if ".rdata" in sections:
                data = sections[".rdata"]
                mutations["changed-data-byte"] = (data.PointerToRawData, None)
                mutations["moved-data-rva"] = (data.get_field_absolute_offset("VirtualAddress"), 0x200000)
            for label, (offset, value) in mutations.items():
                mutated = bytearray(changed)
                if value is None:
                    mutated[offset] ^= 1
                else:
                    struct.pack_into("<I", mutated, offset, value)
                with self.subTest(mutation=label), self.assertRaises(ValueError):
                    module.compare_pe_loader(bytes(mutated), supplier)
            # Raw movement retains the complete bytes of the section, and the
            # relocation RVA controls also update directory 5 consistently.
            for label, section, delta in [("moved-code-raw", code, 512),
                                           ("reloc-raw-gap", reloc, 512),
                                           ("reloc-raw-misaligned", reloc, 1)]:
                offset = ((len(changed) + 511) // 512) * 512 + delta
                mutated = bytearray(changed + bytes(offset + section.SizeOfRawData - len(changed)))
                mutated[offset:offset + section.SizeOfRawData] = changed[section.PointerToRawData:section.PointerToRawData + section.SizeOfRawData]
                struct.pack_into("<I", mutated, section.get_field_absolute_offset("PointerToRawData"), offset)
                with self.subTest(mutation=label), self.assertRaises(ValueError):
                    module.compare_pe_loader(bytes(mutated), supplier)
            for delta in (1, pe.OPTIONAL_HEADER.SectionAlignment):
                mutated = bytearray(changed)
                struct.pack_into("<I", mutated, reloc.get_field_absolute_offset("VirtualAddress"), reloc.VirtualAddress + delta)
                struct.pack_into("<I", mutated, directory, pe.OPTIONAL_HEADER.DATA_DIRECTORY[5].VirtualAddress + delta)
                with self.subTest(relocation_rva_delta=delta), self.assertRaises(ValueError):
                    module.compare_pe_loader(bytes(mutated), supplier)

    @unittest.skipUnless(importlib.util.find_spec("pefile"), "PE parser dependency")
    def test_resource_insertion_negative_controls(self):
        changed, supplier = self.resource_relocation_pair()
        self.assert_resource_movement_negative_controls(load_module(), changed, supplier)

    @unittest.skipUnless(importlib.util.find_spec("pefile"), "PE parser dependency")
    def test_resource_insertion_preserves_other_headers_and_section_attributes(self):
        import pefile
        module = load_module()
        changed, supplier = self.resource_relocation_pair()
        with pefile.PE(data=changed, fast_load=True) as pe:
            for owner, field in [(pe.OPTIONAL_HEADER, key) for key in (
                    "SizeOfHeaders", "SizeOfCode", "BaseOfCode", "SizeOfUninitializedData",
                    "SizeOfImage", "SizeOfInitializedData",
                    "MajorImageVersion", "MinorImageVersion", "MajorLinkerVersion", "MinorLinkerVersion", "Reserved1")
                    ] + [(pe.FILE_HEADER, key) for key in ("PointerToSymbolTable", "NumberOfSymbols")
                    ] + [(next(s for s in pe.sections if s.Name.rstrip(b"\0") == b".reloc"), key) for key in (
                    "PointerToRelocations", "PointerToLinenumbers", "NumberOfRelocations", "NumberOfLinenumbers")]:
                mutated = bytearray(changed)
                mutated[owner.get_field_absolute_offset(field)] ^= 1
                with self.subTest(header=field), self.assertRaises(ValueError):
                    module.compare_pe_loader(bytes(mutated), supplier)

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

    @unittest.skipUnless(importlib.util.find_spec("pefile"), "PE parser dependency")
    def test_pe_rejection_diagnostics_distinguish_bytes_layout_and_headers(self):
        import hashlib
        import struct
        module = load_module()
        original = self.minimal_pe()
        changed = bytearray(original)
        changed[512] ^= 1
        changed[152 + 16] ^= 1
        with self.assertRaises(ValueError) as caught:
            module.compare_pe_loader(bytes(changed), original)
        diagnostic = caught.exception.diagnostics
        self.assertEqual(diagnostic["supplierPrefixSha256"], hashlib.sha256(original).hexdigest())
        self.assertEqual(diagnostic["candidatePrefixSha256"], hashlib.sha256(changed).hexdigest())
        self.assertEqual(diagnostic["sectionDifferences"][0]["changedFields"], ["sha256"])
        self.assertEqual(diagnostic["sectionDifferences"][0]["firstDifferingOffsets"], [0])
        self.assertEqual(diagnostic["headerDifferences"], ["AddressOfEntryPoint"])
        self.assertEqual(diagnostic["candidate"]["headers"]["SizeOfHeaders"], 512)
        self.assertEqual(diagnostic["candidate"]["sections"][0]["raw_offset"], 512)
        self.assertEqual(diagnostic["supplier"]["sections"][0]["raw_offset"], 512)
        moved = bytearray(original + bytes(512))
        moved[1024:1536], moved[512:1024] = original[512:1024], bytes(512)
        struct.pack_into("<I", moved, 392 + 20, 1024)
        with self.assertRaises(ValueError) as caught:
            module.compare_pe_loader(bytes(moved), original)
        difference = caught.exception.diagnostics["sectionDifferences"][0]
        self.assertEqual(difference["changedFields"], ["raw_offset"])
        self.assertNotIn("firstDifferingOffsets", difference)

    @unittest.skipUnless(importlib.util.find_spec("pefile"), "PE parser dependency")
    def test_pe_rejection_diagnostics_are_bounded_without_ignoring_extra_sections(self):
        import struct
        module = load_module()
        # Twenty distinct nonoverlapping sections, all with changed bytes.
        original = bytearray(self.minimal_pe()[:512] + bytes(1536 + 20 * 512 - 512))
        struct.pack_into("<H", original, 134, 20)
        struct.pack_into("<II", original, 152 + 56, 21 * 4096, 1536)
        for index in range(20):
            struct.pack_into("<8sIIIIIIHHI", original, 392 + index * 40,
                             f".s{index:02}".encode(), 512, (index + 1) * 4096, 512,
                             1536 + index * 512, 0, 0, 0, 0, 0x60000020)
        changed = bytearray(original)
        changed[1536:] = b"x" * (20 * 512)
        with self.assertRaises(ValueError) as caught:
            module.compare_pe_loader(bytes(changed), bytes(original))
        diagnostic = caught.exception.diagnostics
        self.assertEqual(diagnostic["sectionDifferenceCount"], 20)
        self.assertEqual(len(diagnostic["sectionDifferences"]), 16)
        self.assertTrue(diagnostic["sectionDifferencesTruncated"])
        self.assertEqual(len(diagnostic["candidate"]["sections"]), 16)
        self.assertTrue(diagnostic["candidate"]["sectionsTruncated"])
        for difference in diagnostic["sectionDifferences"]:
            self.assertEqual(difference["firstDifferingOffsets"], list(range(8)))
            self.assertEqual(difference["comparedPrefixBytes"], 8)
            self.assertTrue(difference["byteScanLimited"])
        # The seventeenth changed section must still reject, even though it is
        # outside the bounded section summary.
        changed = bytearray(original)
        changed[1536 + 16 * 512] ^= 1
        with self.assertRaises(ValueError) as caught:
            module.compare_pe_loader(bytes(changed), bytes(original))
        self.assertEqual(caught.exception.diagnostics["sectionDifferences"][0]["name"], ".s16")

    @unittest.skipUnless(importlib.util.find_spec("pefile"), "PE parser dependency")
    def test_pe_rejects_code_change_beyond_diagnostic_byte_scan(self):
        import struct
        module = load_module()
        size = 1024 * 1024 + 512
        original = bytearray(self.minimal_pe()[:1024] + bytes(size - 512))
        struct.pack_into("<I", original, 152 + 56, size + 8192)
        struct.pack_into("<I", original, 392 + 8, size)
        struct.pack_into("<I", original, 392 + 16, size)
        changed = bytearray(original)
        changed[-1] ^= 1
        with self.assertRaises(ValueError) as caught:
            module.compare_pe_loader(bytes(changed), bytes(original))
        difference = caught.exception.diagnostics["sectionDifferences"][0]
        self.assertEqual(difference["changedFields"], ["sha256"])
        self.assertEqual(difference["firstDifferingOffsets"], [])
        self.assertEqual(difference["comparedPrefixBytes"], 1024 * 1024)
        self.assertTrue(difference["byteScanLimited"])

    @unittest.skipUnless(importlib.util.find_spec("pefile"), "PE parser dependency")
    def test_official_supplier_pe_metadata_replay_and_negative_controls(self):
        """Opt-in real pinned PE bytes; no Windows resource API/native execution."""
        import hashlib
        import os
        import pefile
        import zipfile
        wheel_path = os.environ.get("OAL_WINDOWS_SUPPLIER_WHEEL")
        if not wheel_path:
            self.skipTest("Set OAL_WINDOWS_SUPPLIER_WHEEL for offline pinned supplier replay")
        module = load_module()
        wheel = Path(wheel_path)
        review = json.loads(module.REVIEW.read_text())
        pinned = next(e for e in review["supplier_inputs"] if e["path"] == wheel.name)
        self.assertEqual(hashlib.sha256(wheel.read_bytes()).hexdigest(), pinned["sha256"])
        with zipfile.ZipFile(wheel) as archive:
            supplier = archive.read("PyInstaller/bootloader/Windows-64bit-intel/runw.exe")
            winutils_source = archive.read("PyInstaller/utils/win32/winutils.py")
        self.assertEqual(len(module.compare_pe_loader(supplier, supplier)["sections"]), 6)
        # Execute the pinned supplier's actual timestamp routine, which is
        # platform-independent. No executable is launched and no app is built.
        winutils = {}
        exec(compile(winutils_source, "pinned-wheel/winutils.py", "exec"), winutils)
        with tempfile.TemporaryDirectory() as temp:
            exe = Path(temp) / "metadata-replay.exe"
            exe.write_bytes(supplier)
            winutils["set_exe_build_timestamp"](str(exe), 1700000000)
            with pefile.PE(str(exe)) as pe:
                pe.OPTIONAL_HEADER.CheckSum = pe.generate_checksum()
                changed = pe.write()
            self.assertNotEqual(changed, supplier)
            self.assertEqual(module.normalize_pe_build_metadata(changed),
                             module.normalize_pe_build_metadata(supplier))
            self.assertEqual(len(module.compare_pe_loader(changed, supplier)["sections"]), 6)
        with pefile.PE(data=supplier) as pe:
            text = next(s for s in pe.sections if s.Name.rstrip(b"\0") == b".text")
            mutations = {
                "code": text.PointerToRawData,
                "entrypoint": pe.OPTIONAL_HEADER.get_field_absolute_offset("AddressOfEntryPoint"),
                "import-directory": pe.OPTIONAL_HEADER.DATA_DIRECTORY[1].get_field_absolute_offset("VirtualAddress"),
                "debug-pointer": pe.DIRECTORY_ENTRY_DEBUG[0].struct.get_field_absolute_offset("PointerToRawData"),
            }
        for label, offset in mutations.items():
            with self.subTest(mutation=label):
                changed = bytearray(supplier)
                changed[offset] ^= 1
                with self.assertRaises(ValueError) as caught:
                    module.compare_pe_loader(bytes(changed), supplier)
                diagnostic = caught.exception.diagnostics
                if label == "code":
                    self.assertEqual(diagnostic["sectionDifferences"][0]["name"], ".text")
                elif label == "entrypoint":
                    self.assertEqual(diagnostic["headerDifferences"], ["AddressOfEntryPoint"])
                elif label == "import-directory":
                    self.assertEqual(diagnostic["directoryDifferences"], [1])
                else:
                    self.assertEqual(diagnostic["sectionDifferences"][0]["name"], ".rdata")

    @unittest.skipUnless(importlib.util.find_spec("pefile"), "PE parser dependency")
    def test_native_resource_insertion_diagnostic_with_official_loader(self):
        """Replay native structural evidence, NOT the absent frozen EXE/resources."""
        import hashlib
        import os
        import pefile
        import struct
        import zipfile
        wheel_path = os.environ.get("OAL_WINDOWS_SUPPLIER_WHEEL")
        diagnostic_path = os.environ.get("OAL_WINDOWS_PE_DIAGNOSTIC")
        if not wheel_path or not diagnostic_path:
            self.skipTest("Set OAL_WINDOWS_SUPPLIER_WHEEL and OAL_WINDOWS_PE_DIAGNOSTIC")
        diagnostic_bytes = Path(diagnostic_path).read_bytes()
        self.assertEqual(hashlib.sha256(diagnostic_bytes).hexdigest(),
                         "d89bc85f17130e089636239326abc9801844e1080a22d224ce786a589f3fcdb4")
        evidence = json.loads(diagnostic_bytes)
        self.assertEqual(evidence["identity"]["runId"], "38955553640")
        self.assertEqual(evidence["identity"]["checkoutCommit"],
                         "53508c1617a5c28cdc44a707f120cfdea7659f86")
        wheel = Path(wheel_path)
        self.assertEqual(hashlib.sha256(wheel.read_bytes()).hexdigest(), evidence["supplierWheelSha256"])
        with zipfile.ZipFile(wheel) as archive:
            supplier = archive.read(evidence["supplierMember"])
        comparison = evidence["comparison"]
        self.assertEqual(hashlib.sha256(supplier).hexdigest(), comparison["supplierPrefixSha256"])
        self.assertEqual(comparison["sectionDifferences"],
                         [{"name": ".reloc", "changedFields": ["raw_offset", "rva"]}])
        self.assertEqual(comparison["headerDifferences"], [])
        self.assertEqual(comparison["directoryDifferences"], [5])
        module = load_module()
        candidate = comparison["candidate"]
        rows = candidate["sections"]
        changed = bytearray(max(r["raw_offset"] + r["raw_size"] for r in rows))
        with pefile.PE(data=supplier, fast_load=True) as pe:
            changed[:pe.OPTIONAL_HEADER.SizeOfHeaders] = supplier[:pe.OPTIONAL_HEADER.SizeOfHeaders]
            for key, value in candidate["headers"].items():
                owner = pe.FILE_HEADER if key in {"Machine", "NumberOfSections", "Characteristics"} else pe.OPTIONAL_HEADER
                setattr(owner, key, value)
            for index, address, size in candidate["directories"]:
                pe.OPTIONAL_HEADER.DATA_DIRECTORY[index].VirtualAddress = address
                pe.OPTIONAL_HEADER.DATA_DIRECTORY[index].Size = size
            rewritten = pe.write()
            changed[:pe.OPTIONAL_HEADER.SizeOfHeaders] = rewritten[:pe.OPTIONAL_HEADER.SizeOfHeaders]
            old_sections = {s.Name.rstrip(b"\0").decode(): s for s in pe.sections}
            table = pe.sections[0].get_file_offset()
            for index, row in enumerate(rows):
                # Resource bytes were deliberately not retained by diagnostics.
                # Zero placeholders replay layout only; never assert their hash.
                if row["name"] != ".rsrc":
                    old = old_sections[row["name"]]
                    changed[row["raw_offset"]:row["raw_offset"] + row["raw_size"]] = (
                        supplier[old.PointerToRawData:old.PointerToRawData + old.SizeOfRawData])
                struct.pack_into("<8sIIIIIIHHI", changed, table + index * 40,
                                 row["name"].encode(), row["virtual_size"], row["rva"], row["raw_size"],
                                 row["raw_offset"], 0, 0, 0, 0, row["characteristics"])
        # Independently bind every retained header/directory/section to the native
        # diagnostic. Only .rsrc bytes and whole-prefix hashes are not replayed.
        for blob, summary in [(supplier, comparison["supplier"]), (bytes(changed), candidate)]:
            normalized = module.normalize_pe_build_metadata(blob)
            with pefile.PE(data=normalized, fast_load=True) as pe:
                for key, value in summary["headers"].items():
                    owner = pe.FILE_HEADER if key in {"Machine", "NumberOfSections", "Characteristics"} else pe.OPTIONAL_HEADER
                    self.assertEqual(getattr(owner, key), value, key)
                self.assertEqual([[i, d.VirtualAddress, d.Size] for i, d in enumerate(pe.OPTIONAL_HEADER.DATA_DIRECTORY)],
                                 summary["directories"])
                self.assertFalse(summary["sectionsTruncated"])
                self.assertEqual(len(pe.sections), summary["sectionCount"])
                observed = {s.Name.rstrip(b"\0").decode(): s for s in pe.sections}
                for row in summary["sections"]:
                    section = observed[row["name"]]
                    actual = {"name": row["name"], "rva": section.VirtualAddress,
                              "virtual_size": section.Misc_VirtualSize, "raw_size": section.SizeOfRawData,
                              "raw_offset": section.PointerToRawData, "characteristics": section.Characteristics,
                              "sha256": hashlib.sha256(section.get_data()).hexdigest()}
                    self.assertEqual({k: v for k, v in actual.items() if k != "sha256" or row["name"] != ".rsrc"},
                                     {k: v for k, v in row.items() if k != "sha256" or row["name"] != ".rsrc"})
        result = module.compare_pe_loader(bytes(changed), supplier)
        self.assertEqual(result["sections"], [r for r in rows if r["name"] != ".rsrc"])
        self.assertNotEqual(hashlib.sha256(changed).hexdigest(), comparison["candidatePrefixSha256"])
        self.assert_resource_movement_negative_controls(module, bytes(changed), supplier)

    def test_fetch_selects_required_sources_and_official_probe_wheels(self):
        module = load_module()
        review = json.loads(module.REVIEW.read_text())
        prepare = mock.Mock()
        prepare.prepare.return_value = {"status": "evidence-only"}
        with mock.patch.object(module, "_load", return_value=prepare):
            module.fetch_sources(review, Path("cache"))
        inputs = prepare.prepare.call_args.args[0]["inputs"]
        self.assertEqual(len(inputs), len(review["source_inputs"]) + 2)
        self.assertEqual([i["path"] for i in inputs if i["path"].endswith(".whl")],
                         ["pyinstaller-6.13.0-py3-none-win_amd64.whl",
                          "PySide6_Essentials-6.8.3-cp39-abi3-win_amd64.whl"])

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
            self.assertEqual(environments, ["windows", "windows", "offscreen", "windows"])
            self.assertEqual(result["termsPreview"]["assent"], "not-given")
            self.assertEqual(result["termsPreview"]["platform"], "windows")
            self.assertEqual(result["termsPreview"]["termsToken"], json.loads(module.BOOTSTRAP_POLICY.read_text())["pythonDownstream"]["termsToken"])
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
                import io
                diagnostic_log = io.StringIO()
                with mock.patch("sys.stderr", diagnostic_log), self.assertRaisesRegex(ValueError, "section"):
                    module.probe_native_loader(exe, inventory, review, root)
                diagnostic = json.loads(diagnostic_log.getvalue())
                self.assertEqual(diagnostic["event"], "PE_LOADER_COMPARISON_REJECTED")
                self.assertEqual(diagnostic["status"], "rejected")
                self.assertTrue(diagnostic["notQualification"])
                self.assertEqual(diagnostic["artifactSha256"], inventory["artifactSha256"])
                self.assertEqual(diagnostic["supplierWheelSha256"], review["supplier_inputs"][0]["sha256"])
                self.assertEqual(diagnostic["comparison"]["sectionDifferences"][0]["changedFields"], ["sha256"])
                self.assertNotIn("parser-test-only", diagnostic_log.getvalue())
                self.assertNotIn(str(root), diagnostic_log.getvalue())
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

    def test_bootstrap_filter_removes_exact_ten_paths_before_freeze(self):
        module = load_module()
        policy = json.loads((PACKAGING / "provenance/windows-ci-minimal-policy.json").read_text())
        review = json.loads(module.REVIEW.read_text())
        removed = next(c["payload"] for c in review["components"] if c["id"] == "microsoft-msvc")
        self.assertEqual(len(removed), 10)
        names = {p["path"] for p in removed}
        rows = [(p["path"], "supplier/" + p["path"], "BINARY") for p in policy["expectedNonLoaderPayload"]]
        self.assertEqual(module.windows10_runtime_binaries(rows, bootstrap=True),
                         [r for r in rows if r[0] not in names])
        self.assertEqual(module.windows10_runtime_binaries(rows), rows)
        aliases = [(r[0].replace("/", "\\").upper(), r[1], r[2]) for r in rows if r[0] in names]
        self.assertEqual(module.windows10_runtime_binaries(aliases, bootstrap=True), [])
        for name in ("other/VCRUNTIME140.dll", "VCRUNTIME999.dll", "MSVCP140.dll"):
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "Unreviewed.*runtime"):
                module.windows10_runtime_binaries([(name, "supplier", "BINARY")], bootstrap=True)

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

    @staticmethod
    def bootstrap_fixture():
        """Synthetic candidate/probe identities for contracts, NOT native evidence."""
        module = load_module()
        review = json.loads(module.REVIEW.read_text())
        policy = json.loads((PACKAGING / "provenance/windows-ci-bootstrap-policy.json").read_text())
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
        return module, inventory, review, policy, probe

    def test_bootstrap_review_retains_embedded_notices_and_assent_not_dlls(self):
        self.assertTrue((PACKAGING / "provenance/windows-ci-bootstrap-policy.json").is_file(),
                        "Explicit bootstrap candidate policy missing")
        module, inventory, review, policy, probe = self.bootstrap_fixture()
        result = module.minimal_review(inventory, review, policy, probe)
        self.assertEqual(module.audit(inventory, result)["payload_count"], 37)
        self.assertEqual(result["collectionMode"], module.BOOTSTRAP_MODE)
        byid = {c["id"]: c for c in result["components"]}
        self.assertEqual(byid["microsoft-msvc"]["payload"], [])
        self.assertNotIn("historical_non_native_payload", byid["microsoft-msvc"])
        self.assertIn("microsoft-msvc", byid["cpython"]["statically_linked"])
        self.assertEqual(result["pythonDownstream"], policy["pythonDownstream"])
        self.assertIn(policy["pythonDownstream"]["notice"], byid["cpython"]["notices"])
        self.assertTrue(byid["qt-translations"]["sources"])
        codes = {b["code"] for b in result["blockers"]}
        self.assertEqual(codes, {"WINDOWS_BOOTSTRAP_UNVALIDATED", "WINDOWS_FROZEN_UI_UNTESTED",
                                 "WINDOWS_EMBEDDED_MICROSOFT_TERMS_UNVERIFIED", "WINDOWS_RECOMBINATION_UNTESTED"})
        # Old app-local DLLs are NOT hidden from a frozen inventory.
        original = json.loads((PACKAGING / "provenance/windows-ci-minimal-policy.json").read_text())
        inv = {**inventory, "payload": [inventory["payload"][0]] + original["expectedNonLoaderPayload"]}
        with self.assertRaisesRegex(ValueError, "payload"):
            module.minimal_review(inv, review, policy, probe)
        for mutation in ("missing", "extra", "hash", "msvc", "policy"):
            inv, pol = copy.deepcopy(inventory), copy.deepcopy(policy)
            if mutation == "missing": inv["payload"].pop()
            if mutation == "extra": inv["payload"].append({**inv["payload"][1], "path": "unknown.dll"})
            if mutation == "hash": inv["payload"][1]["sha256"] = "0" * 64
            if mutation == "msvc": inv["payload"].append(original["expectedNonLoaderPayload"][0])
            if mutation == "policy": pol["excludedAppLocalMsvcPayload"].pop()
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                module.minimal_review(inv, review, pol, probe)

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
