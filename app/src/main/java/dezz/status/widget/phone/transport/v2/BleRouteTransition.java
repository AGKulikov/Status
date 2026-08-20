/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Immutable result of one reducer input. */
public final class BleRouteTransition<S> {
    public final S state;
    public final List<BleRouteEffect> effects;
    /** False means the callback was stale, duplicate, or invalid for the current phase. */
    public final boolean accepted;

    public BleRouteTransition(S state, List<BleRouteEffect> effects, boolean accepted) {
        this.state = Objects.requireNonNull(state, "state");
        this.effects = Collections.unmodifiableList(new ArrayList<>(effects));
        this.accepted = accepted;
    }

    public static <S> BleRouteTransition<S> ignored(S state) {
        return new BleRouteTransition<>(state, Collections.emptyList(), false);
    }

    public static <S> BleRouteTransition<S> accepted(S state, BleRouteEffect... effects) {
        List<BleRouteEffect> list = new ArrayList<>();
        if (effects != null) Collections.addAll(list, effects);
        return new BleRouteTransition<>(state, list, true);
    }
}
