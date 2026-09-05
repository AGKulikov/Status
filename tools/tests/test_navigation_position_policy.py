#!/usr/bin/env python3
"""Execute the production vehicle-position selector with sequenced MapKit input samples."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
HARNESS = r'''
package ru.natro.navigation;

public final class PositionReplay {
    private static final double NAN = Double.NaN;
    private final NavigationPositionPolicy policy = new NavigationPositionPolicy();

    private NavigationPositionPolicy.Position sample(String status, long now,
            double liveLat, double liveLon, double liveHeading,
            double routeLat, double routeLon, double routeHeading) {
        return policy.select(true, status, now, liveLat, liveLon, liveHeading,
                routeLat, routeLon, routeHeading);
    }

    private static void point(NavigationPositionPolicy.Position value,
            double lat, double lon, double heading, String source) {
        if (Double.compare(value.latitude, lat) != 0 || Double.compare(value.longitude, lon) != 0
                || Double.compare(value.heading, heading) != 0 || !value.source.equals(source)) {
            throw new AssertionError(value.source + " " + value.latitude + "," + value.longitude
                    + " @ " + value.heading + " expected " + source + " " + lat + "," + lon
                    + " @ " + heading);
        }
    }

    void offRouteSequence() {
        sample("ON_ROUTE", 1000, 55.10, 37.10, 80, 55.11, 37.11, 90);
        for (String status : new String[]{"ROUTE_LOST", "NOT_ON_ROUTE", "UNKNOWN"}) {
            for (int i = 1; i <= 12; i++) {
                double lat = 55.11 + i * .001, lon = 37.11 + i * .002;
                double heading = 90 + i * 5;
                point(sample(status, 1000 + i * 100, lat, lon, heading, 55.11, 37.11, 90),
                        lat, lon, heading, "GUIDANCE_LOCATION");
            }
        }
    }

    void gapCannotSurviveDeparture() {
        sample("ON_ROUTE", 1000, 55, 37, 0, 55.1, 37.1, 90);
        point(sample("ON_ROUTE", 1100, 55, 37, 0, NAN, NAN, NAN),
                55.1, 37.1, 90, "ON_ROUTE_GAP");
        point(sample("ROUTE_LOST", 1200, 55.2, 37.2, 135, NAN, NAN, NAN),
                55.2, 37.2, 135, "GUIDANCE_LOCATION");
        // Returning without a new matched sample must not resurrect the earlier hold cache.
        point(sample("RETURNED_TO_ROUTE", 1300, 55.3, 37.3, 140, NAN, NAN, NAN),
                55.3, 37.3, 140, "GUIDANCE_LOCATION");
    }

    void onRouteParallelStreetDrift() {
        for (String status : new String[]{"ON_ROUTE", "RETURNED_TO_ROUTE", "WAY_POINT_REACHED"}) {
            point(sample(status, 1000, 55.2, 37.2, 270, 55.1, 37.1, 90),
                    55.1, 37.1, 90, "ROUTE_POSITION");
        }
    }

    void returnAndReroute() {
        sample("ON_ROUTE", 1000, 55, 37, 0, 55.1, 37.1, 90);
        point(sample("NOT_ON_ROUTE", 1100, 55.2, 37.2, 120, 55.1, 37.1, 90),
                55.2, 37.2, 120, "GUIDANCE_LOCATION");
        point(sample("RETURNED_TO_ROUTE", 1200, 55.3, 37.3, 125, 55.31, 37.31, 130),
                55.31, 37.31, 130, "ROUTE_POSITION");
        policy.reset(); // Publisher calls this when the route epoch changes.
        point(sample("ON_ROUTE", 1250, 55.4, 37.4, 140, NAN, NAN, NAN),
                55.4, 37.4, 140, "GUIDANCE_LOCATION");
        point(sample("ON_ROUTE", 1300, 55.4, 37.4, 140, 55.41, 37.41, 145),
                55.41, 37.41, 145, "ROUTE_POSITION");
    }

    void noActiveRouteOrUnknownStatus() {
        for (String status : new String[]{"ON_ROUTE", "NOT_ON_ROUTE", "ROUTE_FINISHED"}) {
            point(policy.select(false, status, 1000, 55, 37, 45, 56, 38, 90),
                    55, 37, 45, "GUIDANCE_LOCATION");
        }
        for (String status : new String[]{null, "", "UNKNOWN", "ROUTE_FINISHED", "FUTURE_VALUE"}) {
            point(sample(status, 1000, 55, 37, 45, 56, 38, 90),
                    55, 37, 45, "GUIDANCE_LOCATION");
        }
    }

    void boundedOnRouteGap() {
        sample("ON_ROUTE", 1000, 55, 37, 0, 56, 38, 90);
        point(sample("ON_ROUTE", 3500, 55, 37, 0, NAN, NAN, NAN),
                56, 38, 90, "ON_ROUTE_GAP");
        point(sample("ON_ROUTE", 3501, 55, 37, 0, NAN, NAN, NAN),
                55, 37, 0, "GUIDANCE_LOCATION");
        sample("ON_ROUTE", 4000, 55, 37, 0, 56, 38, 90);
        point(sample("ON_ROUTE", 3999, 55, 37, 0, NAN, NAN, NAN),
                55, 37, 0, "GUIDANCE_LOCATION");
    }

    void invalidMatchingAndIndependentHeading() {
        for (double lat : new double[]{NAN, Double.POSITIVE_INFINITY, -91, 91}) {
            point(sample("ON_ROUTE", 1000, 55, 37, 45, lat, 38, 90),
                    55, 37, 45, "GUIDANCE_LOCATION");
        }
        point(sample("ON_ROUTE", 1000, 55, 37, 45, 56, 38, NAN),
                56, 38, 45, "ROUTE_POSITION");
    }

    void stationaryLocationIsNotInventedMotion() {
        sample("ON_ROUTE", 1000, 55, 37, 0, 56, 38, 90);
        for (int i = 1; i <= 20; i++) {
            point(sample("NOT_ON_ROUTE", 1000 + i * 100, 55, 37, 0, 56, 38, 90),
                    55, 37, 0, "GUIDANCE_LOCATION");
        }
    }

    public static void main(String[] args) throws Exception {
        PositionReplay replay = new PositionReplay();
        PositionReplay.class.getDeclaredMethod(args[0]).invoke(replay);
    }
}
'''


class NavigationPositionReplayTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not shutil.which("java") or not shutil.which("javac"):
            raise RuntimeError("JDK required to execute navigation-position regression tests")
        cls.temp = tempfile.TemporaryDirectory()
        cls.directory = Path(cls.temp.name)
        harness = cls.directory / "PositionReplay.java"
        harness.write_text(HARNESS)
        policy = ROOT / "navigator-mod/src/main/java/ru/natro/navigation/NavigationPositionPolicy.java"
        subprocess.run(["javac", "-d", str(cls.directory), str(policy), str(harness)], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def replay(self, name):
        subprocess.run(["java", "-cp", str(self.directory),
                        "ru.natro.navigation.PositionReplay", name], check=True)


for case in ("offRouteSequence", "gapCannotSurviveDeparture", "onRouteParallelStreetDrift",
             "returnAndReroute", "noActiveRouteOrUnknownStatus", "boundedOnRouteGap",
             "invalidMatchingAndIndependentHeading", "stationaryLocationIsNotInventedMotion"):
    setattr(NavigationPositionReplayTest, "test_" + case,
            lambda self, name=case: self.replay(name))

if __name__ == "__main__":
    unittest.main()
