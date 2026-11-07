#!/usr/bin/env python3
"""Verify an already signed Alice pair against its exact CI candidate; no signing key."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile

from apk_signature_handoff import create, payload, reconstruct
from sign_natro_candidate import CERT, payload_hashes, sha256
from verify_kx11_navigation_pair import verify_maneuver_artwork, native_abis


def require(condition, message):
    if not condition:
        raise ValueError(message)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ('candidate', 'tools', 'natro', 'previous', 'navigator', 'baseline', 'output'):
        p.add_argument('--' + name, type=Path, required=True)
    p.add_argument('--source', required=True)
    p.add_argument('--tree', required=True)
    p.add_argument('--run', required=True)
    a = p.parse_args()
    manifest = json.loads((a.candidate / 'candidate.json').read_text())
    for k, v in [('sourceCommit', a.source), ('sourceTree', a.tree), ('ciRunId', a.run)]:
        require(manifest[k] == v, 'Wrong candidate provenance: ' + k)
    version = manifest['versionName']
    unsigned = a.candidate / ('Natro-' + version + '-unsigned.apk')
    require(sha256(unsigned) == manifest['unsignedSha256'], 'Wrong unsigned candidate')
    require(payload(a.natro) == payload(unsigned), 'Natro differs from tested CI payload')
    for name in ('aapt', 'apksigner', 'zipalign'):
        (a.tools / name).chmod(0o755)

    def run(tool, *args):
        return subprocess.run([str(a.tools / tool), *map(str, args)], check=True,
                              capture_output=True, text=True).stdout

    def verify(apk):
        exact = run('apksigner', 'verify', '--verbose', '--print-certs',
                    '--min-sdk-version', 28, '--max-sdk-version', 28, apk)
        require(re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)$', exact, re.M)
                == [CERT], 'Wrong stable signer: ' + apk.name)
        require('Verified using v3 scheme (APK Signature Scheme v3): true' in exact,
                'No API 28 v3 signature: ' + apk.name)
        v2 = run('apksigner', 'verify', '--verbose', '--min-sdk-version', 24, apk)
        require('Verified using v2 scheme (APK Signature Scheme v2): true' in v2,
                'No v2 signature: ' + apk.name)
        run('zipalign', '-c', '-P', 16, 4, apk)
        return exact + '\nV2 check:\n' + v2

    def identity(apk):
        text = run('aapt', 'dump', 'badging', apk)
        match = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", text)
        require(match is not None, 'Unreadable package identity')
        return dict(package=match[1], versionCode=int(match[2]), versionName=match[3]), text

    a.output.mkdir(parents=True, exist_ok=True)
    natro_sign = verify(a.natro)
    previous_sign = verify(a.previous)
    navigator_sign = verify(a.navigator)
    verify(a.baseline)
    current, badging = identity(a.natro)
    previous, _ = identity(a.previous)
    require(current == {k: manifest[k] for k in ('package', 'versionCode', 'versionName')},
            'Natro identity differs from candidate')
    require(previous['package'] == current['package'] and current['versionCode'] == previous['versionCode'] + 1,
            'Natro must advance versionCode by one')
    for marker in ("application-label:'Natro'", "sdkVersion:'28'", "targetSdkVersion:'28'"):
        require(marker in badging, 'Missing platform identity: ' + marker)
    require('application-debuggable' not in badging, 'Debuggable release')
    nav = manifest['navigator']
    allowed = ['classes11.dex', 'classes15.dex', 'classes19.dex']
    require(nav['pairRequired'] and nav['nativeEngineHooks'] and nav['allowedPayloadChanges'] == allowed,
            'Wrong native hook scope')
    require(sha256(a.baseline) == nav['baselineSha256'], 'Wrong private baseline')
    nav_identity, nav_badging = identity(a.navigator)
    require(nav_badging == identity(a.baseline)[1], 'Navigator manifest or resources changed')
    require(nav_identity == dict(package='ru.yandex.yandexnavi', versionCode=739564630, versionName='30.3.0'),
            'Unexpected Navigator identity')
    require(native_abis(a.navigator) == native_abis(a.baseline) == {'arm64-v8a'}, 'Navigator ABI drift')
    require('android:sharedUserId' not in run('aapt', 'dump', 'xmltree', a.navigator, 'AndroidManifest.xml'),
            'Navigator sharedUserId forbidden')
    verify_maneuver_artwork(a.navigator)
    before, after = payload_hashes(a.baseline), payload_hashes(a.navigator)
    require(set(before) == set(after), 'Navigator entry inventory changed')
    require(sorted(k for k in before if before[k] != after[k]) == allowed, 'Unexpected Navigator changes')
    require(sha256(a.candidate / 'classes19.dex') == nav['patchSha256'] == after['classes19.dex'],
            'Navigator bridge is not exact CI DEX')
    tool = a.candidate / 'alice-focus-tool'
    require(set(nav['nativeToolSha256']) == {f.name for f in tool.iterdir() if f.is_file()},
            'Native tool inventory mismatch')
    for name, digest in nav['nativeToolSha256'].items():
        require(Path(name).name == name and sha256(tool / name) == digest, 'Native tool checksum mismatch')
    cp = os.pathsep.join([str(tool)] + [str(f) for f in sorted(tool.glob('*.jar'))])
    with tempfile.TemporaryDirectory() as temp, zipfile.ZipFile(a.baseline) as baseline:
        for name, lane in [('classes11.dex', 'alicekit'), ('classes15.dex', 'quark')]:
            original, patched = Path(temp) / name, Path(temp) / ('patched-' + name)
            original.write_bytes(baseline.read(name))
            subprocess.run(['java', '-cp', cp, 'PatchAliceFocus', str(original), str(patched), lane], check=True)
            require(sha256(patched) == after[name], 'Native DEX differs from deterministic CI tool: ' + name)
    counts = dict(tests=0, failures=0, errors=0, skipped=0)
    alice = counts.copy()
    for path in (a.candidate / 'test-results').glob('TEST-*.xml'):
        root = ET.parse(path).getroot()
        for key in counts:
            counts[key] += int(root.attrib.get(key, 0))
            if 'Alice' in path.name:
                alice[key] += int(root.attrib.get(key, 0))
    require(counts == manifest['unitTests'] and counts['tests'] >= 2269
            and not any(counts[k] for k in ('failures', 'errors', 'skipped')), 'Incomplete test evidence')
    patch = create(unsigned, a.natro)
    require(reconstruct(unsigned.read_bytes(), patch) == a.natro.read_bytes(), 'Signature handoff mismatch')
    (a.output / 'signing-handoff.json').write_text(json.dumps(patch, separators=(',', ':')) + '\n')
    report = {**manifest, 'signedApk': a.natro.name, 'signedSha256': sha256(a.natro),
              'signedSize': a.natro.stat().st_size, 'certificateSha256': CERT,
              'signatureV2': True, 'signatureV3': True, 'exactApi28Verified': True,
              'signerCount': 1, 'zipalign16KiB': True, 'payloadEntriesUnchanged': len(payload(unsigned)),
              'previous': {**previous, 'sha256': sha256(a.previous)}, 'installOverMetadataVerified': True,
              'physicalKx11Verification': 'pending', 'navigatorPairRequired': True,
              'aliceTests': alice, 'verificationScriptSha256': sha256(Path(__file__)),
              'toolSha256': {str(f.relative_to(a.tools)): sha256(f)
                             for f in sorted(a.tools.rglob('*')) if f.is_file()}}
    nav_report = dict(sourceCommit=a.source, sourceTree=a.tree, ciRunId=a.run, **nav_identity,
                      signedApk=a.navigator.name, signedSha256=sha256(a.navigator),
                      sizeBytes=a.navigator.stat().st_size, baselineSha256=sha256(a.baseline),
                      certificateSha256=CERT, signatureV2=True, signatureV3=True,
                      exactApi28Verified=True, signerCount=1, zipalign16KiB=True,
                      badgingUnchanged=True, unchangedPayloadEntries=len(after)-len(allowed),
                      changedEntries={k: dict(before=before[k], after=after[k]) for k in allowed},
                      nativePatcherReproduced=True, exactCiBridge=True,
                      installOverMetadataVerified=True, physicalKx11Verification='pending')
    update = dict(schema=1, channel='stable', package=current['package'], versionCode=current['versionCode'],
                  versionName=version, certificateSha256=CERT, sha256=report['signedSha256'],
                  size=a.natro.stat().st_size,
                  url='https://github.com/AGKulikov/Status/releases/download/natro-v'+version+'/'+a.natro.name)
    for name, value in [('release-report.json', report), ('navigator-release-report.json', nav_report),
                        ('natro-update.json', update)]:
        (a.output/name).write_text(json.dumps(value, indent=2)+'\n')
    (a.output/'signature-verification.txt').write_text('Natro 3.4.1:\n'+natro_sign+'\nPrevious:\n'+previous_sign)
    (a.output/'navigator-signature-verification.txt').write_text(navigator_sign)
    print(json.dumps(dict(verified=True, natroSha256=report['signedSha256'], navigatorSha256=nav_report['signedSha256'],
                          unitTests=counts, aliceTests=alice, unchangedNavigatorPayloads=nav_report['unchangedPayloadEntries'])))


if __name__ == '__main__':
    main()
