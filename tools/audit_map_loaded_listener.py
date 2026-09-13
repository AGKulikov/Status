#!/usr/bin/env python3
"""Verify the listener declarations in the exact delivered Navigator 2.8.8 APK.

Requires androguard 4.1.4. Emits hashes and API declarations only, not bytecode/decompiled code.
The input APK is private and must not be committed with the output.
"""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile

APK_SHA256 = 'd2620fc5dae6ef05a26aa079ea3d272e498b6a6e0b4d765add772fcf9730a70f'
DEX_SHA256 = '5f618db97dfa9d9e76ebdae849be9d3c251e79ec90bc853703cc7b4ed0620b23'
WANTED = {
    ('Lcom/yandex/mapkit/map/Map;', 'setMapLoadedListener'),
    ('Lcom/yandex/mapkit/map/internal/MapBinding;', 'setMapLoadedListener'),
    ('Lcom/yandex/mapkit/map/MapLoadedListener;', 'onMapLoaded'),
    ('Lcom/yandex/mapkit/map/MapLoadStatistics;', 'getRenderObjectCount'),
}


def audit(apk):
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    if digest != APK_SHA256:
        raise ValueError('Expected the exact delivered Navigator 2.8.8 APK')
    with zipfile.ZipFile(apk) as archive:
        data = archive.read('classes12.dex')
    if hashlib.sha256(data).hexdigest() != DEX_SHA256:
        raise ValueError('Unexpected classes12.dex')
    from loguru import logger
    logger.remove()
    from androguard.core.dex import DEX
    declarations = []
    for cls in DEX(data).get_classes():
        for method in cls.get_methods():
            if (cls.get_name(), method.get_name()) in WANTED:
                declarations.append({'owner': cls.get_name(), 'name': method.get_name(),
                                     'descriptor': method.get_descriptor().replace(' ', ''),
                                     'access': method.get_access_flags_string()})
    setters = [v for v in declarations if v['name'] == 'setMapLoadedListener']
    expected = '(Ljava/lang/ref/WeakReference;)V'
    if len(declarations) != 4 or len(setters) != 2 or any(v['descriptor'] != expected for v in setters):
        raise ValueError('Actual SDK declarations differ from the reviewed four-method contract')
    return {'schema': 'natro-map-loaded-listener-abi-v1',
            'source_apk_sha256': digest, 'dex_entry': 'classes12.dex', 'dex_sha256': DEX_SHA256,
            'declarations': sorted(declarations, key=lambda v: (v['owner'], v['name'])),
            'rejected_288_lookup': 'setMapLoadedListener(com.yandex.mapkit.map.MapLoadedListener)',
            'result': 'No direct-listener overload exists in Map or MapBinding; use WeakReference',
            'scope': 'Declared signatures in supplied APK plus local JVM reflection replay; not a physical KX11 test'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    result = audit(args.apk)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print('Verified Map/MapBinding WeakReference signatures in Navigator 2.8.8')
