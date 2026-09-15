/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.media;

import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;
import java.util.Collections;
import java.util.function.Consumer;
import dezz.status.widget.car.*;

/** Reference previous-mode swap and ignition-gated restoration, driven by SDK events. */
final class ButtonDriveModeController {
    private final Context context;
    private final VehicleButtonController settings;
    private final Handler worker;
    private final Consumer<String> error;
    private boolean subscribed, restored, writing;
    private volatile boolean ignitionOn;
    private int selected, previous = -1, live = -1;
    private volatile long generation;
    private long lastWrite;
    private final CarIntegration.TelemetryListener ignition = new CarIntegration.TelemetryListener() {
        @Override public void onTelemetry(CarIntegration.TelemetryValue value) { worker.post(() -> {
            boolean on = (int) value.value == 2097415;
            if (!on) { ++generation; restored = false; writing = false; }
            ignitionOn = on;
            restore();
        }); }
        @Override public void onTelemetryUnavailable(String id) { worker.post(() -> {
            ignitionOn = false; restored = false; ++generation; writing = false;
        }); }
    };
    private final CarIntegration.ControlStateListener state = this::onControlState;
    private void onControlState(CarControlState value) { worker.post(() -> {
        if (!value.known || !value.available) { live = -1; return; }
        live = (int) value.value;
        if (selected < 0) selected = live;
        if (writing || !restored || !ignitionOn) { restore(); return; }
        int observed = live;
        long owner = generation;
        long delay = Math.max(0, 2500 - (SystemClock.uptimeMillis() - lastWrite));
        worker.postDelayed(() -> {
            if (owner != generation || writing || !ignitionOn || live != observed || selected == observed) return;
            previous = selected; selected = observed;
            settings.put("drive.selected", selected);
        }, delay);
    }); }
    ButtonDriveModeController(Context context, VehicleButtonController settings, Handler worker, Consumer<String> error) {
        this.context = context; this.settings = settings; this.worker = worker; this.error = error;
        selected = settings.integer("drive.selected", -1);
        reconcile();
    }
    void reconcile() { worker.post(() -> {
        boolean needed = settings.bool("drive.restore");
        if (needed == subscribed) return;
        subscribed = needed;
        if (needed) {
            CarIntegrations.get(context).subscribeTelemetry(Collections.singleton("ignition_state"), ignition);
            CarIntegrations.get(context).subscribeControlStates(Collections.singleton("vehicle.drive_mode"), state);
        } else {
            ++generation; ignitionOn = restored = writing = false;
            CarIntegrations.get(context).unsubscribeTelemetry(ignition);
            CarIntegrations.get(context).unsubscribeControlStates(state);
        }
    }); }
    void select(ButtonAction action) { worker.post(() -> {
        if (!settings.bool("drive.restore")) {
            error.accept("Включите сохранение режима движения в параметрах действий кнопок"); return;
        }
        if (!ignitionOn || live < 0 || writing) { error.accept("Режим движения пока недоступен"); return; }
        int requested = value(action);
        int target = requested == selected && previous >= 0 ? previous : requested;
        int old = selected;
        write(target, () -> {
            if (old != target) previous = old;
            selected = target; settings.put("drive.selected", target);
        });
    }); }
    private void restore() {
        if (!subscribed || !ignitionOn || restored || writing || live < 0) return;
        int target = settings.integer("drive.start_mode", -1);
        if (target < 0) target = selected;
        if (target < 0) { restored = true; return; }
        final int accepted = target;
        write(target, () -> { selected = accepted; restored = true; });
    }
    private void write(int target, Runnable accepted) {
        writing = true; long owner = ++generation;
        lastWrite = SystemClock.uptimeMillis();
        CarIntegrations.get(context).selectButtonDriveMode(target,
                () -> owner == generation && ignitionOn && settings.bool("drive.restore"),
                (ok, detail) -> worker.post(() -> {
            if (owner != generation) return;
            writing = false;
            if (ok) { live = target; accepted.run(); }
            else { restored = true; error.accept(detail == null ? "Режим движения недоступен" : detail); }
        }));
    }
    static int value(ButtonAction action) {
        switch (action) {
            case ECO: return 570491137;
            case COMFORT: return 570491138;
            case SPORT: return 570491139;
            case SNOW: return 570491145;
            case SAND: return 570491149;
            case OFFROAD: return 570491155;
            case ADAPTIVE: return 570491158;
            default: throw new IllegalArgumentException("Not a drive mode");
        }
    }
}
