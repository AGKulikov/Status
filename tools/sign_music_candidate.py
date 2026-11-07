#!/usr/bin/env python3
"""Add the CI-built Natro bootstrap to the pinned private Music baseline."""
import argparse
import json
from pathlib import Path
import zipfile
from patch_music_manifest import patch
from sign_natro_candidate import CERT, payload_hashes, run, sha256, signature_entry
from verify_kx11_navigation_pair import verify_signer, verify_zipalign

BASELINE = '7018cb5ba63248a3b33973d5a32d380b7dfaa287c9a416d072754f2670f47f81'

def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ('baseline', 'dex', 'sdk', 'keystore', 'password-file', 'output'):
        p.add_argument('--' + name, type=Path, required=True)
    for name in ('dex-sha256', 'source-commit', 'ci-run'):
        p.add_argument('--' + name, required=True)
    a = p.parse_args()
    if sha256(a.baseline) != BASELINE or sha256(a.dex) != a.dex_sha256:
        raise ValueError('Baseline or CI DEX checksum mismatch')
    if not a.dex.read_bytes().startswith(b'dex\n'):
        raise ValueError('Invalid DEX header')
    before = payload_hashes(a.baseline)
    if 'classes7.dex' in before:
        raise ValueError('Bootstrap lane already occupied')
    a.output.mkdir(parents=True, exist_ok=True)
    unsigned = a.output / 'music-unsigned.apk'
    aligned = a.output / 'music-aligned.apk'
    signed = a.output / 'Yandex-Music-2026.10.2-Natro-1-signed.apk'
    if any(x.exists() for x in (unsigned, aligned, signed)):
        raise FileExistsError('Output already exists')
    with zipfile.ZipFile(a.baseline) as src, zipfile.ZipFile(unsigned, 'w') as dst:
        for entry in src.infolist():
            if signature_entry(entry.filename):
                continue
            data = src.read(entry)
            if entry.filename == 'AndroidManifest.xml':
                data = patch(data)
            dst.writestr(entry, data)
        dst.writestr('classes7.dex', a.dex.read_bytes(), compress_type=zipfile.ZIP_DEFLATED)
    expected = payload_hashes(unsigned)
    if set(expected) - set(before) != {'classes7.dex'} or set(before) - set(expected):
        raise ValueError('Unexpected payload inventory')
    if [n for n in before if before[n] != expected[n]] != ['AndroidManifest.xml']:
        raise ValueError('Native payload changed')
    signer, align, aapt = [a.sdk / n for n in ('apksigner', 'zipalign', 'aapt')]
    run(align, '-f', '-P', '16', '4', unsigned, aligned)
    run(signer, 'sign', '--ks', a.keystore, '--ks-key-alias', 'status-widget-ha',
        '--ks-pass', 'file:' + str(a.password_file), '--v1-signing-enabled', 'true',
        '--v2-signing-enabled', 'true', '--v3-signing-enabled', 'true', '--out', signed, aligned)
    verification = verify_signer(signer, signed, ('v2', 'v3'))
    verify_zipalign(align, signed)
    run(signer, 'verify', '--min-sdk-version', '28', '--max-sdk-version', '28', signed)
    if payload_hashes(signed) != expected:
        raise ValueError('Signing changed payload')
    identity = run(aapt, 'dump', 'badging', signed).stdout.splitlines()[0]
    if "name='ru.yandex.music' versionCode='24026522' versionName='2026.10.2 #169gpr Natro 1'" not in identity:
        raise ValueError('Unexpected identity: ' + identity)
    report = dict(apk=signed.name, sha256=sha256(signed), size=signed.stat().st_size,
                  sourceCommit=a.source_commit, ciRun=a.ci_run, baselineSha256=BASELINE,
                  dexSha256=a.dex_sha256, certificateSha256=CERT, signatureV2=True,
                  signatureV3=True, exactApi28=True, zipalign16KiB=True,
                  unchangedNativeEntries=len(before)-1, changedEntries=['AndroidManifest.xml'],
                  addedEntries=['classes7.dex'], installOverOriginal=False,
                  physicalKx11Verification='pending')
    (a.output/'music-release-report.json').write_text(json.dumps(report, indent=2)+'\n')
    (a.output/'music-signature-verification.txt').write_text(verification)
    unsigned.unlink(); aligned.unlink(); Path(str(signed)+'.idsig').unlink(missing_ok=True)
    print(json.dumps(report, indent=2))

if __name__ == '__main__':
    main()
