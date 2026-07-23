/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.panels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public final class PanelEditSchedulerTest {
    @Test
    public void burstProducesOnePreviewAndOneTrailingSave() {
        FakeDispatcher dispatcher = new FakeDispatcher();
        int[] preview = {0};
        int[] save = {0};
        PanelEditScheduler scheduler = new PanelEditScheduler(dispatcher,
                () -> preview[0]++, () -> save[0]++);

        scheduler.request();
        dispatcher.advanceBy(10);
        scheduler.request();
        dispatcher.advanceBy(10);
        scheduler.request();

        dispatcher.advanceBy(12);
        assertEquals(1, preview[0]);
        assertEquals(0, save[0]);
        assertTrue(scheduler.hasPendingSave());

        dispatcher.advanceBy(167);
        assertEquals(0, save[0]);
        dispatcher.advanceBy(1);
        assertEquals(1, save[0]);
        assertFalse(scheduler.hasPendingSave());
    }

    @Test
    public void flushRunsEachPendingActionExactlyOnce() {
        FakeDispatcher dispatcher = new FakeDispatcher();
        int[] preview = {0};
        int[] save = {0};
        PanelEditScheduler scheduler = new PanelEditScheduler(dispatcher,
                () -> preview[0]++, () -> save[0]++);

        scheduler.request();
        scheduler.flush();
        scheduler.flush();
        dispatcher.advanceBy(1_000);

        assertEquals(1, preview[0]);
        assertEquals(1, save[0]);
    }

    private static final class FakeDispatcher implements PanelEditScheduler.Dispatcher {
        private static final class Pending {
            final Runnable task;
            final long at;
            Pending(Runnable task, long at) { this.task = task; this.at = at; }
        }

        private final List<Pending> pending = new ArrayList<>();
        private long now;

        @Override public void postDelayed(Runnable task, long delayMillis) {
            pending.add(new Pending(task, now + delayMillis));
        }

        @Override public void remove(Runnable task) {
            pending.removeIf(value -> value.task == task);
        }

        void advanceBy(long millis) {
            long target = now + millis;
            while (true) {
                Pending next = null;
                for (Pending value : pending) {
                    if (value.at <= target && (next == null || value.at < next.at)) next = value;
                }
                if (next == null) break;
                pending.remove(next);
                now = next.at;
                next.task.run();
            }
            now = target;
        }
    }
}
