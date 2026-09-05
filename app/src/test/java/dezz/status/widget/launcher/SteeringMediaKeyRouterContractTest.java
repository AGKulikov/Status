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
        assertTrue(service.contains("mediaRouter.dispatch(keyCode, SystemClock.uptimeMillis())"));
        assertTrue(service.contains("consumedMediaKeys.put(keyCode, handled)"));
        assertTrue(service.contains("return handled || super.onKeyEvent(event)"));
    }

    @Test public void keyPressUsesOnlyTheCachedExactMediaSession() throws Exception {
        String router = read("app/src/main/java/dezz/status/widget/launcher/"
                + "SteeringMediaKeyRouter.java");
        String dispatch = between(router,
                "public boolean dispatch(int keyCode, long inputUptimeMs)",
                "public static boolean isSupportedKey");

        assertTrue(dispatch.contains("Route current = route"));
        assertTrue(dispatch.contains("current.controller.getTransportControls()"));
        assertTrue(dispatch.contains("controls.skipToNext()"));
        assertTrue(dispatch.contains("controls.skipToPrevious()"));
        assertTrue(dispatch.contains("input="));
        assertTrue(dispatch.contains("dispatch="));
        assertTrue(dispatch.contains("completed="));
        assertFalse(dispatch.contains("getActiveSessions"));
        assertFalse(dispatch.contains("AudioManager"));
        assertFalse(dispatch.contains("dispatchMediaKeyEvent"));
        assertTrue(router.contains("manager.addOnActiveSessionsChangedListener"));
        assertTrue(router.contains("if (fixed && !preferred.isEmpty())"));
        assertTrue(router.contains("return new Selection(null, null)"));
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
