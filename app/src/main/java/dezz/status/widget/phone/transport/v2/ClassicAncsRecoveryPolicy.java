/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Pure policy for the one logical selected iPhone: Classic up implies ANCS must be acquired.
 *
 * <p>Route-local reducers keep their own bounded scan/GATT retries.  This outer policy replaces
 * a complete route generation only after it is actually down.  Two losses are recovered
 * immediately, then the policy backs off without ever exhausting permanently while the exact
 * selected iPhone still has a Classic profile connected.</p>
 */
public final class ClassicAncsRecoveryPolicy {
    public static final int IMMEDIATE_RECOVERIES = 2;
    public static final long FIRST_BACKOFF_MS = 30_000L;
    public static final long MAX_BACKOFF_MS = 300_000L;

    private static final long[] BACKOFF_MS = {
            FIRST_BACKOFF_MS, 60_000L, 120_000L, MAX_BACKOFF_MS
    };

    public enum Phase {
        NO_CLASSIC,
        ACQUIRING,
        WAIT_SERVICE_CHANGED,
        WAIT_AUTHORIZATION,
        READY,
        RECOVERY_SCHEDULED
    }

    public enum EffectType {
        ENSURE_ROUTE,
        REQUEST_SAME_ROUTE_RECOVERY,
        SCHEDULE_WAKEUP,
        CANCEL_WAKEUP
    }

    public static final class Effect {
        public final EffectType type;
        public final long timerGeneration;
        public final long deadlineMillis;

        private Effect(EffectType type, long timerGeneration, long deadlineMillis) {
            this.type = Objects.requireNonNull(type, "type");
            this.timerGeneration = timerGeneration;
            this.deadlineMillis = deadlineMillis;
        }

        private static Effect action(EffectType type) {
            return new Effect(type, 0L, -1L);
        }

        private static Effect timer(EffectType type, long generation, long deadlineMillis) {
            return new Effect(type, generation, deadlineMillis);
        }
    }

    public static final class State {
        public final Phase phase;
        public final boolean classicConnected;
        public final IphoneTransportRecoveryStateV2 route;
        public final int recoveryCommands;
        public final long timerGeneration;
        public final long deadlineMillis;

        private State(Phase phase, boolean classicConnected,
                      IphoneTransportRecoveryStateV2 route, int recoveryCommands,
                      long timerGeneration, long deadlineMillis) {
            this.phase = Objects.requireNonNull(phase, "phase");
            this.classicConnected = classicConnected;
            this.route = Objects.requireNonNull(route, "route");
            this.recoveryCommands = Math.max(0, recoveryCommands);
            this.timerGeneration = timerGeneration;
            this.deadlineMillis = deadlineMillis;
        }

        public static State initial() {
            return new State(Phase.NO_CLASSIC, false,
                    IphoneTransportRecoveryStateV2.NO_OWNER, 0, 0L, -1L);
        }

        public boolean hasWakeup() {
            return deadlineMillis >= 0L;
        }
    }

    public static final class Transition {
        public final State state;
        public final List<Effect> effects;

        private Transition(State state, List<Effect> effects) {
            this.state = Objects.requireNonNull(state, "state");
            this.effects = Collections.unmodifiableList(new ArrayList<>(effects));
        }
    }

    private ClassicAncsRecoveryPolicy() { }

    public static Transition observe(State previous, boolean classicConnected,
                                     IphoneTransportRecoveryStateV2 route,
                                     long nowMillis) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(route, "route");
        requireNonNegative(nowMillis);
        if (!classicConnected) {
            return settle(previous, Phase.NO_CLASSIC, false, route, 0);
        }
        switch (route) {
            case READY:
                return settle(previous, Phase.READY, true, route, 0);
            case WAIT_SERVICE_CHANGED:
                return settle(previous, Phase.WAIT_SERVICE_CHANGED,
                        true, route, previous.recoveryCommands);
            case WAIT_AUTHORIZATION:
                return settle(previous, Phase.WAIT_AUTHORIZATION,
                        true, route, previous.recoveryCommands);
            case PROGRESSING:
                return settle(previous, Phase.ACQUIRING,
                        true, route, previous.recoveryCommands);
            case NO_OWNER:
            case OWNER_DOWN:
                return observeDown(previous, route, nowMillis);
            default:
                throw new AssertionError(route);
        }
    }

    public static Transition wakeup(State previous, long timerGeneration, long nowMillis) {
        Objects.requireNonNull(previous, "previous");
        requireNonNegative(nowMillis);
        if (!previous.classicConnected || !previous.hasWakeup()
                || timerGeneration != previous.timerGeneration
                || nowMillis < previous.deadlineMillis
                || !isDown(previous.route)) {
            return unchanged(previous);
        }
        return commandAndArm(previous, previous.route, nowMillis);
    }

    public static long backoffMillisAfter(int recoveryCommands) {
        int completedFast = Math.max(0, recoveryCommands - IMMEDIATE_RECOVERIES);
        int index = Math.min(completedFast, BACKOFF_MS.length - 1);
        return BACKOFF_MS[index];
    }

    private static Transition observeDown(State previous,
                                          IphoneTransportRecoveryStateV2 route,
                                          long nowMillis) {
        if (previous.classicConnected && previous.route == route && previous.hasWakeup()) {
            return unchanged(previous);
        }
        if (previous.recoveryCommands < IMMEDIATE_RECOVERIES) {
            return commandAndArm(previous, route, nowMillis);
        }
        return armOnly(previous, route, nowMillis,
                backoffMillisAfter(previous.recoveryCommands));
    }

    private static Transition commandAndArm(State previous,
                                            IphoneTransportRecoveryStateV2 route,
                                            long nowMillis) {
        int commands = previous.recoveryCommands == Integer.MAX_VALUE
                ? Integer.MAX_VALUE : previous.recoveryCommands + 1;
        long generation = nextGeneration(previous.timerGeneration);
        long deadline = saturatingAdd(nowMillis, backoffMillisAfter(commands));
        List<Effect> effects = new ArrayList<>();
        if (previous.hasWakeup()) {
            effects.add(Effect.timer(EffectType.CANCEL_WAKEUP,
                    previous.timerGeneration, previous.deadlineMillis));
        }
        effects.add(Effect.action(route == IphoneTransportRecoveryStateV2.NO_OWNER
                ? EffectType.ENSURE_ROUTE : EffectType.REQUEST_SAME_ROUTE_RECOVERY));
        effects.add(Effect.timer(EffectType.SCHEDULE_WAKEUP, generation, deadline));
        return new Transition(new State(Phase.RECOVERY_SCHEDULED, true, route,
                commands, generation, deadline), effects);
    }

    private static Transition armOnly(State previous,
                                      IphoneTransportRecoveryStateV2 route,
                                      long nowMillis, long delayMillis) {
        long generation = nextGeneration(previous.timerGeneration);
        long deadline = saturatingAdd(nowMillis, delayMillis);
        List<Effect> effects = new ArrayList<>();
        if (previous.hasWakeup()) {
            effects.add(Effect.timer(EffectType.CANCEL_WAKEUP,
                    previous.timerGeneration, previous.deadlineMillis));
        }
        effects.add(Effect.timer(EffectType.SCHEDULE_WAKEUP, generation, deadline));
        return new Transition(new State(Phase.RECOVERY_SCHEDULED, true, route,
                previous.recoveryCommands, generation, deadline), effects);
    }

    private static Transition settle(State previous, Phase phase, boolean classicConnected,
                                     IphoneTransportRecoveryStateV2 route, int commands) {
        List<Effect> effects = new ArrayList<>();
        long generation = previous.timerGeneration;
        if (previous.hasWakeup()) {
            effects.add(Effect.timer(EffectType.CANCEL_WAKEUP,
                    previous.timerGeneration, previous.deadlineMillis));
            generation = nextGeneration(generation);
        }
        State next = new State(phase, classicConnected, route, commands, generation, -1L);
        return new Transition(next, effects);
    }

    private static Transition unchanged(State state) {
        return new Transition(state, Collections.emptyList());
    }

    private static boolean isDown(IphoneTransportRecoveryStateV2 route) {
        return route == IphoneTransportRecoveryStateV2.NO_OWNER
                || route == IphoneTransportRecoveryStateV2.OWNER_DOWN;
    }

    private static long nextGeneration(long generation) {
        return generation == Long.MAX_VALUE ? 1L : generation + 1L;
    }

    private static long saturatingAdd(long left, long right) {
        if (Long.MAX_VALUE - left < right) return Long.MAX_VALUE;
        return left + right;
    }

    private static void requireNonNegative(long value) {
        if (value < 0L) throw new IllegalArgumentException("time must be non-negative");
    }
}
