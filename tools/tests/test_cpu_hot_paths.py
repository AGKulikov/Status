#!/usr/bin/env python3
"""Static regressions for CPU reductions that must not alter visual cadence or quality."""

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


class CpuHotPathContractTest(unittest.TestCase):
    def test_maneuver_measurement_is_content_bound(self):
        canvas = read("app/src/main/java/dezz/status/widget/hud/HudCanvasView.java")
        cluster = read(
            "app/src/main/java/dezz/status/widget/instrument/InstrumentClusterView.java"
        )
        sizer = read("app/src/main/java/dezz/status/widget/hud/ManeuverCardAutoSizer.java")
        self.assertIn("maneuverAutoSizeCache", canvas)
        self.assertIn("ManeuverAutoSizeEntry cached", canvas)
        self.assertIn("maneuverCardContentFingerprint(item, nav)", canvas)
        self.assertIn("measurementOptionsFingerprint", canvas)
        self.assertIn("measurementOptionsFingerprint", cluster)
        self.assertIn("public static long measurementOptionsFingerprint", sizer)
        self.assertIn("cached.matches(contentFingerprint, maximum", canvas)
        self.assertNotIn("this.navigation == navigation", canvas)
        self.assertIn("if (singleLine)", canvas)
        self.assertIn("canvas.drawText(displayed", canvas)
        self.assertNotIn('value.split("\\\\r?\\\\n"', sizer)
        self.assertIn("maneuverAutoSizeMatches", cluster)
        self.assertIn("rememberManeuverAutoSize", cluster)
        self.assertIn("maneuverAutoSizeContentFingerprint", cluster)
        self.assertNotIn("maneuverAutoSizeSource == source", cluster)

    def test_unchanged_route_auxiliary_data_has_fast_paths(self):
        alternatives = read(
            "navigator-mod/src/main/java/ru/natro/navigation/AlternativeRouteMapLayer.java"
        )
        events = read(
            "navigator-mod/src/main/java/ru/natro/navigation/RoadEventRouteSynchronizer.java"
        )
        self.assertLess(
            alternatives.index("input == lastInputAlternatives"),
            alternatives.index("new ArrayList<>(input)"),
        )
        self.assertIn("RETAINED_INPUT_RESCAN_MS = 500L", alternatives)
        self.assertIn("now - lastReflectedScanUptimeMs", alternatives)
        self.assertIn("marker.preparedText", alternatives)
        self.assertIn("marker.footprints", alternatives)
        self.assertLess(
            events.index("nextFingerprint == eventsFingerprint"),
            events.index("new ArrayList<>(nextDrivingEvents)"),
        )

    def test_placement_math_does_not_allocate_per_clipped_segment(self):
        placement = read(
            "navigator-mod/src/main/java/ru/natro/navigation/MapOverlayPlacementCoordinator.java"
        )
        self.assertIn("RIGHT_FIRST_CANDIDATES", placement)
        self.assertIn("LEFT_FIRST_CANDIDATES", placement)
        clipping = placement[
            placement.index("static double clippedSegmentLength"):
            placement.index("private static double overlapArea")
        ]
        self.assertNotIn("new double", clipping)
        self.assertNotIn("double[]", clipping)

    def test_equal_telemetry_and_idle_trip2_do_not_wake_rendering(self):
        runtime = read("app/src/main/java/dezz/status/widget/hud/HudRuntimeData.java")
        trip = read("app/src/geely/java/dezz/status/widget/car/EcarxTrip2Access.java")
        self.assertIn("sameTelemetryContent(previous, value)", runtime)
        self.assertIn("ensureCallbackForDemand", trip)
        self.assertIn("suspendCallbackWithoutDemand", trip)
        self.assertIn("listeners.isEmpty()", trip)

    def test_quality_and_cadence_settings_are_not_reduced(self):
        renderer = read(
            "navigator-mod/src/main/java/ru/natro/navigation/HudMapRenderer.java"
        )
        workflow = read(".github/workflows/build-natro-2.8.6.yml")
        self.assertIn("applyMaximumFps", renderer)
        self.assertIn("profile.maximumFps", renderer)
        self.assertIn("testGeelyDebugUnitTest assembleGeelyRelease", workflow)
        self.assertIn("VERSION_NAME: '2.8.6'", workflow)
        self.assertIn("VERSION_CODE: '208021319'", workflow)


if __name__ == "__main__":
    unittest.main()
