import base64,hashlib,json,os,subprocess,time
from pathlib import Path
from cryptography.hazmat.primitives import serialization,hashes
from cryptography.hazmat.primitives.asymmetric import padding
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
root=Path(os.environ['RUNNER_TEMP'])/'transport349';root.mkdir(mode=0o700,exist_ok=True)
key=serialization.load_pem_private_key((root/'private.pem').read_bytes(),password=None)
repo=os.environ['GITHUB_REPOSITORY'];run=os.environ['GITHUB_RUN_ID']
for attempt in range(120):
    r=subprocess.run(['gh','api',f'repos/{repo}/contents/release-handoff/349-{run}.json?ref=release/natro-3.4.9'],capture_output=True)
    if r.returncode==0:break
    time.sleep(5)
else:raise RuntimeError('Encrypted transfer not received before timeout')
env=json.loads(base64.b64decode(json.loads(r.stdout)['content']))
sym=key.decrypt(base64.b64decode(env['key']),padding.OAEP(mgf=padding.MGF1(hashes.SHA256()),algorithm=hashes.SHA256(),label=None))
data=json.loads(AESGCM(sym).decrypt(base64.b64decode(env['nonce']),base64.b64decode(env['data']),run.encode()))
assert data['run']==run
for app,package in zip(('navigator','music'),data['packages']):
    manifest=json.loads(Path(f'docs/releases/3.4.9/{app}-update.json').read_text())
    filename=manifest['url'].rsplit('/',1)[1];target=root/filename
    encrypted=bytearray()
    for chunk in package['chunks']:
        blob=json.loads(subprocess.check_output(['gh','api',f"repos/{repo}/git/blobs/{chunk['sha']}"]))
        raw=base64.b64decode(blob['content'])
        assert len(raw)==chunk['size']
        encrypted.extend(raw)
    assert hashlib.sha256(encrypted).hexdigest()==package['encryptedSha256']
    plain=AESGCM(sym).decrypt(base64.b64decode(package['nonce']),bytes(encrypted),(run+':'+app).encode())
    assert len(plain)==manifest['size'] and hashlib.sha256(plain).hexdigest()==manifest['sha256']
    target.write_bytes(plain)
    del encrypted,plain
    tag='natro-navigator-v3.4.9' if app=='navigator' else 'natro-music-v4'
    release=json.loads(subprocess.check_output(['gh','api',f'repos/{repo}/releases/tags/{tag}']))
    assert release['draft'] and not any(a['name']==filename for a in release['assets'])
    subprocess.run(['gh','release','upload',tag,str(target),'--repo',repo],check=True)
    print(app+' verified bytes uploaded to draft',flush=True)
(root/'private.pem').unlink()
