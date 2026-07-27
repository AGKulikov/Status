/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dezz.status.hudlab;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Opaque replacement surface for the physical HUD display.
 *
 * <p>This is intentionally an Activity rather than a Presentation: the stock ECARX HUD uses
 * the same activity-on-display architecture.</p>
 */
public final class HudCoverActivity extends Activity {
    static final String ACTION_STOP = "dezz.status.hudlab.action.STOP_HUD_COVER";
    static final String EXTRA_MARKER = "marker";

    private FrameLayout root;

    private final BroadcastReceiver stopReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_STOP.equals(intent.getAction())) {
                finishAndRemoveTask();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);
        render(getIntent());
        registerReceiver(stopReceiver, new IntentFilter(ACTION_STOP));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        render(intent);
    }

    private void render(Intent intent) {
        if (root == null) return;
        root.removeAllViews();
        if (intent == null || !intent.getBooleanExtra(EXTRA_MARKER, false)) return;
        TextView marker = new TextView(this);
        marker.setText("HUD LAB · DISPLAY ID 2");
        marker.setTextColor(Color.rgb(80, 255, 145));
        marker.setTextSize(46);
        marker.setGravity(android.view.Gravity.CENTER);
        marker.setBackgroundColor(Color.rgb(0, 80, 30));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(728, 190);
        params.leftMargin = 0;
        params.topMargin = 720;
        root.addView(marker, params);
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(stopReceiver);
        } catch (IllegalArgumentException ignored) {
            // Receiver was already removed by the system.
        }
        super.onDestroy();
    }
}
