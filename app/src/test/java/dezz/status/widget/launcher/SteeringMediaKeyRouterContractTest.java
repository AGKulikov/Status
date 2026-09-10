/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Static boundary for the KX11 steering-wheel route: no heavy subsystem may enter key dispatch. */
public final class SteeringMediaKeyRouterContractTest {
    @Test public void accessibilityRequestsKeyFilterAndConsumesOnlyExactAcceptedKeys()
            throws Exception {
        String service = read("app/src/main/java/dezz/status/widget/"
                + "WidgetAccessibilityService.java");
        String xml = read("app/src/main/res/xml/widget_accessibility_service.xml");

        assertTrue(xml.contains("flagRequestFilterKeyEvents"));
        assertTrue(xml.contains("android:canRequestFilterKeyEvents=\"true\""));
        assertTrue(service.contains("protected boolean onKeyEvent(@NonNull KeyEvent event)"));
        assertTrue(service.contains("final long callbackEntryUptimeMs = SystemClock.uptimeMillis()"));
        assertTrue(service.contains("mediaRouter.dispatch(keyCode, event.getEventTime(),"
                + " event.getDownTime(),"));
        assertTrue(service.contains("consumedMediaKeys.put(keyCode, handled)"));
        assertTrue(service.contains("return handled || super.onKeyEvent(event)"));
    }

    @Test public void keyPressUsesOnlyTheCachedExactMediaSession() throws Exception {
        String router = read("app/src/main/java/dezz/status/widget/launcher/"
                + "SteeringMediaKeyRouter.java");
        String dispatch = between(router,
                "public boolean dispatch(int keyCode, long eventTimeMs, long downTimeMs,",
                "public static boolean isSupportedKey");

        assertTrue(dispatch.contains("Route current = route"));
        assertTrue(dispatch.contains("enqueueCommand(command)"));
        assertFalse(dispatch.contains("getActiveSessions"));
        assertFalse(dispatch.contains("AudioManager"));
        assertFalse(dispatch.contains("dispatchMediaKeyEvent"));
        String queuedDispatch = between(router,
                "private void dispatchQueued(@NonNull Command queued)",
                "private void clearPendingCommands");
        assertTrue(queuedDispatch.contains("queued.target.controller.getTransportControls()"));
        assertTrue(queuedDispatch.contains("controls.skipToNext()"));
        assertTrue(queuedDispatch.contains("controls.skipToPrevious()"));
        assertTrue(router.contains("manager.addOnActiveSessionsChangedListener"));
        assertTrue(router.contains("sessionsListener, listenerComponent, resolver"));
        assertTrue(router.contains("if (fixed && !preferred.isEmpty())"));
        assertTrue(router.contains("return new Selection(null, null)"));
    }

    @Test public void commandSelectionAndDiagnosticsUseThreeIndependentBoundedChannels()
            throws Exception {
        String router = read("app/src/main/java/dezz/status/widget/launcher/"
                + "SteeringMediaKeyRouter.java");

        assertTrue(router.contains("\"steering-media-route\""));
        assertTrue(router.contains("\"steering-media-command\""));
        assertTrue(router.contains("\"steering-media-journal\""));
        assertTrue(router.contains("MAX_PENDING_COMMANDS = 4"));
        assertTrue(router.contains("MAX_COMMAND_QUEUE_AGE_MS = 750L"));
        assertTrue(router.contains("pendingCommands.size() >= MAX_PENDING_COMMANDS"));
        assertTrue(router.contains("dispatchStarted - queued.enqueuedAtMs"
                + " > MAX_COMMAND_QUEUE_AGE_MS"));
        assertTrue(router.contains("clearPendingCommands(\"session_generation\")"));
        assertTrue(router.contains("clearPendingCommands(\"close\")"));
        assertTrue(router.contains("pendingTraces.size() == MAX_PENDING_TRACES"));

        String queuedDispatch = between(router,
                "private void dispatchQueued(@NonNull Command queued)",
                "private void clearPendingCommands");
        String refresh = between(router,
                "private void requestRefresh()",
                "private void completeRefresh");
        String selection = between(router,
                "private void scheduleSelection(@NonNull List<MediaController> controllers)",
                "private Selection select");
        String callbackRegistration = between(router,
                "private void registerRouteCallback(@NonNull Route next)",
                "private boolean fixedPlayerEnabled");
        assertFalse(queuedDispatch.contains("DiagnosticJournal"));
        assertFalse(queuedDispatch.contains("getActiveSessions"));
        assertFalse(queuedDispatch.contains("main.post"));
        assertTrue(queuedDispatch.contains("resolver.post"));
        assertTrue(refresh.contains("Looper.myLooper() != resolver.getLooper()"));
        assertTrue(refresh.contains("manager.getActiveSessions(listenerComponent)"));
        assertFalse(refresh.contains("main.post"));
        assertTrue(selection.contains("Looper.myLooper() != resolver.getLooper()"));
        assertFalse(selection.contains("main.post"));
        assertTrue(callbackRegistration.contains("registerCallback(next.callback, resolver)"));
        assertFalse(callbackRegistration.contains("main.post"));
        assertTrue(count(router, "getActiveSessions(listenerComponent)") == 1);
        assertFalse(router.contains("event.getEventTime()"));
    }

    private static int count(String source, String value) {
        int result = 0;
        for (int index = 0; (index = source.indexOf(value, index)) >= 0;
             index += value.length()) result++;
        return result;
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, Math.max(0, from + start.length()));
        if (from < 0 || to <= from) throw new AssertionError(start + " -> " + end);
        return source.substring(from, to);
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(projectRoot().resolve(path)),
                StandardCharsets.UTF_8);
    }

    private static Path projectRoot() {
        Path current = Paths.get("").toAbsolutePath().normalize();
        while (current != null && !Files.exists(current.resolve("settings.gradle"))) {
            current = current.getParent();
        }
        if (current == null) throw new IllegalStateException("project root not found");
        return current;
    }
}
