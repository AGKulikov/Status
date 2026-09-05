/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.dim;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Safe, optional bridge to the ECARX DIM menu contract used by mNavi.
 *
 * <p>No vendor class is linked from the common source set. Missing or late vendor services never
 * stop the custom panel: steering broadcasts remain active and reflection reconnects with bounded
 * non-blocking delays.</p>
 */
final class DimMenuVendorBridge {
    interface Listener {
        void onPrevious();
        void onNext();
        void onConfirm();
        void onVendorStateChanged();
    }

    static final String ACTION_SCROLL_UP =
            "ecarx.intent.action.ECARX_KEY_DIMSCROLLUP_EVENT";
    static final String ACTION_SCROLL_DOWN =
            "ecarx.intent.action.ECARX_KEY_DIMSCROLLDOWN_EVENT";
    static final String ACTION_CONFIRM =
            "ecarx.intent.action.ECARX_KEY_DIMCONFIRM_EVENT";
    private static final String TAG = "DimMenuVendor";
    private static final long[] RETRY_DELAYS_MS = {
            2_000L, 5_000L, 10_000L, 30_000L, 60_000L
    };

    @NonNull private final Context context;
    @NonNull private final Listener listener;
    @NonNull private final Handler main = new Handler(Looper.getMainLooper());
    private boolean started;
    private boolean receiverRegistered;
    private boolean connected;
    private boolean engineOn = true;
    private int currentTab = -1;
    private int controlCenterState;
    private int retryIndex;
    @Nullable private Object menu;
    @Nullable private Object callback;
    @Nullable private Method unregister;

    private final BroadcastReceiver steeringReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context receiverContext, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            if (ACTION_SCROLL_UP.equals(action)) listener.onPrevious();
            else if (ACTION_SCROLL_DOWN.equals(action)) listener.onNext();
            else if (ACTION_CONFIRM.equals(action)) listener.onConfirm();
        }
    };

    DimMenuVendorBridge(@NonNull Context context, @NonNull Listener listener) {
        Context app = context.getApplicationContext();
        this.context = app == null ? context : app;
        this.listener = listener;
    }

    void start() {
        if (started) return;
        started = true;
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_SCROLL_UP);
        filter.addAction(ACTION_SCROLL_DOWN);
        filter.addAction(ACTION_CONFIRM);
        try {
            ContextCompat.registerReceiver(context, steeringReceiver, filter,
                    ContextCompat.RECEIVER_EXPORTED);
            receiverRegistered = true;
        } catch (RuntimeException failure) {
            Log.w(TAG, "Could not register steering receiver", failure);
        }
        connect();
    }

    void stop() {
        started = false;
        main.removeCallbacksAndMessages(this);
        if (receiverRegistered) {
            receiverRegistered = false;
            try { context.unregisterReceiver(steeringReceiver); }
            catch (RuntimeException ignored) { }
        }
        Object currentMenu = menu;
        Object currentCallback = callback;
        Method currentUnregister = unregister;
        menu = null;
        callback = null;
        unregister = null;
        connected = false;
        if (currentMenu != null && currentCallback != null && currentUnregister != null) {
            try { currentUnregister.invoke(currentMenu, currentCallback); }
            catch (Throwable ignored) { }
        }
    }

    boolean isConnected() { return connected; }
    boolean isEngineOn() { return engineOn; }
    int currentTab() { return currentTab; }
    int controlCenterState() { return controlCenterState; }

    private void connect() {
        if (!started || connected) return;
        try {
            Class<?> interactionClass = Class.forName(
                    "com.ecarx.xui.adaptapi.diminteraction.DimInteraction");
            Object interaction = interactionClass.getMethod("create", Context.class)
                    .invoke(null, context);
            if (interaction == null) throw new IllegalStateException("DIM interaction missing");
            Object candidate = interactionClass.getMethod("getDimMenuInteraction")
                    .invoke(interaction);
            if (candidate == null) throw new IllegalStateException("DIM menu missing");
            Class<?> callbackClass = Class.forName(
                    "com.ecarx.xui.adaptapi.diminteraction.IDimMenuInteraction"
                            + "$IDimMenuInteractionCallback");
            Object proxy = Proxy.newProxyInstance(callbackClass.getClassLoader(),
                    new Class<?>[]{callbackClass}, (ignored, method, args) -> {
                        main.post(() -> handleCallback(method.getName(), args));
                        return null;
                    });
            Method register = candidate.getClass().getMethod(
                    "registerDimMenuInteractionCallback", callbackClass);
            Method remove = candidate.getClass().getMethod(
                    "unregisterDimMenuInteractionCallback", callbackClass);
            register.invoke(candidate, proxy);
            try { candidate.getClass().getMethod("notifyIHUReady").invoke(candidate); }
            catch (Throwable optional) {
                Log.i(TAG, "Optional notifyIHUReady unavailable");
            }
            menu = candidate;
            callback = proxy;
            unregister = remove;
            connected = true;
            retryIndex = 0;
            listener.onVendorStateChanged();
            Log.i(TAG, "ECARX DIM callback connected");
        } catch (Throwable unavailable) {
            connected = false;
            Log.i(TAG, "ECARX DIM callback not ready: "
                    + unavailable.getClass().getSimpleName());
            scheduleRetry();
        }
    }

    private void scheduleRetry() {
        if (!started) return;
        int index = Math.min(retryIndex, RETRY_DELAYS_MS.length - 1);
        retryIndex = Math.min(RETRY_DELAYS_MS.length - 1, retryIndex + 1);
        main.removeCallbacksAndMessages(this);
        main.postAtTime(this::connect, this,
                android.os.SystemClock.uptimeMillis() + RETRY_DELAYS_MS[index]);
    }

    private void handleCallback(@NonNull String method, @Nullable Object[] args) {
        if (!started) return;
        if ("onEngineStatusChanged".equals(method)) {
            engineOn = booleanArg(args, true);
        } else if ("onTabChanged".equals(method)) {
            currentTab = intArg(args, -1);
        } else if ("onControlCenterStateChanged".equals(method)) {
            controlCenterState = intArg(args, 0);
        }
        listener.onVendorStateChanged();
    }

    private static int intArg(@Nullable Object[] args, int fallback) {
        return args != null && args.length > 0 && args[0] instanceof Number
                ? ((Number) args[0]).intValue() : fallback;
    }

    private static boolean booleanArg(@Nullable Object[] args, boolean fallback) {
        return args != null && args.length > 0 && args[0] instanceof Boolean
                ? (Boolean) args[0] : fallback;
    }
}
