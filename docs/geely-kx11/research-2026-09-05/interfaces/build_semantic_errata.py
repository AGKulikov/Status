#!/usr/bin/env python3
"""Generate explicit semantic errata for the frozen Geely interface index, offline.
Usage: python3 build_semantic_errata.py entry_index.json output_directory
The frozen entries are neither changed nor regenerated.
"""
import argparse
import collections
import hashlib
import json
from pathlib import Path
import re


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('entry_index', type=Path)
    parser.add_argument('output_directory', type=Path)
    args = parser.parse_args()
    raw = args.entry_index.read_bytes()
    entries = json.loads(raw)['entries']
    adapt = [e for e in entries if e['namespace'] == 'adaptapi']
    by_number = collections.defaultdict(list)
    for entry in adapt:
        by_number[entry['id']].append(entry)
    state_pattern = re.compile(r'^SETTING_FUNC_DIGITAL_KEY_(SUSPENSION|TERMINATION|UNPAIR)_(IDLE|SUCCESS|REJECT_.+)$')
    values = []
    for entry in adapt:
        match = state_pattern.fullmatch(entry['name'])
        if match and 0 <= entry['id'] <= 6:
            values.append({
                'entry_key': entry['entry_key'], 'name': entry['name'],
                'declaring_class': entry['declaring_class'],
                'context': 'DIGITAL_KEY_' + match.group(1),
                'state_name': match.group(2),
                'value': entry['id'], 'stored_baseline_id_hex': entry['id_hex'],
                'original_declaration_role': entry['declaration_role'],
                'corrected_declaration_role': 'state_value_declaration',
                'basis': 'Literal declaration name and value in the corresponding digital-key state family. No runtime transition meaning or installed feature support is inferred.',
                'source': entry['source'],
            })
    assert len(values) == 20
    pairs = []
    for number in [0x00800100, 0x00800200, 0x00800300, 0x00800400]:
        config = [e for e in by_number[number] if e['declaring_class'].endswith('/ICarInfo;')]
        sensor = [e for e in by_number[number] if e['declaring_class'].endswith('/ISensorGroup;')]
        assert len(config) == len(sensor) == 1
        pairs.append({
            'numeric_value': number, 'hex': '0x' + format(number, '08X'),
            'relationship': 'same number in distinct API contexts; semantic alias is not established',
            'declarations': [{
                'entry_key': e['entry_key'], 'name': e['name'],
                'declaring_class': e['declaring_class'],
                'api_context': 'car_info_configuration' if e is config[0] else 'sensor_group_type',
                'source': e['source'],
            } for e in [config[0], sensor[0]]],
        })
    broad = [e for e in adapt if e.get('declaration_role') == 'function_id_declaration']
    state_keys = {e['entry_key'] for e in values}
    result = {
        'schema': 'geely-frozen-interface-semantic-errata-v1',
        'applies_to_entry_index_sha256': hashlib.sha256(raw).hexdigest(),
        'frozen_entries_changed': False,
        'scope': 'Semantic corrections to role labels and duplicate-number interpretation, not a modification of source declarations, values, keys or frozen totals.',
        'duplicate_number_interpretation': {
            'adapt_declarations': len(adapt),
            'distinct_numeric_values': len(by_number),
            'repeated_numeric_declarations': len(adapt) - len(by_number),
            'replacement_wording_ru': '909 деклараций содержат 808 различных чисел и 101 повторное числовое объявление, включая алиасы и совпадения разных API-контекстов. Числовое равенство само по себе не доказывает семантическую эквивалентность.',
            'same_name_only_groups_repeated_declarations': sum(len(es) - 1 for es in by_number.values() if len({e['name'] for e in es}) == 1),
            'mixed_name_groups_repeated_declarations': sum(len(es) - 1 for es in by_number.values() if len({e['name'] for e in es}) > 1),
            'all_aliases_assertion_valid': False,
        },
        'broad_role_rule': {
            'matches': {'namespace': 'adaptapi', 'declaration_role': 'function_id_declaration'},
            'matched_entries': len(broad),
            'corrected_default_role': 'function_named_declaration_unresolved',
            'reason': 'The original selector and role assignment use name/class conventions. A field containing _FUNC_ is not thereby proven to be an independent function identifier; state values also contain _FUNC_.',
            'explicit_state_value_overrides': len(values),
            'entries_remaining_under_unresolved_default_role': len(broad) - len(values),
            'precedence': 'Apply explicit state-value overrides first; otherwise apply the corrected default role. Independent stronger static route evidence remains separately available.',
        },
        'state_value_declarations': values,
        'configuration_sensor_group_context_collisions': pairs,
        'consumer_rules': [
            'Always retain namespace, declaring class, declaration name and entry_key; never join Adapt declarations using only a numeric ID.',
            'Read this errata together with the frozen pages and source evidence. Pages intentionally preserve the original baseline bytes.',
            'The id field in the frozen index is a literal declaration value. It is not a universal assertion that this value is an executable function ID.',
            'These 20 state constants and four context collisions are concrete corrections, not a complete audit of every semantic role in the shared SDK.',
            'No firmware is executed and no command is sent to the vehicle by this errata generator.',
        ],
    }
    assert len(broad) == 792 and len(broad) - len(values) == 772
    args.output_directory.mkdir(parents=True, exist_ok=True)
    (args.output_directory / 'semantic_errata.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    lines = ['# Семантические исправления к замороженному индексу интерфейсов', '',
        '**Эти исправления обязательны при чтении страниц исходного индекса.** Значения, ключи, порядок и хэши страниц сохранены. Исправляется смысл классификации, а не исходные декларации.', '',
        '## Повторные числа не всегда являются алиасами', '',
        result['duplicate_number_interpretation']['replacement_wording_ru'], '',
        'Четыре конкретных совпадения разных контекстов:', '',
        '| Число | Конфигурация ICarInfo | Тип группы ISensorGroup |', '|---|---|---|']
    for pair in pairs:
        a, b = pair['declarations']
        lines.append('| `' + pair['hex'] + '` | `' + a['name'] + '` (`' + a['entry_key'] + '`) | `' + b['name'] + '` (`' + b['entry_key'] + '`) |')
    lines += ['', 'Имена API-классов полностью указаны в [машиночитаемых исправлениях](semantic_errata.json). Это не четыре пары взаимозаменяемых функций. В частности, CONFIG_INFO_DVR и SENSOR_GROUP_TYPE_PULES используют одно число в разных контекстах.', '',
        '## Исправление широкой роли function_id_declaration', '',
        'Для **792** Adapt-записей роль `function_id_declaration` была назначена слишком широко по имени и классу. Её следует читать как **`function_named_declaration_unresolved`**: имя похоже на обозначение функции, но назначение каждой декларации этим не доказано. Из этих 792 записей для **20** ниже установлена более точная роль `state_value_declaration`; для остальных **772** сохраняется исправленная общая роль. Отдельная доказанная связь с реализацией функции остаётся самостоятельным более сильным свидетельством.', '',
        'У всех 20 записей класс объявления — `Lcom/ecarx/xui/adaptapi/car/vehicle/IVehicle;`. Это значения семейств состояний цифрового ключа; их наличие не подтверждает установленное оборудование или работающий цифровой ключ.', '',
        '| entry_key | Объявление | Значение |', '|---|---|---:|']
    for value in values:
        lines.append('| `' + value['entry_key'] + '` | `' + value['name'] + '` | ' + str(value['value']) + ' |')
    lines += ['', '## Правило применения', '',
        'При чтении сначала применить 20 явных переопределений; затем для оставшихся записей прежней роли использовать `function_named_declaration_unresolved`. Поле `id` замороженного JSON хранит числовое значение объявления и не гарантирует, что это самостоятельный идентификатор вызываемой функции.', '',
        'Соединять данные нужно по пространству, классу, имени и `entry_key`; одного числа недостаточно. Эти исправления не являются полным аудитом семантики всех деклараций SDK.', '',
        'Счётчики **4440 / 909 / 808 / 16082** не изменяются. SHA-256 исходного `entry_index.json`: `' + result['applies_to_entry_index_sha256'] + '`.', '',
        'Воспроизведение:', '', '```bash', 'python3 build_semantic_errata.py /path/to/entry_index.json /path/to/output', '```']
    (args.output_directory / 'SEMANTIC_ERRATA_RU.md').write_text('\n'.join(lines) + '\n', encoding='utf-8')
    print(json.dumps({'state_value_overrides': len(values), 'context_collision_pairs': len(pairs),
                      'broad_role_matches': len(broad), 'remaining_unresolved_role': len(broad) - len(values)}, ensure_ascii=False))


if __name__ == '__main__':
    main()
