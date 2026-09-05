"""Launch the real CLI through Bash from a spaced path; synthetic ADB only."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[1]
PAYLOAD=b'KX11 authored installed-code fixture\n'

class MacLauncherSmoke(unittest.TestCase):
    def test_launcher_and_partial_archive_from_spaced_directory(self):
        with tempfile.TemporaryDirectory(prefix='KX11 Mac path with spaces ') as directory:
            home=Path(directory)
            bundle=home/'collector folder'
            shutil.copytree(ROOT,bundle,ignore=shutil.ignore_patterns('__pycache__'))
            adb=home/'fake adb'
            adb.write_text('#!'+sys.executable+'\n'+'''import hashlib,shlex,sys
args=sys.argv[1:]
data=b'KX11 authored installed-code fixture\\n'
if args==['devices']:
 print('List of devices attached\\nFIXTURE_SERIAL\\tdevice');sys.exit(0)
if args[:2]!=['-s','FIXTURE_SERIAL'] or args[2]!='exec-out':sys.exit(91)
script=shlex.split(args[3])[2]
if script.startswith('getprop '):print('synthetic-build')
elif script.startswith('pm path '):print('package:/system/app/fixture/base.apk')
elif script.startswith('dumpsys package '):print('versionCode=1 minSdk=28 targetSdk=28')
elif script.startswith('pidof '):pass
elif 'KX_FILE_V1' in script:print('KX_FILE_V1\\n%d\\n1700000000\\n%s'%(len(data),hashlib.sha256(data).hexdigest()))
elif script.startswith('p=') and 'cat "$p"' in script:sys.stdout.buffer.write(data)
else:sys.exit(92)
''')
            adb.chmod(0o700)
            result=subprocess.run(['bash',str(bundle/'Collect-KX11.command'),'--adb',str(adb),
                 '--output',str(home/'output folder'),'--no-qnx','--no-bluetooth'],
                 input='\n',capture_output=True,text=True,timeout=45)
            self.assertEqual(result.returncode,3,result.stderr+result.stdout)
            archives=list((home/'output folder').glob('*.tar.gz'))
            self.assertEqual(len(archives),1)
            with tarfile.open(archives[0]) as archive:
                members={x.name.split('/',1)[1]:x for x in archive if '/' in x.name and x.isfile()}
                report=json.load(archive.extractfile(members['RESULT.json']))
                self.assertEqual(report['status'],'PARTIAL')
                self.assertNotIn('fatal',report)
                self.assertEqual(report['counts']['collected'],1)
                self.assertGreater(report['counts']['duplicate_in_run'],0)
                self.assertEqual(report['counts']['skipped_by_user'],2)
                self.assertFalse(any(x['errors'] for x in report['package_records']))
                manifest=json.load(archive.extractfile(members['FILES.json']))
                for entry in manifest['files']:
                    data=archive.extractfile(members[entry['path']]).read()
                    self.assertEqual(len(data),entry['bytes'])
                    self.assertEqual(hashlib.sha256(data).hexdigest(),entry['sha256'])

if __name__=='__main__':unittest.main()
