/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dezz.status.hudlab.clusterprobe;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Set;

/**
 * A deliberately separate process/package used as the exact target of the MConfig launch flow.
 *
 * <p>HUD Lab must stay alive to coordinate and record the experiment. MConfig, however, kills
 * the target package before creating its task on display 2. Keeping this marker in a companion
 * package lets HUD Lab reproduce that behavior without killing its own controller and journal.</p>
 */
public final class ClusterProbeActivity extends Activity {
    private static final String CONTROLLER_PACKAGE = "dezz.status.hudlab29";
    private static final String ACTION_STATE =
            "dezz.status.hudlab29.action.CLUSTER_PROBE_STATE";
    private static final String EXTRA_DURATION_MS = "duration_ms";
    private static final String EXTRA_LAUNCH_TOKEN = "launch_token";
    private static final String EXTRA_EVENT = "event";
    private static final String EXTRA_STATE = "state";
    private static final String EXTRA_SOURCE_PACKAGE = "source_package";
    private static final String STATE_EXTRAS = "android.car:activityState.extras";
    private static final String STATE_UNOBSCURED = "android.car:activityState.unobscured";
    private static final String STATE_VISIBLE = "android.car:activityState.visible";
    private static final long DEFAULT_DURATION_MS = 30_000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView detailsView;
    private boolean stoppedEventSent;

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().getDecorView().setSystemUiVisibility(5894);

        String state = describeState(getIntent(), "ON_CREATE");
        setContentView(makeContent(state));
        emit("STARTED", state);
        getWindow().getDecorView().post(() -> updateAndEmit("LAYOUT", "FIRST_LAYOUT"));
        main.postDelayed(() -> updateAndEmit("LAYOUT", "LAYOUT_300MS"), 300L);
        main.postDelayed(() -> updateAndEmit("LAYOUT", "LAYOUT_1000MS"), 1_000L);

        long duration = getIntent() == null
                ? DEFAULT_DURATION_MS
                : getIntent().getLongExtra(EXTRA_DURATION_MS, DEFAULT_DURATION_MS);
        main.postDelayed(this::finish, Math.max(3_000L, duration));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String state = describeState(intent, "ON_NEW_INTENT");
        if (detailsView != null) {
            detailsView.setText(state);
        }
        emit("UPDATED", state);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateAndEmit("RESUMED", "ON_RESUME");
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        updateAndEmit("FOCUS", "WINDOW_FOCUS=" + hasFocus);
    }

    @Override
    public void onMultiWindowModeChanged(boolean isInMultiWindowMode) {
        super.onMultiWindowModeChanged(isInMultiWindowMode);
        updateAndEmit("MULTI_WINDOW", "MULTI_WINDOW=" + isInMultiWindowMode);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateAndEmit("CONFIG", "CONFIG_CHANGED");
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (!stoppedEventSent) {
            stoppedEventSent = true;
            emit("STOPPED", "HUD Lab Cluster Probe закрыт");
        }
        super.onDestroy();
    }

    private View makeContent(String state) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(android.view.Gravity.CENTER);
        root.setPadding(dp(this, 44), dp(this, 28), dp(this, 44), dp(this, 28));
        root.setBackgroundColor(Color.rgb(8, 37, 31));

        int displayId = currentDisplayId();
        TextView title = new TextView(this);
        title.setText("HUD LAB CLUSTER PROBE\nDISPLAY ID " + displayId);
        title.setTextColor(Color.WHITE);
        title.setTextSize(34.0f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(android.view.Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView marker = new TextView(this);
        marker.setText(displayId == 2
                ? "УСПЕХ: отдельный пакет создан на приборке"
                : "ОШИБКА: Activity создана на displayId=" + displayId);
        marker.setTextColor(Color.rgb(147, 255, 205));
        marker.setTextSize(21.0f);
        marker.setGravity(android.view.Gravity.CENTER);
        marker.setPadding(0, dp(this, 30), 0, dp(this, 30));
        root.addView(marker, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        detailsView = new TextView(this);
        detailsView.setText(state);
        detailsView.setTextColor(Color.rgb(255, 218, 137));
        detailsView.setTextSize(14.0f);
        detailsView.setTypeface(Typeface.MONOSPACE);
        detailsView.setGravity(android.view.Gravity.CENTER);
        root.addView(detailsView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return root;
    }

    private void updateAndEmit(String event, String phase) {
        String state = describeState(getIntent(), phase);
        if (detailsView != null) {
            detailsView.setText(state);
        }
        emit(event, state);
    }

    private void emit(String event, String state) {
        Intent launchIntent = getIntent();
        String token = launchIntent == null
                ? null
                : launchIntent.getStringExtra(EXTRA_LAUNCH_TOKEN);
        Intent broadcast = new Intent(ACTION_STATE)
                .setPackage(CONTROLLER_PACKAGE)
                .putExtra(EXTRA_EVENT, event)
                .putExtra(EXTRA_STATE, state)
                .putExtra(EXTRA_LAUNCH_TOKEN, token)
                .putExtra(EXTRA_SOURCE_PACKAGE, getPackageName())
                .putExtra("display_id", currentDisplayId());
        sendBroadcast(broadcast);
    }

    private String describeState(Intent intent, String phase) {
        return describeClusterActivityState(intent) + "\n" + describeWindowGeometry(phase);
    }

    private String describeClusterActivityState(Intent intent) {
        Bundle extras = intent == null ? null : intent.getExtras();
        String launch = "Activity: package=" + getPackageName()
                + ", displayId=" + currentDisplayId()
                + ", taskId=" + getTaskId()
                + ", token=" + (intent == null
                ? "нет"
                : intent.getStringExtra(EXTRA_LAUNCH_TOKEN));
        if (extras == null) {
            return launch + "\nClusterActivityState: extras отсутствуют";
        }
        Object rawState = null;
        try {
            rawState = extras.get("android.car.cluster.ClusterActivityState");
        } catch (Throwable ignored) {
            // Some vendor parcelables fail to load outside their process. Keep the rest of trace.
        }
        if (!(rawState instanceof Bundle)) {
            return launch + "\nClusterActivityState: "
                    + (rawState == null
                    ? "НЕТ"
                    : rawState.getClass().getName() + " · " + rawState)
                    + "\nВсе extras: " + describeBundle(extras);
        }
        Bundle state = (Bundle) rawState;
        boolean visible = state.getBoolean(STATE_VISIBLE, true);
        Rect unobscured = state.getParcelable(STATE_UNOBSCURED);
        Bundle stateExtras = state.getBundle(STATE_EXTRAS);
        StringBuilder out = new StringBuilder();
        out.append("ClusterActivityState: visible=")
                .append(visible)
                .append(", unobscured=")
                .append(unobscured)
                .append(", keys=")
                .append(state.keySet());
        if (stateExtras != null) {
            out.append(", extras=").append(describeBundle(stateExtras));
        }
        return launch + "\n" + out + "\nВсе extras: " + describeBundle(extras);
    }

    private static String describeBundle(Bundle bundle) {
        StringBuilder out = new StringBuilder("{");
        Set<String> keys = bundle.keySet();
        boolean first = true;
        for (String key : keys) {
            if (!first) {
                out.append(", ");
            }
            first = false;
            Object value;
            try {
                value = bundle.get(key);
            } catch (Throwable failure) {
                value = "<" + failure.getClass().getSimpleName() + ">";
            }
            out.append(key).append('=').append(value);
        }
        return out.append('}').toString();
    }

    private String describeWindowGeometry(String phase) {
        View decor = getWindow().getDecorView();
        DisplayMetrics real = new DisplayMetrics();
        try {
            getWindowManager().getDefaultDisplay().getRealMetrics(real);
        } catch (Throwable ignored) {
        }
        int[] location = {-1, -1};
        Rect visibleFrame = new Rect();
        try {
            decor.getLocationOnScreen(location);
            decor.getWindowVisibleDisplayFrame(visibleFrame);
        } catch (Throwable ignored) {
        }
        String insetsText = "нет";
        try {
            WindowInsets insets = decor.getRootWindowInsets();
            if (insets != null) {
                insetsText = "system="
                        + insets.getSystemWindowInsetLeft() + ","
                        + insets.getSystemWindowInsetTop() + ","
                        + insets.getSystemWindowInsetRight() + ","
                        + insets.getSystemWindowInsetBottom()
                        + " stable="
                        + insets.getStableInsetLeft() + ","
                        + insets.getStableInsetTop() + ","
                        + insets.getStableInsetRight() + ","
                        + insets.getStableInsetBottom();
            }
        } catch (Throwable ignored) {
        }
        WindowManager.LayoutParams attributes = getWindow().getAttributes();
        Configuration configuration = getResources().getConfiguration();
        return phase
                + ": displayId=" + currentDisplayId()
                + ", taskId=" + getTaskId()
                + ", multiWindow=" + isInMultiWindowMode()
                + "\n  real=" + real.widthPixels + "x" + real.heightPixels
                + " densityDpi=" + real.densityDpi
                + "\n  decor=" + decor.getWidth() + "x" + decor.getHeight()
                + " at=" + location[0] + "," + location[1]
                + " visibleFrame=" + visibleFrame
                + "\n  insets=" + insetsText
                + "\n  window: type=" + attributes.type
                + " flags=0x" + Integer.toHexString(attributes.flags)
                + " gravity=0x" + Integer.toHexString(attributes.gravity)
                + " size=" + attributes.width + "x" + attributes.height
                + " pos=" + attributes.x + "," + attributes.y
                + "\n  config: orientation=" + configuration.orientation
                + " uiMode=0x" + Integer.toHexString(configuration.uiMode)
                + " densityDpi=" + configuration.densityDpi
                + " smallestWidthDp=" + configuration.smallestScreenWidthDp
                + " screen=" + configuration.screenWidthDp
                + "x" + configuration.screenHeightDp + "dp";
    }

    private int currentDisplayId() {
        try {
            return getWindowManager().getDefaultDisplay().getDisplayId();
        } catch (Throwable failure) {
            return -1;
        }
    }
}
