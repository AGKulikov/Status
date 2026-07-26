/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.View;

import androidx.annotation.NonNull;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import dezz.status.widget.shell.PrivilegedShell;

/**
 * Application-side renderer and lifecycle owner for {@link HudSurfaceBridgeMain}.
 *
 * <p>The normal WindowManager HUD remains visible until the bridge acknowledges its first complete
 * frame. This gives the device a seamless fallback when shell access or direct SurfaceFlinger
 * creation is unavailable.</p>
 */
final class HudSystemSurfaceWindow {
    interface Listener {
        void onReady(@NonNull HudSystemSurfaceWindow window);
        void onFailed(@NonNull HudSystemSurfaceWindow window, @NonNull String detail);
    }

    private static final String TAG = "HudSystemSurface";
    private static final int HELLO_OK = 1;
    private static final int FRAME_OK = 1;
    private static final int STOP = -1;
    private static final long FRAME_INTERVAL_MS = 250L;
    private static final int CONNECT_ATTEMPTS = 250;
    private static final int CONNECT_TIMEOUT_MS = 120;
    private static final int SOCKET_TIMEOUT_MS = 5_000;

    @NonNull private final Context appContext;
    @NonNull private final Handler main = new Handler(Looper.getMainLooper());
    @NonNull private final ExecutorService io =
            Executors.newSingleThreadExecutor(daemonThreadFactory());
    @NonNull private final AtomicReference<byte[]> pendingFrame = new AtomicReference<>();
    @NonNull private final AtomicBoolean drainScheduled = new AtomicBoolean();
    @NonNull private final HudCanvasView canvas;
    @NonNull private final Bitmap bitmap = Bitmap.createBitmap(
            HudViewportPolicy.SAFE_WIDTH,
            HudViewportPolicy.SAFE_HEIGHT,
            Bitmap.Config.ARGB_8888);
    @NonNull private final Listener listener;
    @NonNull private final String nonce;
    private final int port;
    private final int layerStack;
    private final int displayId;

    private volatile Socket socket;
    private volatile DataInputStream input;
    private volatile DataOutputStream output;
    private boolean connected;
    private boolean ready;
    private boolean dismissed;
    private boolean failureDelivered;

    @NonNull private final Runnable frameTick = new Runnable() {
        @Override public void run() {
            if (dismissed || !connected) return;
            renderAndQueue();
            main.postDelayed(this, FRAME_INTERVAL_MS);
        }
    };

    private HudSystemSurfaceWindow(@NonNull Context context,
                                   @NonNull Display display,
                                   @NonNull HudPanelConfig config,
                                   @NonNull HudRuntimeData data,
                                   @NonNull Listener listener) {
        appContext = context.getApplicationContext() == null
                ? context : context.getApplicationContext();
        this.listener = listener;
        displayId = display.getDisplayId();
        layerStack = resolveLayerStack(display);
        nonce = randomHex(16);
        port = reserveLoopbackPort();
        Context displayContext = appContext.createDisplayContext(display);
        canvas = new HudCanvasView(displayContext, config, data, false, null, true);
        int widthSpec = View.MeasureSpec.makeMeasureSpec(
                HudViewportPolicy.SAFE_WIDTH, View.MeasureSpec.EXACTLY);
        int heightSpec = View.MeasureSpec.makeMeasureSpec(
                HudViewportPolicy.SAFE_HEIGHT, View.MeasureSpec.EXACTLY);
        canvas.measure(widthSpec, heightSpec);
        canvas.layout(0, 0, HudViewportPolicy.SAFE_WIDTH, HudViewportPolicy.SAFE_HEIGHT);
    }

    @NonNull
    static HudSystemSurfaceWindow show(@NonNull Context context,
                                       @NonNull Display display,
                                       @NonNull HudPanelConfig config,
                                       @NonNull HudRuntimeData data,
                                       @NonNull Listener listener) {
        HudSystemSurfaceWindow window =
                new HudSystemSurfaceWindow(context, display, config, data, listener);
        window.startBridge();
        return window;
    }

    void updateConfig(@NonNull HudPanelConfig config) {
        if (dismissed) return;
        canvas.updateConfig(config);
        invalidateHud();
    }

    void invalidateHud() {
        if (dismissed || !connected) return;
        main.removeCallbacks(frameTick);
        renderAndQueue();
        main.postDelayed(frameTick, FRAME_INTERVAL_MS);
    }

    boolean isReady() {
        return ready && !dismissed;
    }

    int layerStack() {
        return layerStack;
    }

    void dismiss() {
        if (dismissed) return;
        dismissed = true;
        connected = false;
        main.removeCallbacks(frameTick);
        pendingFrame.set(null);
        io.execute(() -> {
            DataOutputStream current = output;
            if (current != null) {
                try {
                    current.writeInt(STOP);
                    current.flush();
                } catch (Exception ignored) {}
            }
            closeSocket();
        });
        io.shutdown();
        if (!bitmap.isRecycled()) bitmap.recycle();
    }

    private void startBridge() {
        String command = bridgeCommand();
        PrivilegedShell.get(appContext).runLongRunningCommand(
                command, new PrivilegedShell.LongRunningCommandCallback() {
            @Override
            public void onStarted() {
                if (!dismissed) io.execute(HudSystemSurfaceWindow.this::connectToBridge);
            }

            @Override
            public void onFinished(String commandOutput, String error) {
                if (dismissed) return;
                String detail = commandCompletionDetail(commandOutput, error);
                if (connected || ready) {
                    closeSocket();
                    fail("системный HUD bridge неожиданно завершился" + detail);
                } else {
                    fail("bridge завершился до открытия порта" + detail);
                }
            }
        });
    }

    private void connectToBridge() {
        Socket connectedSocket = null;
        Exception lastFailure = null;
        for (int attempt = 0; attempt < CONNECT_ATTEMPTS && !dismissed; attempt++) {
            try {
                connectedSocket = new Socket();
                connectedSocket.connect(new InetSocketAddress(
                        InetAddress.getByName("127.0.0.1"), port), CONNECT_TIMEOUT_MS);
                connectedSocket.setSoTimeout(SOCKET_TIMEOUT_MS);
                break;
            } catch (Exception failure) {
                lastFailure = failure;
                closeQuietly(connectedSocket);
                connectedSocket = null;
                SystemClock.sleep(40L);
            }
        }
        if (dismissed) {
            closeQuietly(connectedSocket);
            return;
        }
        if (connectedSocket == null) {
            failFromIo("bridge не запустился"
                    + (lastFailure == null ? "" : ": " + shortMessage(lastFailure)));
            return;
        }
        try {
            socket = connectedSocket;
            input = new DataInputStream(connectedSocket.getInputStream());
            output = new DataOutputStream(connectedSocket.getOutputStream());
            output.writeUTF(nonce);
            output.flush();
            int hello = input.readUnsignedByte();
            if (hello != HELLO_OK) {
                String detail = input.readUTF();
                throw new IllegalStateException(detail);
            }
            main.post(() -> {
                if (dismissed) {
                    closeSocket();
                    return;
                }
                connected = true;
                renderAndQueue();
                main.postDelayed(frameTick, FRAME_INTERVAL_MS);
            });
        } catch (Exception failure) {
            closeSocket();
            failFromIo("SurfaceFlinger отклонил слой: " + shortMessage(failure));
        }
    }

    /** Must run on the main thread because View.draw() is not thread-safe. */
    private void renderAndQueue() {
        if (dismissed || !connected || bitmap.isRecycled()) return;
        Canvas target = new Canvas(bitmap);
        target.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        canvas.draw(target);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream(96 * 1024);
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, encoded)) {
            fail("не удалось подготовить HUD-кадр");
            return;
        }
        pendingFrame.set(encoded.toByteArray());
        scheduleDrain();
    }

    private void scheduleDrain() {
        if (!drainScheduled.compareAndSet(false, true)) return;
        io.execute(this::drainFrames);
    }

    private void drainFrames() {
        try {
            while (!dismissed) {
                byte[] frame = pendingFrame.getAndSet(null);
                if (frame == null) break;
                DataOutputStream currentOutput = output;
                DataInputStream currentInput = input;
                if (currentOutput == null || currentInput == null) {
                    throw new IllegalStateException("HUD bridge socket is closed");
                }
                currentOutput.writeInt(frame.length);
                currentOutput.write(frame);
                currentOutput.flush();
                if (currentInput.readUnsignedByte() != FRAME_OK) {
                    throw new IllegalStateException("HUD bridge rejected a frame");
                }
                if (!ready) {
                    main.post(() -> {
                        if (dismissed || ready) return;
                        ready = true;
                        listener.onReady(this);
                    });
                }
            }
        } catch (Exception failure) {
            closeSocket();
            failFromIo("потеряна системная HUD-поверхность: " + shortMessage(failure));
        } finally {
            drainScheduled.set(false);
            if (!dismissed && pendingFrame.get() != null) scheduleDrain();
        }
    }

    private void failFromIo(@NonNull String detail) {
        main.post(() -> fail(detail));
    }

    private void fail(@NonNull String detail) {
        if (dismissed || failureDelivered) return;
        failureDelivered = true;
        connected = false;
        main.removeCallbacks(frameTick);
        Log.w(TAG, detail);
        listener.onFailed(this, detail);
    }

    @NonNull
    private String bridgeCommand() {
        String packageName = appContext.getPackageName();
        // Every dynamic value is generated locally from a strict alphabet; no user-supplied shell
        // input is interpolated into this command.
        return "APK=`pm path " + packageName
                + " | sed -n '1s/^package://p'`; "
                + "if [ -z \"$APK\" ]; then echo 'base APK path not found'; exit 70; fi; "
                + "export CLASSPATH=\"$APK\"; "
                + "app_process /system/bin " + HudSurfaceBridgeMain.class.getName()
                + " " + nonce
                + " " + port
                + " " + layerStack
                + " " + HudViewportPolicy.SAFE_LEFT
                + " " + HudViewportPolicy.SAFE_TOP
                + " " + HudViewportPolicy.SAFE_WIDTH
                + " " + HudViewportPolicy.SAFE_HEIGHT
                + " status_widget_hud_d" + displayId
                + " </dev/null 2>&1";
    }

    private void closeSocket() {
        closeQuietly(input);
        closeQuietly(output);
        closeQuietly(socket);
        input = null;
        output = null;
        socket = null;
    }

    private static int resolveLayerStack(@NonNull Display display) {
        try {
            Method method = Display.class.getDeclaredMethod("getLayerStack");
            method.setAccessible(true);
            Object value = method.invoke(display);
            if (value instanceof Integer && (Integer) value >= 0) return (Integer) value;
        } catch (Throwable ignored) {
            // Numeric Display ID remains authoritative; the verified ECARX mapping is below.
        }
        // The supplied SurfaceFlinger dump maps Android displayId=2 to layerStack=1. The fallback
        // is deliberately device-specific instead of guessing that display ID equals layer stack.
        return display.getDisplayId() == HudViewportPolicy.VERIFIED_DISPLAY_ID
                ? 1 : Math.max(0, display.getDisplayId());
    }

    private static int reserveLoopbackPort() {
        try (ServerSocket probe = new ServerSocket(
                0, 1, InetAddress.getByName("127.0.0.1"))) {
            return probe.getLocalPort();
        } catch (Exception failure) {
            // Rare fallback; the nonce handshake still prevents another local process from
            // impersonating the bridge if this port happens to be occupied.
            return 32_000 + new SecureRandom().nextInt(12_000);
        }
    }

    @NonNull
    private static String randomHex(int byteCount) {
        byte[] bytes = new byte[byteCount];
        new SecureRandom().nextBytes(bytes);
        StringBuilder out = new StringBuilder(byteCount * 2);
        for (byte value : bytes) {
            out.append(String.format(Locale.US, "%02x", value & 0xFF));
        }
        return out.toString();
    }

    @NonNull
    private static String shortMessage(@NonNull Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isEmpty()
                ? failure.getClass().getSimpleName() : message;
    }

    @NonNull
    private static String commandCompletionDetail(String output, String error) {
        String value = error != null && !error.trim().isEmpty() ? error : output;
        if (value == null || value.trim().isEmpty()) return " (без диагностического вывода)";
        value = value.trim().replace('\n', ' ').replace('\r', ' ');
        if (value.length() > 260) value = value.substring(0, 260);
        return ": " + value;
    }

    private static void closeQuietly(Object value) {
        if (value == null) return;
        try {
            if (value instanceof AutoCloseable) ((AutoCloseable) value).close();
        } catch (Exception ignored) {}
    }

    @NonNull
    private static ThreadFactory daemonThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "hud-system-surface");
            thread.setDaemon(true);
            return thread;
        };
    }
}
