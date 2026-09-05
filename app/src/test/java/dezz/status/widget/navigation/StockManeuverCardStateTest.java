/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.navigation;
import static org.junit.Assert.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import dezz.status.widget.hud.HudElementType;
import dezz.status.widget.hud.HudNavigationState;

public final class StockManeuverCardStateTest {
    private JSONObject frame() throws Exception {
        return new JSONObject().put("schema", 1).put("routeEpoch", 3).put("visible", true)
                .put("package", "ru.yandex.yandexnavi").put("versionCode", 739564630)
                .put("generation", 5).put("revision", 11)
                .put("main", new JSONObject().put("image", "context_ra_turn_right")
                        .put("imageVisible", true).put("distance", "600").put("unit", "м")
                        .put("nextRoad", "Еринское ш."))
                .put("auxiliary", new JSONObject());
    }
    private StockManeuverCardState parse(JSONObject source) { return StockManeuverCardState.parse(source.toString(), 3, true); }
    @Test public void originalResourceAndExactDistanceSurvive() throws Exception {
        StockManeuverCardState state = parse(frame());
        assertTrue(state.hasMain()); assertEquals("context_ra_turn_right", state.image);
        assertEquals("600 м", state.distance); assertEquals("Еринское ш.", state.nextRoad);
    }
    @Test public void hiddenUnknownAndWrongEpochNeverBecomeLegacy() throws Exception {
        for (JSONObject value : new JSONObject[]{frame().put("visible", false), frame().put("routeEpoch", 4),
                frame().put("schema", 2), frame().put("versionCode", 1), frame().put("package", "other")}) {
            assertTrue(parse(value).enabled); assertFalse(parse(value).hasMain());
        }
        assertFalse(StockManeuverCardState.parse(frame().toString(), 3, false).visible);
        assertTrue(StockManeuverCardState.parse("broken", 3, true).enabled);
        assertFalse(StockManeuverCardState.parse("", 3, true).enabled);
    }
    @Test public void callbackFrameIsImmutableAndDoesNotInventMissingRows() throws Exception {
        JSONObject frame = frame(); StockManeuverCardState before = parse(frame);
        frame.getJSONObject("main").put("image", "context_ra_turn_left").put("nextRoad", "");
        StockManeuverCardState after = parse(frame);
        assertEquals("context_ra_turn_right", before.image); assertEquals("", after.nextRoad);
        assertFalse(after.hasAuxiliary()); assertTrue(after.signs.isEmpty());
    }
    @Test public void layersMasksAndOriginalContainerOverlapArePreserved() throws Exception {
        JSONObject frame = frame();
        frame.getJSONObject("main").put("imageVisible", false).put("lanes", new JSONArray().put(
                new JSONObject().put("secondary", new JSONArray().put("lane_secondary"))
                        .put("highlighted", "lane_highlight").put("kind", "lane_bus")
                        .put("crop", "lane_crop").put("width", 32).put("height", 48)
                        .put("left", -4).put("right", 8)));
        StockManeuverCardState state = parse(frame);
        assertTrue(state.hasMain()); assertFalse(state.imageVisible);
        assertEquals("lane_crop", state.lanes.get(0).crop); assertEquals(-4, state.lanes.get(0).left);
        assertEquals(8, state.lanes.get(0).right);
        try { state.lanes.clear(); fail(); } catch (UnsupportedOperationException expected) {}
    }
    @Test public void followingLanePrefixIsOwnedByTheSameAuxiliaryFrame() throws Exception {
        JSONObject frame = frame();
        frame.put("auxiliary", new JSONObject().put("kind", "NEXT_UPCOMING_LANES")
                .put("prefix", "Далее").put("lanes", new JSONArray().put(new JSONObject()
                        .put("secondary", new JSONArray()).put("highlighted", "lane_highlight")
                        .put("width", 32).put("height", 48).put("left", 0).put("right", 0))));
        StockManeuverCardState before = parse(frame);
        assertEquals("Далее", before.auxiliaryText);
        assertEquals(1, before.auxiliaryLanes.size());
        frame.put("auxiliary", new JSONObject());
        StockManeuverCardState cleared = parse(frame);
        assertEquals("", cleared.auxiliaryText);
        assertTrue(cleared.auxiliaryLanes.isEmpty());
        assertFalse(cleared.hasAuxiliary());
        assertEquals("Далее", before.auxiliaryText);
    }
    @Test public void oversizeAndMalformedResourcesHideWholeCard() throws Exception {
        JSONObject frame = frame(); frame.getJSONObject("main").put("image", "../other");
        assertFalse(parse(frame).visible);
        frame = frame(); JSONArray signs = new JSONArray();
        for (int i = 0; i < 17; i++) signs.put(new JSONObject().put("kind", "Road")
                .put("text", "M2").put("bg", -1).put("color", -1));
        assertFalse(parse(frame.put("signs", signs)).visible);
    }
    @Test public void commandSnapshotRoundTripDrivesHudWithoutBitmapAndHidesAtomically() throws Exception {
        JSONObject root = new JSONObject().put("schema", 1).put("sequence", 1)
                .put("routeEpoch", 3).put("routeActive", true).put("sourceTimestampMs", 1000)
                .put("maneuverType", "LEFT").put("maneuverDistanceMeters", 12)
                .put("maneuverTitle", "stale semantic title").put("maneuverCardJson", frame().toString());
        NavigationSnapshotV2 snapshot = NavigationSnapshotV2.fromJson(root.toString());
        assertEquals(snapshot.maneuverCardJson, NavigationSnapshotV2.fromJson(snapshot.toJson().toString()).maneuverCardJson);
        HudNavigationState state = HudNavigationState.fromBridge(snapshot, null);
        assertTrue(state.hasDataFor(HudElementType.NAV_COMBINED)); assertNull(state.maneuverImage);
        assertEquals("600 м", state.turnDistance);
        root.put("maneuverCardJson", frame().put("visible", false).toString());
        state = HudNavigationState.fromBridge(NavigationSnapshotV2.fromJson(root.toString()), null, state);
        assertFalse(state.hasDataFor(HudElementType.NAV_COMBINED)); assertEquals("", state.turnDistance);
    }
}
