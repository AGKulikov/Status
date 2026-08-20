/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;

/** Geometry and source contracts for the Android-9 ECARX Navigator-window observer. */
public final class Ha1220NavigatorWindowObserverTest {
    private static final NavigatorWindowFramePolicy.Frame DISPLAY =
            new NavigatorWindowFramePolicy.Frame(0, 0, 1920, 720);

    @Test public void boundedVisibleApplicationFrameIsWindowed() {
        NavigatorWindowFramePolicy.Result result = classify(sample(
                0, 1, 0, true,
                new NavigatorWindowFramePolicy.Frame(80, 42, 1510, 680)));

        assertEquals(NavigatorWindowFramePolicy.State.WINDOWED, result.state);
        assertEquals(1, result.visibleCandidateCount);
        assertEquals("ru.yandex.yandexnavi", result.evidence.packageName);
    }

    @Test public void nearDisplayFrameIsFullscreenAndWinsOverAuxiliaryPopup() {
        NavigatorWindowFramePolicy.WindowSample popup = sample(
                0, 2, 0, true,
                new NavigatorWindowFramePolicy.Frame(700, 200, 1150, 520));
        NavigatorWindowFramePolicy.WindowSample content = sample(
                0, 1, 0, true,
                new NavigatorWindowFramePolicy.Frame(8, 4, 1905, 712));
        NavigatorWindowFramePolicy.Result result = NavigatorWindowFramePolicy.classify(
                DISPLAY, 0, Arrays.asList(popup, content));

        assertEquals(NavigatorWindowFramePolicy.State.FULLSCREEN, result.state);
        assertEquals(content, result.evidence);
        assertEquals(2, result.visibleCandidateCount);
    }

    @Test public void middleGeometryNeverCollapsesIntoEitherSurface() {
        NavigatorWindowFramePolicy.Result result = classify(sample(
                0, 1, 0, true,
                new NavigatorWindowFramePolicy.Frame(0, 0, 1800, 690)));

        assertEquals(NavigatorWindowFramePolicy.State.UNKNOWN, result.state);
    }

    @Test public void smallApplicationDialogIsNotMistakenForNavigatorWindow() {
        NavigatorWindowFramePolicy.Result result = classify(sample(
                0, 2, 0, true,
                new NavigatorWindowFramePolicy.Frame(760, 240, 1160, 480)));

        assertEquals(NavigatorWindowFramePolicy.State.UNKNOWN, result.state);
    }

    @Test public void oneAxisSystemInsetIsNotMistakenForFloatingMode() {
        NavigatorWindowFramePolicy.Result result = classify(sample(
                0, 1, 0, true,
                new NavigatorWindowFramePolicy.Frame(0, 92, 1920, 720)));

        assertEquals(NavigatorWindowFramePolicy.State.UNKNOWN, result.state);
    }

    @Test public void missingCandidateFrameIsUnknownNotAbsent() {
        NavigatorWindowFramePolicy.Result onlyMissing = classify(sample(
                0, 1, 0, true, null));
        NavigatorWindowFramePolicy.Result missingBesidePopup =
                NavigatorWindowFramePolicy.classify(DISPLAY, 0, Arrays.asList(
                        sample(0, 1, 0, true, null),
                        sample(0, 2, 0, true,
                                new NavigatorWindowFramePolicy.Frame(600, 180, 1100, 500))));

        assertEquals(NavigatorWindowFramePolicy.State.UNKNOWN, onlyMissing.state);
        assertEquals(NavigatorWindowFramePolicy.State.UNKNOWN, missingBesidePopup.state);
        assertEquals(2, missingBesidePopup.visibleCandidateCount);
    }

    @Test public void nonApplicationYandexWindowIsUnknownNotAFalseFloatingMatch() {
        NavigatorWindowFramePolicy.WindowSample overlay = sample(
                0, 2038, 0, true,
                new NavigatorWindowFramePolicy.Frame(100, 100, 700, 600));
        NavigatorWindowFramePolicy.Result result = classify(overlay);

        assertEquals(NavigatorWindowFramePolicy.State.UNKNOWN, result.state);
        assertEquals(1, result.visibleCandidateCount);
        assertEquals(overlay, result.evidence);
    }

    @Test public void nonTargetInvisibleAndForeignWindowsAreAbsent() {
        NavigatorWindowFramePolicy.Result result = NavigatorWindowFramePolicy.classify(
                DISPLAY, 0, Arrays.asList(
                        sample(1, 1, 0, true,
                                new NavigatorWindowFramePolicy.Frame(100, 100, 700, 600)),
                        sample(0, 1, 4, true,
                                new NavigatorWindowFramePolicy.Frame(100, 100, 700, 600)),
                        sample(0, 1, 0, false,
                                new NavigatorWindowFramePolicy.Frame(100, 100, 700, 600))));

        assertEquals(NavigatorWindowFramePolicy.State.ABSENT, result.state);
        assertEquals(0, result.visibleCandidateCount);
        assertNull(result.evidence);
    }

    @Test public void unavailableInventoryOrBoundsIsUnknown() {
        assertEquals(NavigatorWindowFramePolicy.State.UNKNOWN,
                NavigatorWindowFramePolicy.classify(null, 0,
                        Collections.emptyList()).state);
        assertEquals(NavigatorWindowFramePolicy.State.UNKNOWN,
                NavigatorWindowFramePolicy.classify(DISPLAY, 0, null).state);
        assertEquals(NavigatorWindowFramePolicy.State.UNKNOWN,
                NavigatorWindowFramePolicy.classify(
                        new NavigatorWindowFramePolicy.Frame(0, 0, 0, 0), 0,
                        Collections.emptyList()).state);
    }

    @Test public void windowedVendorLeaseExpiresWithoutRevivingConsumedFallback() {
        long confirmedAt = 100L;
        long lease = 6_000L;
        NavigatorWindowFramePolicy.Result windowed = classify(sample(
                0, 1, 0, true,
                new NavigatorWindowFramePolicy.Frame(80, 42, 1510, 680)));
        NavigatorWindowSourcePolicy.VendorDecision decision =
                NavigatorWindowSourcePolicy.decisionFor(windowed);

        // The optimistic startActivity/a11y assertion predates real vendor geometry and is
        // consumed when the independent vendor source takes ownership.
        boolean fallback = NavigatorWindowSourcePolicy.fallbackAfterVendorTakeover(
                true, decision);
        assertFalse(fallback);
        assertTrue(NavigatorWindowSourcePolicy.effectiveWindow(
                fallback, decision, confirmedAt, confirmedAt + lease, lease));

        // No callback/heartbeat arrived to renew the lease: vendor hide is now gone and the old
        // optimistic source cannot keep the element hidden indefinitely.
        assertFalse(NavigatorWindowSourcePolicy.effectiveWindow(
                fallback, decision, confirmedAt, confirmedAt + lease + 1L, lease));
    }

    @Test public void unavailableVendorReturnsToAnIndependentFreshFallback() {
        NavigatorWindowFramePolicy.Result unavailable =
                new NavigatorWindowFramePolicy.Result(
                        NavigatorWindowFramePolicy.State.UNKNOWN, null, 0);
        NavigatorWindowSourcePolicy.VendorDecision decision =
                NavigatorWindowSourcePolicy.decisionFor(unavailable);

        assertEquals(NavigatorWindowSourcePolicy.VendorDecision.NONE, decision);
        assertTrue(NavigatorWindowSourcePolicy.fallbackAfterVendorTakeover(true, decision));
        assertTrue(NavigatorWindowSourcePolicy.effectiveWindow(
                true, decision, -1L, 10_000L, 6_000L));
    }

    @Test public void fullscreenGeometryAlwaysOverridesStaleWindowFallback() {
        NavigatorWindowFramePolicy.Result fullscreen = classify(sample(
                0, 1, 0, true,
                new NavigatorWindowFramePolicy.Frame(0, 0, 1920, 720)));
        NavigatorWindowSourcePolicy.VendorDecision decision =
                NavigatorWindowSourcePolicy.decisionFor(
                        fullscreen, false,
                        NavigatorWindowSourcePolicy.VendorDecision.NONE);

        assertEquals(NavigatorWindowSourcePolicy.VendorDecision.NOT_WINDOWED, decision);
        assertFalse(NavigatorWindowSourcePolicy.effectiveWindow(
                true, decision, 100L, 101L, 6_000L));
    }

    @Test public void launchUnknownThenWindowedKeepsGraceAndAcceptsPositiveFrame() {
        NavigatorWindowFramePolicy.Result unknownFrame = classify(sample(
                0, 1, 0, true, null));
        NavigatorWindowSourcePolicy.VendorDecision duringGrace =
                NavigatorWindowSourcePolicy.decisionFor(
                        unknownFrame, true,
                        NavigatorWindowSourcePolicy.VendorDecision.NONE);
        assertEquals(NavigatorWindowSourcePolicy.VendorDecision.NONE, duringGrace);
        assertTrue(NavigatorWindowSourcePolicy.effectiveWindow(
                true, duringGrace, -1L, 500L, 6_000L));

        NavigatorWindowFramePolicy.Result windowed = classify(sample(
                0, 1, 0, true,
                new NavigatorWindowFramePolicy.Frame(80, 42, 1510, 680)));
        NavigatorWindowSourcePolicy.VendorDecision confirmed =
                NavigatorWindowSourcePolicy.decisionFor(windowed, true, duringGrace);
        boolean consumedOptimistic = NavigatorWindowSourcePolicy.fallbackAfterVendorTakeover(
                true, confirmed);
        assertEquals(NavigatorWindowSourcePolicy.VendorDecision.WINDOWED, confirmed);
        assertFalse(consumedOptimistic);
        assertTrue(NavigatorWindowSourcePolicy.effectiveWindow(
                consumedOptimistic, confirmed, 600L, 601L, 6_000L));
    }

    @Test public void launchAbsentForeverExpiresInsteadOfSticking() {
        NavigatorWindowFramePolicy.Result absent = NavigatorWindowFramePolicy.classify(
                DISPLAY, 0, Collections.emptyList());
        NavigatorWindowSourcePolicy.VendorDecision decision =
                NavigatorWindowSourcePolicy.decisionFor(
                        absent, true, NavigatorWindowSourcePolicy.VendorDecision.NONE);

        assertEquals(NavigatorWindowSourcePolicy.VendorDecision.NONE, decision);
        assertTrue(NavigatorWindowSourcePolicy.effectiveWindow(
                true, decision, -1L, 1_799L, 6_000L));
        boolean afterGrace = NavigatorWindowSourcePolicy.fallbackAfterOptimisticExpiry(
                true, true);
        assertFalse(afterGrace);
        assertFalse(NavigatorWindowSourcePolicy.effectiveWindow(
                afterGrace, decision, -1L, 1_801L, 6_000L));
    }

    @Test public void singleAbsentAfterWindowedIsRetriedAndRecoveryWins() {
        NavigatorWindowSourcePolicy.AbsenceGate gate =
                new NavigatorWindowSourcePolicy.AbsenceGate();

        assertEquals(NavigatorWindowSourcePolicy.ObservationAction.PUBLISH,
                gate.observe(NavigatorWindowFramePolicy.State.WINDOWED));
        assertEquals(NavigatorWindowSourcePolicy.ObservationAction.RETRY,
                gate.observe(NavigatorWindowFramePolicy.State.ABSENT));
        assertEquals(NavigatorWindowSourcePolicy.ObservationAction.PUBLISH,
                gate.observe(NavigatorWindowFramePolicy.State.WINDOWED));
        assertEquals(NavigatorWindowSourcePolicy.ObservationAction.RETRY,
                gate.observe(NavigatorWindowFramePolicy.State.ABSENT));
        assertEquals(NavigatorWindowSourcePolicy.ObservationAction.PUBLISH,
                gate.observe(NavigatorWindowFramePolicy.State.ABSENT));
    }

    @Test public void a11yFallbackRequiresTwoAbsentSamplesThenIsCleared() {
        NavigatorWindowSourcePolicy.AbsenceGate gate =
                new NavigatorWindowSourcePolicy.AbsenceGate();
        NavigatorWindowFramePolicy.Result absent = NavigatorWindowFramePolicy.classify(
                DISPLAY, 0, Collections.emptyList());

        // Accessibility can confirm TransparentSplashActivity before the content frame exists.
        // One empty vendor inventory is a hand-off race and must only request a bounded requery.
        assertEquals(NavigatorWindowSourcePolicy.ObservationAction.RETRY,
                gate.observe(absent.state));

        // A second strong empty inventory is authoritative even when this process has not yet
        // seen WINDOWED. It must override the durable a11y fallback instead of leaving it stuck.
        assertEquals(NavigatorWindowSourcePolicy.ObservationAction.PUBLISH,
                gate.observe(absent.state));
        NavigatorWindowSourcePolicy.VendorDecision decision =
                NavigatorWindowSourcePolicy.decisionFor(
                        absent, false, NavigatorWindowSourcePolicy.VendorDecision.NONE);
        assertEquals(NavigatorWindowSourcePolicy.VendorDecision.NOT_WINDOWED, decision);
        assertFalse(NavigatorWindowSourcePolicy.effectiveWindow(
                true, decision, 200L, 201L, 6_000L));

        // The same confirmed absence stays non-authoritative while an explicit launch owns its
        // short optimistic confirmation grace.
        assertEquals(NavigatorWindowSourcePolicy.VendorDecision.NONE,
                NavigatorWindowSourcePolicy.decisionFor(
                        absent, true, NavigatorWindowSourcePolicy.VendorDecision.NONE));
    }

    @Test public void launchA11yTwoAbsentThenWindowedKeepsIndependentGrace() {
        boolean confirmationPending = false;

        // Successful startActivity publishes the explicit launch-owned marker.
        NavigatorWindowSourcePolicy.OptimisticAction launchAction =
                NavigatorWindowSourcePolicy.optimisticActionAfterSurfaceChange(
                        confirmationPending, true, true);
        assertEquals(NavigatorWindowSourcePolicy.OptimisticAction.START_OR_RESTART,
                launchAction);
        confirmationPending = true;

        // Exact TransparentSplash accessibility confirmation clears the surface token's
        // optimistic bit, but must not cancel the independent vendor geometry grace.
        NavigatorWindowSourcePolicy.OptimisticAction a11yAction =
                NavigatorWindowSourcePolicy.optimisticActionAfterSurfaceChange(
                        confirmationPending, true, false);
        assertEquals(NavigatorWindowSourcePolicy.OptimisticAction.KEEP, a11yAction);

        NavigatorWindowSourcePolicy.AbsenceGate gate =
                new NavigatorWindowSourcePolicy.AbsenceGate();
        NavigatorWindowFramePolicy.Result absent = NavigatorWindowFramePolicy.classify(
                DISPLAY, 0, Collections.emptyList());
        assertEquals(NavigatorWindowSourcePolicy.ObservationAction.RETRY,
                gate.observe(absent.state));
        assertEquals(NavigatorWindowSourcePolicy.ObservationAction.PUBLISH,
                gate.observe(absent.state));
        assertEquals(NavigatorWindowSourcePolicy.VendorDecision.NONE,
                NavigatorWindowSourcePolicy.decisionFor(
                        absent, confirmationPending,
                        NavigatorWindowSourcePolicy.VendorDecision.NONE));

        NavigatorWindowFramePolicy.Result windowed = classify(sample(
                0, 1, 0, true,
                new NavigatorWindowFramePolicy.Frame(80, 42, 1510, 680)));
        assertEquals(NavigatorWindowSourcePolicy.VendorDecision.WINDOWED,
                NavigatorWindowSourcePolicy.decisionFor(
                        windowed, confirmationPending,
                        NavigatorWindowSourcePolicy.VendorDecision.NONE));

        // A lifecycle close remains authoritative and cancels a still-pending grace.
        assertEquals(NavigatorWindowSourcePolicy.OptimisticAction.CANCEL,
                NavigatorWindowSourcePolicy.optimisticActionAfterSurfaceChange(
                        confirmationPending, false, false));
    }

    @Test public void observerUsesOptionalReflectionAndCallbackRequeryContract() throws Exception {
        String observer = source("EcarxNavigatorWindowObserver.java");
        String sourcePolicy = source("NavigatorWindowSourcePolicy.java");
        String surface = source("StatusBarSurfaceContext.java");
        String launcher = source("launcher/YandexWindowLauncher.java");
        String widget = source("WidgetService.java");

        assertFalse(observer.contains("import com.ecarx."));
        assertTrue(observer.contains("Proxy.newProxyInstance("));
        assertTrue(observer.contains("IWindowManager$IWindowViewObserver"));
        assertTrue(observer.contains("registerWindowObserver"));
        assertTrue(observer.contains("getWindowList"));
        assertTrue(observer.contains("takeSnapshot(\"initial\")"));

        String callback = between(observer,
                "private Object createObserverProxy", "private void requestSnapshot");
        assertTrue(callback.contains("name.startsWith(\"onWindow\")"));
        assertTrue(callback.contains("requestSnapshot("));
        assertTrue(callback.contains("CALLBACK_DEBOUNCE_MS"));
        assertFalse(callback.contains("getWindowFrame"));

        String evidence = between(observer,
                "private String evidence", "private NavigatorWindowFramePolicy.Frame displayBounds");
        assertFalse(evidence.contains("getIdentity"));
        assertFalse(evidence.contains("getTag"));
        assertTrue(evidence.contains("sample.packageName"));
        assertTrue(evidence.contains("sample.frame"));

        assertTrue(widget.contains("new EcarxNavigatorWindowObserver("));
        assertTrue(widget.contains("ECARX_NAVIGATOR_CONFIRMATION_LEASE_MS"));
        assertTrue(observer.contains("WINDOW_CONFIRMATION_REFRESH_MS"));
        assertTrue(observer.contains("scheduleConfirmationRefresh()"));
        assertTrue(observer.contains("requestSnapshot(\"confirmation-lease\", 0L)"));
        assertTrue(observer.contains("absenceGate.observe(result.state)"));
        assertTrue(observer.contains("scheduleAbsenceConfirmation()"));
        assertTrue(observer.contains("ABSENCE_CONFIRMATION_MS"));
        assertTrue(widget.contains("!hasLiveEcarxNavigatorWindowConfirmation()"));
        assertTrue(widget.contains("ecarxNavigatorWindowObserver.refresh("));
        assertTrue(sourcePolicy.contains("case UNKNOWN:"));
        assertTrue(sourcePolicy.contains("result.visibleCandidateCount > 0"));
        assertTrue(widget.contains("ecarxNavigatorWindowLeaseExpiry"));
        assertTrue(widget.contains("mainHandler.postDelayed(ecarxNavigatorWindowLeaseExpiry"));
        assertTrue(widget.contains("ECARX_NAVIGATOR_OPTIMISTIC_RETRY_OFFSETS_MS"));
        assertTrue(widget.contains("ecarxNavigatorOptimisticExpiry"));
        assertTrue(widget.contains("optimistic-confirmation-"));
        assertTrue(widget.contains("optimisticActionAfterSurfaceChange("));
        assertTrue(widget.contains("ecarxNavigatorWindowObserver.refresh(\"optimistic-expiry\")"));
        assertTrue(widget.contains("&& !ecarxNavigatorOptimisticConfirmationPending"));
        assertTrue(widget.contains("recomputeForegroundSurfacePresentation()"));
        assertTrue(surface.contains("navigatorWindowOptimistic"));
        assertTrue(surface.contains("consumeNavigatorWindowFallback()"));
        assertTrue(launcher.contains("markNavigatorWindowOptimistic()"));
        String vendorCallback = between(widget,
                "private void onEcarxNavigatorWindowStateChanged",
                "private boolean effectiveNavigatorWindowForeground");
        assertFalse(vendorCallback.contains(
                "StatusBarSurfaceContext.setNavigatorWindowForeground(true)"));
    }

    private static NavigatorWindowFramePolicy.Result classify(
            NavigatorWindowFramePolicy.WindowSample sample) {
        return NavigatorWindowFramePolicy.classify(
                DISPLAY, 0, Collections.singletonList(sample));
    }

    private static NavigatorWindowFramePolicy.WindowSample sample(
            int displayId, int type, int visibility, boolean yandexOwned,
            NavigatorWindowFramePolicy.Frame frame) {
        return new NavigatorWindowFramePolicy.WindowSample(
                "ru.yandex.yandexnavi", displayId, type, visibility, yandexOwned, frame);
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        assertTrue("Missing start marker: " + start, from >= 0);
        assertTrue("Missing end marker: " + end, to > from);
        return source.substring(from, to);
    }

    private static String source(String relative) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        String projectRelative = "app/src/main/java/dezz/status/widget/" + relative;
        for (int depth = 0; depth < 8 && current != null;
             depth++, current = current.getParent()) {
            Path candidate = current.resolve(projectRelative);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("Project file not found: " + projectRelative);
    }
}
