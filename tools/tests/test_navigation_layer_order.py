#!/usr/bin/env python3
"""Run production MapSublayerOrder against a mutable, feature-aware MapKit manager fixture."""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SOURCES = {
    "com/yandex/mapkit/map/SublayerFeatureType.java": '''package com.yandex.mapkit.map;
public enum SublayerFeatureType { GROUND, PLACEMARKS_AND_LABELS }
''',
    "com/yandex/mapkit/map/LayerIds.java": '''package com.yandex.mapkit.map;
public final class LayerIds {
    public static String getMapLayerId() { return "map"; }
    public static String getJamsLayerId() { return "jams"; }
    public static String getRouteMapObjectsLayerId() { return "route_objects"; }
    public static String getDrivingNavigationBaseLayerId() { return "navigation_base"; }
    public static String getDrivingNavigationRoutePinsLayerId() { return "pins"; }
    public static String getUserLocationLayerId() { return "user"; }
    public static String getRoadEventsLayerId() { return "events"; }
}
''',
    "ru/natro/navigation/NavigationMapProfile.java": '''package ru.natro.navigation;
// Profile data is injected independently of the native placement algorithm under test.
final class NavigationMapProfile {
    boolean manualLayerPrioritiesEnabled;
    int routeLight = 20;
    int effectiveCameraPriority() { return 30; }
    int effectiveRoadEventPriority() { return 40; }
    int effectiveRoutePriority() { return 50; }
    int effectiveDestinationPriority() { return 90; }
    int effectiveTrafficLightPriority() { return 70; }
    int effectiveRouteTrafficLightPriority() { return routeLight; }
    int effectiveSpeedBumpPriority() { return 55; }
    int effectiveLanePriority() { return 80; }
    int effectiveCursorPriority() { return 60; }
}
''',
    "ru/natro/navigation/LayerOrderReplay.java": '''package ru.natro.navigation;
import java.util.*;
import com.yandex.mapkit.map.SublayerFeatureType;

public final class LayerOrderReplay {
    private static final String G = ":GROUND", P = ":PLACEMARKS_AND_LABELS";
    public static final class Manager {
        final List<String> layers = new ArrayList<>();
        public int findFirstOf(String id, SublayerFeatureType type) {
            return layers.indexOf(id + ":" + type.name());
        }
        public void moveAfter(int index, int anchor) { move(index, anchor, true); }
        public void moveBefore(int index, int anchor) { move(index, anchor, false); }
        private void move(int index, int anchor, boolean after) {
            String target = layers.get(index), reference = layers.get(anchor);
            if (!target.startsWith("ru.natro.")) throw new AssertionError("Moved stock " + target);
            layers.remove(index);
            layers.add(layers.indexOf(reference) + (after ? 1 : 0), target);
        }
    }
    public static final class MapFixture {
        final Manager manager = new Manager();
        public Manager getSublayerManager() { return manager; }
    }

    static MapFixture map(boolean stockGuidance) {
        MapFixture map = new MapFixture();
        Collections.addAll(map.manager.layers, "map" + G, "jams" + G,
                "route_objects" + G, "navi_route_polyline_layer" + G,
                "navigation_base" + G, "map" + P, "jams" + P, "events" + P);
        if (stockGuidance) Collections.addAll(map.manager.layers, "pins" + P, "user" + P,
                "manual_guidance_my_location_placemark" + P, "navi_guidance_balloons" + P,
                "arrival_points_destination_points_layer" + P);
        Collections.addAll(map.manager.layers, MapSublayerOrder.DESTINATION + P,
                MapSublayerOrder.LANE_GUIDANCE + P, MapSublayerOrder.TRAFFIC_LIGHTS + P,
                MapSublayerOrder.CURSOR + P, MapSublayerOrder.ROUTE_TRAFFIC_LIGHTS + P,
                MapSublayerOrder.SPEED_BUMPS + P, MapSublayerOrder.CAMERA_SIGNS + P,
                MapSublayerOrder.ROUTE + G, MapSublayerOrder.CAMERA_SECTORS + G);
        return map;
    }

    static void below(MapFixture map, String lower, String upper) {
        List<String> values = map.manager.layers;
        if (values.indexOf(lower) < 0 || values.indexOf(upper) < 0
                || values.indexOf(lower) >= values.indexOf(upper)) {
            throw new AssertionError(lower + " should be below " + upper + ": " + values);
        }
    }
    static List<String> stock(MapFixture map) {
        List<String> result = new ArrayList<>();
        for (String layer : map.manager.layers) if (!layer.startsWith("ru.natro.")) result.add(layer);
        return result;
    }
    static void ordinaryIsLow(MapFixture map) {
        String ordinary = MapSublayerOrder.ROUTE_TRAFFIC_LIGHTS + P;
        below(map, MapSublayerOrder.ROUTE + G, ordinary);
        for (String name : new String[]{"map" + P, "jams" + P}) {
            if (map.manager.layers.contains(name)) below(map, name, ordinary);
        }
        for (String name : new String[]{MapSublayerOrder.CAMERA_SIGNS, MapSublayerOrder.SPEED_BUMPS,
                MapSublayerOrder.CURSOR, MapSublayerOrder.TRAFFIC_LIGHTS,
                MapSublayerOrder.LANE_GUIDANCE, MapSublayerOrder.DESTINATION}) {
            below(map, ordinary, name + P);
        }
        int label = Math.max(map.manager.layers.indexOf("map" + P),
                map.manager.layers.indexOf("jams" + P));
        if (label >= 0 && map.manager.layers.indexOf(ordinary) != label + 1) {
            throw new AssertionError("Ordinary lights are not immediately above labels");
        }
    }
    static void applyAndCheck(MapFixture map, NavigationMapProfile profile) throws Exception {
        List<String> originalStock = stock(map);
        MapSublayerOrder.apply(map, profile);
        ordinaryIsLow(map);
        if (!originalStock.equals(stock(map))) throw new AssertionError("Stock order changed");
        List<String> once = new ArrayList<>(map.manager.layers);
        MapSublayerOrder.apply(map, profile);
        if (!once.equals(map.manager.layers)) throw new AssertionError("Repeated apply reordered layers");
    }

    void automaticBothStockAndIndependentMaps() throws Exception {
        for (boolean full : new boolean[]{false, true}) applyAndCheck(map(full), new NavigationMapProfile());
    }
    void labelOrderDoesNotDependOnFirstExistingId() throws Exception {
        MapFixture map = map(false);
        Collections.swap(map.manager.layers, map.manager.layers.indexOf("map" + P),
                map.manager.layers.indexOf("jams" + P));
        applyAndCheck(map, new NavigationMapProfile());
    }
    void missingOptionalAnchorsAndLabels() throws Exception {
        for (String missing : new String[]{"events" + P, "map" + P, "jams" + P}) {
            MapFixture map = map(false);
            map.manager.layers.remove(missing);
            applyAndCheck(map, new NavigationMapProfile());
        }
        MapFixture map = map(false);
        map.manager.layers.removeAll(Arrays.asList("map" + P, "jams" + P, "events" + P));
        applyAndCheck(map, new NavigationMapProfile());
    }
    void lateCreationAndFeatureAwareLookup() throws Exception {
        MapFixture map = map(false);
        map.manager.layers.remove(MapSublayerOrder.ROUTE_TRAFFIC_LIGHTS + P);
        // Same ID with another feature must not be selected or moved by the placemark rule.
        map.manager.layers.add(1, MapSublayerOrder.ROUTE_TRAFFIC_LIGHTS + G);
        NavigationMapProfile profile = new NavigationMapProfile();
        MapSublayerOrder.apply(map, profile);
        map.manager.layers.add(MapSublayerOrder.ROUTE_TRAFFIC_LIGHTS + P);
        applyAndCheck(map, profile);
        if (map.manager.layers.indexOf(MapSublayerOrder.ROUTE_TRAFFIC_LIGHTS + G) != 1)
            throw new AssertionError("Wrong feature moved");
    }
    void standardManualOrderIsAlsoLow() throws Exception {
        NavigationMapProfile profile = new NavigationMapProfile();
        profile.manualLayerPrioritiesEnabled = true;
        applyAndCheck(map(false), profile);
    }
    void manualOverrideIsIndependentForTwoMaps() throws Exception {
        MapFixture hud = map(false), cluster = map(false);
        NavigationMapProfile custom = new NavigationMapProfile();
        custom.manualLayerPrioritiesEnabled = true;
        custom.routeLight = 95;
        MapSublayerOrder.apply(hud, custom);
        below(hud, MapSublayerOrder.DESTINATION + P, MapSublayerOrder.ROUTE_TRAFFIC_LIGHTS + P);
        applyAndCheck(cluster, new NavigationMapProfile());
        custom.routeLight = 0;
        applyAndCheck(hud, custom);
    }
    public static void main(String[] args) throws Exception {
        LayerOrderReplay.class.getDeclaredMethod(args[0]).invoke(new LayerOrderReplay());
    }
}
''',
}


class NavigationLayerOrderTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.directory = Path(cls.temp.name)
        files = []
        for relative, text in SOURCES.items():
            path = cls.directory / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text)
            files.append(str(path))
        production = ROOT / "navigator-mod/src/main/java/ru/natro/navigation"
        files.extend(str(production / name) for name in ("MapSublayerOrder.java", "ReflectMethods.java"))
        subprocess.run(["javac", "-d", str(cls.directory), *files], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def replay(self, name):
        subprocess.run(["java", "-cp", str(self.directory),
                        "ru.natro.navigation.LayerOrderReplay", name], check=True)


for case in ("automaticBothStockAndIndependentMaps", "labelOrderDoesNotDependOnFirstExistingId",
             "missingOptionalAnchorsAndLabels", "lateCreationAndFeatureAwareLookup",
             "standardManualOrderIsAlsoLow", "manualOverrideIsIndependentForTwoMaps"):
    setattr(NavigationLayerOrderTest, "test_" + case, lambda self, name=case: self.replay(name))

if __name__ == "__main__":
    unittest.main()
