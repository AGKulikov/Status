#!/usr/bin/env python3
"""Run the production listener binding against the actual 30.3.0 MapKit parameter shape.

The shape comes from declarations in the delivered APK, not from our reflection call.
No proprietary DEX/code is included and no Android device or APK build is needed.
"""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'navigator-mod/src/main/java/ru/natro/navigation'
REPLAY = r'''package ru.natro.navigation;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;

public final class MapLoadedBindingReplay {
    interface Listener { void onMapLoaded(int renderObjectCount); }
    // Public signatures of both Map and MapBinding are (WeakReference)V in classes12.dex.
    private static final class MapBinding {
        WeakReference<Listener> listener;
        boolean attached;
        public void setMapLoadedListener(WeakReference<Listener> value) {
            if (value == null) throw new AssertionError("Pass the SDK's empty reference when clearing");
            listener = value;
        }
        void loaded(int count) {
            Listener value = listener == null ? null : listener.get();
            if (attached && value != null) value.onMapLoaded(count);
        }
    }
    private static final class FailingMap {
        public void setMapLoadedListener(WeakReference<Listener> value) {
            throw new IllegalStateException("native map invalidated");
        }
    }
    static void check(boolean condition) { if (!condition) throw new AssertionError(); }

    static void reproduces288FailureThenConnectsBothMaps() throws Exception {
        for (int display = 0; display < 2; display++) {
            MapBinding map = new MapBinding();
            // 2.8.8 queried this exact incompatible parameter and stopped before addSurface.
            try {
                map.getClass().getMethod("setMapLoadedListener", Listener.class);
                throw new AssertionError("The 2.8.8 lookup must fail on this SDK");
            } catch (NoSuchMethodException expected) { check(!map.attached); }
            int[] count = {-1};
            Listener stronglyOwnedByRenderer = value -> count[0] = value;
            MapLoadedListenerBinding.set(map, stronglyOwnedByRenderer);
            check(map.listener.get() == stronglyOwnedByRenderer);
            map.attached = true;
            map.loaded(37);
            check(count[0] == 37);
        }
    }

    static void detachAndReplaceDoNotLeakCallbacksAcrossMaps() throws Exception {
        MapBinding hud = new MapBinding(), cluster = new MapBinding();
        hud.attached = cluster.attached = true;
        int[] calls = new int[3];
        Listener oldHud = count -> calls[0]++;
        Listener nextHud = count -> calls[1]++;
        Listener clusterListener = count -> calls[2]++;
        MapLoadedListenerBinding.set(hud, oldHud);
        MapLoadedListenerBinding.set(cluster, clusterListener);
        hud.loaded(1); cluster.loaded(1);
        MapLoadedListenerBinding.set(hud, null);
        check(hud.listener.get() == null);
        hud.loaded(1); cluster.loaded(1);
        check(calls[0] == 1 && calls[2] == 2);
        MapLoadedListenerBinding.set(hud, nextHud);
        hud.loaded(1); cluster.loaded(1);
        check(calls[0] == 1 && calls[1] == 1 && calls[2] == 3);
        MapLoadedListenerBinding.set(hud, null);
        MapLoadedListenerBinding.set(cluster, null);
        hud.loaded(1); cluster.loaded(1);
        check(calls[1] == 1 && calls[2] == 3);
    }

    static void failedNativeRegistrationIsNotAcknowledged() throws Exception {
        boolean connected = false;
        try {
            MapLoadedListenerBinding.set(new FailingMap(), (Listener) count -> {});
            connected = true;
        } catch (InvocationTargetException expected) {
            check(expected.getCause() instanceof IllegalStateException);
        }
        check(!connected);
    }

    public static void main(String[] args) throws Exception {
        MapLoadedBindingReplay.class.getDeclaredMethod(args[0]).invoke(null);
    }
}
'''


class MapLoadedListenerBindingTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='map-loaded-binding-')
        cls.addClassCleanup(cls.temp.cleanup)
        cls.directory = Path(cls.temp.name)
        replay = cls.directory / 'MapLoadedBindingReplay.java'
        replay.write_text(REPLAY, encoding='utf-8')
        compiler = [shutil.which('javac')] if shutil.which('javac') else ['java', 'com.sun.tools.javac.Main']
        result = subprocess.run([*compiler, '-source', '8', '-target', '8', '-Xlint:-options',
                                 '-d', str(cls.directory), str(replay),
                                 str(SOURCE / 'MapLoadedListenerBinding.java'),
                                 str(SOURCE / 'ReflectMethods.java')], capture_output=True, text=True)
        if result.returncode:
            raise AssertionError(result.stderr)

    def replay(self, name):
        subprocess.run(['java', '-cp', str(self.directory), 'ru.natro.navigation.MapLoadedBindingReplay', name],
                       check=True, capture_output=True, text=True)

    def test_reproduces_failure_and_connects_both(self):
        self.replay('reproduces288FailureThenConnectsBothMaps')

    def test_detach_replace_and_independent_maps(self):
        self.replay('detachAndReplaceDoNotLeakCallbacksAcrossMaps')

    def test_native_registration_failure_propagates(self):
        self.replay('failedNativeRegistrationIsNotAcknowledged')

    def test_renderer_uses_the_exercised_binding_for_both_lifecycle_ends(self):
        renderer = (SOURCE / 'HudMapRenderer.java').read_text()
        self.assertIn('MapLoadedListenerBinding.set(map, mapLoadedListener)', renderer)
        self.assertIn('MapLoadedListenerBinding.set(map, null)', renderer)
        self.assertNotIn('"setMapLoadedListener"', renderer)


if __name__ == '__main__':
    unittest.main()
