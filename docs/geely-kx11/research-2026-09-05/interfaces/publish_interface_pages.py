#!/usr/bin/env python3
"""Deterministically paginate the complete interface index into readable UTF-8 JSON.
Usage: python3 publish_interface_pages.py entry_index.json published/
This script operates only on local files and never contacts or controls a vehicle.
"""
import argparse
import hashlib
import json
from pathlib import Path

SCHEMA = 'geely-interface-entry-page-v1'


def encode_entry(entry):
    pretty = json.dumps(entry, ensure_ascii=False, indent=2)
    return '\n'.join('    ' + line for line in pretty.splitlines()).encode('utf-8')


def prefix(number):
    return ('{\n  "schema": "' + SCHEMA + '",\n  "page_number": '
            + str(number) + ',\n  "entries": [\n').encode('utf-8')


def page_data(number, chunks):
    return prefix(number) + b',\n'.join(chunks) + b'\n  ]\n}\n'


def generate(source, output, max_bytes=300000):
    source = Path(source)
    output = Path(output)
    raw = source.read_bytes()
    payload = json.loads(raw)
    entries = payload['entries']
    keys = [e['entry_key'] for e in entries]
    if len(set(keys)) != len(keys):
        raise ValueError('Duplicate entry_key values in source index')
    encoded = [encode_entry(e) for e in entries]
    pages = []
    group = []
    first_index = 0
    length = 0
    for index, chunk in enumerate(encoded):
        page_number = len(pages) + 1
        candidate_size = len(prefix(page_number)) + length + (2 if group else 0) + len(chunk) + len(b'\n  ]\n}\n')
        if group and candidate_size > max_bytes:
            pages.append((first_index, index, page_data(page_number, group)))
            first_index = index
            group = []
            length = 0
            page_number += 1
        if len(prefix(page_number)) + len(chunk) + len(b'\n  ]\n}\n') > max_bytes:
            raise ValueError('One complete entry exceeds page byte limit: ' + entries[index]['entry_key'])
        if group:
            length += 2
        group.append(chunk)
        length += len(chunk)
    if group:
        pages.append((first_index, len(entries), page_data(len(pages) + 1, group)))
    destination = output / 'entries'
    destination.mkdir(parents=True, exist_ok=True)
    manifest_pages = []
    for page_number, (begin, end, data) in enumerate(pages, 1):
        if len(data) > max_bytes:
            raise AssertionError('Generated page exceeded configured limit')
        filename = 'entries/interfaces-' + str(page_number).zfill(4) + '.json'
        (output / filename).write_bytes(data)
        manifest_pages.append({
            'filename': filename,
            'page_number': page_number,
            'entry_count': end - begin,
            'first_entry_key': entries[begin]['entry_key'],
            'last_entry_key': entries[end - 1]['entry_key'],
            'source_first_index': begin,
            'source_last_index': end - 1,
            'size_bytes': len(data),
            'sha256': hashlib.sha256(data).hexdigest(),
        })
    # Remove only stale generated pages from previous runs. Other files are untouched.
    current = {Path(page['filename']).name for page in manifest_pages}
    for old in destination.glob('interfaces-*.json'):
        if old.name not in current and old.stem.removeprefix('interfaces-').isdigit():
            old.unlink()
    manifest = {
        'schema': 'geely-paged-interface-index-v1',
        'scope': 'Lossless pagination of the supplied frozen interface entry index. No additional runtime support is inferred.',
        'source_schema': payload.get('schema'),
        'source_filename': source.name,
        'source_sha256': hashlib.sha256(raw).hexdigest(),
        'encoding': 'UTF-8',
        'max_page_bytes': max_bytes,
        'entry_count': len(entries),
        'page_count': len(manifest_pages),
        'entry_order': 'Identical to source entries array; stable entry_key values retained unchanged',
        'pages': manifest_pages,
    }
    (output / 'entries_manifest.json').write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--max-page-bytes', type=int, default=300000)
    args = parser.parse_args()
    if args.max_page_bytes < 1024:
        parser.error('--max-page-bytes must be at least 1024')
    result = generate(args.source, args.output, args.max_page_bytes)
    print(json.dumps({'entry_count': result['entry_count'], 'page_count': result['page_count'],
                      'largest_page_bytes': max((p['size_bytes'] for p in result['pages']), default=0)}, ensure_ascii=False))


if __name__ == '__main__':
    main()
