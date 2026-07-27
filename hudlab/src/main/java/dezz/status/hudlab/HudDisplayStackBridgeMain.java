/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dezz.status.hudlab;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.view.Surface;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Shell-UID {@code app_process} entry point for a reversible physical HUD routing test.
 *
 * <p>The physical local display 2 is temporarily switched from layer stack 2 to stack 22.
 * A bright test surface is then placed on stack 22. The original stack is restored in a
 * {@code finally} block after twelve seconds, even if surface creation fails.</p>
 */
public final class HudDisplayStackBridgeMain {
    private static final int PHYSICAL_HUD_ID = 2;
    private static final int STOCK_STACK = 2;
    private static final int TEST_STACK = 22;
    private static final long TEST_DURATION_MS = 12_000L;
    private static final int MARKER_LEFT = 0;
    private static final int MARKER_TOP = 720;
    private static final int MARKER_WIDTH = 728;
    private static final int MARKER_HEIGHT = 190;

    private HudDisplayStackBridgeMain() {
    }

    public static void main(String[] args) {
        String action = args == null || args.length == 0 ? "test" : args[0];
        Object displayToken = null;
        DirectSurface marker = null;
        boolean remapped = false;
        try {
            displayToken = physicalDisplayToken(PHYSICAL_HUD_ID);
            if (displayToken == null) {
                throw new IllegalStateException(
                        "SurfaceFlinger не вернул token для physical display 2");
            }
            if ("restore".equals(action)) {
                setDisplayLayerStack(displayToken, STOCK_STACK);
                System.out.println("RESTORED physical=2 layerStack=2");
                return;
            }
            if (!"test".equals(action)) {
                throw new IllegalArgumentException("unknown action " + action);
            }

            setDisplayLayerStack(displayToken, TEST_STACK);
            remapped = true;
            marker = createMarker();
            drawMarker(marker.surface);
            show(marker.control);
            System.out.println("TEST_ACTIVE physical=2 layerStack=22 durationMs="
                    + TEST_DURATION_MS);
            System.out.flush();
            Thread.sleep(TEST_DURATION_MS);
            System.out.println("TEST_COMPLETE");
        } catch (Throwable failure) {
            Throwable cause = unwrap(failure);
            String message = cause.getMessage();
            System.out.println("ERROR " + cause.getClass().getSimpleName()
                    + (message == null || message.isEmpty() ? "" : " · " + message));
        } finally {
            release(marker);
            if (remapped && displayToken != null) {
                try {
                    setDisplayLayerStack(displayToken, STOCK_STACK);
                    System.out.println("RESTORED physical=2 layerStack=2");
                } catch (Throwable restoreFailure) {
                    Throwable cause = unwrap(restoreFailure);
                    System.out.println("RESTORE_ERROR " + cause.getClass().getSimpleName()
                            + " · " + cause.getMessage());
                }
            }
            System.out.flush();
        }
    }

    private static Object physicalDisplayToken(int physicalId) throws Exception {
        Class<?> surfaceControl = Class.forName("android.view.SurfaceControl");

        // Android 9 / ECARX path. The dump identifies the HUD as local:2.
        try {
            Method builtIn = declaredMethod(surfaceControl, "getBuiltInDisplay", int.class);
            Object token = builtIn.invoke(null, physicalId);
            if (token != null) return token;
        } catch (NoSuchMethodException ignored) {
            // Some vendor branches backport the newer physical-display API instead.
        }

        try {
            Method idsMethod = declaredMethod(surfaceControl, "getPhysicalDisplayIds");
            Object value = idsMethod.invoke(null);
            if (!(value instanceof long[])) return null;
            long[] ids = (long[]) value;
            if (physicalId < 0 || physicalId >= ids.length) return null;
            Method tokenMethod = declaredMethod(
                    surfaceControl, "getPhysicalDisplayToken", long.class);
            return tokenMethod.invoke(null, ids[physicalId]);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private static void setDisplayLayerStack(Object token, int layerStack) throws Exception {
        Class<?> surfaceControl = Class.forName("android.view.SurfaceControl");
        Method open = declaredMethod(surfaceControl, "openTransaction");
        Method close = declaredMethod(surfaceControl, "closeTransaction");
        Method set = declaredMethod(
                surfaceControl, "setDisplayLayerStack", IBinder.class, int.class);
        open.invoke(null);
        try {
            set.invoke(null, token, layerStack);
        } finally {
            close.invoke(null);
        }
    }

    private static DirectSurface createMarker() throws Exception {
        Class<?> sessionClass = Class.forName("android.view.SurfaceSession");
        Class<?> controlClass = Class.forName("android.view.SurfaceControl");
        Class<?> builderClass = Class.forName("android.view.SurfaceControl$Builder");
        Object session = declaredConstructor(sessionClass).newInstance();
        Object builder = declaredConstructor(builderClass, sessionClass).newInstance(session);
        invoke(builder, "setName", new Class<?>[]{String.class}, "hud_lab_stack22_marker");
        if (!invokeOptional(builder, "setSize",
                new Class<?>[]{int.class, int.class}, MARKER_WIDTH, MARKER_HEIGHT)) {
            invoke(builder, "setBufferSize",
                    new Class<?>[]{int.class, int.class}, MARKER_WIDTH, MARKER_HEIGHT);
        }
        invoke(builder, "setFormat", new Class<?>[]{int.class}, PixelFormat.RGBX_8888);
        invokeOptional(builder, "setFlags", new Class<?>[]{int.class}, 0x00000004);
        invokeOptional(builder, "setOpaque", new Class<?>[]{boolean.class}, true);
        Object control = invoke(builder, "build", new Class<?>[0]);

        Method open = declaredMethod(controlClass, "openTransaction");
        Method close = declaredMethod(controlClass, "closeTransaction");
        open.invoke(null);
        try {
            invoke(control, "setLayerStack", new Class<?>[]{int.class}, TEST_STACK);
            invoke(control, "setLayer",
                    new Class<?>[]{int.class}, Integer.MAX_VALUE - 1);
            invoke(control, "setPosition", new Class<?>[]{float.class, float.class},
                    (float) MARKER_LEFT, (float) MARKER_TOP);
        } finally {
            close.invoke(null);
        }

        Surface surface;
        try {
            Constructor<Surface> constructor =
                    Surface.class.getDeclaredConstructor(controlClass);
            constructor.setAccessible(true);
            surface = constructor.newInstance(control);
        } catch (NoSuchMethodException missingConstructor) {
            Constructor<Surface> empty = Surface.class.getDeclaredConstructor();
            empty.setAccessible(true);
            surface = empty.newInstance();
            Method copyFrom = declaredMethod(Surface.class, "copyFrom", controlClass);
            copyFrom.invoke(surface, control);
        }
        return new DirectSurface(session, control, surface);
    }

    private static void drawMarker(Surface surface) {
        Canvas canvas = null;
        try {
            canvas = surface.lockCanvas(null);
            canvas.drawColor(Color.rgb(0, 80, 30));
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(Color.rgb(80, 255, 145));
            paint.setTextSize(72f);
            paint.setFakeBoldText(true);
            canvas.drawText("HUD LAB · STACK 22", 30f, 118f, paint);
        } finally {
            if (canvas != null) surface.unlockCanvasAndPost(canvas);
        }
    }

    private static void show(Object control) throws Exception {
        Class<?> controlClass = control.getClass();
        Method open = declaredMethod(controlClass, "openTransaction");
        Method close = declaredMethod(controlClass, "closeTransaction");
        open.invoke(null);
        try {
            invoke(control, "show", new Class<?>[0]);
        } finally {
            close.invoke(null);
        }
    }

    private static void release(DirectSurface direct) {
        if (direct == null) return;
        if (direct.surface != null) {
            try {
                direct.surface.release();
            } catch (Throwable ignored) {
            }
        }
        if (direct.control != null) {
            try {
                if (!invokeOptional(direct.control, "destroy", new Class<?>[0])) {
                    invokeOptional(direct.control, "release", new Class<?>[0]);
                }
            } catch (Throwable ignored) {
            }
        }
        if (direct.session != null) {
            try {
                invokeOptional(direct.session, "kill", new Class<?>[0]);
            } catch (Throwable ignored) {
            }
        }
    }

    private static Object invoke(Object target, String name, Class<?>[] types,
                                 Object... arguments) throws Exception {
        return declaredMethod(target.getClass(), name, types).invoke(target, arguments);
    }

    private static boolean invokeOptional(Object target, String name, Class<?>[] types,
                                          Object... arguments) throws Exception {
        try {
            invoke(target, name, types, arguments);
            return true;
        } catch (NoSuchMethodException missing) {
            return false;
        }
    }

    private static Method declaredMethod(Class<?> type, String name, Class<?>... types)
            throws NoSuchMethodException {
        Class<?> cursor = type;
        while (cursor != null) {
            try {
                Method method = cursor.getDeclaredMethod(name, types);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException missing) {
                cursor = cursor.getSuperclass();
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }

    @SuppressWarnings("unchecked")
    private static <T> Constructor<T> declaredConstructor(
            Class<T> type, Class<?>... parameterTypes) throws NoSuchMethodException {
        Constructor<T> constructor = type.getDeclaredConstructor(parameterTypes);
        constructor.setAccessible(true);
        return constructor;
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable cursor = failure;
        while (cursor instanceof InvocationTargetException
                && ((InvocationTargetException) cursor).getTargetException() != null) {
            cursor = ((InvocationTargetException) cursor).getTargetException();
        }
        return cursor;
    }

    private static final class DirectSurface {
        final Object session;
        final Object control;
        final Surface surface;

        DirectSurface(Object session, Object control, Surface surface) {
            this.session = session;
            this.control = control;
            this.surface = surface;
        }
    }
}
