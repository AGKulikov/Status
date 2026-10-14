"""Offline candidate extraction for the archived KX11 libslog2parse ABI.

Not a replacement for slog2info: no type-1 decoding, chronology or live-reader
guarantee. Never opens vehicle connections or executes the proprietary library.
See docs/geely-kx11/research-2026-10-14-dim/SLOG2_FORMAT_RU.md.
"""
import argparse
import hashlib
import json
import struct
from pathlib import Path

MAX_FILE = 16 * 1024 * 1024


def inspect(data):
    if not 8192 <= len(data) <= MAX_FILE:
        raise ValueError("expected a complete 8 KiB..16 MiB buffer-set file")
    u32 = lambda p: struct.unpack_from("<I", data, p)[0]
    u64 = lambda p: struct.unpack_from("<Q", data, p)[0]
    name = lambda p: data[p:p + 64].split(b"\0", 1)[0].decode("utf-8", "replace")
    count = u32(0xe8)
    if not 1 <= count <= 4 or data[0x54] > 7:
        raise ValueError("header does not match archived KX11 ABI")
    out = {"status": "experimental_candidates_only", "sha256": hashlib.sha256(data).hexdigest(),
           "name": name(0x14), "buffers": [],
           "limitations": ["type1 payload remains encoded", "physical offsets, not chronological order",
                           "live or stale ring contents may produce candidates; FIELD validation pending"]}
    for i in range(count):
        d = i * 0x68
        size, pointer, base = u32(0x134 + d), u64(0x138 + d), u64(0xe0)
        start = 4096 + pointer - base
        if pointer < base or size == 0 or start > len(data) or size > len(data) - start:
            raise ValueError("ring outside complete file")
        if start != 0xf0 + d + u32(0x140 + d):
            raise ValueError("ring offset representations disagree")
        ring = data[start:start + size]
        # Up to 528 bytes per record; retain candidates crossing the ring end.
        scan = ring + ring[:min(528, size)]
        records = []
        offset = 0
        while offset + 4 <= size:
            magic, length = struct.unpack_from("<HH", scan, offset)
            if magic != 0xbdcf or not 17 <= length <= min(528, size):
                offset += 4
                continue
            packet = scan[offset:offset + length]
            kind = packet[11]
            if kind > 2 or (kind == 0 and packet[-1] != 0):
                offset += 4
                continue
            payload = packet[16:]
            record = {"ring_offset": offset, "length": length, "kind": kind,
                      "wrapped": offset + length > size,
                      "sequence_raw": struct.unpack_from("<H", packet, 8)[0],
                      "severity_raw": packet[10] >> 3, "payload_hex": payload.hex()}
            if kind == 0:
                record["text"] = payload[:-1].decode("utf-8", "replace")
            elif kind == 1:
                # 0x2660: NUL-terminated format followed by 2-byte-aligned
                # typed arguments. Expose the template, never fake formatted text.
                end = payload.find(b"\0", 0, 512)
                if end >= 0:
                    args_offset = (end + 2) & ~1
                    if args_offset <= len(payload):
                        record["format_template"] = payload[:end].decode("utf-8", "replace")
                        record["encoded_arguments_hex"] = payload[args_offset:].hex()
            records.append(record)
            offset = (offset + length + 3) & ~3
        out["buffers"].append({"name": name(0xf4 + d), "file_offset": start,
                               "size": size, "candidates": records})
    return out


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("file", type=Path)
    args = parser.parse_args()
    with args.file.open("rb") as stream:
        data = stream.read(MAX_FILE + 1)
    try:
        print(json.dumps(inspect(data), ensure_ascii=False, indent=2))
    except ValueError as error:
        parser.error(str(error))


if __name__ == "__main__":
    main()
