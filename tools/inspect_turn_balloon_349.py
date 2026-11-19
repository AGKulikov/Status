"""Check the primary turn-balloon API and the shipped independent lane renderer.

Pass the published Navigator 3.4.9 APK and Android SDK dexdump. Results describe
compiled code. Runtime visibility, the selected turn, and the active implementation
in a particular drive require separate observations.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile
from zipfile import ZipFile

from inspect_dex_declarations import inspect


ROOT = Path(__file__).resolve().parents[1]
CONTEXT = 'Lcom/yandex/navikit/ui/guidance/context/'
LAYER = 'Lcom/yandex/navikit/guidance_layer/'
MAPKIT = 'Lcom/yandex/mapkit/navigation/automotive/layer/'
COMBINED = CONTEXT + 'LaneAndManeuverBalloonView;'
FACTORY = 'createLaneAndManeuverBalloonView(Z)' + COMBINED
DECLARATIONS = {
    'classes8.dex': {
        'Lru/yandex/yandexnavi/ui/balloons/BalloonFactoryImpl;': [FACTORY],
        'Lru/yandex/yandexnavi/ui/guidance/context/LaneAndManeuverBalloonViewImpl;': [
            'createTexture()Lcom/yandex/runtime/image/ImageProvider;',
            'setDistance(Ljava/lang/String;Ljava/lang/String;)V', 'setNextRoadName(Ljava/lang/String;)V',
            'setItems(Ljava/util/List;)V',
        ],
    },
    'classes12.dex': {
        MAPKIT + 'Balloon;': ['fromLaneSign(' + MAPKIT + 'LaneSignBalloon;)' + MAPKIT + 'Balloon;',
                              'fromManoeuvreWithLaneSign(' + MAPKIT + 'ManoeuvreWithLaneSignBalloon;)' + MAPKIT + 'Balloon;'],
        'Lcom/yandex/mapkit/navigation/RoutePosition;': ['getPoint()Lcom/yandex/mapkit/geometry/Point;'],
    },
    'classes13.dex': {
        LAYER + 'NaviGuidanceLayer;': ['isManeuverVisible()Z', 'setManeuverAndLaneBalloonsMerged(Z)V'],
        LAYER + 'NaviGuidanceLayerListener;': ['onManeuverVisibilityChanged()V'],
        'Lcom/yandex/navikit/ui/balloons/BalloonFactory;': [FACTORY],
        COMBINED: ['getAnchor()Lcom/yandex/mapkit/ScreenPoint;', 'setItems(Ljava/util/List;)V',
                   'setDistance(Ljava/lang/String;Ljava/lang/String;)V', 'setNextRoadName(Ljava/lang/String;)V'],
        'Lcom/yandex/navikit/ui/guidance/ManeuverView;': ['setContentVisible(Z)V'],
    },
}

BRIDGE = {
    ('Lru/natro/navigation/LaneGuidanceMapLayer;', 'applyOriginalYandexIcon'): [
        'com.yandex.mapkit.navigation.automotive.layer.LaneSignBalloon', 'fromLaneSign',
        'com.yandex.mapkit.styling.automotivenavigation.balloons.LaneSignBalloonTextureFactory', 'createTexture',
    ],
    ('Lru/natro/navigation/NavigatorStatePublisher;', 'readLanes'): [
        'getLaneSigns', 'getPosition', 'getPoint', 'getLaneSign',
    ],
}

NATIVE_CLASS = rb'BalloonImplI[A-Za-z0-9_]{1,80}26LaneAndManeuverBalloonViewEE'
NATIVE_SIGNATURES = {
    'constructor_with_collection_and_world_position':
        NATIVE_CLASS + rb'C1E[A-Za-z0-9_]{1,55}19MapObjectCollection[A-Za-z0-9_]{1,20}22PlacemarkWorldPosition',
    'separate_setVisibility_bool': NATIVE_CLASS + rb'13setVisibilityEb',
}


def compiled_bridge_strings(dexdump, dex):
    with tempfile.TemporaryDirectory() as directory:
        name = Path(directory) / 'classes19.dex'
        name.write_bytes(dex)
        output = subprocess.check_output([dexdump, '-d', str(name)], text=True, errors='replace')
    result = {key: set() for key in BRIDGE}
    owner = method = None
    for line in output.splitlines():
        value = line.strip()
        if value.startswith('Class descriptor  :'):
            owner = value.split("'")[1]
            method = None
        elif value.startswith('name          :'):
            method = value.split("'")[1]
        elif (owner, method) in result:
            match = re.search(r'\|[0-9a-f]+: const-string(?:/jumbo)? v\d+, "([^"]+)"', line)
            if match:
                result[owner, method].add(match.group(1))
    return result


def audit(apk, dexdump):
    manifest = json.loads((ROOT / 'docs/releases/3.4.9/navigator-update.json').read_text())
    sha = hashlib.sha256()
    with apk.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            sha.update(block)
    digest = sha.hexdigest()
    if digest != manifest['sha256']:
        raise ValueError('APK SHA-256 differs from the published Navigator 3.4.9 manifest')
    with ZipFile(apk) as archive:
        for dex, expected in DECLARATIONS.items():
            rows = inspect(archive.read(dex), tuple(expected))
            by_class = {row['class']: {m['name'] + m['signature'] for m in row['methods']} for row in rows}
            for owner, methods in expected.items():
                missing = set(methods) - by_class.get(owner, set())
                if missing:
                    raise ValueError(f'{dex} {owner} missing {sorted(missing)}')
        actual = compiled_bridge_strings(dexdump, archive.read('classes19.dex'))
        native = archive.read('lib/arm64-v8a/libmaps-mobile.so')
        for name, pattern in NATIVE_SIGNATURES.items():
            if re.search(pattern, native) is None:
                raise ValueError(f'ARM64 native balloon symbol missing: {name}')
    for key, expected in BRIDGE.items():
        missing = set(expected) - actual[key]
        if missing:
            raise ValueError(f'{key} missing reflection targets {sorted(missing)}')
    return {'apk_sha256': digest, 'declarations': DECLARATIONS, 'arm64_symbols': list(NATIVE_SIGNATURES),
            'bridge_method_literals': {owner + '->' + method: values for (owner, method), values in BRIDGE.items()}}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--dexdump', default='dexdump')
    args = parser.parse_args()
    print(json.dumps(audit(args.apk, args.dexdump), indent=2))
