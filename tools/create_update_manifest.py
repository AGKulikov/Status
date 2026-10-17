#!/usr/bin/env python3
"""Create a stable-channel manifest from an actually verified release APK, never from source version labels."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess

CERT = '6e9855aedc008bbdd8a7fbf3f490be07f964b7ac658a837a1592647a08365c75'
PACKAGE = 'ru.natro.statuswidget'

def manifest(apk, tag, signer_output, badging):
    if not re.fullmatch(r'natro-v[0-9]+\.[0-9]+\.[0-9]+', tag):
        raise ValueError('Use an explicit stable Natro release tag')
    certificates = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)$', signer_output, re.M)
    if [value.lower() for value in certificates] != [CERT]:
        raise ValueError('Stable signing certificate does not match')
    match = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.M)
    if not match or match[1] != PACKAGE or tag != 'natro-v' + match[3]:
        raise ValueError('Package/version/tag mismatch')
    if not re.fullmatch(r'[A-Za-z0-9._-]+\.apk', apk.name):
        raise ValueError('APK filename must be URL-safe')
    size = apk.stat().st_size
    if size <= 0 or size > 128 * 1024 * 1024:
        raise ValueError('APK size out of updater bounds')
    # The Ubuntu 22.04 release runner uses Python 3.10 (no file_digest).
    digest = hashlib.sha256()
    with apk.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(chunk)
    return dict(schema=1, channel='stable', package=PACKAGE, versionCode=int(match[2]),
                versionName=match[3], certificateSha256=CERT, sha256=digest.hexdigest(), size=size,
                url=f'https://github.com/AGKulikov/Status/releases/download/{tag}/{apk.name}')

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--tag', required=True)
    parser.add_argument('--apksigner', default='apksigner')
    parser.add_argument('--aapt', default='aapt')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    signer = subprocess.run([args.apksigner, 'verify', '--verbose', '--print-certs', str(args.apk)], check=True, capture_output=True, text=True)
    badging = subprocess.run([args.aapt, 'dump', 'badging', str(args.apk)], check=True, capture_output=True, text=True)
    value = manifest(args.apk, args.tag, signer.stdout, badging.stdout)
    # Never silently overwrite a published manifest. Publish this with the same verified APK.
    with args.output.open('x', encoding='utf-8') as target:
        json.dump(value, target, ensure_ascii=False, indent=2)
        target.write('\n')

if __name__ == '__main__':
    main()
