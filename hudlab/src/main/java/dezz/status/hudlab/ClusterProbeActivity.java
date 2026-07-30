package dezz.status.hudlab;

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
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.util.Set;

/* loaded from: classes4.dex */
public final class ClusterProbeActivity extends Activity {
    static final String ACTION_STATE = "dezz.status.hudlab29.action.CLUSTER_PROBE_STATE";
    private static final String CLUSTER_ACTIVITY_STATE = "android.car.cluster.ClusterActivityState";
    private static final long DEFAULT_DURATION_MS = 30000;
    static final String EVENT_STARTED = "STARTED";
    static final String EVENT_RESUMED = "RESUMED";
    static final String EVENT_STOPPED = "STOPPED";
    static final String EVENT_UPDATED = "UPDATED";
    static final String EVENT_LAYOUT = "LAYOUT";
    static final String EVENT_FOCUS = "FOCUS";
    static final String EVENT_CONFIG = "CONFIG";
    static final String EVENT_MULTI_WINDOW = "MULTI_WINDOW";
    static final String EXTRA_DURATION_MS = "duration_ms";
    static final String EXTRA_LAUNCH_TOKEN = "launch_token";
    static final String EXTRA_EVENT = "event";
    static final String EXTRA_STATE = "state";
    private static final String STATE_EXTRAS = "android.car:activityState.extras";
    private static final String STATE_UNOBSCURED = "android.car:activityState.unobscured";
    private static final String STATE_VISIBLE = "android.car:activityState.visible";
    private static WeakReference<ClusterProbeActivity> active = new WeakReference<>(null);
    private TextView detailsView;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean stoppedEventSent;

    static void finishActive() {
        ClusterProbeActivity activity = active.get();
        if (activity != null && !activity.isFinishing()) {
            activity.finish();
        }
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private String describeClusterActivityState(Intent intent) {
        Bundle extras = intent == null ? null : intent.getExtras();
        String launch = "Activity: displayId=" + currentDisplayId()
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
        } catch (Throwable th) {
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
        Rect unobscured = (Rect) state.getParcelable(STATE_UNOBSCURED);
        Bundle stateExtras = state.getBundle(STATE_EXTRAS);
        StringBuilder out = new StringBuilder();
        out.append("ClusterActivityState: visible=").append(visible).append(", unobscured=").append(unobscured).append(", keys=").append(state.keySet());
        if (stateExtras != null) {
            out.append(", extras=").append(describeBundle(stateExtras));
        }
        return launch + "\n" + out.append("\nВсе extras: ").append(describeBundle(extras));
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
            Object value = bundle.get(key);
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
        int[] location = new int[]{-1, -1};
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

    private String describeState(Intent intent, String phase) {
        return describeClusterActivityState(intent)
                + "\n"
                + describeWindowGeometry(phase);
    }

    private void emit(String event, String state) {
        Intent launchIntent = getIntent();
        String launchToken = launchIntent == null
                ? null
                : launchIntent.getStringExtra(EXTRA_LAUNCH_TOKEN);
        Intent broadcast = new Intent(ACTION_STATE)
                .setPackage(getPackageName())
                .putExtra(EXTRA_EVENT, event)
                .putExtra(EXTRA_STATE, state)
                .putExtra(EXTRA_LAUNCH_TOKEN, launchToken)
                .putExtra("display_id", currentDisplayId());
        sendBroadcast(broadcast);
    }

    private void updateAndEmit(String event, String phase) {
        String state = describeState(getIntent(), phase);
        if (this.detailsView != null) {
            this.detailsView.setText(state);
        }
        emit(event, state);
    }

    private int currentDisplayId() {
        try {
            return getWindowManager().getDefaultDisplay().getDisplayId();
        } catch (Throwable th) {
            return -1;
        }
    }

    private View makeContent(String state) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(1);
        root.setGravity(17);
        root.setPadding(dp(this, 44), dp(this, 28), dp(this, 44), dp(this, 28));
        root.setBackgroundColor(Color.rgb(8, 37, 31));
        int displayId = currentDisplayId();
        TextView title = new TextView(this);
        title.setText("HUD LAB\nСОБСТВЕННЫЙ ЭКРАН\nDISPLAY ID " + displayId);
        title.setTextColor(-1);
        title.setTextSize(34.0f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(17);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView marker = new TextView(this);
        marker.setText(displayId == 2
                ? "УСПЕХ: Activity запущена на Android displayId=2"
                : "ОШИБКА: система перенесла Activity на другой displayId");
        marker.setTextColor(Color.rgb(147, 255, 205));
        marker.setTextSize(21.0f);
        marker.setGravity(17);
        marker.setPadding(0, dp(this, 30), 0, dp(this, 30));
        root.addView(marker, new LinearLayout.LayoutParams(-1, -2));
        TextView details = new TextView(this);
        this.detailsView = details;
        details.setText(state);
        details.setTextColor(Color.rgb(255, 218, 137));
        details.setTextSize(14.0f);
        details.setTypeface(Typeface.MONOSPACE);
        details.setGravity(17);
        root.addView(details, new LinearLayout.LayoutParams(-1, -2));
        return root;
    }

    @Override // android.app.Activity
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        active = new WeakReference<>(this);
        getWindow().addFlags(128);
        Window window = getWindow();
        window.setStatusBarColor(Color.BLACK);
        window.setNavigationBarColor(Color.BLACK);
        window.getDecorView().setSystemUiVisibility(5894);
        String state = describeState(getIntent(), "ON_CREATE");
        setContentView(makeContent(state));
        emit(EVENT_STARTED, state);
        getWindow().getDecorView().post(new Runnable() {
            @Override
            public void run() {
                updateAndEmit(EVENT_LAYOUT, "FIRST_LAYOUT");
            }
        });
        this.main.postDelayed(new Runnable() {
            @Override
            public void run() {
                updateAndEmit(EVENT_LAYOUT, "LAYOUT_300MS");
            }
        }, 300L);
        this.main.postDelayed(new Runnable() {
            @Override
            public void run() {
                updateAndEmit(EVENT_LAYOUT, "LAYOUT_1000MS");
            }
        }, 1000L);
        long duration = getIntent().getLongExtra(EXTRA_DURATION_MS, DEFAULT_DURATION_MS);
        this.main.postDelayed(new Runnable() { // from class: dezz.status.hudlab.ClusterProbeActivity$$ExternalSyntheticLambda0
            @Override // java.lang.Runnable
            public final void run() {
                ClusterProbeActivity.this.finish();
            }
        }, Math.max(3000L, duration));
    }

    @Override // android.app.Activity
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String state = describeState(intent, "ON_NEW_INTENT");
        if (this.detailsView != null) {
            this.detailsView.setText(state);
        }
        emit(EVENT_UPDATED, state);
    }

    @Override // android.app.Activity
    protected void onResume() {
        super.onResume();
        updateAndEmit(EVENT_RESUMED, "ON_RESUME");
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        updateAndEmit(EVENT_FOCUS, "WINDOW_FOCUS=" + hasFocus);
    }

    @Override
    public void onMultiWindowModeChanged(boolean isInMultiWindowMode) {
        super.onMultiWindowModeChanged(isInMultiWindowMode);
        updateAndEmit(EVENT_MULTI_WINDOW, "MULTI_WINDOW=" + isInMultiWindowMode);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateAndEmit(EVENT_CONFIG, "CONFIG_CHANGED");
    }

    @Override // android.app.Activity
    protected void onDestroy() {
        this.main.removeCallbacksAndMessages(null);
        if (!this.stoppedEventSent) {
            this.stoppedEventSent = true;
            emit(EVENT_STOPPED, "ClusterProbeActivity закрыта");
        }
        ClusterProbeActivity current = active.get();
        if (current == this) {
            active.clear();
        }
        super.onDestroy();
    }
}
