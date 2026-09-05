#!/usr/bin/env python3
"""Replay distinct HUD/cluster profiles through the production native-style provider."""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SOURCES = {
    "android/graphics/PointF.java": '''package android.graphics;
public final class PointF { public float x,y; public PointF(float x,float y){this.x=x;this.y=y;} }
''',
    "ru/natro/navigation/VisibilityReplay.java": '''package ru.natro.navigation;
import java.util.*;
public final class VisibilityReplay {
    public interface Provider { boolean provideStyle(Properties properties, boolean night, float scale, Object style); }
    public static final class Properties {
        final boolean onRoute;
        final List<String> tags;
        Properties(boolean onRoute, String... tags) { this.onRoute=onRoute;this.tags=Arrays.asList(tags); }
        public List<String> getTags() { return tags; }
        public boolean isOnRoute() { return onRoute; }
    }
    public static final class Stock implements Provider {
        int calls;
        public boolean provideStyle(Properties p, boolean n, float s, Object style) { calls++;return true; }
    }
    static Map<String,String> modes(String mode) {
        Map<String,String> map=new HashMap<>();map.put("SPEED_CONTROL",mode);return map;
    }
    static ScaledRoadEventStyleProvider create(Stock stock, String mode, boolean active) {
        ScaledRoadEventStyleProvider p=new ScaledRoadEventStyleProvider(stock,Provider.class);
        p.setVisibility(modes(mode),active);return p;
    }
    static boolean visible(ScaledRoadEventStyleProvider p, boolean onRoute) {
        return ((Provider)p.proxy()).provideStyle(new Properties(onRoute,"SPEED_CONTROL"),false,1f,null);
    }
    static void check(boolean value) { if(!value)throw new AssertionError(); }
    static void adjacentRoadIsRejectedBeforeStockRendering() {
        Stock stock=new Stock();ScaledRoadEventStyleProvider p=create(stock,"ROUTE_ONLY",true);
        check(!visible(p,false));check(stock.calls==0);check(visible(p,true));check(stock.calls==1);
    }
    static void twoProfilesAndLiveEditsAreIndependent() {
        ScaledRoadEventStyleProvider hud=create(new Stock(),"HIDDEN",true);
        ScaledRoadEventStyleProvider dim=create(new Stock(),"ROUTE_ONLY",true);
        check(!visible(hud,true));check(!visible(dim,false));check(visible(dim,true));
        check(dim.setVisibility(modes("ALWAYS"),true));check(visible(dim,false));
        check(!visible(hud,true));check(!dim.setVisibility(modes("ALWAYS"),true));
    }
    static void completedRouteRejectsStaleNativeMembership() {
        ScaledRoadEventStyleProvider p=create(new Stock(),"ROUTE_ONLY",true);
        check(visible(p,true));p.setVisibility(modes("ROUTE_ONLY"),false);check(!visible(p,true));
    }
    static void alwaysWorksWithoutRouteAndUnknownTagsStayHidden() {
        ScaledRoadEventStyleProvider p=create(new Stock(),"ALWAYS",false);check(visible(p,false));
        check(!((Provider)p.proxy()).provideStyle(new Properties(true,"UNKNOWN"),false,1f,null));
    }
    static void profileIsSnapshotAndMixedTagsUseVisibleCategory() {
        Map<String,String> map=modes("HIDDEN");map.put("ACCIDENT","ALWAYS");
        RoadEventVisibility p=new RoadEventVisibility(map,true);map.put("SPEED_CONTROL","ALWAYS");
        check(!p.allows(Arrays.asList("SPEED_CONTROL"),true));
        check(p.allows(Arrays.asList("SPEED_CONTROL","ACCIDENT"),false));check(!p.allows(null,true));
    }
    public static void main(String[] args)throws Exception { VisibilityReplay.class.getDeclaredMethod(args[0]).invoke(null); }
}
''',
}


class NavigationEventVisibilityTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.directory = Path(cls.temp.name)
        files = []
        for name, source in SOURCES.items():
            path = cls.directory / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(source)
            files.append(str(path))
        production = ROOT / "navigator-mod/src/main/java/ru/natro/navigation"
        files += [str(production / name) for name in (
            "RoadEventVisibility.java", "ScaledRoadEventStyleProvider.java", "ReflectMethods.java")]
        subprocess.run(["javac", "-d", str(cls.directory), *files], check=True, capture_output=True)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def replay(self, case):
        subprocess.run(["java", "-cp", str(self.directory),
                        "ru.natro.navigation.VisibilityReplay", case], check=True)

    def test_adjacent_road(self): self.replay("adjacentRoadIsRejectedBeforeStockRendering")
    def test_profiles(self): self.replay("twoProfilesAndLiveEditsAreIndependent")
    def test_end_route(self): self.replay("completedRouteRejectsStaleNativeMembership")
    def test_free_drive(self): self.replay("alwaysWorksWithoutRouteAndUnknownTagsStayHidden")
    def test_snapshot(self): self.replay("profileIsSnapshotAndMixedTagsUseVisibleCategory")


if __name__ == "__main__":
    unittest.main()
