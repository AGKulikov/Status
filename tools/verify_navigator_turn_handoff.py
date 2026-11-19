#!/usr/bin/env python3
"""Check the reconstructed Navigator against its exact CI candidate and signed update baseline."""
import argparse
import json
from pathlib import Path

from create_update_manifest import manifest as update_manifest
from navigation_turn_candidate import (APK, BASE_SHA, CHANGES, NEW_CODE, NEW_NAME, OLD_CODE,
                                      changed_hashes, check_manifest)
from sign_natro_candidate import CERT, payload_hashes, run, sha256
from verify_kx11_navigation_pair import verify_maneuver_artwork, verify_signer, verify_zipalign


def verify(candidate_dir, sdk, release, baseline, source, tree, run_id):
    candidate = json.loads((candidate_dir / 'candidate.json').read_text())
    report = json.loads((release / 'navigator-release-report.json').read_text())
    nav = candidate['navigator']
    for field, expected in [('sourceCommit', source), ('sourceTree', tree), ('ciRunId', run_id)]:
        if candidate.get(field) != expected or report.get(field) != expected:
            raise ValueError('Wrong Navigator release provenance: ' + field)
    if (nav.get('pairRequired') is not True or nav.get('baselineSha256') != BASE_SHA
            or nav.get('allowedPayloadChanges') != CHANGES or nav.get('versionCode') != NEW_CODE
            or nav.get('versionName') != NEW_NAME or nav.get('unsignedFile') != APK + '-unsigned.apk'):
        raise ValueError('Invalid Navigator CI metadata')
    unsigned = candidate_dir / (APK + '-unsigned.apk')
    signed = release / (APK + '-signed.apk')
    if sha256(unsigned) != nav['unsignedSha256'] or sha256(baseline) != BASE_SHA:
        raise ValueError('Navigator source APK hashes differ from their pinned values')
    payload = payload_hashes(signed)
    if payload != payload_hashes(unsigned):
        raise ValueError('Signing changed the tested Navigator APK payload')
    changed = changed_hashes(payload_hashes(baseline), payload)
    if (changed != nav['changedEntryHashes'] or changed['classes19.dex'] != nav['patchSha256']
            or sha256(candidate_dir / 'classes19.dex') != nav['patchSha256']):
        raise ValueError('Navigator payload differs from the reviewed CI bridge, factory hook or manifest')
    for name in ('apksigner', 'aapt', 'zipalign'):
        (sdk / name).chmod(0o755)
    verify_signer(sdk / 'apksigner', baseline, ('v2', 'v3'))
    signer = verify_signer(sdk / 'apksigner', signed, ('v2', 'v3'))
    run(sdk / 'apksigner', 'verify', '--min-sdk-version', '28', '--max-sdk-version', '28', signed)
    verify_zipalign(sdk / 'zipalign', signed)
    verify_maneuver_artwork(signed)
    check_manifest(sdk / 'aapt', baseline, signed)
    update = update_manifest(signed, 'natro-v3.5.1', signer,
                             run(sdk / 'aapt', 'dump', 'badging', signed).stdout, 'navigator', paired=True)
    if update != json.loads((release / 'navigator-update.json').read_text()):
        raise ValueError('Navigator stable manifest differs from verified APK')
    expected = dict(apk=signed.name, package=update['package'], versionCode=NEW_CODE, versionName=NEW_NAME,
                    previousVersionCode=OLD_CODE, previousSha256=BASE_SHA, sha256=update['sha256'], size=update['size'],
                    changedPayload=CHANGES, changedEntryHashes=changed, unchangedPayloadEntries=len(payload)-len(changed),
                    ciBridgeSha256=changed['classes19.dex'], certificateSha256=CERT, signatureV2=True,
                    signatureV3=True, exactApi28=True, zipalign16KiB=True, manifestSemanticsOnlyVersionChanged=True)
    if any(report.get(key) != value for key, value in expected.items()):
        raise ValueError('Navigator release report differs from verified APK')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('candidate', 'tools', 'release', 'baseline'):
        parser.add_argument('--' + name, type=Path, required=True)
    for name in ('source', 'tree', 'run'):
        parser.add_argument('--' + name, required=True)
    args = parser.parse_args()
    verify(args.candidate, args.tools, args.release, args.baseline, args.source, args.tree, args.run)
