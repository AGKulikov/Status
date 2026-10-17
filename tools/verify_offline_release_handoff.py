#!/usr/bin/env python3
"""Verify reconstructed release identity, exact CI payload and manifest before public upload."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

from apk_signature_handoff import payload

CERT = '6e9855aedc008bbdd8a7fbf3f490be07f964b7ac658a837a1592647a08365c75'


def sha(path):
    value = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            value.update(block)
    return value.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--candidate', required=True, type=Path)
    parser.add_argument('--tools', required=True, type=Path)
    parser.add_argument('--release', required=True, type=Path)
    parser.add_argument('--source', required=True)
    parser.add_argument('--tree', required=True)
    parser.add_argument('--run', required=True)
    parser.add_argument('--signed-sha256', required=True)
    args = parser.parse_args()
    report = json.loads((args.release / 'release-report.json').read_text())
    candidate = json.loads((args.candidate / 'candidate.json').read_text())
    for key, value in [('sourceCommit', args.source), ('sourceTree', args.tree), ('ciRunId', args.run)]:
        if report.get(key) != value or candidate.get(key) != value:
            raise ValueError('Wrong release provenance: ' + key)
    if report['signedSha256'] != args.signed_sha256:
        raise ValueError('Unexpected signed APK hash in report')
    for relative, expected in report['toolSha256'].items():
        path = Path(relative)
        if path.is_absolute() or '..' in path.parts or sha(args.tools / path) != expected:
            raise ValueError('Signing tool digest mismatch')
    for name in ['apksigner', 'aapt', 'zipalign']:
        (args.tools / name).chmod(0o755)
    apk = args.release / report['signedApk']
    if apk.parent != args.release or sha(apk) != args.signed_sha256:
        raise ValueError('Wrong signed APK bytes')
    unsigned = args.candidate / ('Natro-' + report['versionName'] + '-unsigned.apk')
    if sha(unsigned) != report['unsignedSha256'] or sha(unsigned) != candidate['unsignedSha256']:
        raise ValueError('Wrong CI APK bytes')
    if payload(apk) != payload(unsigned):
        raise ValueError('APK payload changed')
    counts = dict(tests=0, failures=0, errors=0, skipped=0)
    for path in (args.candidate / 'test-results').glob('TEST-*.xml'):
        root = ET.parse(path).getroot()
        for key in counts:
            counts[key] += int(root.attrib.get(key, 0))
    if counts != candidate['unitTests'] or counts['tests'] < 1900 or any(counts[key] for key in ['failures', 'errors', 'skipped']):
        raise ValueError('Full CI test evidence mismatch')
    def run(name, *arguments):
        return subprocess.run([str(args.tools / name), *map(str, arguments)], check=True,
                              capture_output=True, text=True).stdout
    output = run('apksigner', 'verify', '--verbose', '--print-certs', '--min-sdk-version', '28', '--max-sdk-version', '28', apk)
    if re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)$', output, re.M) != [CERT]:
        raise ValueError('Stable certificate mismatch')
    if 'Verified using v3 scheme (APK Signature Scheme v3): true' not in output:
        raise ValueError('Android 9 v3 signature invalid')
    v2 = run('apksigner', 'verify', '--verbose', '--min-sdk-version', '24', apk)
    if 'Verified using v2 scheme (APK Signature Scheme v2): true' not in v2:
        raise ValueError('v2 signature invalid')
    run('zipalign', '-c', '-P', '16', '4', apk)
    badging = run('aapt', 'dump', 'badging', apk)
    match = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    if not match or list(match.groups()) != ['ru.natro.statuswidget', str(report['versionCode']), report['versionName']]:
        raise ValueError('APK identity mismatch')
    for marker in ["application-label:'Natro'", "sdkVersion:'28'", "targetSdkVersion:'28'"]:
        if marker not in badging:
            raise ValueError('APK platform or label mismatch')
    if 'application-debuggable' in badging:
        raise ValueError('Debug APK forbidden')
    manifest = json.loads((args.release / 'natro-update.json').read_text())
    expected = dict(schema=1, channel='stable', package='ru.natro.statuswidget',
                    versionCode=report['versionCode'], versionName=report['versionName'],
                    certificateSha256=CERT, sha256=args.signed_sha256, size=apk.stat().st_size,
                    url='https://github.com/AGKulikov/Status/releases/download/natro-v'
                        + report['versionName'] + '/' + apk.name)
    if manifest != expected:
        raise ValueError('Update manifest does not describe verified APK')
    print(json.dumps(dict(verified=True, signedSha256=args.signed_sha256, unitTests=counts)))


if __name__ == '__main__':
    main()
