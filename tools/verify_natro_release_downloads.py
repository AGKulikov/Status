"""Verify downloaded Natro 2.9.7 APKs against CI and the previous signed pair.

Requires androguard. Cryptographic verification is done with apksigner in CI; this
verifier binds the checked output to downloaded bytes and inspects APK/DEX identity.
It does not execute the application or prove OEM installation on KX11.
"""
import argparse
import hashlib
import json
import shutil
import zipfile
from pathlib import Path
from loguru import logger
from androguard.core.apk import APK
from androguard.core.dex import DEX

CERT = '6e9855aedc008bbdd8a7fbf3f490be07f964b7ac658a837a1592647a08365c75'
METHODS = {
    'Ldezz/status/widget/media/ButtonGestureEngine;': ['input', 'reset'],
    'Ldezz/status/widget/media/VehicleButtonController;': ['readKeys', 'invalidateInput', 'saveBinding', 'driverMenuBindings'],
    'Ldezz/status/widget/media/ButtonActionExecutor;': ['execute', 'settingsChanged'],
    'Ldezz/status/widget/media/ButtonInputPatch;': ['apply'],
    'Ldezz/status/widget/launcher/LauncherMediaController;': ['adoptNotification', 'publishLiveNotification', 'requestNotificationArtwork'],
    'Ldezz/status/widget/launcher/MediaDisplayNotification;': ['read', 'samePublication'],
    'Ldezz/status/widget/launcher/ShortcutActionPicker;': ['showNew', 'showPrimary', 'chooseKind'],
    'Ldezz/status/widget/instrument/InstrumentOemController;': ['setTsrHidden', 'setWhiteBarHidden'],
    'Lru/natro/navigation/AlternativeRouteMapLayer;': ['reportPlacement', 'footprints', 'geometry'],
    'Lru/natro/navigation/MapOverlayPlacementCoordinator$Footprint;': ['collisionBounds'],
}

def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()

def verify(args):
    logger.remove()
    work, deliver = args.work.resolve(), args.deliver.resolve()
    archive = work / 'signed-pair.zip'
    artifact = json.loads((work / 'signed-pair-artifact.json').read_text())
    assert sha(archive) == artifact['digest'].removeprefix('sha256:')
    assert archive.stat().st_size == artifact['size_in_bytes']
    ci = work / 'ci'
    ci.mkdir(exist_ok=True)
    with zipfile.ZipFile(archive) as z:
        assert z.testzip() is None
        for info in z.infolist():
            assert (ci / info.filename).resolve().is_relative_to(ci)
            z.extract(info, ci)
    for line in (ci / 'SHA256SUMS.txt').read_text().splitlines():
        expected, name = line.split(maxsplit=1)
        target = (ci / name.lstrip('*')).resolve()
        assert target.is_relative_to(ci)
        assert sha(target) == expected
    report = json.loads((ci / 'release-report.json').read_text())
    assert report['source'] == dict(repository='AGKulikov/Status', commit=args.commit, tree=args.tree)
    assert report['certificateSha256'] == CERT
    assert report['natro']['versionName'] == '2.9.7'
    assert report['natro']['versionCode'] == 208021330
    assert report['compatibility']['status'] == 'static-gates-passed'
    deliver.mkdir(exist_ok=True)
    facts, found = [], {}
    for kind, filename, package, version, code in [
        ('natro', 'Natro-2.9.7-signed.apk', 'ru.natro.statuswidget', '2.9.7', '208021330'),
        ('navigator', 'Navigator-30.3.0-Natro-2.9.7-signed.apk', 'ru.yandex.yandexnavi', '30.3.0', '739564630'),
    ]:
        source = (ci / report[kind]['apk']).resolve()
        assert source.is_relative_to(ci)
        assert sha(source) == report[kind]['sha256']
        target = deliver / filename
        if target.exists():
            assert sha(target) == sha(source), 'Refusing to overwrite a different APK'
        else:
            shutil.copyfile(source, target)
        apk = APK(str(target))
        assert apk.get_package() == package
        assert apk.get_androidversion_name() == version
        assert apk.get_androidversion_code() == code
        assert apk.is_signed_v2() and apk.is_signed_v3()
        for certs in [apk.get_certificates_der_v2(), apk.get_certificates_der_v3()]:
            assert len(certs) == 1 and hashlib.sha256(certs[0]).hexdigest() == CERT
        if kind == 'natro':
            assert apk.get_app_name() == 'Natro'
            assert apk.get_min_sdk_version() == '28' and apk.get_target_sdk_version() == '28'
        with zipfile.ZipFile(target) as z:
            assert z.testzip() is None
            for name in z.namelist():
                if not (name.startswith('classes') and name.endswith('.dex')):
                    continue
                if kind == 'navigator' and name != 'classes19.dex':
                    continue
                raw = z.read(name)
                relevant = [c for c in METHODS if c.encode() in raw and c not in found]
                if not relevant:
                    continue
                dex = DEX(raw)
                for cls in dex.get_classes():
                    if cls.get_name() in relevant:
                        names = {m.get_name() for m in cls.get_methods() if m.get_code() is not None}
                        required = METHODS[cls.get_name()]
                        assert set(required).issubset(names), (cls.get_name(), required, names)
                        found[cls.get_name()] = required
        facts.append(dict(file=filename, bytes=target.stat().st_size, sha256=sha(target),
                          package=package, versionName=version, versionCode=int(code),
                          minSdk=apk.get_min_sdk_version(), targetSdk=apk.get_target_sdk_version(),
                          crc_ok=True, certificate_sha256=CERT, v2_block=True, v3_block=True,
                          cryptographic_apksigner_verification='release CI (download hashes match)'))
    assert set(found) == set(METHODS), set(METHODS) - set(found)
    assert sha(args.previous_natro) == '09547edb8da0a58d4e6671e033ef099e15611ff73f40be237dec70cd5e4bf51b'
    old_natro = APK(str(args.previous_natro))
    assert old_natro.get_package() == facts[0]['package']
    assert old_natro.get_androidversion_name() == '2.9.6'
    assert int(old_natro.get_androidversion_code()) + 1 == facts[0]['versionCode']
    for certs in [old_natro.get_certificates_der_v2(), old_natro.get_certificates_der_v3()]:
        assert len(certs) == 1 and hashlib.sha256(certs[0]).hexdigest() == CERT
    assert sha(args.previous_navigator) == '74edb329cb1c8baa854bc753a1a5a0dd8de18542a5ca307aaf4081916a5dd1f7'
    with zipfile.ZipFile(args.previous_navigator) as old, zipfile.ZipFile(deliver / facts[1]['file']) as new:
        names = {n for n in old.namelist() if not n.startswith('META-INF/')}
        assert names == {n for n in new.namelist() if not n.startswith('META-INF/')}
        changed = [n for n in sorted(names) if old.read(n) != new.read(n)]
        assert changed == ['classes19.dex'], changed
    result = dict(source_commit=args.commit, source_tree=args.tree, release_ci=args.run,
                  files=facts, method_checks=found,
                  natro_update_identity=dict(previous_version='2.9.6', previous_code=208021329,
                                             same_package=True, same_certificate=True, code_increment=1,
                                             oem_install_policy='requires KX11 acceptance'),
                  navigator_payload_comparison=dict(previous_version='2.9.6', changed=changed,
                                                     unchanged_entries=len(names)-len(changed),
                                                     excluded='META-INF signatures'),
                  signed_pair_artifact=dict(id=artifact['id'], zip_sha256=sha(archive),
                                            zip_bytes=archive.stat().st_size))
    (work / 'final-download-check.json').write_text(json.dumps(result, indent=2)+'\n')
    print(json.dumps(result))

if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--work', type=Path, required=True)
    p.add_argument('--deliver', type=Path, required=True)
    p.add_argument('--previous-navigator', type=Path, required=True)
    p.add_argument('--previous-natro', type=Path, required=True)
    p.add_argument('--commit', required=True)
    p.add_argument('--tree', required=True)
    p.add_argument('--run', type=int, required=True)
    verify(p.parse_args())
