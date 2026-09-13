#!/usr/bin/env python3
"""Build a deterministic standalone Mac map-diagnostics bundle from public source."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile

DIRECTORY = 'Natro-Map-Check-macOS-v1.0.0'


def sources():
    here = Path(__file__).resolve().parent
    files = {name: here / name for name in ('Collect-Maps.command', 'collect_maps.py', 'README_RU.md')}
    for name in ('collect_trip_screens.py', 'audit_saved_pcaps.py'):
        files[name] = here.parent / 'geely-trip2' / name
    return files


def build(destination):
    payloads = {name: path.read_bytes() for name, path in sources().items()}
    manifest = {'schema': 'natro-map-check-bundle-v1', 'version': '1.0.0',
                'files': [{'path': name, 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()}
                          for name, data in sorted(payloads.items())]}
    payloads['MANIFEST.json'] = (json.dumps(manifest, indent=2) + '\n').encode()
    with zipfile.ZipFile(destination, 'x', compression=zipfile.ZIP_DEFLATED) as archive:
        for name, data in sorted(payloads.items()):
            info = zipfile.ZipInfo(DIRECTORY + '/' + name, date_time=(2026, 9, 13, 0, 0, 0))
            info.create_system = 3
            info.external_attr = (0o100755 if name.endswith('.command') else 0o100644) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, data)
    return manifest


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    build(args.output)
    print(args.output.resolve())
