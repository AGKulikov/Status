/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.climate;

import android.content.Context;
import android.view.Display;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Resolves the native ECARX system-bar window used to reserve an application work area.
 *
 * <p>A normal {@link WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY} is only a visual layer:
 * Android does not include it in the insets delivered to other application windows. ECARX exposes
 * its actual status/navigation bar window types through AdaptAPI. Those types participate in the
 * vendor WindowManager policy and are therefore the preferred reservation mechanism. Reflection
 * keeps the main source set safe on non-ECARX builds and every failure falls back to the ordinary
 * overlay plus the legacy, verified overscan path.</p>
 */
final class ClimateReservationWindowPolicy {
    static final int EDGE_BOTTOM = 0;
    static final int EDGE_TOP = 1;
    static final int EDGE_LEFT = 2;
    static final int EDGE_RIGHT = 3;

    private static final String POLICY_CLASS =
            "com.ecarx.xui.adaptapi.policy.Policy";
    private static final String UI_INTERACTION_CLASS =
            "com.ecarx.xui.adaptapi.uiinteraction.UiInteraction";
    private static final String UI_INTERACTION_INTERFACE =
            "com.ecarx.xui.adaptapi.uiinteraction.IUiInteraction";
    private static final String WINDOW_MANAGER_INTERFACE =
            "com.ecarx.xui.adaptapi.uiinteraction.IWindowManager";
    private static final String WINDOW_INTERFACE =
            "com.ecarx.xui.adaptapi.uiinteraction.IWindowManager$IWindow";
    private static final String CODE_STATUS_BAR = "STATUS_BAR";
    private static final String CODE_NAVIGATION_BAR = "NAVIGATION_BAR";

    private ClimateReservationWindowPolicy() {}

    /**
     * Returns an OEM system-bar type, or {@link WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY}
     * when the API is absent, rejects the call, or returns an unsafe value.
     */
    static int resolve(@NonNull Context context, @Nullable Display display, int edge) {
        try {
            Class<?> policyClass = Class.forName(POLICY_CLASS);
            Method create = policyClass.getMethod("create", Context.class);
            Object policy = create.invoke(null, context.getApplicationContext());
            if (policy == null) return fallbackType();
            Object manager = policyClass.getMethod("getWindowManagerPolicy").invoke(policy);
            if (manager == null) return fallbackType();

            String code = codeForEdge(edge);
            Integer resolved = invokeResolver(manager, code, display);
            return sanitizeType(resolved == null ? -1 : resolved);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException
                 | InvocationTargetException | LinkageError | RuntimeException ignored) {
            return fallbackType();
        }
    }

    /**
     * ECARX bar types are singleton-like policy windows. Never replace or compete with an already
     * attached SystemUI/navigation window of the same type on the target display.
     */
    static boolean isOccupied(@NonNull Context context, @Nullable Display display, int type) {
        if (!isVendorType(type)) return false;
        try {
            Class<?> interactionClass = Class.forName(UI_INTERACTION_CLASS);
            Object interaction = interactionClass.getMethod("create", Context.class)
                    .invoke(null, context.getApplicationContext());
            if (interaction == null) return true;
            Class<?> interactionInterface = Class.forName(UI_INTERACTION_INTERFACE);
            Object manager = interactionInterface.getMethod("getWindowManager")
                    .invoke(interaction);
            if (manager == null) return true;
            Class<?> managerInterface = Class.forName(WINDOW_MANAGER_INTERFACE);
            Object windows = managerInterface.getMethod("getWindowList").invoke(manager);
            if (!(windows instanceof Object[])) return true;
            Class<?> windowInterface = Class.forName(WINDOW_INTERFACE);
            Method getType = windowInterface.getMethod("getType");
            Method getDisplayId = windowInterface.getMethod("getDisplayId");
            int displayId = display == null ? Display.DEFAULT_DISPLAY : display.getDisplayId();
            for (Object window : (Object[]) windows) {
                if (window == null) continue;
                Object candidateType = getType.invoke(window);
                Object candidateDisplay = getDisplayId.invoke(window);
                if (candidateType instanceof Number && candidateDisplay instanceof Number
                        && ((Number) candidateType).intValue() == type
                        && ((Number) candidateDisplay).intValue() == displayId) {
                    return true;
                }
            }
            return false;
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException
                 | InvocationTargetException | LinkageError | RuntimeException ignored) {
            // If the OEM inventory cannot be read, fail closed instead of risking a duplicate
            // singleton system bar. The verified overscan/application-overlay path remains.
            return true;
        }
    }

    @Nullable
    private static Integer invokeResolver(@NonNull Object manager, @NonNull String code,
                                          @Nullable Display display)
            throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        if (display != null) {
            try {
                Method perDisplay = manager.getClass().getMethod(
                        "getWindowTypeByCode", String.class, Display.class);
                Object value = perDisplay.invoke(manager, code, display);
                if (value instanceof Number) return ((Number) value).intValue();
            } catch (NoSuchMethodException ignored) {
                // Older AdaptAPI exposes only the single-argument variant.
            }
        }
        Method defaultDisplay = manager.getClass().getMethod(
                "getWindowTypeByCode", String.class);
        Object value = defaultDisplay.invoke(manager, code);
        return value instanceof Number ? ((Number) value).intValue() : null;
    }

    @NonNull
    static String codeForEdge(int edge) {
        // The top dock is a status-bar inset. Bottom and vertical docks are navigation-bar
        // insets; Android supports a vertical navigation bar in landscape and derives its side
        // from the window gravity.
        return edge == EDGE_TOP ? CODE_STATUS_BAR : CODE_NAVIGATION_BAR;
    }

    static int sanitizeType(int candidate) {
        // Vendor types live in Android's system-window range. Refuse application windows and the
        // generic application-overlay value: neither can provide a global work-area inset.
        if (candidate < WindowManager.LayoutParams.FIRST_SYSTEM_WINDOW
                || candidate > WindowManager.LayoutParams.LAST_SYSTEM_WINDOW
                || candidate == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY) {
            return fallbackType();
        }
        return candidate;
    }

    static boolean isVendorType(int type) {
        return type != fallbackType();
    }

    private static int fallbackType() {
        return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
    }
}
