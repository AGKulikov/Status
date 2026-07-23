/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.climate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ScreenReservationControllerTest {
    @Test
    public void parserAcceptsStockAndVendorOutputAndUsesEffectiveLastLine() {
        assertEquals(new ScreenReservationController.Insets(0, 12, 0, 0),
                ScreenReservationController.parseOverscan("Overscan: 0, 12, 0, 0\n"));
        assertEquals(new ScreenReservationController.Insets(-1, 2, 3, 4),
                ScreenReservationController.parseOverscan(
                        "Physical overscan: 0,0,0,0\nOverride overscan = Rect(-1, 2 - 3, 4)"));
        assertNull(ScreenReservationController.parseOverscan("Error: unknown command"));
        assertNull(ScreenReservationController.parseOverscan("Overscan: none"));
        assertNull(ScreenReservationController.parseOverscan(
                "mOverscan=Rect(0,0 - 1920,1080)"));
    }

    @Test
    public void displayDumpParserSelectsDisplayAndTreatsOmittedTupleAsZero() {
        String dump = "Logical Displays: size=2\n"
                + "  Display 0:\n"
                + "    mDisplayInfo=DisplayInfo{built-in, displayId 0, overscan (1,2,3,4), app}\n"
                + "  Display 3:\n"
                + "    mDisplayInfo=DisplayInfo{passenger, displayId 3, app 1280 x 720}\n";
        assertEquals(new ScreenReservationController.Insets(1, 2, 3, 4),
                ScreenReservationController.parseDisplayDumpOverscan(dump, 0));
        assertEquals(ScreenReservationController.Insets.ZERO,
                ScreenReservationController.parseDisplayDumpOverscan(dump, 3));
        assertNull(ScreenReservationController.parseDisplayDumpOverscan(dump, 9));
    }

    @Test
    public void defaultDisplayUsesCanonicalLegacyCommandsWithoutDisplayFlag() {
        ScreenReservationController.Insets value =
                new ScreenReservationController.Insets(1, 2, 3, 4);
        assertEquals("wm overscan", ScreenReservationController.queryCommand(0));
        assertEquals("wm overscan 1,2,3,4",
                ScreenReservationController.setCommand(0, value));
        assertEquals("wm overscan reset", ScreenReservationController.resetCommand(0));

        assertEquals("wm overscan -d 3", ScreenReservationController.queryCommand(3));
        assertEquals("wm overscan 1,2,3,4 -d 3",
                ScreenReservationController.setCommand(3, value));
        assertEquals("wm overscan reset -d 3", ScreenReservationController.resetCommand(3));
    }

    @Test
    public void legacyOverscanIsNeverSelectedPastAndroidTen() {
        assertTrue(ScreenReservationController.legacyOverscanSupported(28));
        assertTrue(ScreenReservationController.legacyOverscanSupported(29));
        assertFalse(ScreenReservationController.legacyOverscanSupported(30));
        assertFalse(ScreenReservationController.legacyOverscanSupported(36));
    }

    @Test
    public void stockWmQueryFallsBackToReadOnlyDisplayDump() {
        MemoryBackend backend = new MemoryBackend();
        ScriptedRunner runner = new ScriptedRunner();
        runner.respond("wm overscan", "Error: bad rectangle arg", null);
        runner.respond("dumpsys display",
                "Display 0:\n  mDisplayInfo=DisplayInfo{main, displayId 0, app 1920 x 1080}",
                null);
        runner.respond("wm overscan 0,0,0,50", "", null);
        runner.respond("wm overscan", "Error: bad rectangle arg", null);
        runner.respond("dumpsys display",
                "Display 0:\n  mDisplayInfo=DisplayInfo{main, displayId 0, overscan (0,0,0,50)}",
                null);
        Holder result = new Holder();

        controller(backend, runner).apply(
                ScreenReservationController.Edge.BOTTOM, 50, 0, result::set);

        assertTrue(result.value.success);
        assertEquals(5, runner.commands.size());
    }

    @Test
    public void applyAddsExtentToExactNonZeroBaselineAndVerifies() {
        MemoryBackend backend = new MemoryBackend();
        ScriptedRunner runner = new ScriptedRunner();
        runner.respond("wm help",
                "overscan [reset|LEFT,TOP,RIGHT,BOTTOM] [-d DISPLAY_ID]", null);
        runner.respond("wm overscan -d 3", "Physical overscan: 2,3,4,5", null);
        runner.respond("wm overscan 2,3,4,105 -d 3", "", null);
        runner.respond("wm overscan -d 3", "Overscan: 2,3,4,105", null);
        ScreenReservationController controller = controller(backend, runner);
        Holder result = new Holder();

        controller.apply(ScreenReservationController.Edge.BOTTOM, 100, 3, result::set);

        assertNotNull(result.value);
        assertTrue(result.value.success);
        assertEquals(new ScreenReservationController.Insets(2, 3, 4, 5),
                result.value.baseline);
        assertEquals(new ScreenReservationController.Insets(2, 3, 4, 105),
                result.value.actual);
        assertEquals(List.of("wm help", "wm overscan -d 3", "wm overscan 2,3,4,105 -d 3",
                "wm overscan -d 3"), runner.commands);

        ScreenReservationStateStore.Entry entry = store(backend).get(3);
        assertNotNull(entry);
        assertEquals(result.value.baseline, entry.baseline);
        assertEquals(result.value.target, entry.target);
    }

    @Test
    public void baselineIsCommittedBeforeMutatingCommand() {
        MemoryBackend backend = new MemoryBackend();
        InspectingRunner runner = new InspectingRunner(backend);
        ScreenReservationController controller = controller(backend, runner);
        Holder result = new Holder();

        controller.apply(ScreenReservationController.Edge.LEFT, 48, 0, result::set);

        assertTrue(runner.journalExistedAtMutation);
        assertNotNull(result.value);
        assertTrue(result.value.success);
    }

    @Test
    public void reapplyUsesOriginalBaselineInsteadOfAccumulatingExtent() {
        MemoryBackend backend = new MemoryBackend();
        ScriptedRunner first = new ScriptedRunner();
        first.respond("wm overscan", "Overscan: 1,2,3,4", null);
        first.respond("wm overscan 1,22,3,4", "", null);
        first.respond("wm overscan", "Overscan: 1,22,3,4", null);
        controller(backend, first).apply(ScreenReservationController.Edge.TOP, 20, 0, ignored -> { });

        ScriptedRunner second = new ScriptedRunner();
        second.respond("wm overscan", "Overscan: 1,22,3,4", null);
        second.respond("wm overscan 1,42,3,4", "", null);
        second.respond("wm overscan", "Overscan: 1,42,3,4", null);
        Holder result = new Holder();
        controller(backend, second).apply(ScreenReservationController.Edge.TOP, 40, 0, result::set);

        assertTrue(result.value.success);
        assertEquals(new ScreenReservationController.Insets(1, 2, 3, 4), result.value.baseline);
        assertEquals(new ScreenReservationController.Insets(1, 42, 3, 4), result.value.target);
    }

    @Test
    public void restoreWritesExactBaselineAndNeverReset() {
        MemoryBackend backend = new MemoryBackend();
        ScreenReservationStateStore stateStore = store(backend);
        assertTrue(stateStore.journalBeforeMutation(0,
                new ScreenReservationController.Insets(7, 8, 9, 10),
                new ScreenReservationController.Insets(7, 8, 9, 210)));
        ScriptedRunner runner = new ScriptedRunner();
        runner.respond("wm overscan 7,8,9,10", "", null);
        runner.respond("wm overscan", "Overscan: 7,8,9,10", null);
        Holder result = new Holder();

        new ScreenReservationController(stateStore, runner).restore(result::set);

        assertTrue(result.value.success);
        assertNull(stateStore.get(0));
        assertFalse(runner.commands.stream().anyMatch(value -> value.contains("reset")));
    }

    @Test
    public void overlayReconcileRestoresEveryManagedDisplayInOrder() {
        MemoryBackend backend = new MemoryBackend();
        ScreenReservationStateStore stateStore = store(backend);
        stateStore.journalBeforeMutation(3, inset(3), inset(30));
        stateStore.journalBeforeMutation(0, inset(0), inset(10));
        ScriptedRunner runner = new ScriptedRunner();
        runner.respond("wm overscan 0,0,0,0", "", null);
        runner.respond("wm overscan", "Overscan: 0,0,0,0", null);
        runner.respond("wm help",
                "overscan [reset|LEFT,TOP,RIGHT,BOTTOM] [-d DISPLAY_ID]", null);
        runner.respond("wm overscan 3,3,3,3 -d 3", "", null);
        runner.respond("wm overscan -d 3", "Overscan: 3,3,3,3", null);
        Holder result = new Holder();

        new ScreenReservationController(stateStore, runner).reconcile(
                ScreenReservationController.Desired.overlay(), result::set);

        assertTrue(result.value.success);
        assertTrue(stateStore.managedEntries().isEmpty());
        assertEquals("wm overscan 0,0,0,0", runner.commands.get(0));
        assertEquals("wm help", runner.commands.get(2));
        assertEquals("wm overscan 3,3,3,3 -d 3", runner.commands.get(3));
    }

    @Test
    public void nonDefaultDisplayFailsClosedWhenHelpDoesNotAdvertiseTargeting() {
        MemoryBackend backend = new MemoryBackend();
        ScriptedRunner runner = new ScriptedRunner();
        runner.respond("wm help", "overscan [reset|LEFT,TOP,RIGHT,BOTTOM]", null);
        Holder result = new Holder();

        controller(backend, runner).apply(
                ScreenReservationController.Edge.BOTTOM, 80, 3, result::set);

        assertNotNull(result.value);
        assertFalse(result.value.success);
        assertEquals(Collections.singletonList("wm help"), runner.commands);
        assertTrue(store(backend).managedEntries().isEmpty());
    }

    @Test
    public void unsafeNonDefaultRestoreLeavesRecoveryJournalUntouched() {
        MemoryBackend backend = new MemoryBackend();
        ScreenReservationStateStore stateStore = store(backend);
        stateStore.journalBeforeMutation(3, inset(3), inset(30));
        ScriptedRunner runner = new ScriptedRunner();
        runner.respond("wm help", "overscan [reset|LEFT,TOP,RIGHT,BOTTOM]", null);
        Holder result = new Holder();

        new ScreenReservationController(stateStore, runner).restore(result::set);

        assertFalse(result.value.success);
        assertEquals(Collections.singletonList("wm help"), runner.commands);
        assertNotNull(stateStore.get(3));
    }

    @Test
    public void failedVerificationLeavesRecoveryJournalForFutureRestore() {
        MemoryBackend backend = new MemoryBackend();
        ScriptedRunner runner = new ScriptedRunner();
        runner.respond("wm overscan", "Overscan: 0,0,0,0", null);
        runner.respond("wm overscan 0,0,64,0", "", null);
        runner.respond("wm overscan", "Overscan: 0,0,63,0", null);
        Holder result = new Holder();

        controller(backend, runner).apply(
                ScreenReservationController.Edge.RIGHT, 64, 0, result::set);

        assertNotNull(result.value);
        assertFalse(result.value.success);
        assertNotNull(store(backend).get(0));
    }

    @Test
    public void newerGenerationPreventsOlderQueryFromMutatingScreen() {
        MemoryBackend backend = new MemoryBackend();
        DeferredRunner runner = new DeferredRunner();
        ScreenReservationController controller = controller(backend, runner);
        Holder oldResult = new Holder();
        Holder newResult = new Holder();

        controller.apply(ScreenReservationController.Edge.TOP, 10, 0, oldResult::set);
        controller.apply(ScreenReservationController.Edge.BOTTOM, 20, 0, newResult::set);
        assertEquals(2, runner.pending.size());

        runner.completeFirst("Overscan: 0,0,0,0", null); // stale generation
        assertEquals(2, runner.commands.size()); // no command added by stale callback
        runner.completeFirst("Overscan: 0,0,0,0", null); // current generation query
        runner.completeFirst("", null);                  // set
        runner.completeFirst("Overscan: 0,0,0,20", null);// verify

        assertNull(oldResult.value);
        assertTrue(newResult.value.success);
        assertEquals(new ScreenReservationController.Insets(0, 0, 0, 20),
                newResult.value.target);
    }

    @Test
    public void explicitCancellationPreventsOldQueryFromStartingMutation() {
        MemoryBackend backend = new MemoryBackend();
        DeferredRunner runner = new DeferredRunner();
        ScreenReservationController controller = controller(backend, runner);
        Holder result = new Holder();

        controller.apply(ScreenReservationController.Edge.TOP, 10, 0, result::set);
        controller.cancelPending();
        runner.completeFirst("Overscan: 0,0,0,0", null);

        assertNull(result.value);
        assertEquals(Collections.singletonList("wm overscan"), runner.commands);
        assertTrue(store(backend).managedEntries().isEmpty());
    }

    @Test
    public void invalidNewRequestStillSupersedesInFlightMutation() {
        MemoryBackend backend = new MemoryBackend();
        DeferredRunner runner = new DeferredRunner();
        ScreenReservationController controller = controller(backend, runner);
        Holder oldResult = new Holder();
        Holder invalidResult = new Holder();

        controller.apply(ScreenReservationController.Edge.TOP, 10, 0, oldResult::set);
        controller.apply(ScreenReservationController.Edge.TOP, 0, 0, invalidResult::set);
        runner.completeFirst("Overscan: 0,0,0,0", null);

        assertNull(oldResult.value);
        assertNotNull(invalidResult.value);
        assertFalse(invalidResult.value.success);
        assertEquals(Collections.singletonList("wm overscan"), runner.commands);
        assertTrue(store(backend).managedEntries().isEmpty());
    }

    @Test
    public void onlyExplicitEmergencyMethodIssuesReset() {
        MemoryBackend backend = new MemoryBackend();
        store(backend).journalBeforeMutation(0, inset(7), inset(9));
        ScriptedRunner runner = new ScriptedRunner();
        runner.respond("wm overscan reset", "", null);
        runner.respond("wm overscan", "Overscan: 0,0,0,0", null);
        Holder result = new Holder();

        controller(backend, runner).emergencyReset(0, result::set);

        assertTrue(result.value.success);
        assertEquals("wm overscan reset", runner.commands.get(0));
        assertNull(store(backend).get(0));
    }

    private static ScreenReservationController controller(MemoryBackend backend,
                                                          ScreenReservationController.CommandRunner runner) {
        return new ScreenReservationController(store(backend), runner);
    }

    private static ScreenReservationStateStore store(MemoryBackend backend) {
        return new ScreenReservationStateStore(backend);
    }

    private static ScreenReservationController.Insets inset(int value) {
        return new ScreenReservationController.Insets(value, value, value, value);
    }

    private static final class Holder {
        ScreenReservationController.Result value;
        void set(ScreenReservationController.Result value) { this.value = value; }
    }

    private static final class MemoryBackend implements ScreenReservationStateStore.Backend {
        final LinkedHashMap<String, String> values = new LinkedHashMap<>();
        boolean allowCommit = true;

        @Override
        public Map<String, String> readAll() {
            return new LinkedHashMap<>(values);
        }

        @Override
        public boolean commit(Map<String, String> additions, Set<String> removals) {
            if (!allowCommit) return false;
            for (String key : removals) values.remove(key);
            values.putAll(additions);
            return true;
        }
    }

    private static class ScriptedRunner implements ScreenReservationController.CommandRunner {
        final List<String> commands = new ArrayList<>();
        private final ArrayDeque<Response> responses = new ArrayDeque<>();

        void respond(String expectedCommand, String output, String error) {
            responses.add(new Response(expectedCommand, output, error));
        }

        @Override
        public void run(String command, ScreenReservationController.CommandResultCallback callback) {
            commands.add(command);
            Response response = responses.removeFirst();
            assertEquals(response.command, command);
            callback.onResult(response.output, response.error);
        }
    }

    private static final class InspectingRunner implements ScreenReservationController.CommandRunner {
        private final MemoryBackend backend;
        boolean journalExistedAtMutation;
        private ScreenReservationController.Insets current = ScreenReservationController.Insets.ZERO;

        InspectingRunner(MemoryBackend backend) { this.backend = backend; }

        @Override
        public void run(String command, ScreenReservationController.CommandResultCallback callback) {
            if (command.equals("wm overscan")) {
                callback.onResult("Overscan: " + current, null);
                return;
            }
            journalExistedAtMutation = store(backend).get(0) != null;
            current = new ScreenReservationController.Insets(48, 0, 0, 0);
            callback.onResult("", null);
        }
    }

    private static final class DeferredRunner implements ScreenReservationController.CommandRunner {
        final List<String> commands = new ArrayList<>();
        final ArrayDeque<ScreenReservationController.CommandResultCallback> pending =
                new ArrayDeque<>();

        @Override
        public void run(String command, ScreenReservationController.CommandResultCallback callback) {
            commands.add(command);
            pending.add(callback);
        }

        void completeFirst(String output, String error) {
            pending.removeFirst().onResult(output, error);
        }
    }

    private static final class Response {
        final String command;
        final String output;
        final String error;

        Response(String command, String output, String error) {
            this.command = command;
            this.output = output;
            this.error = error;
        }
    }
}
