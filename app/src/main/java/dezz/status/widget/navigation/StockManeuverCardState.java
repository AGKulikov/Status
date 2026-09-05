/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.navigation;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable native-command frame. A present but invalid frame explicitly hides the card. */
public final class StockManeuverCardState {
    public static final StockManeuverCardState LEGACY = new StockManeuverCardState(false);
    public static final StockManeuverCardState HIDDEN = new StockManeuverCardState(true);
    public final boolean enabled, visible, imageVisible, screenSaver;
    public final long generation, revision;
    public final String image, via, distance, nextRoad, auxiliaryKind, auxiliaryText, auxiliaryImage;
    public final List<Lane> lanes, auxiliaryLanes;
    public final List<Sign> signs, followingSigns;

    public static final class Lane {
        public final List<String> secondary;
        public final String highlighted, kind, crop;
        public final int width, height, left, right;
        private Lane(JSONObject value) throws Exception {
            List<String> layers = new ArrayList<>();
            JSONArray array = value.getJSONArray("secondary");
            limit(array);
            for (int i = 0; i < array.length(); i++) layers.add(resource(array.getString(i)));
            secondary = Collections.unmodifiableList(layers);
            highlighted = resource(value.optString("highlighted"));
            kind = resource(value.optString("kind")); crop = resource(value.optString("crop"));
            width = dimension(value.getInt("width"), false);
            height = dimension(value.getInt("height"), false);
            left = dimension(value.getInt("left"), true);
            right = dimension(value.getInt("right"), true);
            if (secondary.isEmpty() && highlighted.isEmpty() && kind.isEmpty()) throw new IllegalArgumentException();
        }
    }
    public static final class Sign {
        public final String kind, text, image;
        public final int background, color;
        private Sign(JSONObject value) throws Exception {
            kind = value.getString("kind"); text = label(value.optString("text"));
            image = resource(value.optString("image"));
            background = value.getInt("bg"); color = value.getInt("color");
            if ((!kind.equals("Icon") && !kind.equals("Road") && !kind.equals("Toponym")
                    && !kind.equals("Exit")) || (text.isEmpty() && image.isEmpty())) throw new IllegalArgumentException();
        }
    }
    private StockManeuverCardState(boolean enabled) {
        this.enabled = enabled; visible = imageVisible = screenSaver = false;
        generation = revision = 0;
        image = via = distance = nextRoad = auxiliaryKind = auxiliaryText = auxiliaryImage = "";
        lanes = auxiliaryLanes = Collections.emptyList();
        signs = followingSigns = Collections.emptyList();
    }
    private StockManeuverCardState(JSONObject source) throws Exception {
        enabled = visible = true;
        generation = source.getLong("generation"); revision = source.getLong("revision");
        if (generation <= 0 || revision <= 0) throw new IllegalArgumentException();
        screenSaver = source.optBoolean("screenSaver");
        JSONObject main = source.getJSONObject("main");
        imageVisible = main.getBoolean("imageVisible");
        image = resource(main.optString("image")); via = label(main.optString("via"));
        distance = (label(main.optString("distance")) + " " + label(main.optString("unit"))).trim();
        nextRoad = label(main.optString("nextRoad"));
        lanes = lanes(main.optJSONArray("lanes"));
        signs = signs(source.optJSONArray("signs"));
        followingSigns = signs(source.optJSONArray("followingSigns"));
        JSONObject aux = source.getJSONObject("auxiliary");
        auxiliaryKind = label(aux.optString("kind"));
        auxiliaryImage = resource(aux.optString("image"));
        auxiliaryText = (label(aux.optString("prefix")) + " " + label(aux.optString("text"))).trim();
        auxiliaryLanes = lanes(aux.optJSONArray("lanes"));
    }
    public boolean hasMain() { return visible && ((imageVisible && !image.isEmpty()) || !lanes.isEmpty()); }
    public boolean hasAuxiliary() { return visible && (!auxiliaryText.isEmpty() || !auxiliaryImage.isEmpty() || !auxiliaryLanes.isEmpty()); }
    public static StockManeuverCardState parse(String raw, long epoch, boolean routeActive) {
        if (raw == null || raw.isEmpty()) return LEGACY;
        if (!routeActive || raw.length() > 32768) return HIDDEN;
        try {
            JSONObject source = new JSONObject(raw);
            if (source.getInt("schema") != 1 || source.getLong("routeEpoch") != epoch
                    || !source.optBoolean("visible")
                    || !"ru.yandex.yandexnavi".equals(source.getString("package"))
                    || source.getLong("versionCode") != 739564630L) return HIDDEN;
            return new StockManeuverCardState(source);
        } catch (Exception invalid) { return HIDDEN; }
    }
    private static List<Lane> lanes(JSONArray source) throws Exception {
        ArrayList<Lane> result = new ArrayList<>();
        if (source != null) {
            limit(source);
            long width = 0;
            for (int i = 0; i < source.length(); i++) {
                Lane value = new Lane(source.getJSONObject(i));
                result.add(value); width += value.width + value.left + value.right;
            }
            if (!result.isEmpty() && width <= 0) throw new IllegalArgumentException();
        }
        return Collections.unmodifiableList(result);
    }
    private static List<Sign> signs(JSONArray source) throws Exception {
        ArrayList<Sign> result = new ArrayList<>();
        if (source != null) {
            limit(source);
            for (int i = 0; i < source.length(); i++) result.add(new Sign(source.getJSONObject(i)));
        }
        return Collections.unmodifiableList(result);
    }
    private static void limit(JSONArray value) { if (value.length() > 16) throw new IllegalArgumentException(); }
    private static String resource(String value) {
        if (!value.isEmpty() && !value.matches("[a-z0-9_]{1,160}")) throw new IllegalArgumentException();
        return value;
    }
    private static String label(String value) {
        if (value.length() > 4096) throw new IllegalArgumentException();
        return value.trim();
    }
    private static int dimension(int value, boolean negative) {
        if (value < (negative ? -4096 : 1) || value > 4096) throw new IllegalArgumentException();
        return value;
    }
}
