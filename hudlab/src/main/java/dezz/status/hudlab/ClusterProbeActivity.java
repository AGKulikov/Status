package dezz.status.hudlab;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.util.Set;

/* loaded from: classes4.dex */
public final class ClusterProbeActivity extends Activity {
    static final String ACTION_STATE = "dezz.status.hudlab26.action.CLUSTER_PROBE_STATE";
    private static final String CLUSTER_ACTIVITY_STATE = "android.car.cluster.ClusterActivityState";
    private static final long DEFAULT_DURATION_MS = 12000;
    static final String EVENT_STARTED = "STARTED";
    static final String EVENT_STOPPED = "STOPPED";
    static final String EVENT_UPDATED = "UPDATED";
    static final String EXTRA_DURATION_MS = "duration_ms";
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
        if (extras == null) {
            return "ClusterActivityState: extras отсутствуют";
        }
        Object rawState = null;
        try {
            rawState = extras.get("android.car.cluster.ClusterActivityState");
        } catch (Throwable th) {
        }
        if (!(rawState instanceof Bundle)) {
            return "ClusterActivityState: " + (rawState == null ? "НЕТ" : rawState.getClass().getName() + " · " + rawState) + "\nВсе extras: " + describeBundle(extras);
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
        return out.append("\nВсе extras: ").append(describeBundle(extras)).toString();
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

    private void emit(String event, String state) {
        Intent broadcast = new Intent(ACTION_STATE).setPackage(getPackageName()).putExtra("event", event).putExtra(EXTRA_STATE, state).putExtra("display_id", currentDisplayId());
        sendBroadcast(broadcast);
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
        title.setText("HUD LAB · ТЕСТ ПРИБОРКИ\nDISPLAY ID " + displayId);
        title.setTextColor(-1);
        title.setTextSize(34.0f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(17);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView marker = new TextView(this);
        marker.setText("Если появились штатные нижние блоки — оставьте экран открытым.\nHUD Lab на центральном дисплее сейчас записывает изменения.");
        marker.setTextColor(Color.rgb(147, 255, 205));
        marker.setTextSize(21.0f);
        marker.setGravity(17);
        marker.setPadding(0, dp(this, 30), 0, dp(this, 30));
        root.addView(marker, new LinearLayout.LayoutParams(-1, -2));
        TextView details = new TextView(this);
        this.detailsView = details;
        details.setText(state);
        details.setTextColor(Color.rgb(255, 218, 137));
        details.setTextSize(15.0f);
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
        String state = describeClusterActivityState(getIntent());
        setContentView(makeContent(state));
        emit(EVENT_STARTED, state);
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
        String state = describeClusterActivityState(intent);
        if (this.detailsView != null) {
            this.detailsView.setText(state);
        }
        emit(EVENT_UPDATED, state);
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
