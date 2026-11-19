/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

/** One native maneuver texture observed on the primary map at this route position. */
final class PrimaryTurnSample {
    static final long FRESH_MS = 900L;
    final NavigatorStatePublisher.RouteTurnFrame turn;
    final StockTurnTextures.Texture texture;
    final long sampledAt;
    final long routeEpoch;

    PrimaryTurnSample(NavigatorStatePublisher.RouteTurnFrame turn, StockTurnTextures.Texture texture,
                      long sampledAt, long routeEpoch) {
        this.turn = turn;
        this.texture = texture;
        this.sampledAt = sampledAt;
        this.routeEpoch = routeEpoch;
    }
}
