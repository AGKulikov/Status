/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Versioned, exportable HUD layout and presentation settings. */
public final class HudPanelConfig {
    /** Schema 6 adds a direct-Surface map; schema 4's frame-copy NAV_MAP stays discarded. */
    public static final int SCHEMA_VERSION = 6;
    /** Safety-only document limit; the editor imposes no practical backdrop count limit. */
    public static final int MAX_ELEMENTS = 4_096;
    public static final int MAX_JSON_CHARS = 1_048_576;

    @NonNull public String displayUniqueId = "";
    /** Verified ECARX hardware constant: local:2 is the composite display containing the HUD. */
    public int displayId = HudViewportPolicy.VERIFIED_DISPLAY_ID;
    @NonNull public String displayName = "";
    public int displayWidth;
    public int displayHeight;

    /**
     * Layout coordinates inside the fixed physical HUD plane. Kept in the document for
     * forward-compatible export/import, but normalized to the verified mHUD dimensions so an
     * imported layout cannot escape onto another part of the composite display.
     */
    public int logicalWidth = HudViewportPolicy.SAFE_WIDTH;
    public int logicalHeight = HudViewportPolicy.SAFE_HEIGHT;
    public int contentWidth = HudViewportPolicy.SAFE_WIDTH;
    public int contentHeight = HudViewportPolicy.SAFE_HEIGHT;
    public int offsetX;
    public int offsetY;
    public int gridColumns = 44;
    public int gridRows = 18;
    public boolean showGrid = true;
    public boolean freeMovement;
    /** Retained in exported schema for compatibility; the live panel is always transparent. */
    @NonNull public String backgroundMode = "TRANSPARENT";
    public boolean snowMode;
    public int globalBrightness = 100;
    @NonNull public String globalTextColor = "#FFFFFFFF";
    @NonNull public String globalUnitColor = "#CCFFFFFF";
    public boolean syncElementColors;
    @NonNull public String customFontUri = "";
    public int globalFontWeight = 600;
    public int navigationDisplayThresholdMeters = 1_500;
    public int navigationHideDelaySeconds = 8;
    @NonNull public final List<HudElementConfig> elements = new ArrayList<>();

    @NonNull
    public static HudPanelConfig defaults() {
        HudPanelConfig out = new HudPanelConfig();
        out.elements.add(positioned(HudElementType.CLOCK, 1, 0, 0, 7, 3, 0));
        out.elements.add(positioned(HudElementType.NAV_MANEUVER_ARROW,
                1, 0, 3, 10, 12, 1));
        out.elements.add(positioned(HudElementType.NAV_MANEUVER_TITLE,
                1, 10, 2, 17, 4, 2));
        out.elements.add(positioned(HudElementType.NAV_TURN_DISTANCE,
                1, 10, 7, 10, 3, 3));
        out.elements.add(positioned(HudElementType.NAV_LANES,
                1, 20, 7, 14, 5, 4));
        out.elements.add(positioned(HudElementType.NAV_SPEED_LIMIT,
                1, 36, 0, 6, 6, 5));
        out.elements.add(positioned(HudElementType.NAV_TRAFFIC_LIGHTS,
                1, 35, 7, 8, 10, 6));
        out.elements.add(positioned(HudElementType.CAR_SPEED,
                1, 10, 12, 7, 4, 7));
        out.elements.add(positioned(HudElementType.GEAR,
                1, 17, 12, 5, 4, 8));
        out.elements.add(positioned(HudElementType.MEDIA_COMBINED,
                1, 22, 13, 12, 3, 9));
        out.normalize();
        return out;
    }

    @NonNull
    private static HudElementConfig positioned(HudElementType type, int ordinal,
                                               int x, int y, int width, int height, int z) {
        HudElementConfig item = HudElementConfig.create(type, ordinal, 44, 18);
        item.x = x;
        item.y = y;
        item.width = width;
        item.height = height;
        item.zIndex = z;
        item.normalize(44, 18);
        return item;
    }

    public void normalize() {
        // Hardware safety boundary: this is deliberately not user-adjustable.
        logicalWidth = HudViewportPolicy.SAFE_WIDTH;
        logicalHeight = HudViewportPolicy.SAFE_HEIGHT;
        contentWidth = HudViewportPolicy.SAFE_WIDTH;
        contentHeight = HudViewportPolicy.SAFE_HEIGHT;
        offsetX = 0;
        offsetY = 0;
        gridColumns = clamp(gridColumns, 4, 200);
        gridRows = clamp(gridRows, 2, 100);
        // Display 2 is a vehicle constant in the supplied ECARX dump. Never allow an imported or
        // hand-edited layout to target the central, passenger or cluster display accidentally.
        if (displayId != HudViewportPolicy.VERIFIED_DISPLAY_ID) {
            displayUniqueId = "";
            displayName = "";
            displayWidth = 0;
            displayHeight = 0;
        }
        displayId = HudViewportPolicy.VERIFIED_DISPLAY_ID;
        displayWidth = clamp(displayWidth, 0, 16_384);
        displayHeight = clamp(displayHeight, 0, 16_384);
        displayUniqueId = bounded(displayUniqueId, 512);
        displayName = bounded(displayName, 256);
        // Imported BLACK/DIM layouts must not be able to restore an opaque HUD substrate.
        backgroundMode = "TRANSPARENT";
        globalBrightness = clamp(globalBrightness, 0, 100);
        globalTextColor = color(globalTextColor, "#FFFFFFFF");
        globalUnitColor = color(globalUnitColor, "#CCFFFFFF");
        customFontUri = bounded(customFontUri, 4_096);
        globalFontWeight = clamp(globalFontWeight, 100, 900);
        navigationDisplayThresholdMeters =
                clamp(navigationDisplayThresholdMeters, 0, 100_000);
        navigationHideDelaySeconds = clamp(navigationHideDelaySeconds, 0, 600);

        Set<String> ids = new HashSet<>();
        boolean directMapFound = false;
        for (int index = elements.size() - 1; index >= 0; index--) {
            HudElementConfig item = elements.get(index);
            try {
                item.normalize(gridColumns, gridRows);
                if (!ids.add(item.id)) {
                    elements.remove(index);
                } else if (item.type == HudElementType.NAV_MAP) {
                    // One physical HUD surface has one independent MapWindow. Multiple map
                    // elements would fight over the same producer and make geometry ambiguous.
                    if (directMapFound) elements.remove(index);
                    else directMapFound = true;
                }
            } catch (RuntimeException invalid) {
                elements.remove(index);
            }
        }
        if (elements.size() > MAX_ELEMENTS) {
            elements.subList(MAX_ELEMENTS, elements.size()).clear();
        }
        normalizeHorizontalGroups(ids);
    }

    /** True when Natro can draw something useful without the separate Navigator map surface. */
    public boolean hasStandaloneDrawableElement() {
        for (HudElementConfig item : elements) {
            if (item.enabled
                    && item.type != HudElementType.HORIZONTAL_GROUP
                    && item.type != HudElementType.NAV_MAP) {
                return true;
            }
        }
        return false;
    }

    /** Keeps group membership deterministic and prevents nested or duplicate ownership. */
    private void normalizeHorizontalGroups(@NonNull Set<String> ids) {
        Set<String> claimed = new HashSet<>();
        for (HudElementConfig group : elements) {
            if (group.type != HudElementType.HORIZONTAL_GROUP) continue;
            List<String> valid = new ArrayList<>();
            for (String id : HudHorizontalGroup.memberIds(group)) {
                HudElementConfig member = find(id);
                if (!ids.contains(id) || member == null
                        || member.type == HudElementType.HORIZONTAL_GROUP
                        || member.type == HudElementType.BACKDROP
                        || member.type == HudElementType.NAV_MAP
                        || !claimed.add(id)) {
                    continue;
                }
                valid.add(id);
            }
            HudHorizontalGroup.setMemberIds(group, valid);
        }
    }

    private HudElementConfig find(@NonNull String id) {
        for (HudElementConfig item : elements) {
            if (id.equals(item.id)) return item;
        }
        return null;
    }

    @NonNull
    public List<HudElementConfig> drawingOrder() {
        ArrayList<HudElementConfig> result = new ArrayList<>(elements);
        result.sort(Comparator
                .comparingInt((HudElementConfig item) ->
                        item.type == HudElementType.BACKDROP ? 0 : 1)
                .thenComparingInt(item -> item.zIndex));
        return result;
    }

    @NonNull
    public JSONObject toJson() throws JSONException {
        normalize();
        JSONObject out = new JSONObject();
        out.put("schema", SCHEMA_VERSION);
        out.put("displayUniqueId", displayUniqueId).put("displayId", displayId);
        out.put("displayName", displayName);
        out.put("displayWidth", displayWidth).put("displayHeight", displayHeight);
        out.put("hardwareProfile", "MHU_HUD_728X190_Y720");
        out.put("logicalWidth", logicalWidth).put("logicalHeight", logicalHeight);
        out.put("contentWidth", contentWidth).put("contentHeight", contentHeight);
        out.put("offsetX", offsetX).put("offsetY", offsetY);
        out.put("gridColumns", gridColumns).put("gridRows", gridRows);
        out.put("showGrid", showGrid).put("freeMovement", freeMovement);
        out.put("backgroundMode", backgroundMode);
        out.put("snowMode", snowMode);
        out.put("globalBrightness", globalBrightness);
        out.put("globalTextColor", globalTextColor).put("globalUnitColor", globalUnitColor);
        out.put("syncElementColors", syncElementColors);
        out.put("customFontUri", customFontUri).put("globalFontWeight", globalFontWeight);
        out.put("navigationDisplayThresholdMeters", navigationDisplayThresholdMeters);
        out.put("navigationHideDelaySeconds", navigationHideDelaySeconds);
        JSONArray items = new JSONArray();
        for (HudElementConfig item : elements) items.put(item.toJson());
        out.put("elements", items);
        return out;
    }

    @NonNull
    public static HudPanelConfig fromJson(@NonNull String raw) {
        String json = raw.trim();
        if (json.isEmpty()) return defaults();
        if (json.length() > MAX_JSON_CHARS) {
            throw new IllegalArgumentException("HUD configuration is too large");
        }
        try {
            JSONObject source = new JSONObject(json);
            int schema = source.optInt("schema", 1);
            if (schema <= 0 || schema > SCHEMA_VERSION) {
                throw new IllegalArgumentException("Unsupported HUD schema " + schema);
            }
            HudPanelConfig out = new HudPanelConfig();
            out.displayUniqueId = source.optString("displayUniqueId", "");
            out.displayId = source.optInt(
                    "displayId", HudViewportPolicy.VERIFIED_DISPLAY_ID);
            out.displayName = source.optString("displayName", "");
            out.displayWidth = source.optInt("displayWidth", 0);
            out.displayHeight = source.optInt("displayHeight", 0);
            out.logicalWidth = source.optInt("logicalWidth", HudViewportPolicy.SAFE_WIDTH);
            out.logicalHeight = source.optInt("logicalHeight", HudViewportPolicy.SAFE_HEIGHT);
            out.contentWidth = source.optInt("contentWidth", out.logicalWidth);
            out.contentHeight = source.optInt("contentHeight", out.logicalHeight);
            out.offsetX = source.optInt("offsetX", 0);
            out.offsetY = source.optInt("offsetY", 0);
            out.gridColumns = source.optInt("gridColumns", 44);
            out.gridRows = source.optInt("gridRows", 18);
            out.showGrid = source.optBoolean("showGrid", true);
            out.freeMovement = source.optBoolean("freeMovement", false);
            out.backgroundMode = source.optString("backgroundMode", "TRANSPARENT");
            out.snowMode = source.optBoolean("snowMode", false);
            out.globalBrightness = source.optInt("globalBrightness", 100);
            out.globalTextColor = source.optString("globalTextColor", "#FFFFFFFF");
            out.globalUnitColor = source.optString("globalUnitColor", "#CCFFFFFF");
            out.syncElementColors = source.optBoolean("syncElementColors", false);
            out.customFontUri = source.optString("customFontUri", "");
            out.globalFontWeight = source.optInt("globalFontWeight", 600);
            out.navigationDisplayThresholdMeters =
                    source.optInt("navigationDisplayThresholdMeters", 1_500);
            out.navigationHideDelaySeconds =
                    source.optInt("navigationHideDelaySeconds", 8);
            out.normalize();
            JSONArray items = source.optJSONArray("elements");
            if (items != null) {
                for (int index = 0; index < items.length() && index < MAX_ELEMENTS; index++) {
                    JSONObject item = items.optJSONObject(index);
                    if (item == null) continue;
                    try {
                        if ("NAV_MAP".equalsIgnoreCase(item.optString("type", ""))) {
                            JSONObject options = item.optJSONObject("options");
                            if (schema < SCHEMA_VERSION || options == null
                                    || !HudElementConfig.DIRECT_MAP_RENDERER.equals(
                                    options.optString("renderer", ""))) {
                                continue;
                            }
                        }
                        HudElementConfig decoded = HudElementConfig.fromJson(
                                item, out.gridColumns, out.gridRows);
                        out.elements.add(decoded);
                    } catch (RuntimeException ignored) {
                        // Preserve every valid element if one imported future/invalid item exists.
                    }
                }
            } else {
                // Early HUD documents did not own an elements array. Treating that as an
                // intentional empty layout produced an accepted all-black mask with no clock.
                out.elements.addAll(defaults().elements);
            }
            out.normalize();
            return out;
        } catch (JSONException error) {
            throw new IllegalArgumentException("Invalid HUD configuration", error);
        }
    }

    @NonNull
    private static String color(String raw, String fallback) {
        String value = bounded(raw, 32);
        return value.isEmpty() ? fallback : value;
    }

    @NonNull
    private static String bounded(String raw, int maximum) {
        String value = raw == null ? "" : raw.trim();
        if (value.length() > maximum || value.indexOf('\u0000') >= 0) return "";
        return value;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
