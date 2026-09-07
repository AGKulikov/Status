/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.instrument;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.junit.Test;

public final class InstrumentPanelLaunchCoordinatorTest {
    private static InstrumentPanelLaunchCoordinator.WindowState visible(int display) {
        return new InstrumentPanelLaunchCoordinator.WindowState(
                true, true, true, true, true, display, 1920, 720);
    }

    @Test public void acceptedActivityStartStillWaitsForTheFirstVisiblePanelFrame() {
        Fixture f = new Fixture();
        f.request(false, true);
        assertEquals(1, f.host.starts);
        assertTrue(f.launch.isPending());
        f.host.window = new InstrumentPanelLaunchCoordinator.WindowState(
                true, true, true, true, false, 2, 1920, 720);
        f.launch.windowChanged();
        assertTrue(f.launch.isPending());
        f.host.window = visible(2);
        f.launch.windowChanged();
        assertFalse(f.launch.isPending());
        f.clock.advance(90_000);
        assertEquals(1, f.host.starts);
    }

    @Test public void lifecycleRestoreReassertsDimEvenWhenTheActivitySurvived() {
        Fixture f = new Fixture();
        f.host.window = visible(2);
        f.request(true, true);
        assertEquals(1, f.host.preparations);
        assertEquals(0, f.host.starts);
        assertEquals(1, f.host.reloads);
        assertFalse(f.launch.isPending());
    }

    @Test public void ordinaryUiReconciliationPreservesAnAlreadyVisibleSecondaryWindow() {
        Fixture f = new Fixture();
        f.host.window = visible(2);
        f.request(true, false);
        assertEquals(0, f.host.preparations);
        assertEquals(0, f.host.starts);
        assertEquals(1, f.host.reloads);
    }

    @Test public void aliveButStoppedActivityDoesNotSuppressPanelStartup() {
        Fixture f = new Fixture();
        f.host.window = new InstrumentPanelLaunchCoordinator.WindowState(
                true, false, true, false, true, 2, 1920, 720);
        f.request(true, false);
        assertEquals(1, f.host.preparations);
        assertEquals(1, f.host.starts);
        assertTrue(f.launch.isPending());
    }

    @Test public void detachedOrInvisibleSurvivingWindowsNeedRestoration() {
        for (boolean detached : new boolean[]{false, true}) {
            Fixture f = new Fixture();
            f.host.window = new InstrumentPanelLaunchCoordinator.WindowState(
                    true, true, !detached, detached, true, 2, 1920, 720);
            f.request(true, true);
            assertEquals(1, f.host.starts);
            assertTrue(f.launch.isPending());
        }
    }

    @Test public void aVisibleWindowOnTheCentreDisplayDoesNotSatisfyInstrumentReadiness() {
        Fixture f = new Fixture();
        f.host.window = visible(0);
        f.request(true, true);
        assertEquals(2, f.host.lastTarget);
        assertEquals(1, f.host.starts);
        f.launch.windowChanged();
        assertTrue(f.launch.isPending());
        f.host.window = visible(2);
        f.launch.windowChanged();
        assertFalse(f.launch.isPending());
    }

    @Test public void visibleStartedWindowNeedsNeitherResumeNorMapAttachmentToComplete() {
        Fixture f = new Fixture();
        f.request(true, true);
        // STARTED + attached + visible + drawn are sufficient on Android 9; the Navigator
        // process, its map lease and the RESUMED Activity on display 0 are independent.
        f.host.window = visible(2);
        f.launch.windowChanged();
        assertFalse(f.launch.isPending());
    }

    @Test public void absentDisplayNeverFallsBackToTheCentreAndLateDisplayEventCanRecover() {
        Fixture f = new Fixture();
        f.host.display = false;
        f.request(true, true);
        f.clock.advance(120_000);
        assertFalse(f.launch.isPending());
        assertEquals(0, f.host.starts);
        assertEquals(0, f.host.preparations);
        f.host.display = true;
        f.launch.request(true, true, "display-ready");
        assertEquals(1, f.host.starts);
        assertEquals(2, f.host.lastTarget);
    }

    @Test public void closeDuringVendorPreparationPreventsLateActivityLaunch() {
        Fixture f = new Fixture();
        f.host.holdDim = true;
        f.request(false, true);
        f.launch.cancel("closed");
        assertFalse(f.host.dimGuards.get(0).getAsBoolean());
        f.host.dimCompletions.get(0).accept(true);
        f.clock.advance(120_000);
        assertEquals(0, f.host.starts);
        assertFalse(f.launch.isPending());
    }

    @Test public void oldDurableWriteCannotLaunchAfterCloseOrReplaceTheNewTransaction() {
        Fixture f = new Fixture();
        f.host.holdStart = true;
        f.request(false, true);
        f.launch.cancel("closed");
        f.request(false, true);
        f.host.startCompletions.get(0).run();
        assertEquals(0, f.host.starts);
        assertTrue(f.launch.isPending());
        f.host.startCompletions.get(1).run();
        assertEquals(1, f.host.starts);
        f.host.window = visible(2);
        f.launch.windowChanged();
        assertFalse(f.launch.isPending());
    }

    @Test public void disablingPanelWhileTokenIsBeingWrittenCancelsTheActivityIntent() {
        Fixture f = new Fixture();
        f.host.holdStart = true;
        f.request(true, true);
        f.host.enabled = false;
        f.host.startCompletions.get(0).run();
        assertEquals(0, f.host.starts);
        assertFalse(f.launch.isPending());
    }

    @Test public void targetChangeInvalidatesTheOldCapabilityCompletion() {
        Fixture f = new Fixture();
        f.host.holdStart = true;
        f.request(false, true);
        f.host.target = 3;
        f.request(false, true);
        f.host.startCompletions.get(0).run();
        assertEquals(0, f.host.starts);
        f.host.startCompletions.get(1).run();
        assertEquals(1, f.host.starts);
        assertEquals(3, f.host.lastTarget);
    }

    @Test public void bootHostAndManualRequestsShareOnePendingLaunch() {
        Fixture f = new Fixture();
        f.host.holdDim = true;
        f.launch.request(true, true, "boot");
        f.launch.request(true, true, "host");
        f.launch.request(false, true, "manual");
        f.host.autostart = false;
        f.launch.request(true, true, "autostart-setting");
        assertTrue(f.launch.isPending());
        assertEquals(1, f.host.preparations);
        f.host.dimCompletions.get(0).accept(true);
        assertEquals(1, f.host.starts);
    }

    @Test public void turningOffAutostartDuringDisplayWaitCancelsOnlyAutomaticWork() {
        Fixture f = new Fixture();
        f.host.display = false;
        f.request(true, true);
        f.host.autostart = false;
        f.clock.advance(2_000);
        assertFalse(f.launch.isPending());
        assertEquals(0, f.host.starts);
        f.host.display = true;
        f.request(false, true);
        assertEquals(1, f.host.starts);
    }

    @Test public void activityAcceptedWithoutAWindowHasBoundedRecovery() {
        Fixture f = new Fixture();
        f.request(true, true);
        f.clock.advance(180_000);
        assertEquals(3, f.host.starts);
        assertEquals(3, f.host.preparations);
        assertFalse(f.launch.isPending());
        assertTrue(f.host.messages.stream().anyMatch(s -> s.contains("launch-exhausted")));
    }

    @Test public void rejectedActivityIntentsCannotProduceAnUnboundedRetryLoop() {
        Fixture f = new Fixture();
        f.host.acceptStart = false;
        f.request(false, true);
        f.clock.advance(180_000);
        assertEquals(3, f.host.tokenPreparations);
        assertEquals(0, f.host.starts);
        assertFalse(f.launch.isPending());
    }

    @Test public void unavailableDimHasBoundedRetriesWithoutLaunchingOnAnUnselectedDisplay() {
        Fixture f = new Fixture();
        f.host.dimReady = false;
        f.request(true, true);
        f.clock.advance(180_000);
        assertEquals(3, f.host.preparations);
        assertEquals(0, f.host.starts);
        assertFalse(f.launch.isPending());
    }

    @Test public void hungVendorRequestTimesOutAndItsLateCallbackIsIgnored() {
        Fixture f = new Fixture();
        f.host.holdDim = true;
        f.request(true, true);
        f.clock.advance(60_000);
        assertFalse(f.launch.isPending());
        f.host.dimCompletions.get(0).accept(true);
        assertEquals(0, f.host.starts);
    }

    @Test public void hungTokenWriteTimesOutWithoutAdmittingItsLateActivityIntent() {
        Fixture f = new Fixture();
        f.host.holdStart = true;
        f.request(false, true);
        f.clock.advance(60_000);
        assertFalse(f.launch.isPending());
        f.host.startCompletions.get(0).run();
        assertEquals(0, f.host.starts);
    }

    @Test public void duplicateWorkerCompletionCannotStartTwoActivities() {
        Fixture f = new Fixture();
        f.host.holdDim = true;
        f.request(false, true);
        f.host.dimCompletions.get(0).accept(true);
        f.host.dimCompletions.get(0).accept(true);
        assertEquals(1, f.host.starts);
    }

    @Test public void realDimReadbackHasPriorityOverAnUnchangedValueReturnCode() {
        assertTrue(InstrumentPanelLaunchCoordinator.dimPrepared(false, 3));
        assertTrue(InstrumentPanelLaunchCoordinator.dimPrepared(true, 3));
        assertFalse(InstrumentPanelLaunchCoordinator.dimPrepared(true, 1));
        assertFalse(InstrumentPanelLaunchCoordinator.dimPrepared(true, -1));
        assertTrue(InstrumentPanelLaunchCoordinator.dimPrepared(true, null));
        assertFalse(InstrumentPanelLaunchCoordinator.dimPrepared(false, null));
    }

    private static final class Fixture {
        final Clock clock = new Clock();
        final FakeHost host = new FakeHost();
        final InstrumentPanelLaunchCoordinator launch = new InstrumentPanelLaunchCoordinator(host, clock);
        void request(boolean automatic, boolean reassert) { launch.request(automatic, reassert, "test"); }
    }

    private static final class Clock implements InstrumentPanelLaunchCoordinator.Scheduler {
        long now, sequence;
        final PriorityQueue<Event> queue = new PriorityQueue<>();
        @Override public long now() { return now; }
        @Override public void after(long delay, Runnable action) {
            queue.add(new Event(now + delay, sequence++, action));
        }
        void advance(long millis) {
            long target = now + millis;
            int actions = 0;
            while (!queue.isEmpty() && queue.peek().at <= target) {
                assertTrue("unbounded scheduler work", ++actions < 2_000);
                Event next = queue.remove();
                now = next.at;
                next.action.run();
            }
            now = target;
        }
    }

    private static final class Event implements Comparable<Event> {
        final long at, sequence;
        final Runnable action;
        Event(long at, long sequence, Runnable action) {
            this.at = at; this.sequence = sequence; this.action = action;
        }
        @Override public int compareTo(Event other) {
            int time = Long.compare(at, other.at);
            return time == 0 ? Long.compare(sequence, other.sequence) : time;
        }
    }

    private static final class FakeHost implements InstrumentPanelLaunchCoordinator.Host {
        boolean enabled = true, autostart = true, display = true, dimReady = true;
        boolean holdDim, holdStart, acceptStart = true;
        int target = 2, preparations, tokenPreparations, starts, reloads, lastTarget = -1;
        InstrumentPanelLaunchCoordinator.WindowState window = InstrumentPanelLaunchCoordinator.WindowState.absent();
        final List<BooleanSupplier> dimGuards = new ArrayList<>();
        final List<Consumer<Boolean>> dimCompletions = new ArrayList<>();
        final List<Runnable> startCompletions = new ArrayList<>();
        final List<String> messages = new ArrayList<>();
        @Override public InstrumentPanelLaunchCoordinator.Settings settings() {
            return new InstrumentPanelLaunchCoordinator.Settings(enabled, autostart, target);
        }
        @Override public boolean displayAvailable(int id) { return display && id == target; }
        @Override public InstrumentPanelLaunchCoordinator.WindowState window() { return window; }
        @Override public void prepareDim(BooleanSupplier allowed, Consumer<Boolean> completion) {
            preparations++;
            dimGuards.add(allowed);
            dimCompletions.add(completion);
            if (!holdDim) completion.accept(dimReady);
        }
        @Override public void startPanel(int displayId, BooleanSupplier current,
                                         BooleanSupplier allowed, Consumer<Boolean> completion) {
            tokenPreparations++;
            Runnable finish = () -> {
                boolean admitted = allowed.getAsBoolean() && acceptStart;
                if (admitted) { starts++; lastTarget = displayId; }
                completion.accept(admitted);
            };
            startCompletions.add(finish);
            if (!holdStart) finish.run();
        }
        @Override public void reloadPanel() { reloads++; }
        @Override public void trace(String message) { messages.add(message); }
    }
}
