#!/usr/bin/env python3
"""Verify declarations in the actual private Navigator APK before producing the signed pair."""
import sys
import zipfile
from inspect_dex_declarations import inspect
P = 'Lcom/yandex/mapkit/'
REQUIRED = {
    P+'road_events_layer/RoadEventStylingProperties;': {
        ('getTags', '()Ljava/util/List;'), ('isOnRoute', '()Z'),
        ('hasSignificanceGreaterOrEqual', '('+P+'road_events_layer/RoadEventSignificance;)Z'),
        ('isSelected','()Z'), ('isUserEvent','()Z'), ('isInFuture','()Z'), ('isValid','()Z')},
    P+'road_events_layer/RoadEventStyle;': {
        ('setIconImage','(Lcom/yandex/runtime/image/ImageProvider;)V'),
        ('setIconAnchor','(Landroid/graphics/PointF;)V'),
        ('setZoomScaleFunction','(Ljava/util/List;)V')},
    'Lr74/c;': {('<init>', '(Landroid/content/Context;)V'),
        ('provideStyle', '('+P+'road_events_layer/RoadEventStylingProperties;ZF'+P+'road_events_layer/RoadEventStyle;)Z')},
    P+'map/Map;': {('addMapObjectLayer','(Ljava/lang/String;)'+P+'map/RootMapObjectCollection;')},
    P+'map/MapObjectCollection;': {('addPlacemark','()'+P+'map/PlacemarkMapObject;')},
    P+'map/BaseMapObjectCollection;': {('clear','()V')},
    P+'map/RootMapObjectCollection;': {('setConflictResolutionMode','('+P+'ConflictResolutionMode;)V')},
    P+'map/PlacemarkMapObject;': {('setGeometry','('+P+'geometry/Point;)V'),
        ('setIcon','(Lcom/yandex/runtime/image/ImageProvider;'+P+'map/IconStyle;)V'),
        ('setScaleFunction','(Ljava/util/List;)V')},
    P+'map/IconStyle;': {('<init>','()V'),
        ('setScale','(Ljava/lang/Float;)'+P+'map/IconStyle;'),
        ('setAnchor','(Landroid/graphics/PointF;)'+P+'map/IconStyle;'),
        ('setZIndex','(Ljava/lang/Float;)'+P+'map/IconStyle;')},
}
def verify(path):
    found = {}
    with zipfile.ZipFile(path) as apk:
        for name in apk.namelist():
            if name.endswith('.dex'):
                for row in inspect(apk.read(name),list(REQUIRED)):
                    found[row['class']] = row
    failures=[]
    for name, required in REQUIRED.items():
        row=found.get(name,{})
        actual={(m['name'],m['signature']) for m in row.get('methods',[]) if m['access'] & 1}
        for method in sorted(required-actual): failures.append(name+' '+str(method))
        if name.endswith(('RoadEventStylingProperties;','RoadEventStyle;')) and not row.get('access',0)&0x200:
            failures.append(name+' must be an interface for stock-style adaptation')
    if failures: raise SystemExit('Missing actual Navigator API: '+str(failures))
    print('Actual Navigator route-event API verified: '+str(sum(map(len,REQUIRED.values())))+' declarations')
if __name__ == '__main__': verify(sys.argv[1])
