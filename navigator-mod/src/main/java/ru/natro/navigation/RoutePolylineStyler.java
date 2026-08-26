/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import android.graphics.Color;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Applies the active route and per-geometry-segment jam palette using public MapKit APIs. */
final class RoutePolylineStyler {
    private RoutePolylineStyler() {}

    static void apply(Object line, Object drivingRoute, NavigationMapProfile profile)
            throws Exception {
        invoke(line, "setStrokeColor", new Class<?>[]{int.class},
                Color.parseColor(profile.routeColor));
        invoke(line, "setOutlineColor", new Class<?>[]{int.class},
                Color.parseColor(profile.routeOutlineColor));
        invoke(line, "setStrokeWidth", new Class<?>[]{float.class},
                (float) profile.routeWidth);
        invoke(line, "setOutlineWidth", new Class<?>[]{float.class},
                (float) profile.routeOutlineWidth);
        invoke(line, "setGradientLength", new Class<?>[]{float.class},
                (float) profile.trafficGradientLength);
        invoke(line, "setZIndex", new Class<?>[]{float.class}, 100f);
        invoke(line, "setVisible", new Class<?>[]{boolean.class}, true);
        if (profile.showTraffic) applyJamPalette(line, drivingRoute, profile);
    }

    static long jamFingerprint(Object drivingRoute) {
        if (drivingRoute == null) return 0L;
        try {
            List<?> segments = list(invoke(drivingRoute, "getJamSegments", new Class<?>[0]));
            long result = 0xcbf29ce484222325L;
            for (Object segment : segments) {
                Object type = invoke(segment, "getJamType", new Class<?>[0]);
                long speed = Double.doubleToLongBits(number(
                        invoke(segment, "getSpeed", new Class<?>[0])));
                result ^= type == null ? 0L : type.toString().hashCode();
                result *= 0x100000001b3L;
                result ^= speed;
                result *= 0x100000001b3L;
            }
            return result ^ segments.size();
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static void applyJamPalette(Object line, Object drivingRoute,
                                        NavigationMapProfile profile) throws Exception {
        List<?> jams = list(invoke(drivingRoute, "getJamSegments", new Class<?>[0]));
        if (jams.isEmpty()) return;
        Object geometry = invoke(drivingRoute, "getGeometry", new Class<?>[0]);
        List<?> points = geometry == null ? new ArrayList<>()
                : list(invoke(geometry, "getPoints", new Class<?>[0]));
        int segmentCount = Math.max(0, points.size() - 1);
        if (segmentCount == 0) return;

        for (int paletteIndex = 0; paletteIndex <= 6; paletteIndex++) {
            invoke(line, "setPaletteColor", new Class<?>[]{int.class, int.class},
                    paletteIndex, profile.trafficColor(paletteIndex));
        }
        ArrayList<Integer> colors = new ArrayList<>(segmentCount);
        for (int index = 0; index < segmentCount; index++) {
            if (index >= jams.size()) {
                colors.add(0);
                continue;
            }
            Object jamType = invoke(jams.get(index), "getJamType", new Class<?>[0]);
            colors.add(paletteIndex(jamType == null ? "UNKNOWN" : jamType.toString()));
        }
        invoke(line, "setStrokeColors", new Class<?>[]{List.class}, colors);
    }

    static int paletteIndex(String jamType) {
        if ("FREE".equals(jamType)) return 1;
        if ("LIGHT".equals(jamType)) return 2;
        if ("HARD".equals(jamType)) return 3;
        if ("VERY_HARD".equals(jamType)) return 4;
        if ("BLOCKED".equals(jamType)) return 5;
        return 6;
    }

    private static Object invoke(Object target, String name, Class<?>[] parameterTypes,
                                 Object... arguments) throws Exception {
        Method method = target.getClass().getMethod(name, parameterTypes);
        method.setAccessible(true);
        return method.invoke(target, arguments);
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> ? (List<?>) value : new ArrayList<>();
    }

    private static double number(Object value) {
        return value instanceof Number ? ((Number) value).doubleValue() : Double.NaN;
    }
}
