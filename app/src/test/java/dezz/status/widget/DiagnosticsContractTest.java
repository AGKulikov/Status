/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Guards the user-visible debug journal and reproducible action-capture mode. */
public final class DiagnosticsContractTest {
    @Test
    public void detailedJournalIsOptInCyclicColoredAndPrivacyFiltered() throws IOException {
        String preferences = source("Preferences.java");
        String journal = source("diagnostics/DiagnosticJournal.java");
        String watchdog = source("diagnostics/MainThreadWatchdog.java");
        String activity = source("DiagnosticsActivity.java");

        assertTrue(preferences.contains("\"debugModeEnabled\", false"));
        assertTrue(journal.contains("ROTATE_AT_BYTES"));
        assertTrue(journal.contains("KEEP_TAIL_BYTES"));
        assertTrue(journal.contains("SECRET_ASSIGNMENT"));
        assertTrue(journal.contains("MAC_ADDRESS"));
        assertTrue(journal.contains("recordCrash("));
        assertTrue(watchdog.contains("HANG_THRESHOLD_MS"));
        assertTrue(watchdog.contains("threadDump()"));
        assertTrue(activity.contains("case ERROR: return 0xFFFF453A"));
        assertTrue(activity.contains("case WARN: return 0xFFFFD60A"));
        assertTrue(activity.contains("DiagnosticJournal.copyForExport("));
        assertTrue(activity.contains("DiagnosticJournal.clear()"));
    }

    @Test
    public void actionRecorderFlushesTxtAndJsonAndPreservesInterruptedSession()
            throws IOException {
        String recorder = source("diagnostics/ActionRecorder.java");
        String overlay = source("diagnostics/ActionRecorderOverlayService.java");

        assertTrue(recorder.contains("actions-\" + session.id + \".jsonl\""));
        assertTrue(recorder.contains("actions-\" + session.id + \".txt\""));
        assertTrue(recorder.contains("output.flush()"));
        assertTrue(recorder.contains("SESSION_INTERRUPTED"));
        assertTrue(recorder.contains("status-widget-action-session-v1"));
        assertTrue(recorder.contains("DiagnosticJournal.redact("));
        assertTrue(overlay.contains("TYPE_APPLICATION_OVERLAY"));
        assertTrue(overlay.contains("toggleRecording()"));
        assertTrue(overlay.contains("dragFrame"));
        assertTrue(overlay.contains("preferences.actionRecorderOverlayX.set("));
        assertTrue(overlay.contains("preferences.actionRecorderOverlayWidth.set("));
        assertTrue(overlay.contains("preferences.actionRecorderOverlayAlpha.get()"));
    }

    @Test
    public void steeringKeysAndScreensAreObservedButNeverConsumed() throws IOException {
        String accessibility = source("WidgetAccessibilityService.java");
        String config = resource("xml/widget_accessibility_service.xml");
        String application = source("StatusWidgetApplication.java");

        assertTrue(accessibility.contains("protected boolean onKeyEvent(KeyEvent event)"));
        assertTrue(accessibility.contains("SOURCE_STEERING_KEY"));
        assertTrue(accessibility.contains("\"repeat\", event.getRepeatCount()"));
        assertTrue(accessibility.contains("\"long_press\", event.isLongPress()"));
        assertTrue(accessibility.contains("return false;"));
        assertTrue(config.contains("flagRequestFilterKeyEvents"));
        assertTrue(config.contains("android:canRequestFilterKeyEvents=\"true\""));
        assertTrue(application.contains("registerActivityLifecycleCallbacks"));
        assertTrue(application.contains("\"intent_action\""));
        assertTrue(application.contains("\"data_scheme\""));
        assertFalse(application.contains("intent.getExtras()"));
    }

    @Test
    public void diagnosticsIsReachableAndOverlayIsManifestDeclared() throws IOException {
        String manifest = manifest();
        String catalog = source("settings/SettingsDestinationCatalog.java");

        assertTrue(manifest.contains("android:name=\".DiagnosticsActivity\""));
        assertTrue(manifest.contains(
                "android:name=\".diagnostics.ActionRecorderOverlayService\""));
        assertTrue(catalog.contains("\"app_diagnostics\""));
        assertTrue(catalog.contains("\"dezz.status.widget.DiagnosticsActivity\""));
    }

    private static String source(String relative) throws IOException {
        return read(Paths.get("java", "dezz", "status", "widget").resolve(relative));
    }

    private static String resource(String relative) throws IOException {
        return read(Paths.get("res").resolve(relative));
    }

    private static String manifest() throws IOException {
        Path fromRoot = Paths.get("app", "src", "main", "AndroidManifest.xml");
        Path fromApp = Paths.get("src", "main", "AndroidManifest.xml");
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static String read(Path relative) throws IOException {
        Path fromRoot = Paths.get("app", "src", "main").resolve(relative);
        Path fromApp = Paths.get("src", "main").resolve(relative);
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
