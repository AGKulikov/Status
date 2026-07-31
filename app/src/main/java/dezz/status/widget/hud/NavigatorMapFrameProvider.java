/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.nio.ByteBuffer;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Direct cross-process surface connection between Status Widget and the original Navigator mod.
 *
 * <p>There is intentionally no capture, MediaProjection, VirtualDisplay or tablet-map mirror in
 * this class. Navigator renders an independent MapKit map into an {@link ImageReader} surface;
 * Status Widget composites the latest frame with every independently configurable HUD element.
 * The explicit foreground service in Navigator keeps that producer alive when its main Activity
 * is minimized.</p>
 */
public final class NavigatorMapFrameProvider {
    public interface Listener { void onNavigatorMapFrameChanged(); }

    private static final String NAVIGATOR_PACKAGE = "ru.yandex.yandexnavi";
    private static final String NAVIGATOR_SERVICE =
            "ru.monjaro.hud.NavigatorHudBridgeService";
    private static final String ACTION_CONNECT =
            "ru.natro.statuswidget.navigatorhud.CONNECT_V1";
    private static final String ACTION_DISCONNECT =
            "ru.natro.statuswidget.navigatorhud.DISCONNECT_V1";
    private static final String ACTION_ACK =
            "ru.natro.statuswidget.navigatorhud.ACK_V1";
    private static final int CONTRACT_VERSION = 1;
    private static final long ACK_RETRY_MS = 3_000L;
    private static final long FRAME_RETRY_MS = 7_000L;
    private static final long READER_RECREATE_MS = 14_000L;
    private static final int MAX_ROUTE_CHARS = 196_608;

    @Nullable private static NavigatorMapFrameProvider instance;

    @NonNull private final Context context;
    @NonNull private final Handler main = new Handler(Looper.getMainLooper());
    @NonNull private final Map<Listener, HudPanelConfig> clients = new IdentityHashMap<>();
    @NonNull private final int[] pixels =
            new int[HudViewportPolicy.SAFE_WIDTH * HudViewportPolicy.SAFE_HEIGHT];
    @NonNull private final Bitmap[] frameBuffers = new Bitmap[3];

    @Nullable private HandlerThread imageThread;
    @Nullable private Handler imageHandler;
    @Nullable private ImageReader reader;
    @Nullable private volatile Bitmap frame;
    @Nullable private HudElementConfig mapConfig;
    @NonNull private String session = "";
    @NonNull private String state = "Карта навигатора выключена";
    @NonNull private String routePoints = "";
    @NonNull private String configurationSignature = "";
    private int writeBuffer;
    private long lastConnectAt;
    private long lastAckAt;
    private long lastFrameAt;
    private boolean ackRegistered;

    private final BroadcastReceiver ackReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ignored, Intent intent) {
            if (!ACTION_ACK.equals(intent.getAction())
                    || intent.getIntExtra("contract_version", 0) != CONTRACT_VERSION
                    || !session.equals(intent.getStringExtra("session"))) {
                return;
            }
            lastAckAt = SystemClock.elapsedRealtime();
            String nextState = bounded(intent.getStringExtra("state"), 64);
            String detail = bounded(intent.getStringExtra("detail"), 320);
            state = (nextState.isEmpty() ? "ACK" : nextState)
                    + (detail.isEmpty() ? "" : " · " + detail);
            notifyClients();
        }
    };

    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (clients.isEmpty()) return;
            HudElementConfig selected = chooseMapConfig();
            if (selected == null) {
                stopConnection();
                return;
            }
            mapConfig = selected;
            ensureReader();
            long now = SystemClock.elapsedRealtime();
            if (lastAckAt <= 0L || now - lastAckAt >= ACK_RETRY_MS) {
                sendConnect(true);
            } else if (lastFrameAt <= 0L || now - lastFrameAt >= FRAME_RETRY_MS) {
                if (now - lastConnectAt >= READER_RECREATE_MS) {
                    recreateReader();
                } else {
                    sendConnect(true);
                }
            }
            main.postDelayed(this, ACK_RETRY_MS);
        }
    };

    private NavigatorMapFrameProvider(@NonNull Context source) {
        Context app = source.getApplicationContext();
        context = app == null ? source : app;
    }

    @NonNull
    public static synchronized NavigatorMapFrameProvider get(@NonNull Context context) {
        if (instance == null) instance = new NavigatorMapFrameProvider(context);
        return instance;
    }

    public void attach(@NonNull HudPanelConfig config, @NonNull Listener listener) {
        runOnMain(() -> {
            clients.put(listener, config);
            registerAckReceiver();
            reconfigure();
        });
    }

    public void update(@NonNull HudPanelConfig config, @NonNull Listener listener) {
        runOnMain(() -> {
            if (!clients.containsKey(listener)) return;
            clients.put(listener, config);
            reconfigure();
        });
    }

    public void detach(@NonNull Listener listener) {
        runOnMain(() -> {
            clients.remove(listener);
            if (clients.isEmpty()) {
                stopConnection();
                unregisterAckReceiver();
            } else {
                reconfigure();
            }
        });
    }

    public void updateRoutePoints(@Nullable String value) {
        runOnMain(() -> {
            String next = bounded(value, MAX_ROUTE_CHARS);
            if (next.equals(routePoints)) return;
            routePoints = next;
            configurationSignature = "";
            if (mapConfig != null) sendConnect(false);
        });
    }

    @Nullable
    public Bitmap frame() {
        return frame;
    }

    @NonNull
    public String state() {
        return state;
    }

    private void reconfigure() {
        HudElementConfig selected = chooseMapConfig();
        mapConfig = selected;
        if (selected == null) {
            stopConnection();
            return;
        }
        ensureReader();
        sendConnect(false);
        main.removeCallbacks(watchdog);
        main.postDelayed(watchdog, ACK_RETRY_MS);
    }

    @Nullable
    private HudElementConfig chooseMapConfig() {
        HudElementConfig fallback = null;
        for (HudPanelConfig config : clients.values()) {
            for (HudElementConfig item : config.elements) {
                if (item.type != HudElementType.NAV_MAP) continue;
                if (fallback == null) fallback = item;
                if (item.enabled) return item;
            }
        }
        return fallback != null && fallback.enabled ? fallback : null;
    }

    private void ensureReader() {
        if (reader != null && reader.getSurface().isValid()) return;
        imageThread = new HandlerThread("navigator-map-frames",
                android.os.Process.THREAD_PRIORITY_DISPLAY);
        imageThread.start();
        imageHandler = new Handler(imageThread.getLooper());
        reader = ImageReader.newInstance(HudViewportPolicy.SAFE_WIDTH,
                HudViewportPolicy.SAFE_HEIGHT, PixelFormat.RGBA_8888, 3);
        reader.setOnImageAvailableListener(this::consumeLatestImage, imageHandler);
        for (int index = 0; index < frameBuffers.length; index++) {
            frameBuffers[index] = Bitmap.createBitmap(HudViewportPolicy.SAFE_WIDTH,
                    HudViewportPolicy.SAFE_HEIGHT, Bitmap.Config.ARGB_8888);
        }
        session = UUID.randomUUID().toString();
        frame = null;
        writeBuffer = 0;
        lastAckAt = 0L;
        lastFrameAt = 0L;
        configurationSignature = "";
        state = "Подключение карты навигатора…";
        notifyClients();
    }

    private void consumeLatestImage(ImageReader source) {
        Image image = null;
        try {
            image = source.acquireLatestImage();
            if (image == null || image.getWidth() != HudViewportPolicy.SAFE_WIDTH
                    || image.getHeight() != HudViewportPolicy.SAFE_HEIGHT) {
                return;
            }
            Image.Plane[] planes = image.getPlanes();
            if (planes.length == 0 || planes[0].getPixelStride() < 4) return;
            Image.Plane plane = planes[0];
            ByteBuffer bytes = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int width = HudViewportPolicy.SAFE_WIDTH;
            int height = HudViewportPolicy.SAFE_HEIGHT;
            for (int y = 0; y < height; y++) {
                int row = y * rowStride;
                int output = y * width;
                for (int x = 0; x < width; x++) {
                    int offset = row + x * pixelStride;
                    int red = bytes.get(offset) & 0xFF;
                    int green = bytes.get(offset + 1) & 0xFF;
                    int blue = bytes.get(offset + 2) & 0xFF;
                    int alpha = bytes.get(offset + 3) & 0xFF;
                    pixels[output + x] = (alpha << 24) | (red << 16) | (green << 8) | blue;
                }
            }
            Bitmap target = frameBuffers[writeBuffer++ % frameBuffers.length];
            target.setPixels(pixels, 0, width, 0, 0, width, height);
            frame = target;
            lastFrameAt = SystemClock.elapsedRealtime();
            state = "CONNECTED · кадры 728×190";
            main.post(this::notifyClients);
        } catch (RuntimeException ignored) {
            state = "Ошибка чтения кадра карты";
            main.post(this::notifyClients);
        } finally {
            if (image != null) {
                try { image.close(); }
                catch (RuntimeException ignored) {}
            }
        }
    }

    private void sendConnect(boolean force) {
        HudElementConfig selected = mapConfig;
        ImageReader current = reader;
        if (selected == null || current == null || !current.getSurface().isValid()) return;
        String signature = selected.options.toString() + '\u0000' + routePoints;
        long now = SystemClock.elapsedRealtime();
        if (!force && signature.equals(configurationSignature)) return;
        if (force && now - lastConnectAt < 750L) return;

        Intent intent = new Intent(ACTION_CONNECT)
                .setComponent(new ComponentName(NAVIGATOR_PACKAGE, NAVIGATOR_SERVICE))
                .putExtra("contract_version", CONTRACT_VERSION)
                .putExtra("session", session)
                .putExtra("surface", current.getSurface())
                .putExtra("width", HudViewportPolicy.SAFE_WIDTH)
                .putExtra("height", HudViewportPolicy.SAFE_HEIGHT)
                .putExtra("dpi", clamp(selected.options.optInt("dpi", 160), 72, 640))
                .putExtra("zoom_delta",
                        clamp(selected.options.optDouble("zoomDelta", 0d), -8d, 8d))
                .putExtra("tilt", clamp(selected.options.optInt("tilt", 60), 0, 80))
                .putExtra("scale_factor",
                        clamp(selected.options.optDouble("scaleFactor", 1d), .5d, 3d))
                .putExtra("fps", clamp(selected.options.optInt("fps", 20), 5, 30))
                .putExtra("night_mode", selected.options.optBoolean("nightMode", true))
                .putExtra("models_enabled",
                        selected.options.optBoolean("modelsEnabled", false))
                .putExtra("show_route", selected.options.optBoolean("showRoute", true))
                .putExtra("route_color",
                        selected.options.optString("routeColor", "#FFFFC400"))
                .putExtra("route_outline_color",
                        selected.options.optString("routeOutlineColor", "#FF16181D"))
                .putExtra("route_width",
                        clamp(selected.options.optDouble("routeWidth", 8d), 1d, 40d))
                .putExtra("route_outline_width",
                        clamp(selected.options.optDouble("routeOutlineWidth", 2d), 0d, 20d))
                .putExtra("map_style", NavigatorMapStyle.build(selected.options))
                .putExtra("route_points", routePoints);
        try {
            ContextCompat.startForegroundService(context, intent);
            configurationSignature = signature;
            lastConnectAt = now;
            state = "Подключение к исходному навигатору…";
        } catch (RuntimeException error) {
            state = "Навигатор с HUD-контрактом не найден: "
                    + error.getClass().getSimpleName();
        }
        notifyClients();
    }

    private void recreateReader() {
        disconnectProducer();
        closeReader();
        ensureReader();
        sendConnect(true);
    }

    private void stopConnection() {
        main.removeCallbacks(watchdog);
        disconnectProducer();
        closeReader();
        mapConfig = null;
        configurationSignature = "";
        state = "Карта навигатора выключена";
        notifyClients();
    }

    private void disconnectProducer() {
        if (session.isEmpty()) return;
        Intent intent = new Intent(ACTION_DISCONNECT)
                .setComponent(new ComponentName(NAVIGATOR_PACKAGE, NAVIGATOR_SERVICE))
                .putExtra("contract_version", CONTRACT_VERSION)
                .putExtra("session", session);
        try { context.startService(intent); }
        catch (RuntimeException ignored) {}
    }

    private void closeReader() {
        ImageReader current = reader;
        reader = null;
        if (current != null) {
            try { current.setOnImageAvailableListener(null, null); }
            catch (RuntimeException ignored) {}
            try { current.close(); }
            catch (RuntimeException ignored) {}
        }
        HandlerThread thread = imageThread;
        imageThread = null;
        imageHandler = null;
        if (thread != null) thread.quitSafely();
        frame = null;
        for (int index = 0; index < frameBuffers.length; index++) {
            frameBuffers[index] = null;
        }
        session = "";
        lastAckAt = 0L;
        lastFrameAt = 0L;
    }

    private void registerAckReceiver() {
        if (ackRegistered) return;
        try {
            ContextCompat.registerReceiver(context, ackReceiver,
                    new IntentFilter(ACTION_ACK), ContextCompat.RECEIVER_EXPORTED);
            ackRegistered = true;
        } catch (RuntimeException error) {
            state = "Не удалось включить канал подтверждения навигатора";
        }
    }

    private void unregisterAckReceiver() {
        if (!ackRegistered) return;
        try { context.unregisterReceiver(ackReceiver); }
        catch (RuntimeException ignored) {}
        ackRegistered = false;
    }

    private void notifyClients() {
        for (Listener listener : clients.keySet().toArray(new Listener[0])) {
            try { listener.onNavigatorMapFrameChanged(); }
            catch (RuntimeException ignored) {}
        }
    }

    private void runOnMain(@NonNull Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else main.post(action);
    }

    @NonNull
    private static String bounded(@Nullable String raw, int maximum) {
        String value = raw == null ? "" : raw.trim();
        if (value.length() > maximum || value.indexOf('\u0000') >= 0) return "";
        return value;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
