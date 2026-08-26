/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import android.content.Context;
import android.content.res.Configuration;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * Applies safe visual fields to Navigator's primary map.
 *
 * <p>The stock Navigator remains the sole owner of camera, route and user-location layers. This
 * class deliberately contains no Map.move, map-object collection or second UserLocationLayer
 * path; the independent HUD renderer owns every custom route/cursor/camera transform.</p>
 */
final class MainMapController {
    private static final String TAG = "NatroMainMap";
    private static final int CUSTOM_STYLE_ID = 0x4E41544D; // "NATM"
    private static final int VISIBILITY_STYLE_ID = CUSTOM_STYLE_ID + 1;

    private final Context context;
    private NavigationMapProfile profile = new NavigationMapProfile();
    private Object mapWindow;
    private Object map;

    private boolean originalsCaptured;
    private float originalScaleFactor = 1f;
    private Object originalFocusPoint;
    private boolean originalNightMode;
    private boolean originalModelsEnabled;
    private boolean originalAwesomeModelsEnabled;
    private Integer originalPoiLimit;

    MainMapController(Context context) {
        this.context = context.getApplicationContext();
    }

    void applyConfiguration(String raw) {
        profile = NavigationMapProfile.fromConfiguration(raw, "mainMap");
        if (map == null || mapWindow == null) return;
        if (profile.enabled) applyProfile();
        else deactivate();
    }

    void attach(Object nextMapWindow, Object nextMap) {
        if (mapWindow == nextMapWindow && map == nextMap) {
            if (profile.enabled) applyProfile();
            return;
        }
        detach();
        mapWindow = nextMapWindow;
        map = nextMap;
        if (nextMapWindow == null || nextMap == null) return;
        captureOriginals();
        if (profile.enabled) applyProfile();
    }

    void detach() {
        deactivate();
        mapWindow = null;
        map = null;
        originalsCaptured = false;
        originalFocusPoint = null;
    }

    /** Primary callbacks always continue to the independent HUD camera renderer. */
    boolean updatePrimaryCamera(NavigatorStatePublisher.CameraState state) {
        return false;
    }

    /** Route geometry belongs only to HudMapRenderer; primary-map duplication is forbidden. */
    void updateRoute(long routeEpoch, Object drivingRoute) {
        // Intentionally empty.
    }

    private void applyProfile() {
        Object currentWindow = mapWindow;
        Object currentMap = map;
        if (currentWindow == null || currentMap == null || !profile.enabled) return;
        try {
            captureOriginals();
            invoke(currentWindow, "setMaxFps", new Class<?>[]{int.class}, profile.maximumFps);
            invoke(currentWindow, "setScaleFactor", new Class<?>[]{float.class},
                    originalScaleFactor * profile.mapScalePercent / 100f);
            int width = ((Number) invoke(currentWindow, "width", new Class<?>[0])).intValue();
            int height = ((Number) invoke(currentWindow, "height", new Class<?>[0])).intValue();
            Class<?> screenPointClass = Class.forName("com.yandex.mapkit.ScreenPoint");
            Object focus = screenPointClass.getConstructor(float.class, float.class).newInstance(
                    width * profile.focusXPercent / 100f,
                    height * profile.focusYPercent / 100f);
            invoke(currentWindow, "setFocusPoint", new Class<?>[]{screenPointClass}, focus);

            boolean systemNight = (context.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            boolean night = profile.automaticDayNight ? systemNight : profile.nightMode;
            invoke(currentMap, "setNightModeEnabled", new Class<?>[]{boolean.class}, night);
            invoke(currentMap, "setModelsEnabled", new Class<?>[]{boolean.class},
                    profile.showModels);
            invoke(currentMap, "setAwesomeModelsEnabled", new Class<?>[]{boolean.class},
                    profile.showModels);
            invoke(currentMap, "setPoiLimit", new Class<?>[]{Integer.class},
                    profile.showPois ? null : Integer.valueOf(0));
            applyStyleSlot(currentMap, CUSTOM_STYLE_ID,
                    night ? profile.nightStyleJson : profile.dayStyleJson);
            applyStyleSlot(currentMap, VISIBILITY_STYLE_ID, profile.visibilityStyleJson());
            Log.i(TAG, "Stable main MapProfile applied; stock camera/route/cursor retained");
        } catch (Throwable failure) {
            Log.w(TAG, "Some safe main MapProfile fields could not be applied", failure);
        }
    }

    private void captureOriginals() {
        if (originalsCaptured || mapWindow == null || map == null) return;
        try {
            originalScaleFactor = ((Number) invoke(
                    mapWindow, "getScaleFactor", new Class<?>[0])).floatValue();
            originalFocusPoint = invoke(mapWindow, "getFocusPoint", new Class<?>[0]);
            originalNightMode = Boolean.TRUE.equals(invoke(
                    map, "isNightModeEnabled", new Class<?>[0]));
            originalModelsEnabled = Boolean.TRUE.equals(invoke(
                    map, "isModelsEnabled", new Class<?>[0]));
            originalAwesomeModelsEnabled = Boolean.TRUE.equals(invoke(
                    map, "isAwesomeModelsEnabled", new Class<?>[0]));
            Object poi = invoke(map, "getPoiLimit", new Class<?>[0]);
            originalPoiLimit = poi instanceof Integer ? (Integer) poi : null;
            originalsCaptured = true;
        } catch (Throwable failure) {
            Log.w(TAG, "Could not snapshot main map defaults", failure);
        }
    }

    private void deactivate() {
        Object currentMap = map;
        Object currentWindow = mapWindow;
        if (currentMap == null || currentWindow == null) return;
        try {
            applyStyleSlot(currentMap, CUSTOM_STYLE_ID, "");
            applyStyleSlot(currentMap, VISIBILITY_STYLE_ID, "");
            if (originalsCaptured) {
                invoke(currentWindow, "setScaleFactor", new Class<?>[]{float.class},
                        originalScaleFactor);
                if (originalFocusPoint != null) {
                    Class<?> screenPointClass = Class.forName("com.yandex.mapkit.ScreenPoint");
                    invoke(currentWindow, "setFocusPoint",
                            new Class<?>[]{screenPointClass}, originalFocusPoint);
                }
                invoke(currentMap, "setNightModeEnabled", new Class<?>[]{boolean.class},
                        originalNightMode);
                invoke(currentMap, "setModelsEnabled", new Class<?>[]{boolean.class},
                        originalModelsEnabled);
                invoke(currentMap, "setAwesomeModelsEnabled", new Class<?>[]{boolean.class},
                        originalAwesomeModelsEnabled);
                invoke(currentMap, "setPoiLimit", new Class<?>[]{Integer.class},
                        originalPoiLimit);
            }
            // MapWindow has no public FPS getter in 30.3.0; 60 is MapKit's unrestricted ceiling.
            invoke(currentWindow, "setMaxFps", new Class<?>[]{int.class}, 60);
        } catch (Throwable failure) {
            Log.w(TAG, "Could not fully restore the main map profile", failure);
        }
    }

    private static void applyStyleSlot(Object target, int id, String style) throws Exception {
        Class<?>[] signature = new Class<?>[]{int.class, String.class};
        invoke(target, "setMapStyle", signature, id, "");
        if (style != null && !style.isEmpty()) {
            invoke(target, "setMapStyle", signature, id, style);
        }
    }

    private static Object invoke(Object target, String name, Class<?>[] parameterTypes,
                                 Object... arguments) throws Exception {
        Method method = target.getClass().getMethod(name, parameterTypes);
        method.setAccessible(true);
        return method.invoke(target, arguments);
    }
}
