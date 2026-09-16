/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.media;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import dezz.status.widget.car.CarControlCommand;
import dezz.status.widget.car.CarControlDescriptor;
import dezz.status.widget.car.CarControlState;
import dezz.status.widget.car.CarIntegration;
import dezz.status.widget.car.CarIntegrations;

/**
 * In-process button selector. No exported receiver, external application or new vehicle IDs.
 * Uses the existing validated vehicle.drive_mode SET route, with SDK read-back.
 * Own subscriptions/window exist only during an explicitly requested preview or switch.
 */
public final class DriveSelectorController {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static DriveSelectorController instance;
    private static volatile boolean visible;
    private final Context context;
    private final WindowManager windows;
    private final CarIntegration car;
    private final List<CarControlDescriptor.Option> options = new ArrayList<>();
    private LinearLayout root;
    private TextView status;
    private HorizontalScrollView scroll;
    private LinearLayout pills;
    private CarIntegration.ControlStateListener listener;
    private Integer current, pendingSteps;
    private boolean catalogReady, available, writing;
    private long generation;
    private int hideMs = 5000;
    private final Runnable hide = this::dismiss;
    private final Runnable readTimeout = () -> {
        if (pendingSteps != null) {
            pendingSteps = null;
            message("Не получен текущий режим: переключение не выполнено");
        }
    };

    public static boolean isShowing() { return visible; }

    public static void request(Context context, int steps) {
        if (steps < -3 || steps > 3) throw new IllegalArgumentException("Invalid drive step count");
        Context app = context.getApplicationContext();
        MAIN.post(() -> {
            if (instance == null) instance = new DriveSelectorController(app);
            instance.open(steps);
        });
    }

    private DriveSelectorController(Context context) {
        this.context = context;
        windows = context.getSystemService(WindowManager.class);
        car = CarIntegrations.get(context);
    }

    private void open(int steps) {
        if (!Settings.canDrawOverlays(context)) {
            toast("Разрешите Natro показ поверх других приложений для меню режимов"); return;
        }
        if (writing || pendingSteps != null) {
            toast("Дождитесь завершения переключения режима"); return;
        }
        hideMs = steps == 0 ? 5000 : 3000;
        try {
            if (root == null) createWindow();
        } catch (RuntimeException error) {
            dismiss(); toast("Не удалось показать меню режимов"); return;
        }
        scheduleHide();
        // Re-subscribe to seed a current value for EACH relative command, not an optimistic target.
        if (listener != null) car.unsubscribeControlStates(listener);
        long owner = ++generation;
        current = null; catalogReady = false; available = false; options.clear();
        pendingSteps = steps == 0 ? null : steps;
        status.setText("Чтение режима автомобиля…");
        render();
        listener = value -> MAIN.post(() -> {
            if (owner != generation || root == null || !"vehicle.drive_mode".equals(value.controlId)) return;
            onState(value);
        });
        car.subscribeControlStates(Collections.singleton("vehicle.drive_mode"), listener);
        car.requestControlCatalog(catalog -> MAIN.post(() -> {
            if (owner != generation || root == null) return;
            options.clear(); available = false;
            for (CarControlDescriptor descriptor : catalog) {
                if (!"vehicle.drive_mode".equals(descriptor.id)) continue;
                available = descriptor.availability == CarControlDescriptor.Availability.SUPPORTED;
                if (available) for (CarControlDescriptor.Option option : descriptor.options) {
                    if (Double.isFinite(option.value) && option.value == (int) option.value) options.add(option);
                }
                break;
            }
            catalogReady = true;
            if (!available) { pendingSteps = null; message("Режимы движения пока недоступны"); }
            render(); applyPending();
        }));
        MAIN.removeCallbacks(readTimeout);
        if (pendingSteps != null) MAIN.postDelayed(readTimeout, 2500);
    }

    private void onState(CarControlState value) {
        current = value.known && value.available && Double.isFinite(value.value)
                && value.value == (int) value.value ? (int) value.value : null;
        status.setText(current == null ? "Текущий режим неизвестен" : "Режим: " + value.valueLabel);
        render(); applyPending();
    }

    private void applyPending() {
        if (pendingSteps == null || !catalogReady || !available || current == null || writing) return;
        int steps = pendingSteps;
        pendingSteps = null; MAIN.removeCallbacks(readTimeout);
        List<Integer> order = new ArrayList<>();
        for (CarControlDescriptor.Option option : options) if (!order.contains((int) option.value)) order.add((int) option.value);
        Integer target = DriveSelectorStepPolicy.target(order, current, steps);
        if (target != null) select(target);
        else { message("Нет другого режима для переключения"); scheduleHide(); }
    }

    private void select(int target) {
        if (writing || pendingSteps != null || current == null || !available || root == null) return;
        boolean member = false;
        for (CarControlDescriptor.Option option : options) member |= (int) option.value == target;
        if (!member || current == target) { scheduleHide(); return; }
        writing = true; MAIN.removeCallbacks(hide); render();
        message("Ожидание подтверждения автомобиля…");
        long owner = generation;
        car.executeControl(new CarControlCommand("vehicle.drive_mode", CarControlCommand.Operation.SET, target),
                (ok, detail) -> MAIN.post(() -> {
                    writing = false;
                    if (root == null || owner != generation) return;
                    // Current highlight changes only from the state subscription, never from intent dispatch.
                    message(detail == null || detail.isEmpty() ? (ok ? "Режим подтверждён" : "Режим не подтверждён") : detail);
                    hideMs = 3000; render(); scheduleHide();
                }));
    }

    private void createWindow() {
        boolean night = (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        root = new LinearLayout(context); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(12), dp(20), dp(12)); root.setBackgroundColor(night ? 0xF0202228 : 0xF0F4F5F7);
        status = label("Режимы вождения", 20, night); root.addView(status);
        scroll = new HorizontalScrollView(context);
        pills = new LinearLayout(context); scroll.addView(pills); root.addView(scroll);
        TextView close = label("Закрыть", 18, night); close.setGravity(Gravity.END);
        close.setOnClickListener(v -> dismiss()); root.addView(close);
        root.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_OUTSIDE) { dismiss(); return true; }
            scheduleHide(); return false;
        });
        scroll.setOnTouchListener((v, event) -> { scheduleHide(); return false; });
        int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(Math.min(dp(1040), screenWidth - dp(32)),
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.CENTER; params.setTitle("Natro — режимы вождения");
        windows.addView(root, params); visible = true;
    }

    private void render() {
        if (pills == null) return;
        pills.removeAllViews();
        boolean night = (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        View active = null;
        for (CarControlDescriptor.Option option : options) {
            boolean selected = current != null && current == (int) option.value;
            TextView pill = label(option.label, selected ? 24 : 20, night);
            pill.setGravity(Gravity.CENTER); pill.setMinWidth(dp(140)); pill.setMinHeight(dp(80));
            if (selected) pill.setBackgroundColor(night ? 0xFF455A64 : 0xFFCFD8DC);
            pill.setEnabled(!writing && pendingSteps == null && current != null && available);
            pill.setAlpha(pill.isEnabled() ? 1f : .55f);
            pill.setOnClickListener(v -> select((int) option.value));
            pills.addView(pill); if (selected) active = pill;
        }
        if (active != null) {
            View target = active;
            HorizontalScrollView owner = scroll;
            owner.post(() -> {
                if (owner == scroll) owner.smoothScrollTo(Math.max(0, target.getLeft() - (owner.getWidth() - target.getWidth()) / 2), 0);
            });
        }
    }

    private TextView label(String text, int size, boolean night) {
        TextView label = new TextView(context); label.setText(text); label.setTextSize(size);
        label.setTextColor(night ? 0xFFFFFFFF : 0xFF111318); label.setPadding(dp(12), dp(10), dp(12), dp(10));
        return label;
    }
    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    private void message(String text) { if (status != null) status.setText(text); }
    private void toast(String text) { Toast.makeText(context, text, Toast.LENGTH_LONG).show(); }
    private void scheduleHide() { MAIN.removeCallbacks(hide); if (!writing) MAIN.postDelayed(hide, hideMs); }
    private void dismiss() {
        ++generation; visible = false; pendingSteps = null;
        MAIN.removeCallbacks(hide); MAIN.removeCallbacks(readTimeout);
        if (listener != null) { car.unsubscribeControlStates(listener); listener = null; }
        if (root != null) try { windows.removeView(root); } catch (RuntimeException ignored) {}
        root = null; status = null; pills = null; scroll = null; current = null; options.clear();
    }
}
