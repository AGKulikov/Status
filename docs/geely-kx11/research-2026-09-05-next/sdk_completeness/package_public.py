#!/usr/bin/env python3
"""Build or verify an explicit publication allowlist without copying private source material."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parent
AUTHORED_TOOLS = (
    'scan_sources.py', 'extract_dex.py', 'decode_initializers.py', 'summarize.py',
    'compact_catalog.py', 'verify_raw_dex.py', 'test_typed_decoder.py',
    'validate_typed_initializers.py', 'prepare_public_metadata.py',
    'lookup.py', 'validate_catalog.py', 'package_public.py',
)
FACTS_AND_REPORTS = (
    'report.md', 'VALIDATION.json', 'TYPED_INITIALIZERS_VALIDATION.json',
    'catalog_manifest.json', 'catalog/sources.json', 'corpus_scope.json',
    'source_inventory.json.gz', 'extraction_summary.json.gz',
    'RAW_DEX_VALIDATION.json.gz', 'system_zero_revalidation.json.gz',
    'additional_family_evidence.json.gz', 'baseline_source_crosswalk.json',
    'collection_targets.json',
)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def permitted_path(name):
    path = PurePosixPath(name)
    return not path.is_absolute() and '..' not in path.parts and 'private' not in path.parts


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--verify', action='store_true', help='Read only: check the existing publication manifest')
    args = parser.parse_args()
    catalog = json.loads((ROOT / 'catalog_manifest.json').read_text())
    names = sorted(set(AUTHORED_TOOLS + FACTS_AND_REPORTS + tuple(f['path'] for f in catalog['files'])))
    if not all(permitted_path(name) for name in names):
        raise SystemExit('Unsafe publication path')
    manifest_path = ROOT / 'PUBLIC_FILES.json'
    if args.verify:
        manifest = json.loads(manifest_path.read_text())
        failures = []
        if [f['path'] for f in manifest['files']] != names:
            failures.append('Manifest does not match the explicit allowlist')
        for entry in manifest['files']:
            name = entry['path']
            if not permitted_path(name) or name not in names:
                failures.append('Unexpected path: ' + name)
                continue
            path = ROOT / name
            if not path.is_file() or path.stat().st_size != entry['size'] or sha(path) != entry['sha256']:
                failures.append('Missing file or integrity mismatch: ' + name)
        actual = sum((ROOT / name).stat().st_size for name in names) + manifest_path.stat().st_size
        if actual >= 10_000_000:
            failures.append('Public package exceeds the 10 MB target')
        print(json.dumps({'publication_manifest_verified': not failures,
                          'payload_files': len(names), 'files_including_manifest': len(names) + 1,
                          'total_bytes_including_manifest': actual, 'failures': failures}, indent=2))
        raise SystemExit(1 if failures else 0)
    files = [{'path': name, 'size': (ROOT / name).stat().st_size, 'sha256': sha(ROOT / name),
              'role': 'authored_tool' if name in AUTHORED_TOOLS else 'extracted_facts_or_authored_report'} for name in names]
    manifest = {'schema': 'sdk-public-file-allowlist-v1',
        'files': files, 'payload_file_count': len(files),
        'total_payload_bytes': sum(f['size'] for f in files),
        'manifest_path': 'PUBLIC_FILES.json',
        'copy_rule': 'Copy these relative files and this manifest preserving directories. No other files are authorized by this allowlist.',
        'self_hash': 'The manifest intentionally omits its own checksum; commit/tree identity anchors it.',
        'excluded': ['private/', 'original archives, APK/JAR/DEX bytes', 'foreign method bodies and disassembly',
                     'resolved arbitrary string initializer content',
                     'uncompressed intermediate inventories and historical page manifests'],
        'runtime_class_origin_verified': False, 'vehicle_commands_sent': False}
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'manifest': str(manifest_path), 'payload_files': len(files),
                      'payload_bytes': manifest['total_payload_bytes']}, indent=2))


if __name__ == '__main__':
    main()
