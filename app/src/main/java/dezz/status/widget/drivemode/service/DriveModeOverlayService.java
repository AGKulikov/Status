/* Copyright © 2026 Dezz; adapted for Natro. SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.drivemode.service;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.IBinder;
import android.provider.Settings;
import android.view.ContextThemeWrapper;
import android.widget.Toast;
import java.util.*;
import dezz.status.widget.R;
import dezz.status.widget.drivemode.MonjaroSelectorApp;
import dezz.status.widget.drivemode.car.*;
import dezz.status.widget.drivemode.knob.KnobReceiver;
import dezz.status.widget.drivemode.settings.*;
import dezz.status.widget.drivemode.ui.overlay.OverlayController;
import dezz.status.widget.media.DriveSelectorStepPolicy;

/** Original presentation, Natro's single vehicle owner, confirmed values only. */
public final class DriveModeOverlayService extends Service implements DriveModeRepository.Listener,
        DriveModeRepository.SupportedModesListener, SharedPreferences.OnSharedPreferenceChangeListener {
    public static final String ACTION_KNOB_STEP = "dezz.status.widget.drivemode.service.KNOB_STEP";
    public static final String ACTION_SHOW_PREVIEW = "dezz.status.widget.drivemode.service.SHOW_PREVIEW";
    public static final String EXTRA_ACTION_DEADLINE = "natro_action_deadline_uptime";
    private static DriveModeOverlayService instance;
    private DriveModeRepository repository;
    private DriveModeSettings settings;
    private OverlayController overlay;
    private List<Integer> enabled = Collections.emptyList();
    private boolean busy, destroyed, recreating;
    private int theme;
    private long operation;
    public static boolean isRunning() { return instance != null; }
    public static void onVisibilityChanged(boolean visible) {
        dezz.status.widget.media.DriveSelectorController.setShowing(visible);
        DriveModeOverlayService service = instance;
        if (!visible && service != null && !service.busy && !service.recreating && !service.settings.isEnabled())
            service.stopSelf();
    }
    @Override public void onCreate() {
        super.onCreate(); instance = this;
        startForeground(NotificationHelper.NOTIFICATION_ID, NotificationHelper.build(this));
        settings = MonjaroSelectorApp.get(this).getSettings(); repository = DriveModeRepository.get();
        repository.addListener(this); repository.addSupportedModesListener(this);
        settings.getPrefs().registerOnSharedPreferenceChangeListener(this);
        createOverlay(); refreshOrder();
    }
    private void createOverlay() {
        theme = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        overlay = new OverlayController(new ContextThemeWrapper(this, R.style.drive_selector_Theme_MonjaroDriveModes));
        overlay.setCarouselMode(settings.isCarouselMode());
        overlay.setOnModeTapListener(this::tap);
    }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY; }
        long deadline = intent == null ? Long.MAX_VALUE
                : intent.getLongExtra(EXTRA_ACTION_DEADLINE, Long.MAX_VALUE);
        if (android.os.SystemClock.uptimeMillis() > deadline) {
            if (!settings.isEnabled() && !busy) stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_SHOW_PREVIEW.equals(intent.getAction())) preview();
        else if (intent != null && ACTION_KNOB_STEP.equals(intent.getAction())) {
            int steps = intent.getIntExtra(KnobReceiver.EXTRA_STEPS, 1);
            String direction = intent.getStringExtra(KnobReceiver.EXTRA_DIRECTION);
            if (steps < 1 || steps > 3 || (!KnobReceiver.DIRECTION_PREV.equals(direction)
                    && !KnobReceiver.DIRECTION_NEXT.equals(direction))) return START_NOT_STICKY;
            step(KnobReceiver.DIRECTION_PREV.equals(direction) ? -steps : steps, deadline);
        }
        return settings.isEnabled() ? START_STICKY : START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    private void refreshOrder() {
        List<Integer> next = new ArrayList<>();
        for (ModeOrderEntry entry : settings.mergeWithSupported(repository.getSupportedModes()))
            if (entry.enabled && entry.code != 255) next.add(entry.code);
        enabled = Collections.unmodifiableList(next);
    }
    private void preview() {
        if (busy) return;
        long owner = ++operation;
        repository.readCurrentModeAsync(actual -> {
            if (destroyed || owner != operation) return;
            if (enabled.isEmpty()) { fail("Выберите активные режимы в настройках селектора"); return; }
            // Unknown or excluded actual is never substituted with an invented first mode.
            overlay.show(enabled, actual, settings.getAutoHidePreviewMs());
        });
    }
    private void step(int steps, long deadline) {
        if (busy) { toast("Дождитесь подтверждения режима"); return; }
        busy = true; long owner = ++operation;
        repository.readCurrentModeAsync(actual -> {
            if (destroyed || owner != operation) return;
            if (android.os.SystemClock.uptimeMillis() > deadline) { busy = false; return; }
            if (actual < 0) { fail("Текущий режим неизвестен — переключение не выполнено"); return; }
            List<Integer> order = new ArrayList<>(enabled);
            Integer target = DriveSelectorStepPolicy.target(order, actual, steps);
            if (target == null) {
                busy = false;
                if (order.isEmpty()) fail("Нет активных режимов");
                else overlay.show(order, actual, settings.getAutoHideSwitchMs());
                return;
            }
            repository.setModeConfirmed(target, ok -> {
                if (destroyed || owner != operation) return;
                busy = false;
                if (!ok) { fail("Режим не подтверждён автомобилем"); return; }
                List<Integer> sequence = new ArrayList<>();
                int start = order.indexOf(actual);
                if (start < 0) start = steps > 0 ? -1 : order.size();
                for (int n = 1; n <= Math.abs(steps); n++)
                    sequence.add(order.get(Math.floorMod(start + Integer.signum(steps) * n, order.size())));
                overlay.animateStepsTo(order, actual, sequence, settings.getAutoHideSwitchMs());
            });
        });
    }
    private void tap(int code) {
        if (busy || !enabled.contains(code)) return;
        busy = true; long owner = ++operation;
        repository.setModeConfirmed(code, ok -> {
            if (destroyed || owner != operation) return;
            busy = false;
            if (ok) overlay.show(enabled, code, 500);
            else fail("Режим не подтверждён автомобилем");
        });
    }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
    private void fail(String text) { busy = false; toast(text); if (!settings.isEnabled()) stopSelf(); }
    @Override public void onModeChanged(int previous, int current, DriveModeChangeOrigin origin) {
        if (current < 0) { overlay.hide(); return; }
        if (!busy && settings.isEnabled() && origin == DriveModeChangeOrigin.EXTERNAL && enabled.contains(current))
            overlay.show(enabled, current, settings.getAutoHideSwitchMs());
    }
    @Override public void onSupportedModesChanged(int[] supported) { if (repository != null) refreshOrder(); }
    @Override public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        if (PreferenceKeys.KEY_MODE_ORDER.equals(key)) refreshOrder();
        if (PreferenceKeys.KEY_CAROUSEL_MODE.equals(key) && overlay != null) overlay.setCarouselMode(settings.isCarouselMode());
        if ("natro_observe_enabled".equals(key) && !settings.isEnabled()) stopSelf();
    }
    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        if ((config.uiMode & Configuration.UI_MODE_NIGHT_MASK) != theme) {
            recreating = true; overlay.dispose(); createOverlay(); recreating = false;
            if (!busy && !settings.isEnabled()) stopSelf();
        }
    }
    @Override public void onDestroy() {
        destroyed = true; ++operation; instance = null;
        dezz.status.widget.media.DriveSelectorController.setShowing(false);
        if (settings != null) settings.getPrefs().unregisterOnSharedPreferenceChangeListener(this);
        if (overlay != null) overlay.dispose();
        if (repository != null) { repository.removeListener(this); repository.removeSupportedModesListener(this); repository.shutdown(); }
        super.onDestroy();
    }
}
