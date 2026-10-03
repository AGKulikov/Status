#!/usr/bin/env python3
"""Source-change tripwire for backup design; not proof of runtime backup completeness.

No refresh option: changed owners need an explicit review of data/restore policy.
Runtime discovery, strict adapters and crash-safe restore remain separate gates.
"""
import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONTRACT = ROOT / 'docs/backup/storage-inventory.json'
PATTERNS = {
    'preferences': r'\b(?:getSharedPreferences|getDefaultSharedPreferences)\s*\(|RuntimeSnapshotPreferences\s*\.\s*open\s*\(',
    'files': r'\b(?:getFilesDir|getNoBackupFilesDir|getExternalFilesDir|getExternalFilesDirs|getExternalStorageDirectory|openFileOutput|openFileInput)\s*\(|\bnew\s+(?:File|FileOutputStream|FileWriter|RandomAccessFile|AtomicFile)\s*\(|\bFiles\s*\.\s*(?:write|move|copy|newOutputStream|createFile)\s*\(',
    'database': r'\b(?:SQLiteOpenHelper|RoomDatabase|DataStore|openOrCreateDatabase|getDatabasePath)\b',
    'external_resource': r'\b(?:takePersistableUriPermission|openOutputStream|openFileDescriptor)\s*\(',
    'keystore': r'\b(?:AndroidKeyStore|SecretStore)\b',
}

def production_sources(root):
    for path in sorted(root.rglob('*')):
        relative = path.relative_to(root)
        if path.suffix not in {'.java', '.kt'} or 'src' not in relative.parts:
            continue
        if any(part in {'build', '.git', 'test', 'androidTest', 'testFixtures'} for part in relative.parts):
            continue
        yield path


def discover(root):
    result = {}
    for path in production_sources(root):
        source = path.read_text(encoding='utf-8')
        kinds = sorted(kind for kind, pattern in PATTERNS.items() if re.search(pattern, source))
        if kinds:
            result[path.relative_to(root).as_posix()] = {
                'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
                'access_kinds': kinds,
            }
    return result


def compare(expected, actual):
    failures = []
    for path in sorted(set(expected) | set(actual)):
        if path not in expected:
            failures.append('UNREVIEWED storage owner: ' + path)
        elif path not in actual:
            failures.append('REMOVED storage owner (migration review required): ' + path)
        elif expected[path] != actual[path]:
            failures.append('CHANGED storage owner (review data/defaults/adapters): ' + path)
    return failures


def main():
    contract = json.loads(CONTRACT.read_text(encoding='utf-8'))
    failures = compare(contract['source_owners'], discover(ROOT))
    if failures:
        print('\n'.join(failures))
        return 1
    print('Reviewed source inventory unchanged: %d owners. Runtime archive completeness NOT certified.'
          % len(contract['source_owners']))
    return 0

if __name__ == '__main__':
    raise SystemExit(main())
