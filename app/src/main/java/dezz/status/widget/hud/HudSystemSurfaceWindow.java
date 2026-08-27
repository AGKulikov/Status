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
import androidx.annotation.Nullable;

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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

import dezz.status.widget.automation.AutomationState;
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
    /** Snow is the only deliberately continuous effect; all normal frames are data-driven. */
    private static final long SNOW_FRAME_INTERVAL_MS = 250L;
    private static final long WARNING_BLINK_INTERVAL_MS = 500L;
    private static final int CONNECT_ATTEMPTS = 250;
    private static final int CONNECT_TIMEOUT_MS = 120;
    private static final int SOCKET_TIMEOUT_MS = 5_000;

    @NonNull private final Context appContext;
    @NonNull private final Handler main = new Handler(Looper.getMainLooper());
    @NonNull private final ExecutorService io =
            Executors.newSingleThreadExecutor(daemonThreadFactory());
    @NonNull private final Object frameLock = new Object();
    @NonNull private final FrameBuffer[] frameBuffers = {
            new FrameBuffer(), new FrameBuffer()
    };
    @Nullable private FrameBuffer renderingFrame;
    @Nullable private FrameBuffer pendingFrame;
    @Nullable private FrameBuffer encodingFrame;
    private boolean drainScheduled;
    @NonNull private final AtomicBoolean dirtyFramePosted = new AtomicBoolean();
    @NonNull private final HudCanvasView canvas;
    @NonNull private final HudRuntimeData data;
    @NonNull private HudPanelConfig config;
    @NonNull private final Listener listener;
    @NonNull private final String nonce;
    private final int port;
    private final int layerStack;
    private final int displayId;

    private volatile Socket socket;
    private volatile DataInputStream input;
    private volatile DataOutputStream output;
    private volatile boolean connected;
    private volatile boolean ready;
    private volatile boolean dismissed;
    private boolean failureDelivered;

    /** Re-armed only while a configured effect is currently time-dependent. */
    @NonNull private final Runnable animationTick = new Runnable() {
        @Override public void run() {
            if (dismissed || !connected) return;
            invalidateHud();
        }
    };
    /** One no-delay main-loop task coalesces bursts of telemetry into their newest snapshot. */
    @NonNull private final Runnable renderDirtyFrame = new Runnable() {
        @Override public void run() {
            dirtyFramePosted.set(false);
            if (dismissed || !connected) return;
            renderAndQueue();
            scheduleNextAnimationFrame();
        }
    };

    private HudSystemSurfaceWindow(@NonNull Context context,
                                   @NonNull Display display,
                                   @NonNull HudPanelConfig config,
                                   @NonNull HudRuntimeData data,
                                   @NonNull Listener listener) {
        appContext = context.getApplicationContext() == null
                ? context : context.getApplicationContext();
        this.config = config;
        this.data = data;
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
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> updateConfig(config));
            return;
        }
        if (dismissed) return;
        this.config = config;
        canvas.updateConfig(config);
        invalidateHud();
    }

    void invalidateHud() {
        if (dismissed || !connected) return;
        if (dirtyFramePosted.compareAndSet(false, true)) main.post(renderDirtyFrame);
    }

    boolean isReady() {
        return ready && !dismissed;
    }

    int layerStack() {
        return layerStack;
    }

    void dismiss() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(this::dismiss);
            return;
        }
        if (dismissed) return;
        dismissed = true;
        connected = false;
        main.removeCallbacks(animationTick);
        main.removeCallbacks(renderDirtyFrame);
        dirtyFramePosted.set(false);
        synchronized (frameLock) {
            pendingFrame = null;
        }
        try {
            io.execute(() -> {
                DataOutputStream current = output;
                if (current != null) {
                    try {
                        current.writeInt(STOP);
                        current.flush();
                    } catch (Exception ignored) {}
                }
                closeSocket();
                recycleFrameBuffers();
            });
        } catch (RejectedExecutionException stopped) {
            closeSocket();
            recycleFrameBuffers();
        }
        io.shutdown();
    }

    private void startBridge() {
        String command = bridgeCommand();
        PrivilegedShell.get(appContext).runLongRunningCommand(
                command, new PrivilegedShell.LongRunningCommandCallback() {
            @Override
            public void onStarted() {
                if (dismissed) return;
                try {
                    io.execute(HudSystemSurfaceWindow.this::connectToBridge);
                } catch (RejectedExecutionException stopped) {
                    if (!dismissed) failFromIo("очередь запуска HUD bridge остановлена");
                }
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
                invalidateHud();
            });
        } catch (Exception failure) {
            closeSocket();
            failFromIo("SurfaceFlinger отклонил слой: " + shortMessage(failure));
        }
    }

    /**
     * Draws only the Android View snapshot on main. The pending buffer can be overwritten while the
     * worker encodes its sibling, so a slow PNG/socket never builds an unbounded frame queue.
     */
    private void renderAndQueue() {
        if (dismissed || !connected) return;
        final FrameBuffer target;
        synchronized (frameLock) {
            // A not-yet-encoded pending frame is owned by main and is safe to replace in place.
            target = pendingFrame != null ? pendingFrame : availableFrameLocked();
            if (target == null || target.bitmap.isRecycled()) return;
            pendingFrame = null;
            renderingFrame = target;
        }

        try {
            target.canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            canvas.draw(target.canvas);
        } catch (RuntimeException failure) {
            synchronized (frameLock) {
                if (renderingFrame == target) renderingFrame = null;
            }
            fail("не удалось нарисовать HUD-кадр: " + shortMessage(failure));
            return;
        }

        synchronized (frameLock) {
            if (renderingFrame == target) renderingFrame = null;
            if (dismissed) return;
            // Replaces the prior pending snapshot; the worker will always take the newest one.
            pendingFrame = target;
        }
        scheduleDrain();
    }

    @Nullable
    private FrameBuffer availableFrameLocked() {
        for (FrameBuffer candidate : frameBuffers) {
            if (candidate != encodingFrame && candidate != renderingFrame) return candidate;
        }
        return null;
    }

    private void scheduleDrain() {
        synchronized (frameLock) {
            if (dismissed || !connected || drainScheduled || pendingFrame == null) return;
            drainScheduled = true;
        }
        try {
            io.execute(this::drainFrames);
        } catch (RejectedExecutionException stopped) {
            synchronized (frameLock) {
                drainScheduled = false;
            }
            if (!dismissed) failFromIo("очередь HUD-кодировщика остановлена");
        }
    }

    private void drainFrames() {
        ReusableByteArrayOutputStream encoded = new ReusableByteArrayOutputStream(96 * 1024);
        try {
            while (!dismissed) {
                FrameBuffer frame;
                synchronized (frameLock) {
                    frame = pendingFrame;
                    pendingFrame = null;
                    encodingFrame = frame;
                }
                if (frame == null) break;
                encoded.reset();
                if (!frame.bitmap.compress(Bitmap.CompressFormat.PNG, 100, encoded)) {
                    throw new IllegalStateException("PNG encoder rejected HUD bitmap");
                }
                DataOutputStream currentOutput = output;
                DataInputStream currentInput = input;
                if (currentOutput == null || currentInput == null) {
                    throw new IllegalStateException("HUD bridge socket is closed");
                }
                currentOutput.writeInt(encoded.size());
                currentOutput.write(encoded.buffer(), 0, encoded.size());
                currentOutput.flush();
                if (currentInput.readUnsignedByte() != FRAME_OK) {
                    throw new IllegalStateException("HUD bridge rejected a frame");
                }
                synchronized (frameLock) {
                    if (encodingFrame == frame) encodingFrame = null;
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
            connected = false;
            closeSocket();
            failFromIo("потеряна системная HUD-поверхность: " + shortMessage(failure));
        } finally {
            boolean hasPending;
            synchronized (frameLock) {
                encodingFrame = null;
                drainScheduled = false;
                hasPending = !dismissed && connected && pendingFrame != null;
            }
            if (hasPending) scheduleDrain();
        }
    }

    private void scheduleNextAnimationFrame() {
        main.removeCallbacks(animationTick);
        if (dismissed || !connected) return;
        long delay = nextAnimationDelayMillis();
        if (delay > 0L) main.postDelayed(animationTick, delay);
    }

    /** No timer exists for a static HUD; only an actively changing visual effect returns a delay. */
    private long nextAnimationDelayMillis() {
        long now = SystemClock.uptimeMillis();
        long next = config.snowMode ? SNOW_FRAME_INTERVAL_MS : Long.MAX_VALUE;
        for (HudElementConfig item : config.elements) {
            if (!item.enabled) continue;
            AutomationState automation = data.automation(item);
            if (automation.present && !automation.visible) continue;
            if ((item.type == HudElementType.TURN_SIGNAL_LEFT
                    || item.type == HudElementType.TURN_SIGNAL_RIGHT)
                    && item.options.optBoolean("animated", true) && data.active(item)) {
                long frequency = Math.max(150L,
                        item.options.optLong("blinkFrequencyMs", 500L));
                next = Math.min(next, delayToBoundary(now, frequency));
            } else if (item.type == HudElementType.NAV_SPEED_LIMIT
                    && item.options.optBoolean("overspeedBlink", true)
                    && isSpeedLimitWarningActive(item)) {
                next = Math.min(next, delayToBoundary(now, WARNING_BLINK_INTERVAL_MS));
            } else if (isTirePressure(item.type)
                    && item.options.optBoolean("blinkBelowThreshold", true)) {
                double value = data.numericValue(item);
                if (Double.isFinite(value)
                        && value < item.options.optDouble("lowThreshold", 2d)) {
                    next = Math.min(next,
                            delayToBoundary(now, WARNING_BLINK_INTERVAL_MS));
                }
            }
        }
        return next == Long.MAX_VALUE ? -1L : Math.max(1L, next);
    }

    private boolean isSpeedLimitWarningActive(@NonNull HudElementConfig item) {
        double limit = unsignedDigits(data.textFor(item));
        double current = data.numericValue(item);
        return Double.isFinite(limit) && Double.isFinite(current)
                && current > limit + item.options.optInt("overspeedDelta", 10);
    }

    /** Matches the canvas' digits-only speed-limit parsing without allocating a regex result. */
    private static double unsignedDigits(@NonNull String text) {
        long value = 0L;
        boolean found = false;
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character < '0' || character > '9') continue;
            found = true;
            value = Math.min(Integer.MAX_VALUE, value * 10L + character - '0');
        }
        return found ? value : Double.NaN;
    }

    private static boolean isTirePressure(@NonNull HudElementType type) {
        return type == HudElementType.TIRE_PRESSURE_FRONT_LEFT
                || type == HudElementType.TIRE_PRESSURE_FRONT_RIGHT
                || type == HudElementType.TIRE_PRESSURE_REAR_LEFT
                || type == HudElementType.TIRE_PRESSURE_REAR_RIGHT;
    }

    static long delayToBoundary(long nowMillis, long intervalMillis) {
        long interval = Math.max(1L, intervalMillis);
        long normalized = Math.max(0L, nowMillis);
        long remainder = normalized % interval;
        return remainder == 0L ? interval : interval - remainder;
    }

    private void failFromIo(@NonNull String detail) {
        main.post(() -> fail(detail));
    }

    private void fail(@NonNull String detail) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> fail(detail));
            return;
        }
        if (dismissed || failureDelivered) return;
        failureDelivered = true;
        connected = false;
        main.removeCallbacks(animationTick);
        main.removeCallbacks(renderDirtyFrame);
        dirtyFramePosted.set(false);
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

    private void recycleFrameBuffers() {
        synchronized (frameLock) {
            pendingFrame = null;
            renderingFrame = null;
            encodingFrame = null;
            for (FrameBuffer frame : frameBuffers) frame.recycle();
        }
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
        // Hidden-API reflection is blocked on the user's Android 9 firmware. The supplied ECARX
        // dump maps Android displayId=2 (local:2) to layerStack=2. Keep that hardware mapping
        // explicit so a successful bridge can never render on the neighbouring HDMI stack.
        return display.getDisplayId() == HudViewportPolicy.VERIFIED_DISPLAY_ID
                ? HudViewportPolicy.VERIFIED_LAYER_STACK
                : Math.max(0, display.getDisplayId());
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
            Thread thread = new Thread(() -> {
                try {
                    android.os.Process.setThreadPriority(
                            android.os.Process.THREAD_PRIORITY_BACKGROUND);
                } catch (RuntimeException ignored) {}
                runnable.run();
            }, "hud-system-surface");
            thread.setDaemon(true);
            return thread;
        };
    }

    /** One of two fixed-size buffers; Canvas allocation never appears in the steady-state path. */
    private static final class FrameBuffer {
        @NonNull final Bitmap bitmap = Bitmap.createBitmap(
                HudViewportPolicy.SAFE_WIDTH, HudViewportPolicy.SAFE_HEIGHT,
                Bitmap.Config.ARGB_8888);
        @NonNull final Canvas canvas = new Canvas(bitmap);

        void recycle() {
            canvas.setBitmap(null);
            if (!bitmap.isRecycled()) bitmap.recycle();
        }
    }

    /** Exposes ByteArrayOutputStream's reusable backing array to avoid one byte[] per HUD frame. */
    private static final class ReusableByteArrayOutputStream extends ByteArrayOutputStream {
        ReusableByteArrayOutputStream(int size) { super(size); }
        byte[] buffer() { return buf; }
    }
}

