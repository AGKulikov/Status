/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import android.graphics.Color;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Applies the active route and per-geometry-segment jam palette using public MapKit APIs. */
final class RoutePolylineStyler {
    private RoutePolylineStyler() {}

    static void apply(Object line, JamStyle jamStyle, NavigationMapProfile profile,
                      int firstSegmentIndex, int renderedSegmentCount,
                      ArrayList<Integer> colorScratch)
            throws Exception {
        invoke(line, "setStrokeColor", new Class<?>[]{int.class},
                Color.parseColor(profile.routeColor));
        invoke(line, "setOutlineColor", new Class<?>[]{int.class},
                Color.parseColor(profile.routeOutlineColor));
        float widthScale = profile.routeWidthPercent / 100f;
        invoke(line, "setStrokeWidth", new Class<?>[]{float.class},
                (float) profile.routeWidth * widthScale);
        invoke(line, "setOutlineWidth", new Class<?>[]{float.class},
                (float) profile.routeOutlineWidth * widthScale);
        invoke(line, "setGradientLength", new Class<?>[]{float.class},
                (float) profile.trafficGradientLength);
        invoke(line, "setZIndex", new Class<?>[]{float.class},
                NavigationMapProfile.layerZ(profile.routeLayerPriority));
        invoke(line, "setVisible", new Class<?>[]{boolean.class}, true);
        if (profile.showRouteTraffic) {
            for (int paletteIndex = 0; paletteIndex <= 6; paletteIndex++) {
                invoke(line, "setPaletteColor", new Class<?>[]{int.class, int.class},
                        paletteIndex, profile.trafficColor(paletteIndex));
            }
        }
        applyProgressColors(line, jamStyle, profile, firstSegmentIndex,
                renderedSegmentCount, colorScratch);
    }

    /** Route movement keeps all static line styling and changes only per-segment colours. */
    static void applyProgressColors(Object line, JamStyle jamStyle,
                                    NavigationMapProfile profile, int firstSegmentIndex,
                                    int renderedSegmentCount,
                                    ArrayList<Integer> colorScratch) throws Exception {
        if (profile.showRouteTraffic) {
            applyJamPalette(line, jamStyle, firstSegmentIndex,
                    renderedSegmentCount, colorScratch);
        } else {
            clearJamPalette(line, renderedSegmentCount, colorScratch);
        }
    }

    static long jamFingerprint(Object drivingRoute) {
        return readJamStyle(drivingRoute).fingerprint;
    }

    /** One reflected jam scan shared by every independent MapWindow. */
    static JamStyle readJamStyle(Object drivingRoute) {
        if (drivingRoute == null) return JamStyle.EMPTY;
        try {
            List<?> segments = list(invoke(drivingRoute, "getJamSegments", new Class<?>[0]));
            long result = 0xcbf29ce484222325L;
            int[] palette = new int[segments.size()];
            for (int index = 0; index < segments.size(); index++) {
                Object segment = segments.get(index);
                Object type = invoke(segment, "getJamType", new Class<?>[0]);
                String name = type == null ? "UNKNOWN" : type.toString();
                result ^= name.hashCode();
                result *= 0x100000001b3L;
                palette[index] = paletteIndex(name);
            }
            return new JamStyle(result ^ segments.size(), palette);
        } catch (Throwable ignored) {
            return JamStyle.EMPTY;
        }
    }

    private static void applyJamPalette(Object line, JamStyle jamStyle,
                                        int firstSegmentIndex,
                                        int renderedSegmentCount,
                                        ArrayList<Integer> colorScratch) throws Exception {
        int segmentCount = Math.max(0, renderedSegmentCount);
        if (segmentCount == 0) return;
        int[] palette = jamStyle == null ? JamStyle.EMPTY.palette : jamStyle.palette;
        if (palette.length == 0) {
            clearJamPalette(line, segmentCount, colorScratch);
            return;
        }

        colorScratch.clear();
        colorScratch.ensureCapacity(segmentCount);
        for (int index = 0; index < segmentCount; index++) {
            int sourceIndex = firstSegmentIndex + index;
            colorScratch.add(sourceIndex < 0 || sourceIndex >= palette.length
                    ? 0 : palette[sourceIndex]);
        }
        invoke(line, "setStrokeColors", new Class<?>[]{List.class}, colorScratch);
    }

    private static void clearJamPalette(Object line, int segmentCount,
                                        ArrayList<Integer> colorScratch) throws Exception {
        colorScratch.clear();
        colorScratch.ensureCapacity(Math.max(0, segmentCount));
        for (int index = 0; index < segmentCount; index++) colorScratch.add(0);
        invoke(line, "setStrokeColors", new Class<?>[]{List.class}, colorScratch);
    }

    static int paletteIndex(String jamType) {
        if ("FREE".equals(jamType)) return 1;
        if ("LIGHT".equals(jamType)) return 2;
        if ("HARD".equals(jamType)) return 3;
        if ("VERY_HARD".equals(jamType)) return 4;
        if ("BLOCKED".equals(jamType)) return 5;
        return 6;
    }

    static final class JamStyle {
        static final JamStyle EMPTY = new JamStyle(0L, new int[0]);
        final long fingerprint;
        final int[] palette;

        JamStyle(long fingerprint, int[] palette) {
            this.fingerprint = fingerprint;
            this.palette = palette == null ? new int[0] : palette;
        }
    }

    private static Object invoke(Object target, String name, Class<?>[] parameterTypes,
                                 Object... arguments) throws Exception {
        Method method = ReflectMethods.publicMethod(target.getClass(), name, parameterTypes);
        return method.invoke(target, arguments);
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> ? (List<?>) value : new ArrayList<>();
    }

}
