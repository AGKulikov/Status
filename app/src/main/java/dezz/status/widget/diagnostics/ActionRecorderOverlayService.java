/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.diagnostics;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import dezz.status.widget.DiagnosticsActivity;
import dezz.status.widget.Preferences;
import dezz.status.widget.R;
import dezz.status.widget.car.CarIntegrations;

/** Movable always-on-top control for action recording. */
public final class ActionRecorderOverlayService extends Service {
    public static final String ACTION_SHOW =
            "dezz.status.widget.action.SHOW_ACTION_RECORDER";
    public static final String ACTION_HIDE =
            "dezz.status.widget.action.HIDE_ACTION_RECORDER";
    public static final String ACTION_TOGGLE =
            "dezz.status.widget.action.TOGGLE_ACTION_RECORDER";

    private static final String CHANNEL_ID = "status_action_recorder";
    private static final int NOTIFICATION_ID = 7384;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable timerTick = new Runnable() {
        @Override public void run() {
            refreshState();
            main.postDelayed(this, 500L);
        }
    };

    private Preferences preferences;
    private WindowManager windowManager;
    private WindowManager.LayoutParams windowParams;
    private LinearLayout frame;
    private TextView state;
    private Button record;
    private float touchRawX;
    private float touchRawY;
    private int touchWindowX;
    private int touchWindowY;

    public static void show(@NonNull Context context) {
        Preferences values = new Preferences(context);
        values.actionRecorderOverlayVisible.set(true);
        Intent intent = new Intent(context, ActionRecorderOverlayService.class)
                .setAction(ACTION_SHOW);
        ContextCompat.startForegroundService(context, intent);
    }

    public static void hide(@NonNull Context context) {
        Preferences values = new Preferences(context);
        values.actionRecorderOverlayVisible.set(false);
        context.startService(new Intent(context, ActionRecorderOverlayService.class)
                .setAction(ACTION_HIDE));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        preferences = new Preferences(this);
        DiagnosticJournal.initialize(this, preferences.debugModeEnabled.get());
        ActionRecorder.initialize(this);
        CarIntegrations.get(this);
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, notification());
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_SHOW : intent.getAction();
        ActionRecorder.recordServiceIntent(getClass().getName(), action, startId);
        if (ACTION_HIDE.equals(action)) {
            preferences.actionRecorderOverlayVisible.set(false);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_TOGGLE.equals(action)) toggleRecording();
        if (!preferences.actionRecorderOverlayVisible.get()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        ensureWindow();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        main.removeCallbacks(timerTick);
        if (frame != null && windowManager != null) {
            try {
                windowManager.removeViewImmediate(frame);
            } catch (RuntimeException ignored) {
            }
        }
        frame = null;
        ActionRecorder.recordOverlay("action_recorder", "CLOSED", "service destroyed");
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void ensureWindow() {
        if (frame != null) {
            refreshGeometry();
            refreshState();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !Settings.canDrawOverlays(this)) {
            DiagnosticJournal.warn("recorder", "overlay permission is missing");
            Toast.makeText(this, "Разрешите показ поверх других приложений",
                    Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException ignored) {
            }
            stopSelf();
            return;
        }
        if (windowManager == null) {
            stopSelf();
            return;
        }

        frame = new LinearLayout(this);
        frame.setOrientation(LinearLayout.HORIZONTAL);
        frame.setGravity(Gravity.CENTER_VERTICAL);
        frame.setPadding(dp(8), dp(6), dp(8), dp(6));
        frame.setElevation(dp(12));
        applyFrameAppearance();

        state = new TextView(this);
        state.setTextColor(Color.WHITE);
        state.setTextSize(14);
        state.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        state.setGravity(Gravity.CENTER_VERTICAL);
        state.setPadding(dp(4), 0, dp(8), 0);
        state.setOnTouchListener(this::dragFrame);
        frame.addView(state, new LinearLayout.LayoutParams(
                0, dp(52), 1f));

        record = button("Запись");
        record.setOnClickListener(view -> toggleRecording());
        frame.addView(record, new LinearLayout.LayoutParams(dp(108), dp(52)));

        Button marker = button("Метка");
        marker.setOnClickListener(view -> {
            ActionRecorder.mark("floating control");
            Toast.makeText(this, "Метка добавлена", Toast.LENGTH_SHORT).show();
        });
        frame.addView(marker, new LinearLayout.LayoutParams(dp(88), dp(52)));

        Button smaller = button("−");
        smaller.setOnClickListener(view -> resize(-40));
        frame.addView(smaller, new LinearLayout.LayoutParams(dp(50), dp(52)));
        Button larger = button("+");
        larger.setOnClickListener(view -> resize(40));
        frame.addView(larger, new LinearLayout.LayoutParams(dp(50), dp(52)));

        windowParams = new WindowManager.LayoutParams(
                clamp(preferences.actionRecorderOverlayWidth.get(), 330, 760),
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT);
        windowParams.gravity = Gravity.TOP | Gravity.START;
        windowParams.x = Math.max(0, preferences.actionRecorderOverlayX.get());
        windowParams.y = Math.max(0, preferences.actionRecorderOverlayY.get());
        windowParams.setTitle("Status Widget action recorder");
        try {
            windowManager.addView(frame, windowParams);
            ActionRecorder.recordOverlay("action_recorder", "OPENED", "persistent control");
            main.removeCallbacks(timerTick);
            main.post(timerTick);
        } catch (RuntimeException failure) {
            DiagnosticJournal.error("recorder", "could not attach control overlay", failure);
            frame = null;
            stopSelf();
        }
    }

    private void toggleRecording() {
        if (ActionRecorder.isRecording()) {
            ActionRecorder.stop("floating control");
        } else {
            ActionRecorder.start("floating control");
        }
        refreshState();
    }

    private void refreshState() {
        if (state == null || record == null) return;
        if (ActionRecorder.isRecording()) {
            long seconds = Math.max(0L,
                    (System.currentTimeMillis() - ActionRecorder.startedAt()) / 1_000L);
            state.setText(String.format(java.util.Locale.US,
                    "● REC  %02d:%02d", seconds / 60L, seconds % 60L));
            state.setTextColor(0xFFFF453A);
            record.setText("Стоп");
        } else {
            state.setText("Регистратор");
            state.setTextColor(Color.WHITE);
            record.setText("Запись");
        }
    }

    private boolean dragFrame(View view, MotionEvent event) {
        if (windowParams == null || windowManager == null || frame == null) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchRawX = event.getRawX();
                touchRawY = event.getRawY();
                touchWindowX = windowParams.x;
                touchWindowY = windowParams.y;
                return true;
            case MotionEvent.ACTION_MOVE:
                windowParams.x = Math.max(0,
                        touchWindowX + Math.round(event.getRawX() - touchRawX));
                windowParams.y = Math.max(0,
                        touchWindowY + Math.round(event.getRawY() - touchRawY));
                try {
                    windowManager.updateViewLayout(frame, windowParams);
                } catch (RuntimeException ignored) {
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                preferences.actionRecorderOverlayX.set(windowParams.x);
                preferences.actionRecorderOverlayY.set(windowParams.y);
                return true;
            default:
                return false;
        }
    }

    private void resize(int deltaPx) {
        if (windowParams == null || windowManager == null || frame == null) return;
        windowParams.width = clamp(windowParams.width + deltaPx, 330, 760);
        preferences.actionRecorderOverlayWidth.set(windowParams.width);
        try {
            windowManager.updateViewLayout(frame, windowParams);
        } catch (RuntimeException ignored) {
        }
    }

    private void refreshGeometry() {
        if (windowParams == null || windowManager == null || frame == null) return;
        windowParams.width = clamp(preferences.actionRecorderOverlayWidth.get(),
                330, 760);
        windowParams.x = Math.max(0, preferences.actionRecorderOverlayX.get());
        windowParams.y = Math.max(0, preferences.actionRecorderOverlayY.get());
        applyFrameAppearance();
        try {
            windowManager.updateViewLayout(frame, windowParams);
        } catch (RuntimeException ignored) {
        }
    }

    private void applyFrameAppearance() {
        if (frame == null) return;
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xF0181B22);
        background.setCornerRadius(dp(14));
        background.setStroke(dp(1), 0x99FFFFFF);
        frame.setBackground(background);
        frame.setAlpha(clamp(preferences.actionRecorderOverlayAlpha.get(),
                80, 255) / 255f);
    }

    @NonNull
    private Button button(@NonNull String label) {
        Button value = new Button(this);
        value.setAllCaps(false);
        value.setText(label);
        value.setTextSize(13);
        value.setMinWidth(0);
        value.setMinimumWidth(0);
        value.setPadding(dp(4), 0, dp(4), 0);
        return value;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "Регистратор действий", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Плавающее управление диагностической записью");
        manager.createNotificationChannel(channel);
    }

    @NonNull
    private Notification notification() {
        PendingIntent settings = PendingIntent.getActivity(this, 0,
                new Intent(this, DiagnosticsActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_status_gps_good)
                .setContentTitle("Регистратор действий")
                .setContentText("Плавающее управление включено")
                .setContentIntent(settings)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
