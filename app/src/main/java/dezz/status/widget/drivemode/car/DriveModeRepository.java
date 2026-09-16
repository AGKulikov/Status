/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.drivemode.car;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import androidx.annotation.NonNull;
import java.util.*;
import java.util.function.IntConsumer;
import java.util.function.Consumer;
import dezz.status.widget.car.*;

/** Adapter to the single existing Natro vehicle owner; never binds a second ECARX SDK. */
public final class DriveModeRepository {
    private static final DriveModeRepository INSTANCE = new DriveModeRepository();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<Listener> listeners = new LinkedHashSet<>();
    private final Set<SupportedModesListener> supportedListeners = new LinkedHashSet<>();
    private Context context;
    private CarIntegration car;
    private boolean attached, available, writing;
    private int current = -1, requested = -1;
    private int[] supported = new int[0];
    private long generation;
    private final CarIntegration.ControlStateListener stateListener = this::receiveState;

    public interface Listener { void onModeChanged(int previous, int current, @NonNull DriveModeChangeOrigin origin); }
    public interface SupportedModesListener { void onSupportedModesChanged(@NonNull int[] supported); }
    public interface ProbeCallback { void onComplete(@NonNull int[] supported); void onFailed(); }
    public static DriveModeRepository get() { return INSTANCE; }

    public void init(@NonNull Context ctx) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(() -> init(ctx)); return; }
        if (context == null) { context = ctx.getApplicationContext(); car = CarIntegrations.get(context); }
        if (attached) return;
        attached = true; ++generation;
        car.subscribeControlStates(Collections.singleton("vehicle.drive_mode"), stateListener);
        refreshCatalog(null);
    }
    private void receiveState(CarControlState state) {
        main.post(() -> {
            if (!attached || !"vehicle.drive_mode".equals(state.controlId)) return;
            if (state.available && !available) refreshCatalog(null);
            int previous = current;
            current = state.available && state.known && Double.isFinite(state.value)
                    && state.value == (int) state.value ? (int) state.value : -1;
            if (previous >= 0 && current >= 0 && previous != current) {
                DriveModeChangeOrigin origin = writing && requested == current
                        ? DriveModeChangeOrigin.PROGRAMMATIC : DriveModeChangeOrigin.EXTERNAL;
                for (Listener listener : new ArrayList<>(listeners)) listener.onModeChanged(previous, current, origin);
            }
        });
    }
    private void refreshCatalog(ProbeCallback callback) {
        if (car == null) { if (callback != null) callback.onFailed(); return; }
        long owner = generation;
        car.requestControlCatalog(catalog -> main.post(() -> {
            if (owner != generation) { if (callback != null) callback.onFailed(); return; }
            available = false; List<Integer> codes = new ArrayList<>();
            for (CarControlDescriptor entry : catalog) if ("vehicle.drive_mode".equals(entry.id)) {
                available = entry.availability == CarControlDescriptor.Availability.SUPPORTED;
                if (available) for (CarControlDescriptor.Option option : entry.options)
                    if (option.value == (int) option.value && DriveModeCatalog.byCode((int) option.value) != null)
                        codes.add((int) option.value);
                break;
            }
            supported = codes.stream().mapToInt(Integer::intValue).toArray();
            for (SupportedModesListener listener : new ArrayList<>(supportedListeners))
                listener.onSupportedModesChanged(supported.clone());
            if (callback != null) { if (available) callback.onComplete(supported.clone()); else callback.onFailed(); }
        }));
    }
    public int getLastKnownMode() { return current; }
    public int[] getSupportedModes() { return supported.clone(); }
    public boolean isFunctionAvailable() { return available; }
    public void readCurrentModeAsync(@NonNull IntConsumer callback) {
        if (car == null || !attached) { callback.accept(-1); return; }
        long owner = generation;
        boolean[] done = {false};
        CarIntegration.ControlStateListener[] once = new CarIntegration.ControlStateListener[1];
        Runnable timeout = () -> {
            if (done[0]) return; done[0] = true;
            car.unsubscribeControlStates(once[0]); callback.accept(-1);
        };
        once[0] = state -> main.post(() -> {
            if (done[0]) return; done[0] = true; main.removeCallbacks(timeout);
            car.unsubscribeControlStates(once[0]);
            callback.accept(owner == generation && state.available && state.known
                    && Double.isFinite(state.value) && state.value == (int) state.value ? (int) state.value : -1);
        });
        car.subscribeControlStates(Collections.singleton("vehicle.drive_mode"), once[0]);
        main.postDelayed(timeout, 3000);
    }
    public void setModeConfirmed(int code, Consumer<Boolean> callback) {
        if (car == null || !attached || writing || DriveModeCatalog.byCode(code) == null) {
            callback.accept(false); return;
        }
        writing = true; requested = code; long owner = generation;
        car.executeControl(new CarControlCommand("vehicle.drive_mode", CarControlCommand.Operation.SET, code),
                (ok, detail) -> main.post(() -> {
                    writing = false; requested = -1;
                    if (owner != generation) { callback.accept(false); return; }
                    if (ok) current = code; // executeControl success includes SDK read-back, not just dispatch.
                    else Toast.makeText(context, detail == null ? "Автомобиль не подтвердил режим" : detail, Toast.LENGTH_LONG).show();
                    callback.accept(ok);
                }));
    }
    public void probeSupportedModes(@NonNull ProbeCallback callback) { refreshCatalog(callback); }
    public void addListener(@NonNull Listener listener) { listeners.add(listener); }
    public void removeListener(@NonNull Listener listener) { listeners.remove(listener); }
    public void addSupportedModesListener(@NonNull SupportedModesListener listener) {
        supportedListeners.add(listener); listener.onSupportedModesChanged(supported.clone());
    }
    public void removeSupportedModesListener(@NonNull SupportedModesListener listener) { supportedListeners.remove(listener); }
    public void shutdown() {
        if (!listeners.isEmpty() || !supportedListeners.isEmpty()) return;
        ++generation; attached = false; current = -1; available = false;
        if (car != null) car.unsubscribeControlStates(stateListener);
    }
}
