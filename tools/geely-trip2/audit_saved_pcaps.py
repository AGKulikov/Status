#!/usr/bin/env python3
"""Read saved KX11 captures; output only Trip-2 candidate scalars, counts and source hashes.

No network, ECU writes or packet replay. Offsets apply to the native VHAL hash documented
in docs/geely-kx11/research-2026-09-13-trip2/README_RU.md, not arbitrary firmware.
"""
import argparse
import collections
import hashlib
import json
from pathlib import Path
import struct
import zipfile


def trip_message(datagram):
    if len(datagram) != 728:
        return None
    if struct.unpack_from('>HHI', datagram) != (0x6e, 0xc8, 720):
        return None
    payload = datagram[16:]
    # Wire order is availability, data, format, status. Do not infer distance units.
    return {
        'time_words': list(struct.unpack_from('>4I', payload, 0x30)),
        'distance_words': list(struct.unpack_from('>4I', payload, 0x50)),
    }


def audit_capture(data):
    magic = {b'\xd4\xc3\xb2\xa1': ('<', 1e6), b'\xa1\xb2\xc3\xd4': ('>', 1e6),
             b'\x4d\x3c\xb2\xa1': ('<', 1e9), b'\xa1\xb2\x3c\x4d': ('>', 1e9)}
    if len(data) < 24 or data[:4] not in magic:
        return {'unsupported': 'Not a classic PCAP'}
    endian, scale = magic[data[:4]]
    link = struct.unpack_from(endian + 'I', data, 20)[0]
    if link not in (1, 113, 101, 228):
        return {'unsupported': 'Unsupported link type', 'link_type': link}
    counts = collections.Counter()
    observations = []
    offset = 24
    while offset + 16 <= len(data):
        sec, subsec, captured, original = struct.unpack_from(endian + '4I', data, offset)
        offset += 16
        counts['packet_records'] += 1
        if offset + captured > len(data):
            counts['truncated_records'] += 1
            break
        raw = data[offset:offset + captured]
        offset += captured
        if captured < original:
            counts['snaplen_truncated_records'] += 1
        if link == 113:
            if len(raw) < 16 or raw[14:16] != b'\x08\x00':
                continue
            raw = raw[16:]
        elif link == 1:
            if len(raw) < 14:
                continue
            ether_type = struct.unpack_from('>H', raw, 12)[0]
            cursor = 14
            while ether_type in (0x8100, 0x88a8) and len(raw) >= cursor + 4:
                ether_type = struct.unpack_from('>H', raw, cursor + 2)[0]
                cursor += 4
            if ether_type != 0x800:
                continue
            raw = raw[cursor:]
        if len(raw) < 20 or raw[0] >> 4 != 4 or raw[9] != 17:
            continue
        header = (raw[0] & 15) * 4
        total = struct.unpack_from('>H', raw, 2)[0]
        if header < 20 or total < header + 8 or len(raw) < total:
            counts['malformed_ipv4_udp'] += 1
            continue
        if struct.unpack_from('>H', raw, 6)[0] & 0x3fff:
            counts['fragmented_udp_skipped'] += 1
            continue
        sport, dport, udp_length, _ = struct.unpack_from('>4H', raw, header)
        if udp_length < 8 or header + udp_length > total:
            counts['malformed_udp_length'] += 1
            continue
        # Only the observed incoming MCU-to-Android endpoint pair; never expose other payloads.
        if raw[12:20] != bytes((198, 18, 34, 1, 198, 18, 34, 15)):
            continue
        if (sport, dport) != (50500, 50335):
            continue
        item = trip_message(raw[header + 8:header + udp_length])
        if item is not None:
            item.update(packet=counts['packet_records'], timestamp=sec + subsec / scale)
            observations.append(item)
    counts['unparsed_tail_bytes'] = len(data) - offset
    counts['trip_messages'] = len(observations)
    result = {'link_type': link, 'counts': dict(counts)}
    if observations:
        first, last = observations[0], observations[-1]
        dt = last['timestamp'] - first['timestamp']
        raw_delta = last['time_words'][1] - first['time_words'][1]
        result.update(first=first, last=last, elapsed_capture_seconds=dt,
                      elapsed_raw_delta=raw_delta,
                      raw_time_units_per_second=raw_delta / dt if dt > 0 else None,
                      distance_raw_values=sorted({v['distance_words'][1] for v in observations}),
                      time_encodings=sorted({tuple(v['time_words'][i] for i in (0, 2, 3))
                                             for v in observations}),
                      distance_encodings=sorted({tuple(v['distance_words'][i] for i in (0, 2, 3))
                                                 for v in observations}))
    return result


def audit_archives(paths):
    archives = []
    captures = {}
    for path in sorted(paths):
        archives.append({'archive': path.name, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()})
        with zipfile.ZipFile(path) as archive:
            for member in archive.infolist():
                if not member.filename.endswith('.pcap') or '__MACOSX' in member.filename:
                    continue
                data = archive.read(member)
                digest = hashlib.sha256(data).hexdigest()
                if digest not in captures:
                    captures[digest] = {'sha256': digest, 'bytes': len(data), 'sources': [],
                                        'analysis': audit_capture(data)}
                captures[digest]['sources'].append({'archive': path.name, 'member': member.filename})
    return {'schema': 'kx11-pa-trip-observation-v1', 'archives': archives,
            'scope': 'Classic PCAP IPv4 UDP only. Duplicate captures deduplicated by bytes; '
                     'different interfaces may still contain the same wire events. '
                     'Time unit observation does not establish stock Trip-2 identity or reset.',
            'captures': sorted(captures.values(), key=lambda x: x['sha256'])}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archives', nargs='+', type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(audit_archives(args.archives), ensure_ascii=False, indent=2) + '\n')
