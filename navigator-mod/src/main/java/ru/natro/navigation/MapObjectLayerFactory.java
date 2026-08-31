/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import java.lang.reflect.Method;

/** Creates real MapKit root layers so its native label/placemark collision engine stays active. */
final class MapObjectLayerFactory {
    static final String IGNORE = "IGNORE";
    static final String MINOR = "MINOR";
    static final String EQUAL = "EQUAL";
    static final String MAJOR = "MAJOR";

    private MapObjectLayerFactory() {}

    @SuppressWarnings({"rawtypes", "unchecked"})
    static Object create(Object map, String layerId, String conflictMode, float zIndex)
            throws Exception {
        // ConflictResolutionMode is supported only by a root collection. A child collection from
        // getMapObjects().addCollection() silently stays in IGNORE mode and is the reason custom
        // HUD objects used to overlap each other and the stock map labels.
        Object root = invoke(map, "addMapObjectLayer",
                new Class<?>[]{String.class}, layerId);
        Class<?> modeClass = Class.forName("com.yandex.mapkit.ConflictResolutionMode");
        Object mode = Enum.valueOf((Class<? extends Enum>) modeClass, conflictMode);
        invoke(root, "setConflictResolutionMode", new Class<?>[]{modeClass}, mode);
        setZIndex(root, zIndex);
        return root;
    }

    static void setZIndex(Object root, float zIndex) {
        if (root == null) return;
        try {
            invoke(root, "setZIndex", new Class<?>[]{float.class}, zIndex);
        } catch (Throwable ignored) {
            // Per-object zIndex remains a safe fallback on an unexpected MapKit build.
        }
    }

    private static Object invoke(Object target, String name, Class<?>[] parameterTypes,
                                 Object... arguments) throws Exception {
        Method method = ReflectMethods.publicMethod(target.getClass(), name, parameterTypes);
        return method.invoke(target, arguments);
    }
}
