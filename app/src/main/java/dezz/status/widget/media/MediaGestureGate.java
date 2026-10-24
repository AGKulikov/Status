/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.media;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Input-thread-only correlation of physical XSF edges and its actual MEDIA route.
 * InputImpl also reports call/DIM/gallery keys. Those edges alone must NEVER execute
 * a media assignment. NEDIA confirms that XSF selected media for the same press.
 * Neither synthetic DOWN/UP duration nor receipt-time broadcast gaps imply a hold.
 */
public final class MediaGestureGate {
    public static final long DELIVERY_MS = 750;
    private static final int MAX_PRESS = 32, MAX_NATIVE = 16;
    public interface Output {
        void confirmed(String group, String gesture);
        void diagnostic(String group, String outcome);
    }
    private static final class Press {
        final int code;
        final long down;
        long up;
        long nativeAt;
        boolean qualified;
        Press(int code, long down) { this.code = code; this.down = down; }
    }
    private static final class Native {
        final int code;
        final long event;
        Object expiry;
        Native(int code, long event) { this.code = code; this.event = event; }
    }
    private static final class Pending {
        final String group, gesture;
        final List<Press> presses;
        Object expiry;
        Pending(String group, String gesture, List<Press> presses) {
            this.group = group; this.gesture = gesture; this.presses = presses;
        }
    }
    private final ButtonGestureEngine.Scheduler scheduler;
    private final Output output;
    private final Map<Integer, Press> active = new HashMap<>();
    private final Map<Integer, List<Press>> sequences = new HashMap<>();
    private final List<Press> history = new ArrayList<>();
    private final List<Native> earlyNative = new ArrayList<>();
    private final List<Pending> pending = new ArrayList<>();

    public MediaGestureGate(ButtonGestureEngine.Scheduler scheduler, Output output) {
        this.scheduler = scheduler; this.output = output;
    }
    public long downAt(int code) {
        Press press = active.get(code);
        return press == null ? 0 : press.down;
    }
    public long lastRelease(int code) {
        List<Press> series = sequences.get(code);
        return series == null || series.isEmpty() ? 0 : series.get(series.size() - 1).up;
    }
    public boolean physical(int code, boolean down, long event, long now) {
        if (VehicleButton.fromCode(code) != VehicleButton.MEDIA || event <= 0
                || now < event || now - event > DELIVERY_MS) return false;
        if (down) {
            if (active.containsKey(code)) return false;
            history.removeIf(p -> p.up > 0 && now - p.up > DELIVERY_MS * 4
                    && !inUse(p));
            if (history.size() >= MAX_PRESS) { reset(); output.diagnostic("media", "overflow_reset"); return false; }
            Press press = new Press(code, event);
            active.put(code, press); history.add(press);
            sequences.computeIfAbsent(code, k -> new ArrayList<>()).add(press);
        } else {
            Press press = active.get(code);
            if (press == null || event < press.down) return false;
            active.remove(code);
            press.up = event;
        }
        for (Native nativeEvent : new ArrayList<>(earlyNative)) {
            if (qualify(nativeEvent)) {
                earlyNative.remove(nativeEvent); scheduler.cancel(nativeEvent.expiry);
            }
        }
        drain();
        return true;
    }
    /** Only the first/duplicate-filtered NEDIA route enters here, never returned MEDIA. */
    public void nativeMedia(int androidCode, long event, long now) {
        int code = VehicleButton.mediaPhysicalCode(androidCode);
        if (code == 0 || event <= 0 || now < event || now - event > DELIVERY_MS) return;
        Native nativeEvent = new Native(code, event);
        if (!qualify(nativeEvent)) {
            if (earlyNative.size() >= MAX_NATIVE) {
                output.diagnostic(VehicleButton.bindingGroup(code), "native_queue_full"); return;
            }
            earlyNative.add(nativeEvent);
            nativeEvent.expiry = scheduler.after(DELIVERY_MS - (now - event), () -> {
                if (earlyNative.remove(nativeEvent))
                    output.diagnostic(VehicleButton.bindingGroup(code), "physical_edge_missing");
            });
        }
        drain();
    }
    public void recognized(String group, String gesture) {
        int code = VehicleButton.mediaPhysicalCode(VehicleButton.mediaCode(group));
        List<Press> series = sequences.remove(code);
        if (series == null || series.isEmpty()) {
            output.diagnostic(group, "physical_series_missing"); return;
        }
        // A hold cancels preceding short taps, matching the shared gesture engine.
        if ("long".equals(gesture)) series = new ArrayList<>(series.subList(series.size() - 1, series.size()));
        Pending command = new Pending(group, gesture, series);
        pending.add(command);
        command.expiry = scheduler.after(DELIVERY_MS, () -> {
            if (pending.remove(command)) output.diagnostic(group, "media_route_unconfirmed");
        });
        drain();
    }
    public void discard(int code) { sequences.remove(code); }
    private boolean qualify(Native event) {
        // Native timestamps are generated after the physical edge. Allow only 5ms
        // rounding error in epoch-to-uptime conversion, not an arbitrary time window.
        for (int i = history.size() - 1; i >= 0; i--) {
            Press press = history.get(i);
            if (press.code != event.code || event.event + 5 < press.down) continue;
            if (press.up > 0 && event.event > press.up + DELIVERY_MS) return false;
            press.qualified = true;
            press.nativeAt = Math.max(press.nativeAt, event.event);
            return true;
        }
        return false;
    }
    private boolean inUse(Press press) {
        List<Press> series = sequences.get(press.code);
        if (series != null && series.contains(press)) return true;
        for (Pending command : pending) if (command.presses.contains(press)) return true;
        return false;
    }
    private void drain() {
        for (Pending command : new ArrayList<>(pending)) {
            boolean qualified = true;
            for (Press press : command.presses) {
                qualified &= press.qualified;
                // A short native click plus a missing physical UP is not proof of a hold.
                if ("long".equals(command.gesture))
                    qualified &= press.nativeAt + 5 >= press.down + ButtonGestureEngine.LONG_PRESS_MS;
            }
            if (!qualified) continue;
            pending.remove(command); scheduler.cancel(command.expiry);
            output.confirmed(command.group, command.gesture);
        }
    }
    public void reset() {
        for (Pending command : pending) scheduler.cancel(command.expiry);
        for (Native event : earlyNative) scheduler.cancel(event.expiry);
        pending.clear(); earlyNative.clear(); active.clear(); sequences.clear(); history.clear();
    }
}
