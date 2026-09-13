#!/usr/bin/env python3
"""Build a deterministic, stdlib-only Mac trip-screen collector bundle."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile

FILES = ('Collect-Trip-Screens.command', 'collect_trip_screens.py',
         'audit_saved_pcaps.py', 'SCREEN_CHECK_RU.md')
DIRECTORY = 'Natro-Trip-Check-macOS-v1.0.0'


def build(destination, source=None):
    source = Path(source or Path(__file__).resolve().parent)
    payloads = {name: (source / name).read_bytes() for name in FILES}
    manifest = {'schema': 'natro-trip-screen-bundle-v1', 'version': '1.0.0',
                'sources': 'AGKulikov/Status tools/geely-trip2',
                'files': [{'path': name, 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()}
                          for name, data in sorted(payloads.items())]}
    payloads['MANIFEST.json'] = (json.dumps(manifest, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
    with zipfile.ZipFile(destination, 'x', compression=zipfile.ZIP_DEFLATED) as archive:
        for name, data in sorted(payloads.items()):
            item = zipfile.ZipInfo(DIRECTORY + '/' + name, date_time=(2026, 9, 13, 0, 0, 0))
            item.create_system = 3
            item.external_attr = (0o100755 if name.endswith('.command') else 0o100644) << 16
            item.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(item, data)
    return manifest


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    build(args.output)
    print(args.output.resolve())
