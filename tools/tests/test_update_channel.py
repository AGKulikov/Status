"""Manifest policy replay and source integration checks; not an Android installer/network test."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest import mock

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('update_manifest',ROOT/'tools/create_update_manifest.py')
module=importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class UpdateChannel(unittest.TestCase):
    def test_manifest_streams_multiple_chunks_without_python_311_api(self):
        with tempfile.TemporaryDirectory() as directory:
            apk=Path(directory)/'Natro-3.2.4-signed.apk'
            payload=b'multi-chunk fixture\0' * 120000 + b'final partial chunk'
            apk.write_bytes(payload)
            signer='Signer #1 certificate SHA-256 digest: '+module.CERT
            badging="package: name='ru.natro.statuswidget' versionCode='208021357' versionName='3.2.4'"
            with mock.patch.object(module.hashlib, 'file_digest', create=True,
                                   side_effect=AssertionError('Requires Python 3.11')):
                value=module.manifest(apk,'natro-v3.2.4',signer,badging)
            self.assertEqual(value['sha256'],module.hashlib.sha256(payload).hexdigest())
            self.assertEqual(value['size'],len(payload))

    def test_manifest_identity_signature_and_hash(self):
        with tempfile.TemporaryDirectory() as directory:
            apk=Path(directory)/'Natro-3.2.4-signed.apk'
            apk.write_bytes(b'fixture: generator inputs are mocked; not an APK signature verification')
            signer='Signer #1 certificate SHA-256 digest: '+module.CERT
            badging="package: name='ru.natro.statuswidget' versionCode='208021357' versionName='3.2.4'"
            value=module.manifest(apk,'natro-v3.2.4',signer,badging)
            self.assertEqual(value['versionCode'],208021357)
            self.assertEqual(value['size'],apk.stat().st_size)
            self.assertEqual(value['sha256'],module.hashlib.sha256(apk.read_bytes()).hexdigest())
            for tag,cert,package in [('natro-v3.2.3',signer,badging),('natro-v3.2.4','Signer #1 certificate SHA-256 digest: '+ '0'*64,badging),('natro-v3.2.4',signer,badging.replace('ru.natro.statuswidget','other.package'))]:
                with self.assertRaises(ValueError):module.manifest(apk,tag,cert,package)

    def test_updater_guards_and_both_entry_points(self):
        base=ROOT/'app/src/main/java/dezz/status/widget'
        source=(base/'update/NatroUpdater.java').read_text()
        for guard in ['GET_SIGNING_CERTIFICATES','getApkContentsSigners','next.getLongVersionCode()<=current.getLongVersionCode()',
                      'canRequestPackageInstalls','ACTION_INSTALL_PACKAGE','setInstanceFollowRedirects(false)',
                      'natro-update.json','requireAsset(url)','stableSigner(current)','stableSigner(next)']:
            self.assertIn(guard,source)
        self.assertNotIn('daemonRoot',source)
        self.assertNotIn('pm install',source)
        self.assertIn('UpdateActivity',(base/'settings/SettingsDestinationCatalog.java').read_text())
        self.assertIn('case "/api/update"',(base/'transfer/LanTransferService.java').read_text())

    def test_phone_exports_reuse_exact_collectors(self):
        service=(ROOT/'app/src/main/java/dezz/status/widget/transfer/LanTransferService.java').read_text()
        for call in ['DiagnosticJournal.copyForExport(this)','DiagnosticBundle.create(this)','SystemFileDump.collect(',
                     'SystemFileDump.RUNNING.compareAndSet(false,true)','ready.token.equals(request.token())','x-natro-export']:
            self.assertIn(call,service)
        self.assertIn('exportWorker.execute',service)
        client=(ROOT/'app/src/main/assets/lan/client.js').read_text()
        for button in ['logsTxt','logsZip','systemDump','updateCheck','updateInstall']:
            self.assertIn("action('"+button+"'",client)

if __name__=='__main__':unittest.main()
