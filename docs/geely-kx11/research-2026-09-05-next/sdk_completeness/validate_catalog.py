#!/usr/bin/env python3
"""Verify published catalog hashes, counts, occurrence identity and optional private coverage."""
import argparse
import collections
import gzip
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read(path):
    if str(path).endswith('.gz'):
        with gzip.open(path, 'rt') as stream:
            return json.load(stream)
    return json.loads(path.read_text())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--private-coverage', action='store_true', help='Compare every public occurrence with private lossless pages')
    parser.add_argument('--write', action='store_true', help='Write VALIDATION.json')
    args = parser.parse_args()
    manifest = read(ROOT / 'catalog_manifest.json')
    source_ids = read(ROOT / manifest['source_table'])['source_ids']
    source_index = {s: i for i, s in enumerate(source_ids)}
    inventory = read(ROOT / 'source_inventory.json.gz')
    summary = read(ROOT / 'extraction_summary.json.gz')
    raw = read(ROOT / 'RAW_DEX_VALIDATION.json.gz')
    typed = read(ROOT / 'TYPED_INITIALIZERS_VALIDATION.json')
    checks = {}
    checks['source_table_unique_and_complete'] = (len(source_ids) == len(set(source_ids)) == 621 and
        set(source_ids) == {s['source_id'] for s in inventory['sources'] if s['kind'] == 'dex'})
    checks['archive_scan_has_no_errors'] = not inventory['errors']
    checks['all_dex_declaration_scans_have_no_errors'] = not summary['extraction_errors']
    checks['independent_typed_values_pass'] = typed['all_checks_pass']
    checks['typed_validation_hashes_match_published_files'] = all(
        sha(ROOT / path) == typed['provenance'][key] for key, path in (
            ('validator_sha256', 'validate_typed_initializers.py'),
            ('decoder_under_validation_sha256', 'decode_initializers.py'),
            ('catalog_sources_sha256', 'catalog/sources.json'),
            ('catalog_fields_sha256', 'catalog/fields.jsonl.gz'),
            ('catalog_corrections_sha256', 'catalog/typed_initializer_corrections.jsonl.gz')))
    findings = {}
    all_occurrences = {}
    for entry in manifest['files']:
        path = ROOT / entry['path']
        kind = path.name.split('.')[0]
        checks[kind + '_file_integrity'] = path.stat().st_size == entry['size'] and sha(path) == entry['sha256']
        rows, variants, occurrences = 0, 0, 0
        categories = collections.Counter()
        keys, seen = set(), set()
        well_formed = True
        with gzip.open(path, 'rt') as stream:
            for line in stream:
                row = json.loads(line)
                rows += 1
                if kind not in ('classes', 'fields', 'methods'):
                    continue
                key = tuple(row['key'])
                if key in keys:
                    well_formed = False
                keys.add(key)
                for variant in row['variants']:
                    variants += 1
                    if not variant['p']:
                        well_formed = False
                    for occurrence in variant['p']:
                        identity = (occurrence[0], occurrence[1], key)
                        if identity in seen or not (0 <= occurrence[0] < len(source_ids)):
                            well_formed = False
                        seen.add(identity)
                        occurrences += 1
                        categories[row['category']] += 1
                    if kind == 'fields':
                        init = variant['v'].get('typed_encoded_initializer', {})
                        if init.get('value_kind') == 'string':
                            for label in ('encoded_value', 'extractor_encoded_value_raw', 'vm_initial_value_before_clinit'):
                                if not isinstance(variant['v'][label], dict) or variant['v'][label].get('content_withheld') != 'resolved_string_initializer':
                                    well_formed = False
        checks[kind + '_record_count'] = rows == entry['records']
        findings[kind] = {'rows': rows}
        if kind in ('classes', 'fields', 'methods'):
            expected = manifest['counts'][kind]
            checks[kind + '_occurrence_balance'] = (
                rows == expected['unique_declaration_keys'] and
                variants == expected['variants'] and
                occurrences == expected['source_records'] == summary['sdk_namespace_declaration_counts'][kind] and
                dict(categories) == expected['source_records_by_category'])
            checks[kind + '_identities_and_string_withholding'] = well_formed
            findings[kind].update(variants=variants, source_occurrences=occurrences,
                                  source_records_by_category=dict(categories))
            all_occurrences[kind] = seen
    if args.private_coverage:
        private = read(ROOT / 'private/declaration_pages_manifest.json')
        for kind, actual in all_occurrences.items():
            expected = set()
            hashes_valid = True
            count = 0
            for page in private[kind]['pages']:
                path = ROOT / page['path']
                hashes_valid = hashes_valid and sha(path) == page['sha256']
                for row in read(path)['records']:
                    key = (row['class'],) if kind == 'classes' else (
                        row['class'], row['name'] if kind == 'fields' else row['method'], row['descriptor'])
                    index = row['class_idx'] if kind == 'classes' else row['field_idx'] if kind == 'fields' else row['method_idx']
                    expected.add((source_index[row['source_id']], index, key))
                    count += 1
            checks[kind + '_private_source_coverage'] = actual == expected and len(expected) == count
            checks[kind + '_private_page_hashes'] = hashes_valid
    checks['raw_dex_counts_and_sha256_adler32_pass'] = all(
        failures == 0 for name, failures in raw['failure_counts'].items() if name != 'dex_sha1_signature_valid')
    checks['raw_dex_sha1_anomalies_exactly_accounted_for'] = (
        raw['dexes'] == 621 and raw['failure_counts']['dex_sha1_signature_valid'] == 75 and
        sum(not source['dex_sha1_signature_valid'] for source in raw['sources']) == 75)
    checks['system_zero_revalidation'] = summary['system_zero_revalidation'] == {
        'candidates': 305, 'same_dex_written': 2, 'same_container_other_dex_written': 0, 'same_dex_unwritten': 303}
    checks['frozen_baseline_source_crosswalk_has_no_errors'] = not read(ROOT / 'baseline_source_crosswalk.json')['errors']
    scope = read(ROOT / 'corpus_scope.json')
    checks['registered_corpus_scope'] = scope['counts'] == {
        'registered_sources': 158, 'code_container_inputs': 102, 'registered_non_code_inputs': 56,
        'auxiliary_checksum_sidecars': 1}
    passed = all(checks.values())
    result = {'schema': 'typed-compact-sdk-validation-v3',
        'status': 'PASS_WITH_RECORDED_ORIGINAL_DEX_SHA1_ANOMALIES' if passed else 'FAIL',
        'catalog_checks_pass': passed, 'checks': checks, 'public_catalog_counts': findings,
        'private_coverage_checked_this_run': args.private_coverage,
        'raw_dex_sha1_header_signatures_all_valid': False,
        'original_dex_sha1_mismatch_count': 75,
        'raw_dex_anomaly_note': '75 source DEX files already have an invalid embedded SHA-1 signature. All 621 exact source SHA-256 identities, Adler-32 checksums and declaration counts match. No source bytes were repaired; the cause of the stored SHA-1 anomalies is not established.',
        'runtime_class_origin_verified': False, 'effective_runtime_values_computed': False,
        'all_vehicle_systems_understood': False, 'firmware_executed': False,
        'apk_built': False, 'vehicle_commands_sent': False}
    if args.write:
        (ROOT / 'VALIDATION.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(result, ensure_ascii=False, indent=2))
    raise SystemExit(0 if passed else 1)


if __name__ == '__main__':
    main()
