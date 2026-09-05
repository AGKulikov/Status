#!/usr/bin/env python3
"""Offline search of Geely interface JSON pages. Never contacts or controls a vehicle.
Examples:
  python3 lookup_interfaces.py PAC_ACTIVATION
  python3 lookup_interfaces.py EpbSoftSwtCtrlSt --json
  python3 lookup_interfaces.py 0x214070F4 --root /path/to/published
"""
import argparse
import hashlib
import json
from pathlib import Path


def search(root, query):
    root = Path(root).resolve()
    manifest = json.loads((root / 'entries_manifest.json').read_text(encoding='utf-8'))
    errata = json.loads((root / 'semantic_errata.json').read_text(encoding='utf-8'))
    if errata['applies_to_entry_index_sha256'] != manifest['source_sha256']:
        raise ValueError('Semantic errata targets a different baseline')
    overrides = {e['entry_key']: e for e in errata['state_value_declarations']}
    needle = query.casefold()
    matches = []
    seen_keys = set()
    total = 0
    for page in manifest['pages']:
        path = (root / page['filename']).resolve()
        if root not in path.parents:
            raise ValueError('Page filename resolves outside index directory')
        data = path.read_bytes()
        if hashlib.sha256(data).hexdigest() != page['sha256']:
            raise ValueError('SHA-256 mismatch: ' + page['filename'])
        entries = json.loads(data)['entries']
        if len(entries) != page['entry_count']:
            raise ValueError('Entry count mismatch: ' + page['filename'])
        if entries and (entries[0]['entry_key'] != page['first_entry_key'] or entries[-1]['entry_key'] != page['last_entry_key']):
            raise ValueError('Boundary keys mismatch: ' + page['filename'])
        total += len(entries)
        for entry in entries:
            key = entry['entry_key']
            if key in seen_keys:
                raise ValueError('Duplicate entry_key: ' + key)
            seen_keys.add(key)
            original_role = entry.get('declaration_role')
            if key in overrides:
                correction = overrides[key]
                if (entry['id'], entry['name'], entry['declaring_class']) != (correction['value'], correction['name'], correction['declaring_class']):
                    raise ValueError('State-value correction does not match entry: ' + key)
                entry['baseline_declaration_role'] = original_role
                entry['declaration_role'] = correction['corrected_declaration_role']
                entry['semantic_correction_source'] = 'semantic_errata.json'
            elif entry['namespace'] == 'adaptapi' and original_role == 'function_id_declaration':
                entry['baseline_declaration_role'] = original_role
                entry['declaration_role'] = errata['broad_role_rule']['corrected_default_role']
                entry['semantic_correction_source'] = 'semantic_errata.json'
            if needle in json.dumps(entry, ensure_ascii=False).casefold():
                matches.append({'page': page['filename'], 'entry': entry})
    if total != manifest['entry_count']:
        raise ValueError('Manifest total entry count mismatch')
    return matches, total


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('query', help='Case-insensitive text, method name, decimal ID or hexadecimal ID')
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parent)
    parser.add_argument('--limit', type=int, default=20, help='Maximum displayed matches; use 0 for all')
    parser.add_argument('--json', action='store_true', help='Emit complete matched entries as JSON')
    args = parser.parse_args()
    if not args.query.strip():
        parser.error('Query must contain non-whitespace text')
    if args.limit < 0:
        parser.error('--limit must be non-negative')
    try:
        matches, total = search(args.root, args.query)
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(2, 'Index validation failed: ' + str(error) + '\n')
    displayed = matches[:args.limit] if args.limit else matches
    if args.json:
        print(json.dumps({'query': args.query, 'entries_scanned': total,
                          'match_count': len(matches), 'displayed_count': len(displayed),
                          'results': displayed}, ensure_ascii=False, indent=2))
        return
    print('Matches: ' + str(len(matches)) + '; scanned: ' + str(total) + '; displayed: ' + str(len(displayed)))
    for result in displayed:
        entry = result['entry']
        print('\n' + entry['entry_key'] + ' | ' + entry['namespace'] + ' | ' + str(entry['id']) + ' | ' + entry['id_hex'])
        print(entry['name'])
        print('Class: ' + entry['declaring_class'])
        print('Declaration role: ' + str(entry.get('declaration_role', 'unresolved')))
        print('Page: ' + result['page'])
        access = entry.get('sdk_access')
        print('SDK access: ' + (', '.join(access) if access else 'not established per function in this index'))
        for method in entry.get('methods', []):
            print('Method: ' + method['name'] + method['descriptor'])
        for route in entry.get('native_routes', []):
            print('VHAL: ' + route['vhal_hex'] + ' | ' + route['type'] + ' | ' + route['native_access'] + ' | SDK ' + route['direction'])
        observation = entry.get('observation', {})
        print('Numeric CarSignal observation recorded: ' + str(observation.get('recorded')))
    if len(displayed) != len(matches):
        print('\nUse --limit 0 to display all matches.')
    print('\nStatic interface metadata does not establish runtime support or a physical control effect.')


if __name__ == '__main__':
    main()
