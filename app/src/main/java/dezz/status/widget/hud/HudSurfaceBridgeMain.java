/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.view.Surface;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Tiny {@code app_process} entry point that owns two unparented SurfaceFlinger layers.
 *
 * <p>The dump-verified stock {@code com.ecarx.hud} footprint is covered by an opaque 808x266
 * mask. The custom 728x190 widget frame is composited immediately above it. Keeping these as
 * separate surfaces prevents widget bounds from being widened just to cover the stock pixels.
 * The configured local ADB/Telnet shell starts this bridge under the shell identity, and the
 * application sends complete clipped PNG frames over a nonce-protected loopback socket.</p>
 *
 * <p>No ECARX process is stopped and no persistent system setting is changed. The surface exists
 * only while the socket is connected; an app crash, service stop, or five-second heartbeat
 * timeout destroys it automatically.</p>
 */
public final class HudSurfaceBridgeMain {
    private static final int HELLO_OK = 1;
    private static final int HELLO_FAILED = 2;
    private static final int FRAME_OK = 1;
    private static final int STOP = -1;
    private static final int MAX_FRAME_BYTES = 4 * 1024 * 1024;
    private static final int ACCEPT_TIMEOUT_MS = 15_000;
    private static final int FRAME_TIMEOUT_MS = 5_000;
    private static final int MASK_SURFACE_LAYER = Integer.MAX_VALUE - 2;
    private static final int CONTENT_SURFACE_LAYER = Integer.MAX_VALUE - 1;

    private HudSurfaceBridgeMain() {}

    public static void main(String[] args) {
        if (args == null || args.length != 12) return;

        int port;
        int layerStack;
        int contentLeft;
        int contentTop;
        int contentWidth;
        int contentHeight;
        int maskLeft;
        int maskTop;
        int maskWidth;
        int maskHeight;
        try {
            port = Integer.parseInt(args[1]);
            layerStack = Integer.parseInt(args[2]);
            contentLeft = Integer.parseInt(args[3]);
            contentTop = Integer.parseInt(args[4]);
            contentWidth = Integer.parseInt(args[5]);
            contentHeight = Integer.parseInt(args[6]);
            maskLeft = Integer.parseInt(args[7]);
            maskTop = Integer.parseInt(args[8]);
            maskWidth = Integer.parseInt(args[9]);
            maskHeight = Integer.parseInt(args[10]);
        } catch (RuntimeException badArguments) {
            return;
        }
        String expectedNonce = args[0];
        String surfaceName = args[11];
        if (port <= 0 || port > 65_535 || layerStack < 0
                || contentLeft < 0 || contentTop < 0
                || contentWidth <= 0 || contentHeight <= 0
                || maskLeft < 0 || maskTop < 0
                || maskWidth <= 0 || maskHeight <= 0
                || expectedNonce.isEmpty()) {
            return;
        }

        ServerSocket server = null;
        Socket client = null;
        DataInputStream input = null;
        DataOutputStream output = null;
        DirectSurfaces surfaces = null;
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(
                    InetAddress.getByName("127.0.0.1"), port), 1);
            server.setSoTimeout(ACCEPT_TIMEOUT_MS);
            client = server.accept();
            client.setSoTimeout(FRAME_TIMEOUT_MS);
            input = new DataInputStream(client.getInputStream());
            output = new DataOutputStream(client.getOutputStream());

            String actualNonce = input.readUTF();
            if (!MessageDigest.isEqual(
                    expectedNonce.getBytes(StandardCharsets.UTF_8),
                    actualNonce.getBytes(StandardCharsets.UTF_8))) {
                output.writeByte(HELLO_FAILED);
                output.writeUTF("nonce rejected");
                output.flush();
                return;
            }

            surfaces = createDirectSurfaces(surfaceName, layerStack,
                    contentLeft, contentTop, contentWidth, contentHeight,
                    maskLeft, maskTop, maskWidth, maskHeight);
            drawMask(surfaces.maskSurface);
            output.writeByte(HELLO_OK);
            output.flush();

            boolean contentShown = false;
            Boolean currentMaskVisibility = null;
            while (true) {
                int length = input.readInt();
                if (length == STOP) break;
                if (length <= 0 || length > MAX_FRAME_BYTES) {
                    throw new IllegalArgumentException("invalid frame length " + length);
                }
                boolean maskEnabled = input.readBoolean();
                byte[] encoded = new byte[length];
                input.readFully(encoded);
                Bitmap bitmap = BitmapFactory.decodeByteArray(encoded, 0, encoded.length);
                if (bitmap == null || bitmap.getWidth() != contentWidth
                        || bitmap.getHeight() != contentHeight) {
                    throw new IllegalArgumentException("invalid HUD bitmap");
                }
                drawContent(surfaces.contentSurface, bitmap);
                bitmap.recycle();
                if (!contentShown || currentMaskVisibility == null
                        || currentMaskVisibility != maskEnabled) {
                    setVisibility(surfaces.maskControl, maskEnabled,
                            surfaces.contentControl, true);
                    contentShown = true;
                    currentMaskVisibility = maskEnabled;
                }
                output.writeByte(FRAME_OK);
                output.flush();
            }
        } catch (Throwable failure) {
            if (output != null && surfaces == null) {
                try {
                    output.writeByte(HELLO_FAILED);
                    output.writeUTF(shortMessage(failure));
                    output.flush();
                } catch (Throwable ignored) {
                    // The caller also treats a closed socket as a bridge startup failure.
                }
            }
        } finally {
            closeQuietly(input);
            closeQuietly(output);
            closeQuietly(client);
            closeQuietly(server);
            releaseSurfaces(surfaces);
        }
    }

    private static DirectSurfaces createDirectSurfaces(
            String name, int layerStack,
            int contentLeft, int contentTop, int contentWidth, int contentHeight,
            int maskLeft, int maskTop, int maskWidth, int maskHeight) throws Exception {
        Class<?> sessionClass = Class.forName("android.view.SurfaceSession");
        Object session = declaredConstructor(sessionClass).newInstance();
        DirectSurface mask = null;
        DirectSurface content = null;
        try {
            mask = createSurface(session, name + "_mask", layerStack,
                    maskLeft, maskTop, maskWidth, maskHeight,
                    PixelFormat.RGBX_8888, true, MASK_SURFACE_LAYER);
            content = createSurface(session, name + "_content", layerStack,
                    contentLeft, contentTop, contentWidth, contentHeight,
                    PixelFormat.TRANSLUCENT, false, CONTENT_SURFACE_LAYER);
            return new DirectSurfaces(session, mask, content);
        } catch (Throwable failure) {
            releaseDirectSurface(content);
            releaseDirectSurface(mask);
            try { invokeOptional(session, "kill", new Class<?>[0]); }
            catch (Throwable ignored) {}
            if (failure instanceof Exception) throw (Exception) failure;
            throw new RuntimeException(failure);
        }
    }

    private static DirectSurface createSurface(
            Object session, String name, int layerStack,
            int left, int top, int width, int height,
            int format, boolean opaque, int layer) throws Exception {
        Class<?> sessionClass = Class.forName("android.view.SurfaceSession");
        Class<?> controlClass = Class.forName("android.view.SurfaceControl");
        Class<?> builderClass = Class.forName("android.view.SurfaceControl$Builder");
        Object builder = declaredConstructor(builderClass, sessionClass).newInstance(session);
        invoke(builder, "setName", new Class<?>[]{String.class}, name);
        if (!invokeOptional(builder, "setSize",
                new Class<?>[]{int.class, int.class}, width, height)) {
            invoke(builder, "setBufferSize",
                    new Class<?>[]{int.class, int.class}, width, height);
        }
        invoke(builder, "setFormat", new Class<?>[]{int.class}, format);
        // Pie's builder defaults to HIDDEN. Keep the explicit flag where the OEM implementation
        // exposes it, so no uninitialised buffer can flash on the windscreen.
        invokeOptional(builder, "setFlags", new Class<?>[]{int.class}, 0x00000004);
        invokeOptional(builder, "setOpaque", new Class<?>[]{boolean.class}, opaque);
        Object control = invoke(builder, "build", new Class<?>[0]);

        Method open = declaredMethod(controlClass, "openTransaction");
        Method close = declaredMethod(controlClass, "closeTransaction");
        open.invoke(null);
        try {
            invoke(control, "setLayerStack", new Class<?>[]{int.class}, layerStack);
            invoke(control, "setLayer", new Class<?>[]{int.class}, layer);
            invoke(control, "setPosition",
                    new Class<?>[]{float.class, float.class}, (float) left, (float) top);
            invokeOptional(control, "setWindowCrop",
                    new Class<?>[]{android.graphics.Rect.class},
                    new android.graphics.Rect(0, 0, width, height));
            invokeOptional(control, "setAlpha", new Class<?>[]{float.class}, 1f);
        } finally {
            close.invoke(null);
        }

        Surface surface;
        try {
            Constructor<Surface> constructor = Surface.class.getDeclaredConstructor(controlClass);
            constructor.setAccessible(true);
            surface = constructor.newInstance(control);
        } catch (NoSuchMethodException missingConstructor) {
            Constructor<Surface> empty = Surface.class.getDeclaredConstructor();
            empty.setAccessible(true);
            surface = empty.newInstance();
            Method copyFrom = declaredMethod(Surface.class, "copyFrom", controlClass);
            copyFrom.invoke(surface, control);
        }
        return new DirectSurface(surface, control);
    }

    private static void drawMask(Surface surface) {
        Canvas canvas = null;
        try {
            canvas = surface.lockCanvas(null);
            canvas.drawColor(Color.BLACK);
        } finally {
            if (canvas != null) surface.unlockCanvasAndPost(canvas);
        }
    }

    private static void drawContent(Surface surface, Bitmap bitmap) {
        Canvas canvas = null;
        try {
            canvas = surface.lockCanvas(null);
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            canvas.drawBitmap(bitmap, 0f, 0f, null);
        } finally {
            if (canvas != null) surface.unlockCanvasAndPost(canvas);
        }
    }

    private static void setVisibility(Object maskControl, boolean maskVisible,
                                      Object contentControl, boolean contentVisible)
            throws Exception {
        Class<?> controlClass = contentControl.getClass();
        Method open = declaredMethod(controlClass, "openTransaction");
        Method close = declaredMethod(controlClass, "closeTransaction");
        open.invoke(null);
        try {
            invoke(maskControl, maskVisible ? "show" : "hide", new Class<?>[0]);
            invoke(contentControl, contentVisible ? "show" : "hide", new Class<?>[0]);
        } finally {
            close.invoke(null);
        }
    }

    private static void releaseSurfaces(DirectSurfaces surfaces) {
        if (surfaces == null) return;
        releaseDirectSurface(surfaces.content);
        releaseDirectSurface(surfaces.mask);
        if (surfaces.session != null) {
            try { invokeOptional(surfaces.session, "kill", new Class<?>[0]); }
            catch (Throwable ignored) {}
        }
    }

    private static void releaseDirectSurface(DirectSurface direct) {
        if (direct == null) return;
        Object control = direct.control;
        if (control != null) {
            try {
                Class<?> controlClass = control.getClass();
                Method open = declaredMethod(controlClass, "openTransaction");
                Method close = declaredMethod(controlClass, "closeTransaction");
                open.invoke(null);
                try {
                    invokeOptional(control, "hide", new Class<?>[0]);
                } finally {
                    close.invoke(null);
                }
            } catch (Throwable ignored) {}
        }
        if (direct.surface != null) {
            try { direct.surface.release(); } catch (Throwable ignored) {}
        }
        if (control != null) {
            try {
                if (!invokeOptional(control, "destroy", new Class<?>[0])) {
                    invokeOptional(control, "release", new Class<?>[0]);
                }
            } catch (Throwable ignored) {}
        }
    }

    private static Object invoke(Object target, String name, Class<?>[] parameterTypes,
                                 Object... arguments) throws Exception {
        Method method = declaredMethod(target.getClass(), name, parameterTypes);
        return method.invoke(target, arguments);
    }

    private static boolean invokeOptional(Object target, String name, Class<?>[] parameterTypes,
                                          Object... arguments) throws Exception {
        try {
            invoke(target, name, parameterTypes, arguments);
            return true;
        } catch (NoSuchMethodException missing) {
            return false;
        }
    }

    private static Method declaredMethod(Class<?> type, String name,
                                         Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Class<?> cursor = type;
        while (cursor != null) {
            try {
                Method method = cursor.getDeclaredMethod(name, parameterTypes);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException missing) {
                cursor = cursor.getSuperclass();
            }
        }
        throw new NoSuchMethodException(type.getName() + "#" + name);
    }

    private static <T> Constructor<T> declaredConstructor(Class<T> type,
                                                           Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Constructor<T> constructor = type.getDeclaredConstructor(parameterTypes);
        constructor.setAccessible(true);
        return constructor;
    }

    private static String shortMessage(Throwable failure) {
        String message = failure.getClass().getSimpleName();
        if (failure.getMessage() != null && !failure.getMessage().isEmpty()) {
            message += ": " + failure.getMessage();
        }
        return message.length() > 240 ? message.substring(0, 240) : message;
    }

    private static void closeQuietly(AutoCloseable value) {
        if (value == null) return;
        try { value.close(); } catch (Exception ignored) {}
    }

    private static final class DirectSurface {
        final Surface surface;
        final Object control;

        DirectSurface(Surface surface, Object control) {
            this.surface = surface;
            this.control = control;
        }
    }

    private static final class DirectSurfaces {
        final Object session;
        final DirectSurface mask;
        final DirectSurface content;
        final Surface maskSurface;
        final Object maskControl;
        final Surface contentSurface;
        final Object contentControl;

        DirectSurfaces(Object session, DirectSurface mask, DirectSurface content) {
            this.session = session;
            this.mask = mask;
            this.content = content;
            maskSurface = mask.surface;
            maskControl = mask.control;
            contentSurface = content.surface;
            contentControl = content.control;
        }
    }
}
