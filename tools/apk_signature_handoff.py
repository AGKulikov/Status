#!/usr/bin/env python3
"""Reconstruct exact offline-signed APK bytes from a pinned CI APK, without transferring a key."""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import re
import struct
import zipfile

LIMIT = 128 * 1024 * 1024
LITERAL_LIMIT = 2 * 1024 * 1024


def digest(data):
    return hashlib.sha256(data).hexdigest()


def payload(path):
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)) or archive.testzip() is not None:
            raise ValueError('Duplicate or corrupt APK entries')
        return {name: digest(archive.read(name)) for name in names
                if not re.fullmatch(r'META-INF/(?:MANIFEST\.MF|[^/]+\.(?:RSA|DSA|EC|SF))', name, re.I)}


def data_range(raw, info):
    start = info.header_offset
    if raw[start:start + 4] != b'PK\x03\x04':
        raise ValueError('Invalid local ZIP header')
    name, extra = struct.unpack_from('<HH', raw, start + 26)
    offset = start + 30 + name + extra
    if offset + info.compress_size > len(raw):
        raise ValueError('ZIP data out of bounds')
    return offset, info.compress_size


def create(unsigned, signed):
    if payload(unsigned) != payload(signed):
        raise ValueError('Signed APK changed tested payload')
    before, after = unsigned.read_bytes(), signed.read_bytes()
    if not 0 < len(before) <= LIMIT or not 0 < len(after) <= LIMIT:
        raise ValueError('APK size out of bounds')
    with zipfile.ZipFile(unsigned) as source, zipfile.ZipFile(signed) as target:
        sources = {entry.filename: entry for entry in source.infolist()}
        ranges = []
        for entry in target.infolist():
            old = sources.get(entry.filename)
            if old is None or not entry.compress_size:
                continue
            old_start, old_length = data_range(before, old)
            start, length = data_range(after, entry)
            if length == old_length and after[start:start + length] == before[old_start:old_start + length]:
                ranges.append((start, length, old_start))
    ops, cursor, literals = [], 0, 0
    for start, length, old_start in sorted(ranges):
        if start < cursor:
            raise ValueError('Overlapping ZIP entries')
        if start > cursor:
            block = after[cursor:start]
            literals += len(block)
            ops.append({'data': base64.b64encode(block).decode('ascii')})
        ops.append({'offset': old_start, 'length': length})
        cursor = start + length
    if cursor < len(after):
        block = after[cursor:]
        literals += len(block)
        ops.append({'data': base64.b64encode(block).decode('ascii')})
    if literals > LITERAL_LIMIT:
        raise ValueError('Handoff must contain headers/signature only, not a full APK')
    result = dict(schema=1, unsignedSha256=digest(before), signedSha256=digest(after),
                  signedSize=len(after), literalBytes=literals, ops=ops)
    if reconstruct(before, result) != after:
        raise ValueError('Handoff roundtrip failed')
    return result


def reconstruct(before, patch):
    if patch.get('schema') != 1 or digest(before) != patch.get('unsignedSha256'):
        raise ValueError('Wrong unsigned APK or handoff schema')
    size = patch.get('signedSize')
    if type(size) is not int or not 0 < size <= LIMIT or len(before) > LIMIT:
        raise ValueError('APK size out of bounds')
    ops = patch.get('ops')
    if not isinstance(ops, list) or len(ops) > 20000:
        raise ValueError('Invalid operation count')
    after, literals = bytearray(), 0
    for op in ops:
        if set(op) == {'data'}:
            block = base64.b64decode(op['data'], validate=True)
            literals += len(block)
            if literals > LITERAL_LIMIT:
                raise ValueError('Too many literal bytes')
        elif set(op) == {'offset', 'length'}:
            start, length = op['offset'], op['length']
            if (type(start) is not int or type(length) is not int or start < 0
                    or length < 1 or start + length > len(before)):
                raise ValueError('Copy range out of bounds')
            block = before[start:start + length]
        else:
            raise ValueError('Unknown operation')
        if len(after) + len(block) > size:
            raise ValueError('Output exceeds pinned size')
        after.extend(block)
    if len(after) != size or digest(after) != patch.get('signedSha256') or literals != patch.get('literalBytes'):
        raise ValueError('Signed APK digest, size or literal count mismatch')
    return bytes(after)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['create', 'apply'])
    parser.add_argument('--unsigned', required=True, type=Path)
    parser.add_argument('--signed', type=Path)
    parser.add_argument('--patch', required=True, type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    if args.mode == 'create':
        if args.signed is None:
            parser.error('--signed is required')
        value = create(args.unsigned, args.signed)
        with args.patch.open('x', encoding='utf-8') as stream:
            json.dump(value, stream, separators=(',', ':'))
            stream.write('\n')
    else:
        if args.output is None:
            parser.error('--output is required')
        if args.patch.stat().st_size > 8 * 1024 * 1024:
            raise ValueError('Handoff file too large')
        value = reconstruct(args.unsigned.read_bytes(), json.loads(args.patch.read_text()))
        with args.output.open('xb') as stream:
            stream.write(value)
        if payload(args.output) != payload(args.unsigned):
            raise ValueError('Reconstructed APK changed tested payload')
    print(json.dumps({'mode': args.mode, 'verified': True}))


if __name__ == '__main__':
    main()
