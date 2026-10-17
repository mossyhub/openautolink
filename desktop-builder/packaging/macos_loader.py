"""Bounded Mac loader diagnostics. NEVER approves or rewrites native coverage.

The rejected CI binaries were not retained. Record real candidate/supplier
comparisons before temporary-directory cleanup, not guessed replacement hashes.
Only UUID equality is derived here (PyInstaller 6.13.0 SHA1(PKG + supplier UUID)).
Other packaging fields are reported, not treated as permission to alter bytes.
"""
from __future__ import annotations

import hashlib
import json
import os
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
