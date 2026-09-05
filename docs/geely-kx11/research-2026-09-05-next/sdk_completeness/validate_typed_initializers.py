#!/usr/bin/env python3
"""Independently validate typed static initializers against original DEX bytes.

This verifier does not import the extraction decoder or Androguard. Its cursor,
arithmetic sign extension, and IEEE payload padding are implemented separately.
Only aggregates, hashes, and field indices are written to the public report.
The private DEX files and extraction caches are required for reproduction.
"""
import argparse
import collections
import gzip
import hashlib
import json
import math
import pathlib
import struct


NAMES = {
    0x00: 'byte', 0x02: 'short', 0x03: 'char', 0x04: 'int', 0x06: 'long',
    0x10: 'float', 0x11: 'double', 0x15: 'method_type', 0x16: 'method_handle',
    0x17: 'string', 0x18: 'type', 0x19: 'field', 0x1a: 'method', 0x1b: 'enum',
    0x1c: 'array', 0x1d: 'annotation', 0x1e: 'null', 0x1f: 'boolean',
}
MAX_ARG = {
    0x00: 0, 0x02: 1, 0x03: 1, 0x04: 3, 0x06: 7, 0x10: 3, 0x11: 7,
    0x15: 3, 0x16: 3, 0x17: 3, 0x18: 3, 0x19: 3, 0x1a: 3, 0x1b: 3,
    0x1c: 0, 0x1d: 0, 0x1e: 0, 0x1f: 1,
}
PRIMITIVE = {0x00, 0x02, 0x03, 0x04, 0x06, 0x10, 0x11, 0x1e, 0x1f}
SIGNED = {0x00, 0x02, 0x04, 0x06}
PREFIXES = ('Lcom/ecarx/', 'Lecarx/', 'Lvendor/ecarx/', 'Landroid/car/', 'Lcom/ts/')
CATALOG_TYPED_KEYS = ('value_kind', 'value_type', 'value_arg', 'encoded_hex', 'typed_value')


class Cursor:
    def __init__(self, blob, offset=0):
        self.blob = blob
        self.pos = offset

    def take(self, count):
        end = self.pos + count
        if count < 0 or end > len(self.blob):
            raise ValueError('truncated input')
        result = self.blob[self.pos:end]
        self.pos = end
        return result

    def byte(self):
        return self.take(1)[0]

    def natural(self):
        result, multiplier = 0, 1
        for index in range(5):
            digit = self.byte()
            if index == 4 and digit > 0x0f:
                raise ValueError('ULEB128 exceeds uint32')
            result += (digit % 128) * multiplier
            if digit < 128:
                return result
            multiplier *= 128
        raise ValueError('unterminated ULEB128')


def unsigned_le(payload):
    result = 0
    for octet in reversed(payload):
        result = result * 256 + octet
    return result


def read_value(cursor):
    start = cursor.pos
    header = cursor.byte()
    kind, argument = header % 32, header // 32
    if kind not in MAX_ARG or argument > MAX_ARG[kind]:
        raise ValueError('invalid encoded_value type or value_arg')
    if kind < 0x1c:
        payload = cursor.take(argument + 1)
        numeric = unsigned_le(payload)
        if kind in SIGNED:
            typed = numeric - 256 ** len(payload) if payload[-1] >= 128 else numeric
        elif kind == 0x03:
            typed = numeric
        elif kind in (0x10, 0x11):
            width = 4 if kind == 0x10 else 8
            # DEX stores the significant high bytes, least significant byte first.
            # Reverse those bytes and append zeros, then read a big-endian IEEE value.
            ieee = payload[::-1] + bytes(width - len(payload))
            typed = struct.unpack('>f' if width == 4 else '>d', ieee)[0]
            if not math.isfinite(typed):
                typed = {'nonfinite_float': repr(typed),
                         'bits_hex': hex(unsigned_le(ieee[::-1]))}
        else:
            typed = {'index_kind': NAMES[kind], 'index': numeric}
    elif kind == 0x1c:
        typed = [read_value(cursor) for _ in range(cursor.natural())]
    elif kind == 0x1d:
        type_index, count = cursor.natural(), cursor.natural()
        elements = []
        for _ in range(count):
            name_index = cursor.natural()
            elements.append({'name_idx': name_index, 'value': read_value(cursor)})
        typed = {'type_idx': type_index, 'elements': elements}
    elif kind == 0x1e:
        typed = None
    else:
        typed = argument == 1
    return {
        'value_type': kind, 'value_kind': NAMES[kind], 'value_arg': argument,
        'encoded_offset': start, 'encoded_size': cursor.pos - start,
        'encoded_hex': cursor.blob[start:cursor.pos].hex(), 'typed_value': typed,
    }


def equal(left, right):
    """Retain float type, signed zero, and nested NaN payload dictionaries."""
    if type(left) is not type(right):
        return False
    if isinstance(left, float):
        return struct.pack('>d', left) == struct.pack('>d', right)
    if isinstance(left, dict):
        return left.keys() == right.keys() and all(equal(left[k], right[k]) for k in left)
    if isinstance(left, (list, tuple)):
        return len(left) == len(right) and all(equal(a, b) for a, b in zip(left, right))
    return left == right


def serialized(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=True, separators=(',', ':'))


def sha(blob):
    return hashlib.sha256(blob).hexdigest()


def rows(path):
    with gzip.open(path, 'rt', encoding='utf-8') as stream:
        for line in stream:
            yield json.loads(line)


class Dex:
    def __init__(self, blob):
        self.blob = blob
        if blob[:4] != b'dex\n' or self.u32(40) != 0x12345678:
            raise ValueError('unsupported DEX magic or endianness')
        self.string_count, self.string_offset = struct.unpack_from('<II', blob, 56)
        self.type_count, self.type_offset = struct.unpack_from('<II', blob, 64)
        self.field_count, self.field_offset = struct.unpack_from('<II', blob, 80)
        self.class_count, self.class_offset = struct.unpack_from('<II', blob, 96)
        self.strings, self.types = {}, {}

    def u32(self, offset):
        return struct.unpack_from('<I', self.blob, offset)[0]

    def string(self, index):
        if not 0 <= index < self.string_count:
            raise ValueError('string index out of range')
        if index not in self.strings:
            cursor = Cursor(self.blob, self.u32(self.string_offset + index * 4))
            cursor.natural()
            end = self.blob.index(b'\0', cursor.pos)
            # MUTF-8 differs for NUL and surrogate code points. Catalog declaration
            # names are ASCII in this corpus; this also preserves non-ASCII names.
            payload = self.blob[cursor.pos:end].replace(b'\xc0\x80', b'\0')
            self.strings[index] = payload.decode('utf-8', errors='surrogatepass')
        return self.strings[index]

    def type_name(self, index):
        if not 0 <= index < self.type_count:
            raise ValueError('type index out of range')
        if index not in self.types:
            self.types[index] = self.string(self.u32(self.type_offset + index * 4))
        return self.types[index]

    def field_key(self, index):
        if not 0 <= index < self.field_count:
            raise ValueError('field index out of range')
        owner, descriptor, name = struct.unpack_from('<HHI', self.blob,
                                                     self.field_offset + index * 8)
        return (self.type_name(owner), self.string(name), self.type_name(descriptor))

    def declarations(self):
        """Yield class, fields, and original encoded-array values independently."""
        for number in range(self.class_count):
            definition = struct.unpack_from('<8I', self.blob, self.class_offset + number * 32)
            owner, data_offset, values_offset = definition[0], definition[6], definition[7]
            fields = []
            if data_offset:
                cursor = Cursor(self.blob, data_offset)
                counts = [cursor.natural() for _ in range(4)]
                for static, count in ((True, counts[0]), (False, counts[1])):
                    field_index = 0
                    for _ in range(count):
                        field_index += cursor.natural()
                        fields.append((field_index, cursor.natural(), static))
            if values_offset:
                cursor = Cursor(self.blob, values_offset)
                count = cursor.natural()
                if count > sum(static for _, _, static in fields):
                    raise ValueError('encoded array exceeds static field count')
                initializers = []
                for position in range(count):
                    value = read_value(cursor)
                    value.update({'class_idx': owner, 'field_idx': fields[position][0]})
                    initializers.append(value)
            else:
                initializers = []
            yield owner, fields, initializers


def vector_checks():
    vectors = [
        ('00ff', -1), ('0080', -128), ('007f', 127), ('02ff', -1),
        ('220080', -32768), ('227fff', -129), ('23ffff', 65535),
        ('04ff', -1), ('2480ff', -128), ('447fffff', -129),
        ('6400000080', -2147483648), ('64ffffff7f', 2147483647),
        ('06ff', -1), ('e60000000000000080', -9223372036854775808),
        ('e6ffffffffffffff7f', 9223372036854775807),
        ('1000', 0.0), ('1080', -0.0), ('30803f', 1.0),
        ('30c0bf', -1.5), ('70cdcccc3d', 0.10000000149011612),
        ('7001000000', struct.unpack('>f', bytes.fromhex('00000001'))[0]),
        ('1100', 0.0), ('1180', -0.0), ('31f03f', 1.0),
        ('31f8bf', -1.5), ('f10100000000000000', struct.unpack('>d', bytes.fromhex('0000000000000001'))[0]),
        ('30807f', {'nonfinite_float': 'inf', 'bits_hex': '0x7f800000'}),
        ('3080ff', {'nonfinite_float': '-inf', 'bits_hex': '0xff800000'}),
        ('70a1c0ff7f', {'nonfinite_float': 'nan', 'bits_hex': '0x7fffc0a1'}),
        ('31f07f', {'nonfinite_float': 'inf', 'bits_hex': '0x7ff0000000000000'}),
        ('f1341200000000f87f', {'nonfinite_float': 'nan', 'bits_hex': '0x7ff8000000001234'}),
        ('1e', None), ('1f', False), ('3f', True),
        ('370001', {'index_kind': 'string', 'index': 256}),
    ]
    failures = []
    for encoded, expected in vectors:
        cursor = Cursor(bytes.fromhex(encoded))
        value = read_value(cursor)
        if not equal(value['typed_value'], expected) or cursor.pos != len(cursor.blob):
            failures.append(encoded)
    invalid = ('20ff', '42ffff00', '83ffffffff00', '5f', '3e', '10', '04', '01')
    for encoded in invalid:
        try:
            read_value(Cursor(bytes.fromhex(encoded)))
            failures.append('accepted-invalid:' + encoded)
        except (ValueError, IndexError):
            pass
    return {'valid_vectors': len(vectors), 'invalid_vectors_rejected': len(invalid),
            'all_pass': not failures, 'failed_synthetic_vectors': failures}


def validate(root):
    source_table = json.loads((root / 'catalog/sources.json').read_text())['source_ids']
    source_indices = {source: index for index, source in enumerate(source_table)}
    failures = collections.Counter()
    samples = []

    def check(condition, name, source=None, field=None):
        if not condition:
            failures[name] += 1
            if len(samples) < 20:
                samples.append({'check': name, 'source_id': source, 'field_idx': field})

    catalog = collections.defaultdict(dict)
    catalog_count = 0
    fields_path = root / 'catalog/fields.jsonl.gz'
    for record in rows(fields_path):
        key = tuple(record['key'])
        for variant in record['variants']:
            for occurrence in variant['p']:
                source_index, field_index, offset, _ = occurrence
                target = catalog[source_index]
                check(field_index not in target, 'duplicate_catalog_occurrence',
                      source_table[source_index], field_index)
                target[field_index] = (key, variant['v'], offset)
                catalog_count += 1

    corrections_path = root / 'catalog/typed_initializer_corrections.jsonl.gz'
    corrections = {}
    for record in rows(corrections_path):
        identity = (record['source_index'], record['field_idx'])
        check(identity not in corrections, 'duplicate_correction',
              source_table[identity[0]], identity[1])
        corrections[identity] = record

    count = collections.Counter()
    all_kinds, scoped_kinds, corrected_kinds = (collections.Counter() for _ in range(3))
    source_hashes, cache_hashes = [], []
    raw_correction_keys, baseline_indices = set(), set()
    dex_paths = sorted((root / 'private/dex').glob('*.dex'))
    check({'sha256:' + p.stem for p in dex_paths} == set(source_table), 'raw_dex_source_set')
    for path in dex_paths:
        source = 'sha256:' + path.stem
        source_index = source_indices[source]
        blob = path.read_bytes()
        digest = sha(blob)
        check(digest == path.stem, 'raw_dex_sha256_identity', source)
        source_hashes.append((source, digest))
        dex = Dex(blob)
        cache_path = root / 'private/typed_initializers' / (path.stem + '.json')
        cache_blob = cache_path.read_bytes()
        cache_hashes.append((source, sha(cache_blob)))
        cache = json.loads(cache_blob)
        check(cache['source_id'] == source, 'typed_cache_source_identity', source)
        cached = {record['field_idx']: record for record in cache['initializers']}
        check(len(cached) == len(cache['initializers']), 'duplicate_typed_cache_field', source)
        per_source_kinds = collections.Counter()
        raw_field_ids, scoped_field_ids = set(), set()
        for owner, fields, initializers in dex.declarations():
            class_name = dex.type_name(owner)
            scoped = class_name.startswith(PREFIXES)
            initialized = {record['field_idx']: record for record in initializers}
            for raw in initializers:
                field_index = raw['field_idx']
                raw_field_ids.add(field_index)
                count['all_encoded_static_initializers'] += 1
                per_source_kinds[raw['value_kind']] += 1
                check(equal(raw, cached.get(field_index)), 'private_typed_cache_value_and_metadata',
                      source, field_index)
                if raw['value_type'] in SIGNED and raw['typed_value'] < 0:
                    count['all_negative_signed_initializers'] += 1
                if raw['value_type'] in (0x10, 0x11):
                    count['all_ieee_initializers_checked'] += 1
            for field_index, access_flags, static in fields:
                count['all_field_declarations'] += 1
                if not scoped:
                    continue
                count['scoped_field_occurrences_checked'] += 1
                scoped_field_ids.add(field_index)
                published = catalog[source_index].get(field_index)
                check(published is not None, 'missing_scoped_catalog_field', source, field_index)
                if published is None:
                    continue
                key, metadata, offset = published
                check(key == dex.field_key(field_index), 'catalog_field_identity', source, field_index)
                check(key[0] == class_name, 'raw_field_class_identity', source, field_index)
                check(metadata['access_flags'] == access_flags, 'catalog_field_access_flags', source, field_index)
                raw = initialized.get(field_index)
                check(metadata['encoded_initializer_present'] == (raw is not None),
                      'catalog_encoded_initializer_presence', source, field_index)
                check(offset == (raw['encoded_offset'] if raw else None),
                      'catalog_encoded_initializer_offset', source, field_index)
                if raw is None:
                    check('typed_encoded_initializer' not in metadata, 'unexpected_catalog_typed_value',
                          source, field_index)
                    continue
                count['scoped_encoded_static_initializers_checked'] += 1
                scoped_kinds[raw['value_kind']] += 1
                check(static, 'initializer_assigned_to_static_field', source, field_index)
                expected_typed = {key: raw[key] for key in CATALOG_TYPED_KEYS}
                check(equal(expected_typed, metadata.get('typed_encoded_initializer')),
                      'catalog_typed_value_and_bits', source, field_index)
                if raw['value_type'] in PRIMITIVE:
                    count['scoped_primitive_initializers_checked'] += 1
                    check(equal(metadata['encoded_value'], raw['typed_value']),
                          'catalog_normalized_primitive_value', source, field_index)
                    check(equal(metadata['vm_initial_value_before_clinit'], raw['typed_value']),
                          'catalog_primitive_value_before_clinit', source, field_index)
                    differs = serialized(metadata['extractor_encoded_value_raw']) != serialized(raw['typed_value'])
                    if differs:
                        identity = (source_index, field_index)
                        raw_correction_keys.add(identity)
                        corrected_kinds[raw['value_kind']] += 1
                        correction = corrections.get(identity)
                        check(correction is not None, 'missing_required_correction', source, field_index)
                        if correction is not None:
                            correction_key = (correction['class'], correction['name'], correction['descriptor'])
                            check(correction_key == key, 'correction_field_identity', source, field_index)
                            check(equal(correction['typed_encoded_initializer'], raw),
                                  'correction_value_and_raw_metadata', source, field_index)
                            check(equal(correction['extractor_encoded_value_raw'], metadata['extractor_encoded_value_raw']),
                                  'correction_original_value_preserved', source, field_index)
                            check(correction['baseline_constant_indices'] == metadata['baseline_constant_indices'],
                                  'correction_baseline_links', source, field_index)
                            baseline_indices.update(correction['baseline_constant_indices'])
        check(raw_field_ids == set(cached), 'typed_cache_initializer_set', source)
        check(scoped_field_ids == set(catalog[source_index]), 'catalog_scoped_field_set', source)
        check(dict(per_source_kinds) == cache['counts'], 'typed_cache_kind_counts', source)
        all_kinds.update(per_source_kinds)
        count['dex_files_checked'] += 1
    check(raw_correction_keys == set(corrections), 'correction_set_complete_and_exact')
    check(count['scoped_field_occurrences_checked'] == catalog_count, 'catalog_occurrence_count')
    vectors = vector_checks()
    check(vectors['all_pass'], 'synthetic_decoder_vectors')
    return {
        'schema': 'independent-typed-initializer-validation-v1',
        'all_checks_pass': not failures,
        'method': 'Independent raw DEX class_data and encoded_array parser; arithmetic signed extension; byte-padded IEEE float/double decoding; exact float comparison including signed zero; NaN/Infinity payload preservation.',
        'scope_prefixes': list(PREFIXES),
        'counts': dict(count),
        'all_initializer_counts_by_kind': dict(sorted(all_kinds.items())),
        'scoped_initializer_counts_by_kind': dict(sorted(scoped_kinds.items())),
        'corrections': {'published_records': len(corrections),
                        'independently_required_records': len(raw_correction_keys),
                        'by_kind': dict(sorted(corrected_kinds.items())),
                        'affected_baseline_declarations': len(baseline_indices)},
        'synthetic_vectors': vectors,
        'failure_counts': dict(failures),
        'failure_samples': samples,
        'provenance': {
            'validator_sha256': sha(pathlib.Path(__file__).read_bytes()),
            'decoder_under_validation_sha256': sha((root / 'decode_initializers.py').read_bytes()),
            'catalog_sources_sha256': sha((root / 'catalog/sources.json').read_bytes()),
            'catalog_fields_sha256': sha(fields_path.read_bytes()),
            'catalog_corrections_sha256': sha(corrections_path.read_bytes()),
            'raw_source_id_and_sha256_pairs_sha256': sha(serialized(source_hashes).encode()),
            'private_typed_cache_hash_pairs_sha256': sha(serialized(cache_hashes).encode()),
        },
        'limitations': [
            'Validates encoded initializers and catalog correction coverage, not class initializer execution or final effective runtime values.',
            'Disk DEX identity does not establish runtime classloader origin or physical vehicle support.',
            'String/reference payload metadata is checked, but resolved reference values are outside this primitive normalization check.',
            'Original extractor values are checked for preservation between public catalog and correction records, not independently re-created.',
        ],
        'runtime_class_origin_verified': False,
        'effective_runtime_values_computed': False,
        'firmware_executed': False,
        'vehicle_commands_sent': False,
        'public_output_contains_foreign_method_bodies': False,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=pathlib.Path, default=pathlib.Path(__file__).resolve().parent)
    parser.add_argument('--output', type=pathlib.Path)
    arguments = parser.parse_args()
    report = validate(arguments.root)
    output = arguments.output or arguments.root / 'TYPED_INITIALIZERS_VALIDATION.json'
    output.write_text(json.dumps(report, ensure_ascii=True, indent=2) + '\n')
    print(json.dumps({key: report[key] for key in ('all_checks_pass', 'counts', 'corrections', 'failure_counts')}, sort_keys=True))
    return 0 if report['all_checks_pass'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
