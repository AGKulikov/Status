#!/usr/bin/env python3
"""Audit the exact 2026-09-13 trip-screen archive without publishing its PCAPs.

Collector 1.0.0 used exec-out, which merged tcpdump stderr into stdout on KX11.
Recover only the observed framing: one exact banner after the global header,
20 intact Ethernet records, then the exact zero-drop capture statistics.
Never scan for packets or remove arbitrary text from payloads.
"""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import zipfile

from audit_saved_pcaps import audit_capture

ARCHIVE_SHA256 = 'd0c27bc08dc766bc94c41b3c7dee688641be5de1f1088678b7d5184a6d5146ba'
BANNER = b'tcpdump: listening on eth0, link-type EN10MB (Ethernet), capture size 1024 bytes\n'
FOOTER = b'20 packets captured\n20 packets received by filter\n0 packets dropped by kernel\n'
HEADER = struct.pack('<IHHIIII', 0xa1b2c3d4, 2, 4, 0, 0, 1024, 1)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def checksum(data):
    if len(data) % 2:
        data += b'\0'
    value = sum(struct.unpack('>' + 'H' * (len(data) // 2), data))
    while value >> 16:
        value = (value & 65535) + (value >> 16)
    return value


def recover_capture(data):
    if not data.startswith(HEADER + BANNER):
        raise ValueError('Not the observed PCAP header/banner framing')
    start = cursor = len(HEADER) + len(BANNER)
    previous_time = None
    udp_present = 0
    for _ in range(20):
        if cursor + 16 > len(data):
            raise ValueError('Missing packet header')
        sec, subsec, captured, original = struct.unpack_from('<4I', data, cursor)
        cursor += 16
        if captured != 770 or original != captured or cursor + captured > len(data):
            raise ValueError('Unexpected or truncated packet length')
        timestamp = sec * 1000000 + subsec
        if subsec >= 1000000 or (previous_time is not None and timestamp <= previous_time):
            raise ValueError('Invalid or non-increasing packet timestamp')
        previous_time = timestamp
        packet = data[cursor:cursor + captured]
        cursor += captured
        ip = packet[14:]
        if packet[12:14] != b'\x08\x00' or ip[0] != 0x45 or checksum(ip[:20]) != 65535:
            raise ValueError('Unexpected Ethernet/IPv4 framing or IPv4 checksum')
        udp = ip[20:]
        if udp[6:8] != b'\0\0':
            udp_present += 1
            pseudo = ip[12:20] + bytes((0, 17)) + struct.pack('>H', len(udp))
            if checksum(pseudo + udp) != 65535:
                raise ValueError('Invalid UDP checksum')
    if data[cursor:] != FOOTER:
        raise ValueError('Unexpected tail, packet count or drop statistics')
    recovered = data[:24] + data[start:cursor]
    observation = audit_capture(recovered)
    if observation.get('counts') != {'packet_records': 20, 'unparsed_tail_bytes': 0,
                                     'trip_messages': 20}:
        raise ValueError('Recovered records do not all match the reviewed incoming IPCP route')
    return recovered, {
        'source_bytes': len(data), 'source_sha256': sha256(data),
        'recovered_bytes': len(recovered), 'recovered_sha256': sha256(recovered),
        'retained_source_ranges_half_open': [[0, 24], [start, cursor]],
        'removed_spans': [{'start': 24, 'bytes': len(BANNER), 'text': BANNER.decode()},
                          {'start': cursor, 'bytes': len(FOOTER), 'text': FOOTER.decode()}],
        'ipv4_checksums_valid': 20, 'udp_checksums_present_and_valid': udp_present,
        'observation': observation,
    }


def audit_archive(path):
    data = path.read_bytes()
    if sha256(data) != ARCHIVE_SHA256:
        raise ValueError('This audit is pinned to the exact supplied 2026-09-13 archive')
    with zipfile.ZipFile(path) as archive:
        prefix = 'Natro-Trip-Check-20260913-113103-9f194d/'
        for entry in json.loads(archive.read(prefix + 'FILES.json')):
            content = archive.read(prefix + entry['path'])
            if len(content) != entry['bytes'] or sha256(content) != entry['sha256']:
                raise ValueError('Archive file manifest mismatch')
        receipt = json.loads(archive.read(prefix + 'RESULT.json'))
        expected_native = 'b6feed82f777bfa8773c03b78c1f17038e122f492ef85126c095354a29c16756'
        if not any(f['sha256'] == expected_native for f in receipt['firmware']['native_files']):
            raise ValueError('Recorded native SHA does not match the reviewed decoder')
        captures = []
        for name in ('trip2.pcap', 'trip1.pcap'):
            _, result = recover_capture(archive.read(prefix + name))
            captures.append(dict(file=name, **result))
    return {'schema': 'kx11-trip-screen-recovery-v1', 'archive': path.name,
            'archive_sha256': ARCHIVE_SHA256, 'archive_bytes': len(data),
            'native_sha256_from_collector_receipt': expected_native,
            'original_collector_status': receipt['status'],
            'method': 'Retain the original global header and all 20 original packet records; '
                      'remove only the exact observed tcpdump banner and zero-drop footer.',
            'captures': captures,
            'limits': 'Photo files have no EXIF timestamp. No automatic reset was captured. '
                      'PA time is seconds; PA distance units and stock source remain unresolved.'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = audit_archive(args.archive)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
