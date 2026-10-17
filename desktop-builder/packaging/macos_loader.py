"""Strict Mac supplier comparison plus bounded failure diagnostics.

Live coverage assignment requires the pinned official wheel, complete loader-byte
comparison with exact causal metadata rewrites, and native codesign verification.
The diagnostic JSON remains discovery-only: it is never imported as approval.
Mac onedir evidence is external to PKG, keeping raw two-pass hashes nonrecursive.
"""
from __future__ import annotations

import hashlib
import json
import os
import platform
import subprocess
from pathlib import Path
import re
import struct
import zipfile

BASE = Path(__file__).resolve().parent
WHEEL = 'pyinstaller-6.13.0-py3-none-macosx_10_13_universal2.whl'
MEMBER = 'PyInstaller/bootloader/Darwin-64bit/run'


def digest(data):
    return hashlib.sha256(data).hexdigest()


def parse_thin(data):
    if len(data) < 32 or data[:4] != b'\xcf\xfa\xed\xfe':
        raise ValueError('Expected little-endian Mach-O64')
    header = struct.unpack_from('<8I', data)
    count, size = header[4:6]
    if count > 256 or size > len(data) - 32:
        raise ValueError('Invalid Mach-O command table')
    commands, offset = [], 32
    for _ in range(count):
        if offset + 8 > 32 + size:
            raise ValueError('Truncated Mach-O command')
        command, length = struct.unpack_from('<II', data, offset)
        if length < 8 or length % 8 or offset + length > 32 + size:
            raise ValueError('Invalid Mach-O command size')
        raw = data[offset:offset + length]
        fields, sections = {}, []
        if command == 0x1b:  # LC_UUID
            if length != 24:
                raise ValueError('Invalid UUID command')
            fields['uuidSha256'] = digest(raw[8:24])
        elif command == 0x19:  # LC_SEGMENT_64; preserve section contents and flags
            if length < 72:
                raise ValueError('Invalid segment command')
            vmaddr, vmsize, fileoff, filesize = struct.unpack_from('<4Q', raw, 24)
            nsects = struct.unpack_from('<I', raw, 64)[0]
            if length != 72 + 80 * nsects:
                raise ValueError('Invalid segment sections')
            for index in range(nsects):
                record = raw[72 + index * 80:152 + index * 80]
                address, section_size, section_offset = struct.unpack_from('<QQI', record, 32)
                flags = struct.unpack_from('<I', record, 64)[0]
                zero_fill = flags & 0xff in (1, 12, 18)
                if not zero_fill and (section_offset + section_size > len(data)):
                    raise ValueError('Section extends outside loader bytes')
                sections.append({'headerSha256': digest(record), 'address': address,
                                 'size': section_size, 'offset': section_offset, 'flags': flags,
                                 'sha256': None if zero_fill else digest(data[section_offset:section_offset + section_size])})
            fields = {'segmentNameSha256': digest(raw[8:24]), 'vmaddr': vmaddr,
                      'vmsize': vmsize, 'fileoff': fileoff, 'filesize': filesize,
                      'sectionsSha256': digest(raw[72:])}
        elif command == 2:  # LC_SYMTAB
            if length != 24:
                raise ValueError('Invalid symtab command')
            fields = dict(zip(('symoff', 'nsyms', 'stroff', 'strsize'), struct.unpack_from('<4I', raw, 8)))
        elif command == 0x1d:  # LC_CODE_SIGNATURE
            if length != 16:
                raise ValueError('Invalid signature command')
            fields = dict(zip(('dataoff', 'datasize'), struct.unpack_from('<2I', raw, 8)))
        elif command == 0x24:  # LC_VERSION_MIN_MACOSX on the Intel supplier
            if length != 16:
                raise ValueError('Invalid minimum version command')
            fields = dict(zip(('version', 'sdk'), struct.unpack_from('<2I', raw, 8)))
        elif command == 0x32:  # LC_BUILD_VERSION, including SDK rewrite seen in retained CI logs
            if length < 24:
                raise ValueError('Invalid build version command')
            fields = dict(zip(('platform', 'minos', 'sdk', 'ntools'), struct.unpack_from('<4I', raw, 8)))
        commands.append({'command': command, 'offset': offset, 'size': length,
                         'sha256': digest(raw), 'fields': fields, 'sections': sections})
        offset += length
    if offset != 32 + size:
        raise ValueError('Unaccounted Mach-O commands')
    return {'header': list(header), 'commands': commands, 'commandEnd': offset}


def supplier_slice(data, cpu):
    if data[:4] == b'\xcf\xfa\xed\xfe':
        if parse_thin(data)['header'][1] != cpu:
            raise ValueError('Wrong supplier architecture')
        return data
    if len(data) < 8 or data[:4] != b'\xca\xfe\xba\xbe':
        raise ValueError('Expected official universal2 supplier')
    count = struct.unpack_from('>I', data, 4)[0]
    if count != 2 or len(data) < 8 + 20 * count:
        raise ValueError('Invalid universal2 header')
    matches, spans = [], []
    for index in range(count):
        arch, subtype, offset, size, align = struct.unpack_from('>5I', data, 8 + 20 * index)
        if offset < 48 or size < 32 or offset + size > len(data) or align > 30 or offset % (1 << align):
            raise ValueError('Invalid universal2 slice')
        if any(offset < end and offset + size > start for start, end in spans):
            raise ValueError('Overlapping universal2 slices')
        spans.append((offset, offset + size))
        if arch == cpu:
            matches.append(data[offset:offset + size])
    if len(matches) != 1 or parse_thin(matches[0])['header'][1] != cpu:
        raise ValueError('Missing unique supplier architecture')
    return matches[0]


def compare_loader(candidate_prefix, supplier, pkg):
    """Exact comparison except *validated* PKG-derived UUID, never code masking.

    Code signature removal/addition, LINKEDIT sizes and SDK edits remain unequal
    and visible in command records. Native evidence must precede any approval rule.
    """
    left, right = parse_thin(candidate_prefix), parse_thin(supplier)
    original, actual = bytearray(supplier), bytearray(candidate_prefix)
    uuids = [[c for c in p['commands'] if c['command'] == 0x1b] for p in (left, right)]
    uuid_matches = False
    if len(uuids[0]) == len(uuids[1]) == 1:
        a, b = uuids[0][0]['offset'] + 8, uuids[1][0]['offset'] + 8
        expected = hashlib.sha1(pkg + supplier[b:b + 16]).digest()[:16]
        uuid_matches = candidate_prefix[a:a + 16] == expected
        if uuid_matches and a == b:
            actual[a:a + 16] = original[b:b + 16]
    differences = [i for i, (a, b) in enumerate(zip(actual, original)) if a != b]
    left_sections = [s for c in left['commands'] for s in c['sections']]
    right_sections = [s for c in right['commands'] for s in c['sections']]
    return {'status': 'UNQUALIFIED-DISCOVERY-ONLY', 'notQualification': True,
            'candidatePrefixSha256': digest(candidate_prefix), 'candidatePrefixSize': len(candidate_prefix),
            'supplierSliceSha256': digest(supplier), 'supplierSliceSize': len(supplier),
            'pkgSha256': digest(pkg), 'uuidMatchesPkg': uuid_matches,
            'sectionPayloadsEqual': left_sections == right_sections if left_sections or right_sections else None,
            'nonMetadataBytesEqual': actual == original,
            'comparisonScope': 'all-prefix-bytes-except-validated-PKG-derived-UUID',
            'differenceCount': len(differences) + abs(len(actual) - len(original)),
            'firstDifferenceOffsets': differences[:64], 'candidate': left, 'supplier': right}


def _one(parsed, kind):
    matches = [c for c in parsed['commands'] if c['command'] == kind]
    if len(matches) != 1:
        raise ValueError('Expected unique Mach-O command')
    return matches[0]


def sdk_version(data):
    parsed = parse_thin(data)
    versions = [c['fields']['sdk'] for c in parsed['commands'] if c['command'] in (0x24, 0x32)]
    if len(versions) != 1:
        raise ValueError('Expected unique macOS SDK version')
    return versions[0]


def validate_transformation(data, supplier, pkg_start, pkg_end, *, python_sdk):
    """Compare EVERY loader byte with the official slice, including padding.

    This is structural evidence only; verify_candidate additionally requires live
    native codesign verification. Rules are bound to the 6.13.0 supplier and the
    arm/Intel native diagnostics from run 38999990975, not arbitrary Mach-O edits.
    No executable sections, gaps, command flags or unknown bytes are masked.
    """
    original = parse_thin(supplier)
    if original['header'][1] not in (0x1000007, 0x100000c):
        raise ValueError('Unsupported Mac loader architecture')
    signatures = [c for c in original['commands'] if c['command'] == 0x1d]
    if len(signatures) > 1:
        raise ValueError('Duplicate supplier signature')
    supplier_end = signatures[0]['fields']['dataoff'] if signatures else len(supplier)
    if (not 32 < pkg_start == supplier_end < pkg_end < len(data)
            or supplier_end > len(supplier)):
        raise ValueError('Invalid loader/PKG boundaries')
    actual = parse_thin(data[:pkg_start])
    signature = _one(actual, 0x1d)
    sign_offset, sign_size = (signature['fields'][k] for k in ('dataoff', 'datasize'))
    if (sign_offset != (pkg_end + 15) // 16 * 16 or sign_size <= 0
            or sign_offset + sign_size != len(data)
            or data[pkg_end:sign_offset] != bytes(sign_offset - pkg_end)):
        raise ValueError('Signature does not bound complete archive and file')
    expected = bytearray(supplier[:supplier_end])
    if signatures:
        old = signatures[0]
        if (old != original['commands'][-1]
                or old['fields']['dataoff'] + old['fields']['datasize'] != len(supplier)):
            raise ValueError('Unexpected supplier signature layout')
        signature_offset = old['offset']
    else:
        # codesign adds exactly one final command in existing ZERO header padding.
        signature_offset = original['commandEnd']
        if expected[signature_offset:signature_offset + 16] != bytes(16):
            raise ValueError('No empty supplier signature command slot')
        struct.pack_into('<II', expected, 16, original['header'][4] + 1, original['header'][5] + 16)
    if signature['offset'] != signature_offset:
        raise ValueError('Moved signature command')
    struct.pack_into('<4I', expected, signature_offset, 0x1d, 16, sign_offset, sign_size)
    uuid = _one(original, 0x1b)['offset'] + 8
    expected[uuid:uuid + 16] = hashlib.sha1(data[pkg_start:pkg_end] + supplier[uuid:uuid + 16]).digest()[:16]
    symtab = _one(original, 2)
    if symtab['fields']['stroff'] + symtab['fields']['strsize'] != supplier_end:
        raise ValueError('Supplier string table does not end at loader boundary')
    struct.pack_into('<I', expected, symtab['offset'] + 20, pkg_end - symtab['fields']['stroff'])
    linkedit = [c for c in original['commands'] if c['command'] == 0x19
                and supplier[c['offset'] + 8:c['offset'] + 24] == b'__LINKEDIT' + bytes(6)]
    if len(linkedit) != 1 or linkedit[0]['sections']:
        raise ValueError('Unexpected LINKEDIT layout')
    linkedit = linkedit[0]
    filesize = len(data) - linkedit['fields']['fileoff']
    # Native codesign in BOTH retained architectures rounds final LINKEDIT to 16K.
    struct.pack_into('<Q', expected, linkedit['offset'] + 32, (filesize + 16383) // 16384 * 16384)
    struct.pack_into('<Q', expected, linkedit['offset'] + 48, filesize)
    version = next(c for c in original['commands'] if c['command'] in (0x24, 0x32))
    expected_sdk = min(sdk_version(supplier), python_sdk)
    # Retained native evidence establishes this SDK rewrite only. New suppliers
    # or SDK behavior must be reviewed, not automatically widened.
    if expected_sdk != 0x0c0100:
        raise ValueError('Unreviewed Python SDK rewrite')
    struct.pack_into('<I', expected, version['offset'] + (12 if version['command'] == 0x24 else 16), expected_sdk)
    if bytes(expected) != data[:pkg_start]:
        raise ValueError('Mac loader differs beyond exact packaging rewrites')
    return {'comparisonScope': 'all-loader-bytes-with-exact-packaging-rewrites',
            'candidatePrefixSha256': digest(data[:pkg_start]), 'candidatePrefixSize': pkg_start,
            'supplierSliceSha256': digest(supplier), 'pkgSha256': digest(data[pkg_start:pkg_end]),
            'pythonSdk': python_sdk, 'signatureOffset': sign_offset, 'signatureSize': sign_size}


def verify_candidate(candidate, cache):
    """Live gate, not importable JSON acceptance. Repeat after EVERY freeze.

    Mac uses onedir: evidence lives outside PKG. Thus raw prefix and whole EXE
    hashes remain exact and stable across the two passes; no normalization or
    new inventory hash scope is needed. An embedded compliance ZIP is rejected.
    """
    from PyInstaller.archive.readers import CArchiveReader
    import io

    if platform.system() != 'Darwin':
        raise ValueError('Mac loader verification requires native macOS')
    wheel = (cache / WHEEL).read_bytes()
    wheel_sha = 'aa404f0b02cd57948098055e76ee190b8e65ccf7a2a3f048e5000f668317069f'
    if digest(wheel) != wheel_sha:
        raise ValueError('Supplier wheel mismatch')
    with zipfile.ZipFile(io.BytesIO(wheel)) as archive:
        loader = archive.read(MEMBER)
    data = candidate.read_bytes()
    reader = CArchiveReader(str(candidate))
    external = candidate.parent / '_internal/builder-compliance.zip'
    if (any(name.replace('\\', '/') == 'builder-compliance.zip' for name in reader.toc)
            or external.is_symlink() or not external.is_file()):
        raise ValueError('Mac compliance must remain external to PKG')
    cpu = parse_thin(data)['header'][1]
    supplier = supplier_slice(loader, cpu)
    python = candidate.parent / '_internal/Python.framework/Versions/3.12/Python'
    if python.is_symlink() or not python.is_file():
        raise ValueError('Expected reviewed bundled Python library')
    python_bytes = python.read_bytes()
    if parse_thin(python_bytes)['header'][1] != cpu:
        raise ValueError('Bundled Python architecture mismatch')
    proof = validate_transformation(data, supplier, reader._start_offset, reader._end_offset,
                                    python_sdk=sdk_version(python_bytes))
    try:
        subprocess.run(['/usr/bin/codesign', '--verify', '--strict', '--all-architectures', str(candidate)],
                       check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=120)
    except (OSError, subprocess.SubprocessError) as exc:
        raise ValueError('Mac loader native signature verification failed') from exc
    if candidate.read_bytes() != data or python.read_bytes() != python_bytes:
        raise ValueError('Candidate changed during loader verification')
    return {**proof, 'artifactSha256': digest(data), 'supplierWheelSha256': wheel_sha,
            'supplierMember': MEMBER, 'supplierMemberSha256': digest(loader),
            'pythonLibrarySha256': digest(python_bytes), 'codesignVerified': True,
            'complianceLayout': 'onedir-external-to-PKG'}


def candidate_coverage(candidate, cache, discovery, coverage):
    """Assign only @bootloader after live proof; preserve all other exact records."""
    import copy

    proof = verify_candidate(candidate, cache)
    if proof['artifactSha256'] != discovery['artifactSha256']:
        raise ValueError('Loader evidence does not match discovery')
    result = copy.deepcopy(coverage)
    if (result.get('schema') != 2 or result.get('system') != 'Darwin'
            or result.get('architecture') not in ('arm64', 'x86_64')):
        raise ValueError('Expected reviewed Mac coverage')
    expected_entries = [p for c in result['components'] for p in c.get('payload', [])]
    expected = {p['path']: p for p in expected_entries}
    actual = {p['path']: p for p in discovery['payload']}
    if (len(expected) != len(expected_entries) or len(actual) != len(discovery['payload'])
            or {p: e for p, e in actual.items() if p != '@bootloader'}
            != {p: e for p, e in expected.items() if p != '@bootloader'}):
        raise ValueError('Unreviewed non-loader Mac payload')
    loader = {'path': '@bootloader', 'kind': 'bootloader', 'hash_scope': 'prefix-bytes',
              'sha256': proof['candidatePrefixSha256'], 'size': proof['candidatePrefixSize']}
    python = actual.get('Python.framework/Versions/3.12/Python', {})
    if (actual.get('@bootloader') != loader or proof['codesignVerified'] is not True
            or python.get('sha256') != proof['pythonLibrarySha256']):
        raise ValueError('Loader evidence does not match inventory')
    components = [c for c in result['components'] if c.get('id') == 'pyinstaller-embedded-loader']
    if (len(components) != 1 or components[0].get('version') != '6.13.0'
            or components[0].get('license') != 'GPL-2.0-or-later WITH Bootloader-exception'
            or components[0].get('payload') != [expected.get('@bootloader')]):
        raise ValueError('Expected scoped PyInstaller supplier component')
    component = components[0]
    component['payload'] = [loader]
    component['provenance'] = ('Official hash-pinned PyInstaller 6.13.0 universal2 run slice. '
                               'Every loader byte compared; only exact PKG UUID, SDK, LINKEDIT, '
                               'SYMTAB and signature-command rewrites accepted. Native codesign '
                               '--verify --strict passed on this candidate. See macos_loader_evidence.')
    component['exception_basis'] = ('Standard embedded supplier loader with unchanged code, data, '
                                    'layout and static headers except validated packaging metadata; '
                                    'not standalone distribution or a blanket GPL exemption.')
    result['macos_loader_evidence'] = proof
    return result


def diagnose(candidate, cache, output, discovery):
    """Persist bounded hashes/layout only, never a candidate or raw command bytes."""
    from PyInstaller.archive.readers import CArchiveReader, ArchiveReadError

    report = {'schema': 1, 'status': 'UNQUALIFIED-DISCOVERY-ONLY', 'notQualification': True,
              'artifactSha256': discovery['artifactSha256'],
              'builderSourceSha256': discovery['builderSourceSha256']}
    for env, key, pattern in [('EXPECTED_COMMIT', 'commit', r'[0-9a-f]{40}'),
                              ('RUN_ID', 'runId', r'[0-9]{1,20}'),
                              ('RUN_ATTEMPT', 'runAttempt', r'[0-9]{1,5}')]:
        value = os.environ.get(env, '')
        if re.fullmatch(pattern, value):
            report[key] = value
    try:
        entry = next(e for e in json.loads((BASE / 'provenance/macos-ci/supplier-inputs.lock.json').read_text())['inputs']
                     if e['path'] == WHEEL)
        wheel = (cache / WHEEL).read_bytes()
        if digest(wheel) != entry['sha256']:
            raise ValueError('Supplier wheel mismatch')
        import io
        with zipfile.ZipFile(io.BytesIO(wheel)) as archive:
            loader = archive.read(MEMBER)
        reader = CArchiveReader(str(candidate))
        data = candidate.read_bytes()
        if digest(data) != discovery['artifactSha256']:
            raise ValueError('Candidate changed after inventory')
        prefix = data[:reader._start_offset]
        # _end_offset is immediately after the CArchive cookie, before code signing.
        pkg = data[reader._start_offset:reader._end_offset]
        supplier = supplier_slice(loader, parse_thin(prefix)['header'][1])
        report.update(supplierWheelSha256=entry['sha256'], supplierMember=MEMBER,
                      supplierMemberSha256=digest(loader), comparison=compare_loader(prefix, supplier, pkg))
    except (OSError, ValueError, KeyError, StopIteration, zipfile.BadZipFile, struct.error, ArchiveReadError):
        report['diagnosticError'] = 'MAC_LOADER_DIAGNOSTIC_UNAVAILABLE'
    output.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    return report
