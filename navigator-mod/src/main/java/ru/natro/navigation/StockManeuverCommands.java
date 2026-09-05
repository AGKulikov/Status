/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import android.app.Activity;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Passive adapter for the pinned original ContextManeuverView. No presenter subscription. */
public final class StockManeuverCommands {
    private static final String OWNER = "ru.yandex.yandexnavi.ui.guidance.maneuver.ContextManeuverView";
    private static final String UI = "ru.yandex.yandexnavi.ui.guidance.context.";
    private static final WeakHashMap<View, State> STATES = new WeakHashMap<>();
    private static long generation, revision, epoch;
    private static Runnable changed;
    private StockManeuverCommands() {}

    private static final class State {
        final long generation = ++StockManeuverCommands.generation;
        final WeakReference<Object> presenter;
        long revision;
        boolean valid = true, maneuverReceived;
        JSONObject maneuver = new JSONObject(), auxiliary = new JSONObject();
        JSONArray lanes = new JSONArray(), signs = new JSONArray(), followingSigns = new JSONArray();
        State(Object presenter) { this.presenter = new WeakReference<>(presenter); }
    }

    static void listen(Runnable listener) { changed = listener; }
    static void reset(long routeEpoch) { epoch = routeEpoch; STATES.clear(); }

    private static State state(Object owner) throws Exception {
        if (Looper.myLooper() != Looper.getMainLooper() || !(owner instanceof View)
                || !OWNER.equals(owner.getClass().getName())) return null;
        Object presenter = call(owner, "getPresenter");
        if (presenter == null) { STATES.remove(owner); return null; }
        View view = (View) owner;
        State value = STATES.get(view);
        if (value == null || value.presenter.get() != presenter) {
            value = new State(presenter);
            STATES.put(view, value);
        }
        value.revision = ++revision;
        return value;
    }

    /** Hook exceptions must never escape into Navigator's original command handler. */
    private interface Read { void run(State value) throws Exception; }
    private static void observe(Object owner, Read read) {
        try {
            State value = state(owner);
            if (value != null) {
                try { read.run(value); } catch (Throwable unavailable) { value.valid = false; }
            }
        } catch (Throwable ignored) {
            STATES.remove(owner);
        } finally {
            try { if (changed != null) changed.run(); } catch (Throwable ignored) {}
        }
    }

    public static void onChanged(Object owner) { observe(owner, value -> {}); }
    public static void onLanes(Object owner, Object items) {
        observe(owner, value -> value.lanes = lanes(items));
    }
    public static void onSigns(Object owner, Object items) {
        observe(owner, value -> value.signs = signs((View) owner, items));
    }
    public static void onFollowingSigns(Object owner, Object items) {
        observe(owner, value -> value.followingSigns = signs((View) owner, items));
    }
    public static void onManeuver(Object owner, Object description, String distance,
                                  String unit, Object auxiliary) {
        observe(owner, value -> {
            JSONObject maneuver = new JSONObject().put("distance", bounded(distance))
                    .put("unit", bounded(unit));
            Object regular = call(description, "getRegularManeuver");
            Object via = call(description, "getViaPointManeuver");
            if (regular != null) {
                maneuver.put("image", resource(call(regular, "getImageId")))
                        .put("nextRoad", bounded(call(regular, "getNextRoadName")));
            } else if (via != null) {
                // Exact resource used by the reviewed original via-point handler.
                maneuver.put("image", "context_ra_via")
                        .put("via", bounded(call(via, "getViaPointNumber")));
            } else throw new IllegalArgumentException("Unknown maneuver description");
            JSONObject next = auxiliary(auxiliary);
            value.maneuver = maneuver;
            value.auxiliary = next;
            value.maneuverReceived = true;
        });
    }

    /** One route-epoch snapshot; unavailable command data is explicit, never legacy fallback. */
    static String snapshot(Activity activity, long routeEpoch, boolean active) {
        try {
            if (!active || routeEpoch != epoch || activity == null || activity.isFinishing()
                    || activity.isDestroyed()) return hidden(routeEpoch);
            View root = activity.getWindow().getDecorView();
            View owner = null;
            State selected = null;
            for (Map.Entry<View, State> entry : STATES.entrySet()) {
                View view = entry.getKey();
                State value = entry.getValue();
                if (view != null && belongs(view, root)
                        && value.presenter.get() == call(view, "getPresenter")
                        && (selected == null || value.revision > selected.revision)) {
                    owner = view; selected = value;
                }
            }
            if (selected == null || !selected.valid || !selected.maneuverReceived
                    || !Boolean.TRUE.equals(field(owner, "isViewContentVisible"))
                    || !Boolean.TRUE.equals(call(owner, "getCanBeVisible"))
                    || owner.getVisibility() != View.VISIBLE) return hidden(routeEpoch);
            JSONObject result = new JSONObject().put("schema", 1).put("routeEpoch", epoch)
                    .put("generation", selected.generation).put("revision", selected.revision)
                    .put("visible", true).put("package", "ru.yandex.yandexnavi")
                    .put("versionCode", 739564630)
                    .put("mode", bounded(field(owner, "viewMode")))
                    .put("scale", field(owner, "viewScale"))
                    .put("screenSaver", field(owner, "screenSaverMode"))
                    .put("style", bounded(call(owner, "getStyle")))
                    .put("maxLines", call(owner, "getMaxLines"))
                    .put("nextStreetCanBeLarge", call(owner, "getNextStreetCanBeLarge"))
                    .put("directionSignRedesigned", call(owner, "isDirectionSignRedisigned"));
            JSONObject main = new JSONObject(selected.maneuver.toString());
            main.put("imageVisible", visible(owner, "image_maneuverballoon_maneuver"))
                    .put("via", text(owner, "text_via_point_number"))
                    .put("distance", text(owner, "text_maneuverballoon_distance"))
                    .put("unit", text(owner, "text_maneuverballoon_metrics"))
                    .put("nextRoad", text(owner, "text_nextstreet"))
                    .put("lanes", geometry(owner, "lane_signs_container", selected.lanes));
            result.put("main", main)
                    .put("signs", visible(owner, "roadsign_container") ? selected.signs : new JSONArray())
                    .put("followingSigns", visible(owner, "next_upcoming_roadsign_container")
                            ? selected.followingSigns : new JSONArray());
            JSONObject aux = new JSONObject(selected.auxiliary.toString());
            String kind = aux.optString("kind");
            boolean upcoming = kind.startsWith("NEXT_UPCOMING");
            if (!visible(owner, upcoming ? "next_upcoming_group" : "under_balloon")) {
                aux = new JSONObject();
            } else if ("NEXT_UPCOMING_LANES".equals(kind)) {
                aux.put("lanes", geometry(owner, "next_upcoming_lane_signs_container", aux.getJSONArray("lanes")));
            } else if ("EXIT_NUMBER".equals(kind) || "TURN_NUMBER".equals(kind)) {
                aux.put("text", text(owner, "exit_number_text"));
            }
            if (upcoming && aux.length() > 0) aux.put("prefix", text(owner, "next_upcoming_then_text"));
            result.put("auxiliary", aux);
            String json = result.toString();
            return json.length() <= 32768 ? json : hidden(routeEpoch);
        } catch (Throwable unavailable) { return hidden(routeEpoch); }
    }

    private static String hidden(long routeEpoch) {
        return "{\"schema\":1,\"visible\":false,\"routeEpoch\":" + routeEpoch + "}";
    }
    private static JSONObject auxiliary(Object source) throws Exception {
        JSONObject result = new JSONObject();
        if (source == null) return result;
        String[] getters = {"getNextUpcomingManeuver", "getNextUpcomingLaneSigns",
                "getNextManeuver", "getExitNumberInfo", "getTurnNumberInfo"};
        String[] kinds = {"NEXT_UPCOMING_MANEUVER", "NEXT_UPCOMING_LANES",
                "NEXT_MANEUVER", "EXIT_NUMBER", "TURN_NUMBER"};
        for (int i = 0; i < getters.length; i++) {
            Object value = call(source, getters[i]);
            if (value == null) continue;
            result.put("kind", kinds[i]);
            if (i == 0 || i == 2) result.put("image", resource(call(value, "getImageId")));
            if (i == 0) result.put("text", bounded(call(value, "getDistanceWithUnits")));
            if (i == 1) result.put("lanes", lanes(call(value, "getLaneItems")));
            if (i == 2) result.put("text", (bounded(call(value, "getDistance")) + " "
                    + bounded(call(value, "getUnit"))).trim());
            // Number wording is read from the original localized TextView after the setter.
            return result;
        }
        return result;
    }
    private static JSONArray lanes(Object raw) throws Exception {
        JSONArray result = new JSONArray();
        if (raw == null) return result;
        List<?> items = (List<?>) raw;
        if (items.size() > 16) throw new IllegalArgumentException("Too many lanes");
        for (Object item : items) {
            JSONArray secondary = new JSONArray();
            List<?> images = (List<?>) call(item, "getSecondaryLanesImages");
            if (images.size() > 16) throw new IllegalArgumentException("Too many lane layers");
            for (Object image : images) secondary.put(resource(image));
            result.put(new JSONObject().put("secondary", secondary)
                    .put("highlighted", resource(call(item, "getHighlightedLaneImage")))
                    .put("kind", resource(call(item, "getLaneKindImage")))
                    .put("crop", resource(call(item, "getLaneKindCropImage")))
                    .put("leftOffset", call(item, "getHasLeftOffset"))
                    .put("rightOffset", call(item, "getHasRightOffset"))
                    .put("overlap", bounded(call(item, "getOverlap"))));
        }
        return result;
    }
    private static JSONArray geometry(View owner, String name, JSONArray raw) throws Exception {
        JSONArray result = new JSONArray();
        if (!visible(owner, name) || raw.length() == 0) return result;
        ViewGroup group = (ViewGroup) child(owner, name);
        if (group.getChildCount() != raw.length()) throw new IllegalStateException("Lane layout mismatch");
        for (int i = 0; i < raw.length(); i++) {
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) group.getChildAt(i).getLayoutParams();
            if (params.width <= 0 || params.height <= 0) throw new IllegalStateException("Unresolved lane size");
            result.put(new JSONObject(raw.getJSONObject(i).toString()).put("width", params.width)
                    .put("height", params.height).put("left", params.leftMargin).put("right", params.rightMargin));
        }
        return result;
    }
    private static JSONArray signs(View owner, Object raw) throws Exception {
        JSONArray result = new JSONArray();
        if (raw == null) return result;
        List<?> items = (List<?>) raw;
        if (items.size() > 16) throw new IllegalArgumentException("Too many signs");
        for (Object item : items) {
            String[] kinds = {"Icon", "Road", "Toponym", "Exit"};
            for (String kind : kinds) {
                Object value = call(item, "get" + kind);
                if (value == null) continue;
                Object style = call(value, "getStyle");
                JSONObject sign = new JSONObject().put("kind", kind)
                        .put("bg", call(style, "getBgColor")).put("color", call(style, "getTextColor"));
                if ("Icon".equals(kind)) {
                    Object icon = call(value, "getImage");
                    Method method = Class.forName(UI + "DirectionSignViewKt").getDeclaredMethod(
                            "access$toIconId", icon.getClass());
                    int id = ((Number) method.invoke(null, icon)).intValue();
                    sign.put("image", owner.getResources().getResourceEntryName(id));
                } else sign.put("text", bounded(call(value, "Toponym".equals(kind) ? "getText" : "getName")));
                result.put(sign);
                break;
            }
        }
        return result;
    }
    private static boolean belongs(View view, View root) {
        for (int i = 0; i < 64 && view != null; i++) {
            if (view == root) return true;
            ViewParent parent = view.getParent();
            view = parent instanceof View ? (View) parent : null;
        }
        return false;
    }
    private static View child(View owner, String name) {
        int id = owner.getResources().getIdentifier(name, "id", owner.getContext().getPackageName());
        return id == 0 ? null : owner.findViewById(id);
    }
    private static boolean visible(View owner, String name) {
        View value = child(owner, name);
        for (int i = 0; i < 64 && value != null; i++) {
            if (value.getVisibility() != View.VISIBLE || value.getAlpha() <= .01f) return false;
            if (value == owner) return true;
            ViewParent parent = value.getParent();
            value = parent instanceof View ? (View) parent : null;
        }
        return false;
    }
    private static String text(View owner, String name) throws Exception {
        View value = child(owner, name);
        return visible(owner, name) && value instanceof TextView ? bounded(((TextView) value).getText()) : "";
    }
    private static String resource(Object value) throws Exception {
        String name = value == null ? "" : bounded(call(value, "getInternalId"));
        if (!name.isEmpty() && !name.matches("[a-z0-9_]{1,160}")) throw new IllegalArgumentException("Invalid resource name");
        return name;
    }
    private static String bounded(Object value) {
        String text = value == null ? "" : String.valueOf(value).trim();
        if (text.length() > 4096) throw new IllegalArgumentException("Oversized command text");
        return text;
    }
    private static Object call(Object owner, String name) throws Exception {
        return owner == null ? null : owner.getClass().getMethod(name).invoke(owner);
    }
    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }
}
