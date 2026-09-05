#!/usr/bin/env python3
"""Offline lookup in the published ECARX declaration catalog (stdlib only)."""
import argparse
import gzip
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parent
KINDS = ('classes', 'fields', 'methods', 'static_writes', 'family_calls',
         'inheritance', 'value_conflicts', 'typed_initializer_corrections')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('kind', choices=KINDS + ('sources',))
    parser.add_argument('query', nargs='?', default='', help='Case-insensitive name/class substring')
    parser.add_argument('--class', dest='class_name', help='Exact Java class or DEX descriptor')
    parser.add_argument('--name', help='Exact field or method name')
    parser.add_argument('--descriptor', help='Exact field/method descriptor')
    parser.add_argument('--source', help='Unique full or abbreviated DEX SHA-256')
    parser.add_argument('--category', help='Exact category from catalog_manifest.json')
    parser.add_argument('--limit', type=int, default=20, help='Maximum records; zero means all')
    args = parser.parse_args()
    if args.limit < 0:
        parser.error('--limit must be nonnegative')
    source_ids = json.loads((ROOT / 'catalog/sources.json').read_text())['source_ids']
    selected = None
    if args.source:
        selected_ids = [i for i, sid in enumerate(source_ids)
                        if sid.removeprefix('sha256:').startswith(args.source.removeprefix('sha256:'))]
        if len(selected_ids) != 1:
            parser.error('--source must identify exactly one DEX; matches: ' + str(len(selected_ids)))
        selected = selected_ids[0]
    if args.kind == 'sources':
        with gzip.open(ROOT / 'source_inventory.json.gz', 'rt') as stream:
            inventory = json.load(stream)
        occurrences = inventory['occurrences']
        graph = {s['source_id']: s for s in inventory['sources']}
        parents = {}
        for source in graph.values():
            for child in source['children']:
                parents.setdefault(child['source_id'], []).append((source['source_id'], child['member']))

        def paths(sid, trail=frozenset()):
            if sid in trail:
                return []
            out = [o['path_chain'] for o in occurrences if o['source_id'] == sid]
            for parent, member in parents.get(sid, []):
                out.extend(path + [member] for path in paths(parent, trail | {sid}))
            return sorted({tuple(path) for path in out})

        emitted = 0
        for sid in source_ids:
            if selected is not None and sid != source_ids[selected]:
                continue
            found_paths = paths(sid)
            if args.query.lower() not in json.dumps([sid, found_paths]).lower():
                continue
            print(json.dumps({'source_id': sid, 'size': graph[sid]['size'],
                              'path_chains': found_paths}, ensure_ascii=False))
            emitted += 1
            if args.limit and emitted >= args.limit:
                break
        return
    class_name = args.class_name
    if class_name and not (class_name.startswith('L') and class_name.endswith(';')):
        class_name = 'L' + class_name.replace('.', '/') + ';'
    emitted = 0
    with gzip.open(ROOT / 'catalog' / (args.kind + '.jsonl.gz'), 'rt') as stream:
        for line in stream:
            row = json.loads(line)
            key = row.get('key', [row.get('class'), row.get('name', row.get('method')),
                                  row.get('descriptor')])
            if class_name and key[0] != class_name:
                continue
            if args.name and (len(key) < 2 or key[1] != args.name):
                continue
            if args.descriptor and (len(key) < 3 or key[2] != args.descriptor):
                continue
            if args.query.lower() not in json.dumps(key).lower():
                continue
            if args.category and row.get('category') != args.category:
                continue
            if args.kind in ('classes', 'fields', 'methods'):
                variants = []
                for variant in row['variants']:
                    provenance = [p for p in variant['p'] if selected is None or p[0] == selected]
                    if provenance:
                        variants.append({'metadata': variant['v'],
                                         'occurrences': [[source_ids[p[0]], *p[1:]] for p in provenance]})
                if not variants:
                    continue
                row['variants'] = variants
            elif 'source_index' in row:
                index = row.pop('source_index')
                if selected is not None and index != selected:
                    continue
                row['source_id'] = source_ids[index]
            elif args.kind == 'value_conflicts' and selected is not None:
                variants = []
                for variant in row['variants']:
                    relevant = [p for p in variant['sources'] if p['source_id'] == source_ids[selected]]
                    if relevant:
                        variants.append({**variant, 'sources': relevant})
                if not variants:
                    continue
                row['variants'] = variants
            for label in ('same_container_source_indices', 'other_source_indices'):
                if label in row:
                    row[label.replace('_indices', '_ids')] = [source_ids[i] for i in row.pop(label)]
            print(json.dumps(row, ensure_ascii=False, allow_nan=False))
            emitted += 1
            if args.limit and emitted >= args.limit:
                break


if __name__ == '__main__':
    main()
