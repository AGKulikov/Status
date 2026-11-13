#!/usr/bin/env python3
"""Package the tested Music 4 bridge and passive hooks with the stable Natro signer."""
import argparse
import json
import subprocess
import sys
from pathlib import Path
from prepare_music_wave import BASE
from sign_natro_candidate import CERT, payload_hashes, run, sha256
from verify_kx11_navigation_pair import verify_signer, verify_zipalign


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ('previous-apk', 'dex', 'tool-dir', 'sdk', 'keystore', 'password-file', 'output'):
        p.add_argument('--' + name, type=Path, required=True)
    for name in ('dex-sha256', 'source-commit', 'source-tree', 'ci-run'):
        p.add_argument('--' + name, required=True)
    a = p.parse_args()
    if sha256(a.previous_apk) != BASE or sha256(a.dex) != a.dex_sha256:
        raise ValueError('Pinned Music 3 or exact CI bridge checksum mismatch')
    if not a.dex.read_bytes().startswith(b'dex\n'):
        raise ValueError('Invalid bridge DEX')
    signer, align, aapt = [a.sdk / n for n in ('apksigner', 'zipalign', 'aapt')]
    previous_verification = verify_signer(signer, a.previous_apk, ('v2', 'v3'))
    old_identity = run(aapt, 'dump', 'badging', a.previous_apk).stdout
    if "name='ru.yandex.music' versionCode='24026524' versionName='2026.10.2 #169gpr Natro 3'" not in old_identity:
        raise ValueError('Unexpected previous Music identity')
    a.output.mkdir(parents=True, exist_ok=False)
    payload = a.output / 'payload'
    subprocess.run([sys.executable, str(Path(__file__).with_name('prepare_music_wave.py')),
                    '--previous', str(a.previous_apk), '--bridge', str(a.dex),
                    '--tool-dir', str(a.tool_dir), '--out', str(payload)], check=True)
    unsigned = payload / 'Music-Natro4-UNSIGNED.apk'
    expected = payload_hashes(unsigned)
    previous = payload_hashes(a.previous_apk)
    changed = sorted(n for n in previous if previous[n] != expected.get(n))
    if set(expected) != set(previous) or changed != ['AndroidManifest.xml', 'classes.dex', 'classes7.dex']:
        raise ValueError('Unexpected native payload change')
    old_manifest = run(aapt, 'dump', 'xmltree', a.previous_apk, 'AndroidManifest.xml').stdout
    new_manifest = run(aapt, 'dump', 'xmltree', unsigned, 'AndroidManifest.xml').stdout
    if new_manifest != old_manifest.replace(hex(24026524), hex(24026525)).replace(
            '2026.10.2 #169gpr Natro 3', '2026.10.2 #169gpr Natro 4'):
        raise ValueError('Manifest changes exceed the version update')
    aligned = payload / 'aligned.apk'
    signed = a.output / 'Yandex-Music-2026.10.2-Natro-4-signed.apk'
    run(align, '-f', '-P', '16', '4', unsigned, aligned)
    run(signer, 'sign', '--ks', a.keystore, '--ks-key-alias', 'status-widget-ha',
        '--ks-pass', 'file:' + str(a.password_file), '--v1-signing-enabled', 'true',
        '--v2-signing-enabled', 'true', '--v3-signing-enabled', 'true', '--out', signed, aligned)
    verification = verify_signer(signer, signed, ('v2', 'v3'))
    verify_zipalign(align, signed)
    run(signer, 'verify', '--min-sdk-version', '28', '--max-sdk-version', '28', signed)
    if payload_hashes(signed) != expected:
        raise ValueError('Signing altered the tested payload')
    new_identity = run(aapt, 'dump', 'badging', signed).stdout
    if new_identity != old_identity.replace("versionCode='24026524'", "versionCode='24026525'").replace(
            '2026.10.2 #169gpr Natro 3', '2026.10.2 #169gpr Natro 4'):
        raise ValueError('Identity, permissions, label, SDK or ABI changed unexpectedly')
    report = dict(apk=signed.name, sha256=sha256(signed), size=signed.stat().st_size,
                  sourceCommit=a.source_commit, sourceTree=a.source_tree, ciRun=a.ci_run,
                  previousSha256=BASE, previousVersionCode=24026524, versionCode=24026525,
                  dexSha256=a.dex_sha256, nativeDexSha256=expected['classes.dex'],
                  certificateSha256=CERT, signatureV2=True, signatureV3=True, exactApi28=True,
                  zipalign16KiB=True, changedFromPrevious=changed,
                  unchangedPayloadEntries=len(previous)-3, nativeBodyRestorationVerified=True,
                  manifestSemanticsOnlyVersionChanged=True, installOverNatro3MetadataVerified=True,
                  physicalKx11Verification='pending')
    (a.output / 'music-release-report.json').write_text(json.dumps(report, indent=2) + '\n')
    (a.output / 'music-signature-verification.txt').write_text(
        'Music Natro 4:\n' + verification + '\nMusic Natro 3:\n' + previous_verification)
    Path(str(signed) + '.idsig').unlink(missing_ok=True)
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
