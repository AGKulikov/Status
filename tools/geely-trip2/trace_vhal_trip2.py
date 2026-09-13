#!/usr/bin/env python3
"""Verify Trip-2 PA route in the saved native VHAL. Requires capstone and pyelftools.

Accepts only the exact investigated ELF. Produces our route metadata, no disassembly dump.
"""
import argparse
from collections import defaultdict, deque
import hashlib
import json
from pathlib import Path
import re
import struct

from capstone import Cs, CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN
from elftools.elf.elffile import ELFFile

EXPECTED = 'b6feed82f777bfa8773c03b78c1f17038e122f492ef85126c095354a29c16756'


def inspect(path):
    if hashlib.sha256(path.read_bytes()).hexdigest() != EXPECTED:
        raise ValueError('Wrong native VHAL: offsets must not be applied to another firmware')
    with path.open('rb') as stream:
        elf = ELFFile(stream)

        def read(va, size):
            for section in elf.iter_sections():
                base = section['sh_addr']
                if base <= va and va + size <= base + section['sh_size']:
                    return section.data()[va - base:va - base + size]
            raise ValueError('Unmapped address')

        start, size, table = 0x125b60, 475796, 0x24f370
        decoder = Cs(CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN)
        instructions = {i.address: (i.mnemonic, i.op_str) for i in decoder.disasm(read(start, size), start)}
        entries = {service: table + struct.unpack('<i', read(table + (service - 0x6e) * 4, 4))[0]
                   for service in range(0x6e, 0xc8)}
        reverse = defaultdict(list)
        for address, (op, operands) in instructions.items():
            targets = [] if op in ('br', 'ret') else [address + 4]
            if op == 'b':
                targets = [int(operands.removeprefix('#'), 16)]
            elif op.startswith(('b.', 'cb', 'tb')):
                match = re.search(r'#0x([\da-f]+)', operands)
                if match:
                    targets.append(int(match[1], 16))
            for target in targets:
                if target in instructions:
                    reverse[target].append(address)
        # Method and byte-size dispatch precede the copy to the x26 buffer.
        assert instructions[0x125be4] == ('cmp', 'w8, #0xc8')
        assert instructions[0x125bf0] == ('cmp', 'x5, #0x2c8')
        result = []
        for sdk, prop, literal, load, field_read, offset in (
            (33749, 0x217083d5, 0x244110, 0x135db0, 0x135e2c, 0x30),
            (33751, 0x217083d7, 0x244120, 0x13a1a4, 0x13a220, 0x50),
        ):
            assert struct.unpack('<II', read(literal, 8)) == (0x20000000, prop)
            assert instructions[load] == ('ldr', f'd0, [x9, #0x{literal & 0xfff:x}]')
            assert instructions[field_read] == ('ldp', f'w8, w9, [x26, #0x{offset:x}]')
            queue, reachable = deque([load]), {load}
            while queue:
                for previous in reverse[queue.popleft()]:
                    if previous not in reachable:
                        reachable.add(previous)
                        queue.append(previous)
            services = [s for s, target in entries.items() if target in reachable]
            assert services == [0x6e]
            result.append({'sdk_manager_id': sdk, 'native_property': hex(prop),
                           'literal_words': ['0x20000000', hex(prop)],
                           'service': hex(services[0]), 'method': '0xc8', 'payload_bytes': 712,
                           'payload_offset': hex(offset), 'property_literal_va': hex(literal),
                           'property_load_va': hex(load), 'field_load_va': hex(field_read)})
        return {'vhal_sha256': EXPECTED, 'converter_va': hex(start), 'converter_bytes': size,
                'routes': result,
                'limit': 'Static transport route. Does not prove distance unit or stock reset behavior.'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('vhal', type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    args.output.write_text(json.dumps(inspect(args.vhal), indent=2) + '\n')
