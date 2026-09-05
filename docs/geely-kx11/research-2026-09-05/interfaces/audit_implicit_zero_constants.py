#!/usr/bin/env python3
"""Audit omitted encoded initializers in one original ECARX SDK JAR, offline.
Requires androguard and loguru. Reads ZIP/JAR/DEX without executing firmware.
Usage: python3 audit_implicit_zero_constants.py ARCHIVE BASELINE_BUNDLE OUTPUT_JSON
"""
import argparse
import collections
import hashlib
import io
import json
import re
from pathlib import Path
import zipfile

PRIMITIVES = {'Z', 'B', 'C', 'S', 'I', 'J', 'F', 'D'}
MEMBER = 'framework/system_framework/ecarx.adaptapi.jar'
SPEC = 'https://source.android.com/docs/core/runtime/dex-format#class-def-item'


def in_old_scope(name):
    return name.startswith(('Lcom/ecarx/xui/adaptapi/', 'Lecarx/car/', 'Landroid/car/',
                            'Lvendor/ecarx/xma/', 'Lecarx/dimprotocol/', 'Lecarx/powersomeip/'))


def audit(archive, baseline, destination):
    from androguard.core.dex import DEX
    from loguru import logger
    logger.remove()
    archive = Path(archive); baseline = Path(baseline); destination = Path(destination)
    raw = archive.read_bytes()
    with zipfile.ZipFile(io.BytesIO(raw)) as outer:
        jar = outer.read(MEMBER)
    frozen_bytes = (baseline / 'firmware/constants.json').read_bytes()
    constants = json.loads(frozen_bytes)['constants']
    by_decl = collections.defaultdict(list)
    for i, constant in enumerate(constants):
        by_decl[(constant['class'], constant['name'], constant['descriptor'])].append((i, constant))
    catalog = json.loads((baseline / 'firmware/catalog.json').read_text())
    validators = collections.defaultdict(set)
    for section in ['car_signals', 'manager_ids']:
        for i, entry in enumerate(catalog[section]):
            for method in entry.get('methods', []):
                for validator in method.get('validators', []):
                    validators[validator].add(section + ':' + str(i))
    dex_sources = []
    records = []
    errors = []
    totals = collections.Counter()
    with zipfile.ZipFile(io.BytesIO(jar)) as inner:
        for dex_name in sorted(n for n in inner.namelist() if re.fullmatch(r'classes\d*\.dex', n)):
            dex_bytes = inner.read(dex_name)
            dex = DEX(dex_bytes)
            source_number = len(dex_sources)
            dex_sources.append({'member': MEMBER, 'jar_sha256': hashlib.sha256(jar).hexdigest(),
                                'dex_entry': dex_name, 'dex_sha256': hashlib.sha256(dex_bytes).hexdigest()})
            classes = {c.get_name(): c for c in dex.get_classes()}
            candidates = {}
            for cls in classes.values():
                for field in cls.get_fields():
                    descriptor = field.get_descriptor()
                    if (field.get_access_flags() & 0x18) != 0x18 or descriptor not in PRIMITIVES:
                        continue
                    totals['static_final_primitive_fields'] += 1
                    if field.get_init_value() is None:
                        candidates[field.get_field_idx()] = (cls, field)
                    else:
                        totals['static_final_primitive_fields_with_encoded_initializer'] += 1
            puts = collections.defaultdict(list)
            clinit_info = {}
            candidate_classes = {c.get_name() for c, f in candidates.values()}
            # Scan every bytecode method for exact field-index sput references, not just matching text.
            for cls in classes.values():
                for method in cls.get_methods():
                    if method.get_code() is None:
                        if method.get_name() == '<clinit>' and cls.get_name() in candidate_classes:
                            clinit_info[cls.get_name()] = {'class': cls.get_name(), 'method': '<clinit>',
                                                          'descriptor': method.get_descriptor(), 'decoded': False,
                                                          'reason': 'class initializer has no bytecode body'}
                        continue
                    totals['bytecode_methods_scanned'] += 1
                    is_clinit = method.get_name() == '<clinit>'
                    method_locator = {'class': cls.get_name(), 'method': method.get_name(),
                                      'descriptor': method.get_descriptor(), 'method_index': method.get_method_idx()}
                    registers = {}
                    decoded = True
                    method_puts = 0
                    try:
                        instructions = list(method.get_instructions_idx())
                        for offset, instruction in instructions:
                            name = instruction.get_name()
                            operands = instruction.get_operands()
                            # Only local literal propagation for describing a concrete assignment;
                            # never substitute this as the final runtime field value.
                            if name.startswith('const') and not name.startswith(('const-string', 'const-class')):
                                regs = [v[1] for v in operands if v[0] == 0]
                                literals = [v[1] for v in operands if v[0] == 1]
                                if regs and literals:
                                    registers[regs[0]] = (literals[0], offset)
                            elif name.startswith('move') and not name.startswith(('move-result', 'move-exception')):
                                regs = [v[1] for v in operands if v[0] == 0]
                                if len(regs) >= 2 and regs[1] in registers:
                                    registers[regs[0]] = registers[regs[1]]
                                elif regs:
                                    registers.pop(regs[0], None)
                            elif name.startswith(('if-', 'goto', 'packed-switch', 'sparse-switch')):
                                registers.clear()
                            elif name.startswith('sput'):
                                method_puts += 1
                                field_indices = [v[1] for v in operands if v[0] == 258]
                                if len(field_indices) != 1:
                                    raise ValueError('Unexpected sput operand schema')
                                field_index = field_indices[0]
                                if field_index in candidates:
                                    regs = [v[1] for v in operands if v[0] == 0]
                                    literal = registers.get(regs[0]) if regs else None
                                    item = dict(method_locator, instruction_offset_bytes=offset,
                                                operation=name, in_class_initializer=is_clinit)
                                    if literal is not None:
                                        item['assignment_literal'] = literal[0]
                                        item['literal_instruction_offset_bytes'] = literal[1]
                                    puts[field_index].append(item)
                            elif operands and operands[0][0] == 0 and not name.startswith(('invoke-', 'return', 'throw', 'iput', 'aput', 'monitor-')):
                                registers.pop(operands[0][1], None)
                    except Exception as exc:
                        decoded = False
                        errors.append(dict(method_locator, error=type(exc).__name__ + ': ' + str(exc)))
                    if is_clinit and cls.get_name() in candidate_classes:
                        clinit_info[cls.get_name()] = dict(method_locator, decoded=decoded,
                                                          bytecode_sha256=hashlib.sha256(method.get_code().get_bc().get_raw()).hexdigest(),
                                                          total_sput_instructions=method_puts)
            for field_index, (cls, field) in sorted(candidates.items(), key=lambda x: (x[1][0].get_name(), x[1][1].get_name())):
                name = cls.get_name()
                descriptor = field.get_descriptor()
                writes = puts.get(field_index, [])
                clinit = clinit_info.get(name)
                uncertain = bool(errors) or (clinit is not None and not clinit['decoded'])
                if writes:
                    status = 'assigned_by_bytecode_effective_value_not_inferred'
                    value = None
                elif uncertain:
                    status = 'unknown_due_to_incomplete_bytecode_scan'
                    value = None
                else:
                    status = 'vm_default_zero_no_sput_in_scanned_dex'
                    value = False if descriptor == 'Z' else 0.0 if descriptor in {'F', 'D'} else 0
                old = by_decl.get((name, field.get_name(), descriptor), [])
                frozen = [{'json_pointer': '/constants/' + str(i), 'value': c['value'],
                           'same_exact_jar_source': any(s.get('member') == MEMBER and s.get('sha256') == hashlib.sha256(jar).hexdigest()
                                                        for s in c['sources'])} for i, c in old]
                records.append({
                    'class': name, 'name': field.get_name(), 'descriptor': descriptor,
                    'access_flags': field.get_access_flags_string(), 'field_index': field_index,
                    'source_ref': source_number,
                    'encoded_initializer': None,
                    'vm_initial_value_before_clinit': False if descriptor == 'Z' else 0.0 if descriptor in {'F', 'D'} else 0,
                    'classification': status, 'static_effective_value': value,
                    'class_initializer': clinit, 'exact_field_sput_sites': writes,
                    'in_original_extractor_namespace_scope': in_old_scope(name),
                    'frozen_constant_declarations': frozen,
                    'validator_used_by_baseline_entry_keys': sorted(validators.get(name, [])),
                    'runtime_class_origin_verified': False,
                })
    counts = dict(collections.Counter(r['classification'] for r in records))
    result = {
        'schema': 'geely-implicit-primitive-initializer-audit-v1',
        'scope': 'All static final primitive fields without an encoded initializer in the exact system ecarx.adaptapi.jar; not all archived SDK variants.',
        'source_archive_filename': archive.name, 'source_archive_sha256': hashlib.sha256(raw).hexdigest(),
        'sources': dex_sources,
        'baseline_constants_sha256': hashlib.sha256(frozen_bytes).hexdigest(),
        'basis': {'android_dex_specification': SPEC,
                  'rule': 'Missing trailing encoded static values receive type-appropriate zero/null before class initialization. A later sput assignment must be assessed separately.'},
        'method': 'Match access flags static+final and primitive descriptor, require get_init_value() is None, then scan every bytecode method in each DEX for sput instructions to that exact field index. A confirmed default requires a complete scan and no direct field writes; no firmware is executed.',
        'limitations': ['This is a static field-value audit, not runtime classloader verification.',
                       'Fields written by bytecode retain static_effective_value=null even when a literal assignment site is shown.',
                       'JNI, reflection and other external runtime writes are not inferred or ruled out by this static DEX scan.',
                       'Frozen baseline totals 4440 and 16082 remain unchanged. Supplementary findings must not be silently merged into baseline metrics.',
                       'This audits only omitted encoded initializers, not all other selection rules or completeness defects in the old extractor.'],
        'counts': dict(totals, candidates=len(records), candidate_classes=len({r['class'] for r in records}),
                       classifications=counts,
                       in_old_namespace_scope=sum(r['in_original_extractor_namespace_scope'] for r in records),
                       absent_from_frozen_all_sources=sum(not r['frozen_constant_declarations'] for r in records),
                       absent_from_frozen_exact_jar_source=sum(not any(x['same_exact_jar_source'] for x in r['frozen_constant_declarations']) for r in records),
                       affected_baseline_validator_entries=len({k for r in records for k in r['validator_used_by_baseline_entry_keys']}),
                       bytecode_scan_errors=len(errors)),
        'scan_errors': errors, 'fields': records,
    }
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive', type=Path)
    parser.add_argument('baseline_bundle', type=Path)
    parser.add_argument('output_json', type=Path)
    args = parser.parse_args()
    result = audit(args.archive, args.baseline_bundle, args.output_json)
    print(json.dumps(result['counts'], ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
