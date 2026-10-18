#!/usr/bin/env python3
"""Offline fixtures only: no actual ADB/device calls. Run with Python3.8+."""
import pathlib,os,sys,json,subprocess,hashlib,io,importlib.util,contextlib,tempfile,struct,zlib
temporary=tempfile.TemporaryDirectory(prefix='kx11-owner-offline-');root=pathlib.Path(temporary.name)
p=root/'fake-adb';p.write_text('''#!/usr/bin/env python3
import sys,os,pathlib,json,base64
x=sys.argv[1:]
with open(os.environ['KX11_FAKE_ADB_LOG'],'a') as f:f.write(json.dumps(x)+'\\n')
if x==['devices']:
 print('List of devices attached\\nfixture1\\tdevice'+('\\nfixture2\\tdevice' if os.environ.get('KX11_FAKE_CASE')=='ambiguous' else ''))
else:
 assert x[:2]==['-s','fixture1'];x=x[2:]
 if x[:2]==['exec-out','screencap']:
  if os.environ.get('KX11_FAKE_CASE')=='bad_png': print('capture unavailable')
  else:sys.stdout.buffer.write(pathlib.Path(os.environ['KX11_FAKE_PNG']).read_bytes())
 elif x[:3]==['shell','pm','path']: print('package:/system/app/Fixture/base.apk')
 elif x[0]=='pull': pathlib.Path(x[2]).write_bytes(b'fixture-apk')
 else:print('offline fixture metadata')
''');p.chmod(0o755)
def chunk(kind, data):
 return struct.pack('>I',len(data))+kind+data+struct.pack('>I',zlib.crc32(kind+data))
png=root/'fixture.png';png.write_bytes(b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>2I5B',192,108,8,2,0,0,0))+chunk(b'IDAT',zlib.compress((b'\0'+bytes([20,40,60])*192)*108))+chunk(b'IEND',b''))
script=pathlib.Path(__file__).resolve().with_name('collect_owner.py');results=[]
for case,extras in [('metadata',[]),('ambiguous',[]),('bad_png',['--capture-id','2']),('capture_apk',['--capture-id','2','--package','com.fixture.owner'])]:
 dest=root/case;dest.mkdir(exist_ok=True);log=dest/'adb-calls.jsonl';log.write_text('');env=dict(os.environ,KX11_FAKE_CASE=case,KX11_FAKE_ADB_LOG=str(log),KX11_FAKE_PNG=str(png))
 r=subprocess.run([sys.executable,str(script),'--adb',str(p),'--out',str(dest),*extras],capture_output=True,env=env,timeout=15)
 assert not r.stderr,r.stderr
 out=pathlib.Path(r.stdout.decode().strip());j=json.load(open(out/'RESULT.json'));calls=[json.loads(v)for v in log.read_text().splitlines()];expected=3 if case in ['ambiguous','bad_png']else 0;assert r.returncode==expected,(case,r.returncode)
 for rec in j['records']:
  if 'stdout_sha256'in rec:
   b=(out/(rec['name']+'.out')).read_bytes();assert hashlib.sha256(b).hexdigest()==rec['stdout_sha256'] and len(b)==rec['stdout_bytes']
 if case=='ambiguous':assert len(calls)==1 and j['status']=='BLOCKED_DEVICE_SELECTION'
 if case=='metadata':assert not any('pull'in c or'exec-out'in c for c in calls)
 if case=='bad_png':assert not(out/'framework_capture.png').exists() and j['status']=='PARTIAL'
 if case=='capture_apk':
  cap=next(v for v in j['records']if v['name']=='framework_capture');assert(cap['png_width'],cap['png_height'])==(192,108)
  pull=next(v for v in j['records']if v['name'].endswith('_pull_0'));assert pull['copy_complete'] and pull['sha256']==hashlib.sha256(b'fixture-apk').hexdigest()
 results.append(dict(check=case,status='PASS',exit_code=r.returncode,adb_calls=len(calls)))
# Exercise partial output retention on an actual TimeoutExpired without waiting on a device.
spec=importlib.util.spec_from_file_location('owner_collector',script);mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod)
original_run=mod.subprocess.run

def fake_timeout(argv,**kw):
 if argv[-1]=='devices':return subprocess.CompletedProcess(argv,0,b'List of devices attached\nfixture1\tdevice\n',b'')
 if argv[-2:]==['dumpsys','SurfaceFlinger']:raise subprocess.TimeoutExpired(argv,kw['timeout'],output=b'partial metadata',stderr=b'timed out')
 return subprocess.CompletedProcess(argv,0,b'fixture',b'')
mod.subprocess.run=fake_timeout;oldargv=sys.argv;sys.argv=[str(script),'--adb',str(p),'--out',str(root/'timeout')];buf=io.StringIO()
try:
 with contextlib.redirect_stdout(buf):rc=mod.main()
finally:mod.subprocess.run=original_run;sys.argv=oldargv
out=pathlib.Path(buf.getvalue().strip());j=json.load(open(out/'RESULT.json'));t=next(v for v in j['records']if v['name']=='surfaceflinger');assert rc==3 and j['status']=='PARTIAL' and t['status']=='timeout' and(out/'surfaceflinger.out').read_bytes()==b'partial metadata'
results.append(dict(check='timeout_partial_output_retained',status='PASS',exit_code=rc))
(root/'results.json').write_text(json.dumps(results,indent=2)+'\n');print(json.dumps(results))
