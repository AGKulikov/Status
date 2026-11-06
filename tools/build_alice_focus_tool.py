#!/usr/bin/env python3
"""Build the project-owned DEX hook tool with pinned libraries from the Android SDK."""
import hashlib, os, shutil, subprocess, urllib.request
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
LIBS = [
 ('smali-dexlib2-3.0.3.jar', '11cc5e7b75b23feb65e41e28e5c225b4a77dbf7210d6cedd4bdb3f04c1f6ea24', 'https://dl.google.com/dl/android/maven2/com/android/tools/smali/smali-dexlib2/3.0.3/smali-dexlib2-3.0.3.jar'),
 ('guava-32.0.1-jre.jar', 'bd7fa227591fb8509677d0d1122cf95158f3b8a9f45653f58281d879f6dc48c5', 'https://repo.maven.apache.org/maven2/com/google/guava/guava/32.0.1-jre/guava-32.0.1-jre.jar'),
 ('jsr305-3.0.2.jar', '766ad2a0783f2687962c8ad74ceecc38a28b9f72a2d085ee438b7813e928d0c7', 'https://repo.maven.apache.org/maven2/com/google/code/findbugs/jsr305/3.0.2/jsr305-3.0.2.jar'),
]
def build():
 out = ROOT/'build/alice-focus-tool'; out.mkdir(parents=True, exist_ok=True)
 sdk = Path(os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT', '/missing')))
 for name, digest, url in LIBS:
  dest = out/name
  if not dest.exists():
   found = next(sdk.glob('cmdline-tools/**/'+name), None)
   if found: shutil.copy2(found, dest)
   else:
    with urllib.request.urlopen(url, timeout=60) as response: dest.write_bytes(response.read())
  if hashlib.sha256(dest.read_bytes()).hexdigest() != digest: raise ValueError('Dependency hash mismatch: '+name)
 cp = os.pathsep.join(str(out/name) for name,_,_ in LIBS)
 subprocess.run(['javac','-encoding','UTF-8','-cp',cp,'-d',str(out),
                 str(ROOT/'tools/alice/PatchAliceFocus.java'),str(ROOT/'tools/alice/PatchAliceFocusSelfTest.java')],check=True)
 subprocess.run(['java','-cp',str(out)+os.pathsep+cp,'PatchAliceFocusSelfTest'],check=True)
 print('Native Alice hook tool and positive/negative DEX fixtures PASS')
 return out
if __name__=='__main__': build()
