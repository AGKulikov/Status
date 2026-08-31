/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import android.graphics.PointF;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/** Delegates to Yandex's stock provider, then scales only its icon/caption size functions. */
final class ScaledRoadEventStyleProvider implements InvocationHandler {
    private final Object delegate;
    private final Object proxy;
    private volatile int eventScalePercent = 100;
    private volatile int cameraScalePercent = 100;

    ScaledRoadEventStyleProvider(Object delegate, Class<?> providerClass) {
        this.delegate = delegate;
        proxy = Proxy.newProxyInstance(providerClass.getClassLoader(),
                new Class<?>[]{providerClass}, this);
    }

    Object proxy() {
        return proxy;
    }

    boolean setScales(int nextEventScalePercent, int nextCameraScalePercent) {
        int events = clamp(nextEventScalePercent);
        int cameras = clamp(nextCameraScalePercent);
        boolean changed = eventScalePercent != events || cameraScalePercent != cameras;
        eventScalePercent = events;
        cameraScalePercent = cameras;
        return changed;
    }

    @Override public Object invoke(Object ignored, Method method, Object[] arguments)
            throws Throwable {
        final Object result;
        try {
            result = method.invoke(delegate, arguments);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
        if (!"provideStyle".equals(method.getName()) || !Boolean.TRUE.equals(result)
                || arguments == null || arguments.length == 0) {
            return result;
        }
        Object style = arguments[arguments.length - 1];
        if (style != null) {
            int scale = containsSpeedCamera(arguments[0])
                    ? cameraScalePercent : eventScalePercent;
            scaleStyle(style, scale / 100f);
        }
        return result;
    }

    private static boolean containsSpeedCamera(Object properties) {
        if (properties == null) return false;
        try {
            Object raw = invoke(properties, "getTags", new Class<?>[0]);
            if (!(raw instanceof List)) return false;
            for (Object tag : (List<?>) raw) {
                if (tag != null && "SPEED_CONTROL".equals(String.valueOf(tag))) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static void scaleStyle(Object style, float scale) throws Exception {
        if (Math.abs(scale - 1f) < 0.0001f) return;
        Object rawFunction = invoke(style, "getZoomScaleFunction", new Class<?>[0]);
        if (rawFunction instanceof List) {
            ArrayList<PointF> scaled = new ArrayList<>(((List<?>) rawFunction).size());
            for (Object rawPoint : (List<?>) rawFunction) {
                if (rawPoint instanceof PointF) {
                    PointF point = (PointF) rawPoint;
                    scaled.add(new PointF(point.x, point.y * scale));
                }
            }
            if (!scaled.isEmpty()) {
                invoke(style, "setZoomScaleFunction", new Class<?>[]{List.class}, scaled);
            }
        }

        Object caption = invoke(style, "getCaptionStyle", new Class<?>[0]);
        if (caption == null) return;
        Class<?> captionClass = Class.forName(
                "com.yandex.mapkit.road_events_layer.TextStyle");
        float size = ((Number) invoke(caption, "getFontSize", new Class<?>[0]))
                .floatValue() * scale;
        int color = ((Number) invoke(caption, "getColor", new Class<?>[0])).intValue();
        Integer outline = (Integer) invoke(caption, "getOutlineColor", new Class<?>[0]);
        Object scaledCaption = captionClass
                .getConstructor(float.class, int.class, Integer.class)
                .newInstance(size, color, outline);
        invoke(style, "setCaptionStyle", new Class<?>[]{captionClass}, scaledCaption);
    }

    private static int clamp(int value) {
        return Math.max(50, Math.min(250, value));
    }

    private static Object invoke(Object target, String name, Class<?>[] parameterTypes,
                                 Object... arguments) throws Exception {
        Method method = ReflectMethods.publicMethod(target.getClass(), name, parameterTypes);
        return method.invoke(target, arguments);
    }
}
