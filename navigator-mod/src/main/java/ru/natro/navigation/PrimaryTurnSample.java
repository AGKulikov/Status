/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

/** One native maneuver texture observed on the primary map at this route position. */
final class PrimaryTurnSample {
    // A short render transition may miss one capture. Only a new pixel confirmation
    // renews this deadline; route/view invalidations are still immediate.
    static final long FRESH_MS = 1500L;
    final NavigatorStatePublisher.RouteTurnFrame turn;
    final StockTurnTextures.Texture texture;
    final long sampledAt;
    final long routeEpoch;

    String maneuverIdentity() {
        return "maneuver:" + routeEpoch + ':' + turn.routeSegmentIndex + ':'
                + Math.round(turn.routeSegmentPosition * 1_000_000d) + ':' + turn.action;
    }

    PrimaryTurnSample(NavigatorStatePublisher.RouteTurnFrame turn, StockTurnTextures.Texture texture,
                      long sampledAt, long routeEpoch) {
        this.turn = turn;
        this.texture = texture;
        this.sampledAt = sampledAt;
        this.routeEpoch = routeEpoch;
    }
}
