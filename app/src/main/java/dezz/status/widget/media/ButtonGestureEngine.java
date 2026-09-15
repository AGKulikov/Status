/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.media;

import java.util.LinkedHashMap;
import java.util.Map;

/** Single input-thread state machine. Timers never run shell, Binder or application launches. */
public final class ButtonGestureEngine {
    public static final long MULTI_CLICK_MS = 600, LONG_PRESS_MS = 1000, STAR_HOLD_MS = 10000;
    public interface Scheduler { Object after(long milliseconds, Runnable action); void cancel(Object token); }
    public interface Bindings {
        boolean enabled(VehicleButton button);
        int actionId(String group, String gesture);
        boolean knobVolume(); boolean musicActive(); boolean driveMenuShowing();
        boolean stockMediaSource();
    }
    public interface Output {
        void action(String group, String gesture);
        void stockSrc(); void volume(int direction); void driveMenu(int direction); void starHeld();
    }
    private static final class State {
        boolean down, longFired;
        int clicks;
        Object clickTimer, longTimer, starTimer;
    }
    private final Scheduler scheduler;
    private final Bindings bindings;
    private final Output output;
    private final Map<Integer, State> states = new LinkedHashMap<>();
    public ButtonGestureEngine(Scheduler scheduler, Bindings bindings, Output output) {
        this.scheduler = scheduler; this.bindings = bindings; this.output = output;
    }
    public void input(int code, boolean pressed) {
        VehicleButton button = VehicleButton.fromCode(code);
        if (button == null) return;
        State state = states.get(code);
        if (pressed) {
            if (state == null) { state = new State(); states.put(code, state); }
            final State held = state;
            held.down = true; held.longFired = false;
            cancel(held.longTimer); cancel(held.starTimer);
            if (button.longPress) held.longTimer = scheduler.after(LONG_PRESS_MS, () -> {
                if (!held.down || held.longFired) return;
                held.longFired = true;
                cancel(held.clickTimer); held.clickTimer = null; held.clicks = 0;
                dispatch(code, "long");
            });
            if (button == VehicleButton.STAR) held.starTimer = scheduler.after(STAR_HOLD_MS,
                    () -> { if (held.down) output.starHeld(); });
            return;
        }
        if (state == null || !state.down) return;
        state.down = false;
        cancel(state.longTimer); cancel(state.starTimer);
        state.longTimer = state.starTimer = null;
        if (state.longFired) return;
        if (button == VehicleButton.DM) {
            int direction = code == 300001 ? -1 : 1;
            if (bindings.driveMenuShowing()) { output.driveMenu(direction); return; }
            if (bindings.knobVolume() && (!bindings.enabled(button) || bindings.musicActive())) {
                output.volume(direction); return;
            }
        }
        cancel(state.clickTimer); state.clickTimer = null;
        int count = ++state.clicks;
        boolean awaitMore = false;
        for (int next = count + 1; next <= 3; next++)
            awaitMore |= bindings.actionId(VehicleButton.bindingGroup(code), String.valueOf(next)) != 0;
        if (count >= 3 || !awaitMore) {
            state.clicks = 0;
            dispatch(code, String.valueOf(count));
        } else {
            final State pending = state;
            state.clickTimer = scheduler.after(MULTI_CLICK_MS, () -> {
                int clicks = pending.clicks;
                pending.clicks = 0; pending.clickTimer = null;
                if (clicks > 0) dispatch(code, String.valueOf(clicks));
            });
        }
    }
    private void dispatch(int code, String gesture) {
        VehicleButton button = VehicleButton.fromCode(code);
        if (!bindings.enabled(button)) return;
        String group = VehicleButton.bindingGroup(code);
        int id = bindings.actionId(group, gesture);
        if (id == 0) return;
        // DEX slh.y 0x019a..0x01e8: only SINGLE SRC redirects in stock multimedia mode.
        if (button == VehicleButton.SRC && "1".equals(gesture)
                && bindings.stockMediaSource() && id != ButtonAction.SOURCE.id) output.stockSrc();
        else output.action(group, gesture);
    }
    public void reset() {
        for (State state : states.values()) {
            cancel(state.clickTimer); cancel(state.longTimer); cancel(state.starTimer);
        }
        states.clear();
    }
    private void cancel(Object timer) { if (timer != null) scheduler.cancel(timer); }
}
