#!/usr/bin/env python3
"""Prepare compact factual metadata; never copy original archives or method bodies."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent
EXTRA_SIDECAR = 'KX11-HU-Route-20260810-070911-29994-1234567.zip.sha256'


def sha_file(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(8 * 1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def write_json(path, data):
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n')


def compressed_json(path, data):
    with path.open('wb') as stream:
        with gzip.GzipFile(filename='', fileobj=stream, mode='wb', mtime=0) as output:
            output.write((json.dumps(data, ensure_ascii=True, separators=(',', ':')) + '\n').encode())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--originals', type=Path, default=ROOT.parents[1] / 'originals')
    args = parser.parse_args()
    inventory = json.loads((ROOT / 'source_inventory.json').read_text())
    archives = {a['filename']: a for a in inventory['archives']}
    registered, auxiliary = [], []
    for path in sorted(args.originals.iterdir()):
        if not path.is_file() or path.name.startswith('.') or '.openai-download-' in path.name:
            continue
        record = {'filename': path.name, 'size': path.stat().st_size, 'sha256': sha_file(path)}
        if path.name == EXTRA_SIDECAR:
            auxiliary.append({**record, 'role': 'auxiliary_checksum_sidecar_not_a_registered_source'})
            continue
        record['sdk_pass'] = 'code_container_scanned' if path.name in archives else 'outside_code_container_pass'
        if path.name in archives:
            assert record['sha256'] == archives[path.name]['sha256'], path.name
            assert record['size'] == archives[path.name]['size'], path.name
        registered.append(record)
    assert len(registered) == 158 and len(archives) == 102
    write_json(ROOT / 'corpus_scope.json', {
        'schema': 'sdk-exact-input-scope-v1',
        'registered_sources': registered, 'auxiliary_files': auxiliary,
        'counts': {'registered_sources': len(registered), 'code_container_inputs': len(archives),
                   'registered_non_code_inputs': len(registered) - len(archives),
                   'auxiliary_checksum_sidecars': len(auxiliary)},
        'registered_source_hashes_rechecked_from_bytes': True,
        'scope': 'This SDK pass inventories 102 code/container inputs. The 56 text, JSON, script and checksum inputs are registered here, but their semantic analysis belongs to other research passes.',
        'not_claimed': ['complete vehicle firmware image', 'all ECU interfaces', 'runtime class origin',
                        'exhaustive semantic interpretation of all method bodies']})
    for archive in inventory['archives']:
        archive.pop('local_path', None)
    compressed_json(ROOT / 'source_inventory.json.gz', inventory)
    for name in ('extraction_summary', 'RAW_DEX_VALIDATION', 'system_zero_revalidation'):
        data = json.loads((ROOT / (name + '.json')).read_text())
        if name == 'extraction_summary':
            data['scope_note'] = ('All DEX declarations are counted in private inventories. Public compact '
                                  'catalog covers five explicit namespace prefixes, including vendor application '
                                  'and UI resource declarations. Every source occurrence is retained.')
            data['baseline_comparison']['interpretation'] = ('71,833 original extractor values reproduce the '
                'historical baseline representation. This is not validation of their Java numeric meaning; '
                'see independent typed correction validation and 84 affected baseline declaration indices.')
        compressed_json(ROOT / (name + '.json.gz'), data)
    evidence_path = ROOT / 'additional_family_evidence.json'
    evidence = json.loads(evidence_path.read_text())
    private_path = ROOT / 'private/additional_family_evidence_full.json'
    if not private_path.exists():
        private_path.write_bytes(evidence_path.read_bytes())
    for method in evidence['methods']:
        method.pop('instruction_offsets_bytes', None)
    evidence['public_content'] = 'Original analytical claims, method identities, bytecode digests and direct-call references only; no method bodies.'
    write_json(evidence_path, evidence)
    compressed_json(ROOT / 'additional_family_evidence.json.gz', evidence)


if __name__ == '__main__':
    main()
