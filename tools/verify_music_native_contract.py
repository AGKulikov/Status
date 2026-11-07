#!/usr/bin/env python3
"""Verify the read-only Media3 adapter against the pinned private Music APK."""
import hashlib
import json
from pathlib import Path
import zipfile
from inspect_dex_declarations import inspect

BASELINE='7018cb5ba63248a3b33973d5a32d380b7dfaa287c9a416d072754f2670f47f81'
FIELDS={
 'Ld4j;': {'b':'Ljava/lang/Object;','c':'Ljava/util/HashMap;','a':'Landroidx/media3/session/i;'},
 'Landroidx/media3/session/i;': {'a':'Ljava/lang/Object;','f':'Landroid/content/Context;',
     'h':'Ly5j;','l':'Landroid/os/Handler;','y':'Z'},
 'Ly5j;': {'l':'Lt4j;'}, 'Lt4j;': {'a':'Ljava/lang/Object;'},
 'Lj4j;': {'a':'Landroid/media/session/MediaSession;'},
}

def verify(path):
    if hashlib.sha256(Path(path).read_bytes()).hexdigest()!=BASELINE:
        raise ValueError('Unreviewed Music baseline')
    found={}
    with zipfile.ZipFile(path) as apk:
        for name in apk.namelist():
            if name.endswith('.dex'):
                for item in inspect(apk.read(name),list(FIELDS)):
                    if item['class'] in FIELDS: found[item['class']]=item
    for name,expected in FIELDS.items():
        actual={f['name']:f for f in found[name]['fields']}
        for field,typ in expected.items():
            if actual[field]['type']!=typ or not actual[field]['access']&1:
                raise ValueError('Changed public field '+name+field)
    return {'baselineSha256':BASELINE,'classes':len(FIELDS),
            'fields':sum(map(len,FIELDS.values())),'nativeCodeModified':False}

if __name__=='__main__':
    import argparse
    parser=argparse.ArgumentParser();parser.add_argument('apk',type=Path)
    print(json.dumps(verify(parser.parse_args().apk),indent=2))
