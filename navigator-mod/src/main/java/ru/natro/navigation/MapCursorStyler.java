/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.util.Log;
import android.view.View;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** Owns a strong UserLocationObjectListener and a resource-free configurable car cursor. */
final class MapCursorStyler {
    private static final String TAG = "NatroMapCursor";
    private final Context context;
    private Object layer;
    private Object listener;
    private WeakReference<Object> listenerReference;
    private Object locationView;
    private Object viewProvider;
    private boolean visible;
    private int scalePercent = 100;
    private int fillColor = Color.parseColor("#FFFFC400");
    private int outlineColor = Color.parseColor("#FF17191E");

    MapCursorStyler(Context context) {
        this.context = context.getApplicationContext();
    }

    void attach(Object nextLayer) throws Exception {
        if (layer == nextLayer) return;
        detach();
        if (nextLayer == null) return;
        layer = nextLayer;
        Class<?> listenerClass = Class.forName(
                "com.yandex.mapkit.user_location.UserLocationObjectListener");
        listener = Proxy.newProxyInstance(listenerClass.getClassLoader(),
                new Class<?>[]{listenerClass}, (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return objectMethodResult(proxy, method, arguments);
                    }
                    String name = method.getName();
                    if (("onObjectAdded".equals(name) || "onObjectUpdated".equals(name))
                            && arguments != null && arguments.length > 0) {
                        if (locationView != arguments[0]) {
                            locationView = arguments[0];
                            try {
                                applyToCurrentView();
                            } catch (Throwable failure) {
                                Log.w(TAG, "Could not style user location view", failure);
                            }
                        }
                    } else if ("onObjectRemoved".equals(name)) {
                        locationView = null;
                    }
                    return null;
                });
        listenerReference = new WeakReference<>(listener);
        invoke(nextLayer, "setObjectListener", new Class<?>[]{WeakReference.class},
                listenerReference);
    }

    void apply(boolean nextVisible, int nextScalePercent, String nextFill,
               String nextOutline) throws Exception {
        visible = nextVisible;
        scalePercent = Math.max(25, Math.min(300, nextScalePercent));
        fillColor = Color.parseColor(nextFill);
        outlineColor = Color.parseColor(nextOutline);
        Object currentLayer = layer;
        if (currentLayer != null) {
            invoke(currentLayer, "setVisible", new Class<?>[]{boolean.class}, visible);
        }
        viewProvider = null;
        applyToCurrentView();
    }

    void detach() {
        Object currentLayer = layer;
        if (currentLayer != null) {
            try {
                invoke(currentLayer, "setObjectListener",
                        new Class<?>[]{WeakReference.class}, (Object) null);
            } catch (Throwable ignored) {}
            try {
                invoke(currentLayer, "setVisible", new Class<?>[]{boolean.class}, false);
            } catch (Throwable ignored) {}
        }
        layer = null;
        listener = null;
        listenerReference = null;
        locationView = null;
        viewProvider = null;
    }

    private void applyToCurrentView() throws Exception {
        Object currentView = locationView;
        if (currentView == null) return;
        Object provider = viewProvider;
        if (provider == null) {
            provider = createViewProvider();
            viewProvider = provider;
        }
        Object arrow = invoke(currentView, "getArrow", new Class<?>[0]);
        Object pin = invoke(currentView, "getPin", new Class<?>[0]);
        applyPlacemark(arrow, provider, true);
        applyPlacemark(pin, provider, false);

        Object accuracy = invoke(currentView, "getAccuracyCircle", new Class<?>[0]);
        int accuracyFill = (fillColor & 0x00ffffff) | 0x22000000;
        int accuracyStroke = (outlineColor & 0x00ffffff) | 0x66000000;
        invoke(accuracy, "setFillColor", new Class<?>[]{int.class}, accuracyFill);
        invoke(accuracy, "setStrokeColor", new Class<?>[]{int.class}, accuracyStroke);
        invoke(accuracy, "setStrokeWidth", new Class<?>[]{float.class}, 1.5f);
        invoke(accuracy, "setVisible", new Class<?>[]{boolean.class}, visible);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void applyPlacemark(Object placemark, Object provider, boolean rotating)
            throws Exception {
        Class<?> styleClass = Class.forName("com.yandex.mapkit.map.IconStyle");
        Class<?> rotationClass = Class.forName("com.yandex.mapkit.map.RotationType");
        Class<?> providerClass = Class.forName("com.yandex.runtime.ui_view.ViewProvider");
        Object style = styleClass.getConstructor().newInstance();
        Object rotation = Enum.valueOf((Class<? extends Enum>) rotationClass,
                rotating ? "ROTATE" : "NO_ROTATION");
        invoke(style, "setAnchor", new Class<?>[]{PointF.class}, new PointF(0.5f, 0.5f));
        invoke(style, "setRotationType", new Class<?>[]{rotationClass}, rotation);
        invoke(style, "setScale", new Class<?>[]{Float.class},
                Float.valueOf(scalePercent / 100f));
        invoke(style, "setFlat", new Class<?>[]{Boolean.class}, Boolean.FALSE);
        invoke(style, "setVisible", new Class<?>[]{Boolean.class},
                Boolean.valueOf(visible));
        invoke(style, "setZIndex", new Class<?>[]{Float.class}, Float.valueOf(20f));
        invoke(placemark, "setView", new Class<?>[]{providerClass, styleClass}, provider, style);
        invoke(placemark, "setVisible", new Class<?>[]{boolean.class}, visible);
    }

    private Object createViewProvider() throws Exception {
        int size = Math.max(32, Math.round(48f
                * context.getResources().getDisplayMetrics().density));
        CursorView view = new CursorView(context, fillColor, outlineColor);
        int specification = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY);
        view.measure(specification, specification);
        view.layout(0, 0, size, size);
        Class<?> providerClass = Class.forName("com.yandex.runtime.ui_view.ViewProvider");
        return providerClass.getConstructor(View.class, boolean.class)
                .newInstance(view, false);
    }

    private static Object objectMethodResult(Object proxy, Method method, Object[] arguments) {
        if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
        if ("equals".equals(method.getName())) {
            return arguments != null && arguments.length == 1 && proxy == arguments[0];
        }
        return "NatroUserLocationListener";
    }

    private static Object invoke(Object target, String name, Class<?>[] parameterTypes,
                                 Object... arguments) throws Exception {
        Method method = ReflectMethods.publicMethod(target.getClass(), name, parameterTypes);
        return method.invoke(target, arguments);
    }

    /** A compact north-facing vehicle chevron; ViewProvider snapshots it only on style changes. */
    private static final class CursorView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        CursorView(Context context, int fillColor, int outlineColor) {
            super(context);
            fill.setColor(fillColor);
            fill.setStyle(Paint.Style.FILL);
            outline.setColor(outlineColor);
            outline.setStyle(Paint.Style.STROKE);
            outline.setStrokeJoin(Paint.Join.ROUND);
            outline.setStrokeCap(Paint.Cap.ROUND);
            outline.setStrokeWidth(Math.max(2f,
                    2.5f * context.getResources().getDisplayMetrics().density));
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float width = getWidth();
            float height = getHeight();
            path.reset();
            path.moveTo(width * 0.50f, height * 0.06f);
            path.lineTo(width * 0.88f, height * 0.88f);
            path.lineTo(width * 0.50f, height * 0.70f);
            path.lineTo(width * 0.12f, height * 0.88f);
            path.close();
            canvas.drawPath(path, outline);
            canvas.drawPath(path, fill);
        }
    }
}
