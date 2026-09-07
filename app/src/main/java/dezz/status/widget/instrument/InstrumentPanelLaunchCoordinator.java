/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.instrument;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** One bounded launch transaction. All entry points and completions run on the UI scheduler. */
final class InstrumentPanelLaunchCoordinator {
    static final int MAX_DISPLAY_RETRIES = 10;
    static final long DISPLAY_RETRY_MS = 1_500L;
    static final int MAX_LAUNCH_ATTEMPTS = 3;
    static final long READY_TIMEOUT_MS = 15_000L;
    static final long WINDOW_CHECK_MS = 500L;

    interface Scheduler {
        long now();
        void after(long delayMs, Runnable action);
    }

    interface Host {
        Settings settings();
        boolean displayAvailable(int displayId);
        WindowState window();
        void prepareDim(BooleanSupplier allowed, Consumer<Boolean> completion);
        void startPanel(int displayId, BooleanSupplier current, BooleanSupplier allowed,
                        Consumer<Boolean> completion);
        void reloadPanel();
        void trace(String message);
    }

    static final class Settings {
        final boolean enabled;
        final boolean autostart;
        final int displayId;

        Settings(boolean enabled, boolean autostart, int displayId) {
            this.enabled = enabled;
            this.autostart = autostart;
            this.displayId = displayId;
        }
    }

    /** Android 9 can keep a visible secondary Activity PAUSED and without input focus. */
    static final class WindowState {
        final boolean alive, started, attached, visible, drawn;
        final int displayId, width, height;

        WindowState(boolean alive, boolean started, boolean attached, boolean visible,
                    boolean drawn, int displayId, int width, int height) {
            this.alive = alive;
            this.started = started;
            this.attached = attached;
            this.visible = visible;
            this.drawn = drawn;
            this.displayId = displayId;
            this.width = width;
            this.height = height;
        }

        boolean readyFor(int targetDisplay) {
            return alive && started && attached && visible && drawn
                    && displayId == targetDisplay && width > 1 && height > 1;
        }

        @Override public String toString() {
            return "alive=" + alive + " started=" + started + " attached=" + attached
                    + " visible=" + visible + " drawn=" + drawn + " display=" + displayId
                    + " size=" + width + "x" + height;
        }

        static WindowState absent() {
            return new WindowState(false, false, false, false, false, -1, 0, 0);
        }
    }

    private enum Phase { DISPLAY, PREPARE, START, WINDOW }

    private static final class Request {
        final long id;
        final int displayId;
        boolean automatic, reassertDim;
        int displayRetries, attempts, phaseVersion;
        long windowDeadline;
        Phase phase = Phase.DISPLAY;

        Request(long id, Settings settings, boolean automatic, boolean reassertDim,
                String reason) {
            this.id = id;
            displayId = settings.displayId;
            this.automatic = automatic;
            this.reassertDim = reassertDim;
        }
    }

    private final Host host;
    private final Scheduler scheduler;
    private volatile Request pending;
    private long nextRequest;

    InstrumentPanelLaunchCoordinator(Host host, Scheduler scheduler) {
        this.host = host;
        this.scheduler = scheduler;
    }

    void request(boolean automatic, boolean reassertDim, String reason) {
        Settings settings = host.settings();
        if (!settings.enabled || (automatic && !settings.autostart)) {
            host.trace("skip reason=" + reason + " enabled=" + settings.enabled
                    + " autostart=" + settings.autostart);
            if (pending != null && (!settings.enabled || pending.automatic)) cancel("disabled");
            return;
        }
        if (pending != null && pending.displayId == settings.displayId) {
            // A manual request remains valid if autostart is subsequently switched off.
            pending.automatic &= automatic;
            pending.reassertDim |= reassertDim;
            trace(pending, "coalesced reason=" + reason);
            return;
        }
        cancel("replaced");
        Request request = new Request(++nextRequest, settings, automatic, reassertDim, reason);
        pending = request;
        trace(request, "requested automatic=" + automatic + " reason=" + reason
                + " display=" + settings.displayId + " " + host.window());
        checkDisplay(request);
    }

    void cancel(String reason) {
        Request old = pending;
        pending = null;
        if (old != null) trace(old, "cancelled reason=" + reason);
    }

    void windowChanged() {
        Request current = pending;
        if (current != null && current.phase == Phase.WINDOW && allowed(current)
                && host.displayAvailable(current.displayId)
                && host.window().readyFor(current.displayId)) {
            finish(current, "window-ready " + host.window());
        }
    }

    boolean isPending() { return pending != null; }

    static boolean dimPrepared(boolean switchAccepted, Integer readback) {
        // A verified existing mode also covers the vendor returning false for an unchanged value.
        return readback == null ? switchAccepted : readback == 3;
    }

    private boolean allowed(Request request) {
        if (pending != request) return false;
        Settings settings = host.settings();
        if (!settings.enabled || (request.automatic && !settings.autostart)
                || settings.displayId != request.displayId) {
            finish(request, "cancelled settings-changed");
            return false;
        }
        return true;
    }

    private void checkDisplay(Request request) {
        if (!allowed(request)) return;
        request.phase = Phase.DISPLAY;
        if (!host.displayAvailable(request.displayId)) {
            if (request.displayRetries >= MAX_DISPLAY_RETRIES) {
                finish(request, "display-unavailable; waiting for a display/lifecycle event");
                return;
            }
            trace(request, "waiting-display attempt=" + ++request.displayRetries);
            scheduler.after(DISPLAY_RETRY_MS, () -> checkDisplay(request));
            return;
        }
        if (!request.reassertDim && host.window().readyFor(request.displayId)) {
            host.reloadPanel();
            finish(request, "existing-window-ready " + host.window());
            return;
        }
        if (++request.attempts > MAX_LAUNCH_ATTEMPTS) {
            finish(request, "launch-exhausted " + host.window());
            return;
        }
        request.phase = Phase.PREPARE;
        int phaseVersion = ++request.phaseVersion;
        trace(request, "prepare-dim attempt=" + request.attempts);
        // A stuck vendor Binder or durable write must never keep admission pending forever.
        scheduler.after(READY_TIMEOUT_MS, () -> {
            if (inPhase(request, Phase.PREPARE, phaseVersion)) finish(request, "dim-timeout");
        });
        host.prepareDim(() -> pending == request, success -> {
            if (!inPhase(request, Phase.PREPARE, phaseVersion)) return;
            if (!success) {
                retry(request, "dim-not-ready");
            } else if (!host.displayAvailable(request.displayId)) {
                checkDisplay(request);
            } else if (host.window().readyFor(request.displayId)) {
                host.reloadPanel();
                finish(request, "dim-restored existing-window-ready " + host.window());
            } else {
                launch(request);
            }
        });
    }

    private void launch(Request request) {
        request.phase = Phase.START;
        int phaseVersion = ++request.phaseVersion;
        scheduler.after(READY_TIMEOUT_MS, () -> {
            if (inPhase(request, Phase.START, phaseVersion)) {
                finish(request, "launch-preparation-timeout");
            }
        });
        host.startPanel(request.displayId, () -> pending == request, () -> allowed(request), success -> {
            if (!inPhase(request, Phase.START, phaseVersion)) return;
            if (!success) {
                retry(request, "activity-request-failed");
                return;
            }
            request.phase = Phase.WINDOW;
            request.windowDeadline = scheduler.now() + READY_TIMEOUT_MS;
            trace(request, "activity-request-accepted; awaiting window");
            checkWindow(request);
        });
    }

    private void checkWindow(Request request) {
        if (!allowed(request) || request.phase != Phase.WINDOW) return;
        if (host.displayAvailable(request.displayId)
                && host.window().readyFor(request.displayId)) {
            finish(request, "window-ready " + host.window());
        } else if (scheduler.now() >= request.windowDeadline) {
            retry(request, "window-not-ready " + host.window());
        } else {
            scheduler.after(WINDOW_CHECK_MS, () -> checkWindow(request));
        }
    }

    private void retry(Request request, String reason) {
        if (!allowed(request)) return;
        request.phase = Phase.DISPLAY;
        request.phaseVersion++;
        trace(request, reason);
        if (request.attempts >= MAX_LAUNCH_ATTEMPTS) {
            finish(request, "launch-exhausted " + host.window());
        } else {
            scheduler.after(DISPLAY_RETRY_MS, () -> checkDisplay(request));
        }
    }

    private boolean inPhase(Request request, Phase phase, int version) {
        return allowed(request) && request.phase == phase && request.phaseVersion == version;
    }

    private void finish(Request request, String outcome) {
        if (pending != request) return;
        pending = null;
        trace(request, outcome);
    }

    private void trace(Request request, String message) {
        host.trace("request=" + request.id + " " + message);
    }
}
