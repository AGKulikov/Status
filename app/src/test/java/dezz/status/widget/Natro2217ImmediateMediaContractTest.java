/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Regression boundary for the exact 2.2.16 road trace: 5.7 s queue wait + inert Yandex receiver. */
public final class Natro2217ImmediateMediaContractTest {
    @Test public void bootDeadlineCannotQueueBehindAnOlderMediaCommand() throws Exception {
        String build = read("build.gradle");
        String boot = read("app/src/main/java/dezz/status/widget/BootReceiver.java");
        String controller = read("app/src/main/java/dezz/status/widget/launcher/"
                + "MediaAutoResumeController.java");

        assertTrue(build.contains("if (version == '2.2.17')"));
        assertTrue(build.contains("return 208021251"));
        assertTrue(boot.contains(
                "MediaAutoResumeController.armAtReceiverBoundary(receiverContext, receivedAction)"));
        String boundary = between(controller, "public static void armAtReceiverBoundary(",
                "/** Called at the receiver boundary");
        assertTrue(boundary.contains("captureBootHistorySnapshot(exactApp, action)"));
        assertTrue(boundary.contains("scheduleAfterBoot(exactApp)"));
        assertFalse(boundary.contains("EXACT_TIMER.execute"));
        assertTrue(boundary.contains("route=inline"));
    }

    @Test public void anInertExplicitReceiverGetsOneExactPackageWarmLaunch() throws Exception {
        String controller = read("app/src/main/java/dezz/status/widget/launcher/"
                + "MediaAutoResumeController.java");
        String command = read("app/src/main/java/dezz/status/widget/launcher/"
                + "MediaResumeCommand.java");

        assertTrue(controller.contains("KEY_WARM_LAUNCH_ELAPSED"));
        assertTrue(controller.contains("MediaAppLauncher.launchPackage(app, target)"));
        assertTrue(controller.contains("event=target_warm_launch"));
        assertTrue(controller.contains("300L"));
        assertTrue(command.contains("sessions=" + "\" + sessionInventory"));
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, Math.max(0, from + start.length()));
        if (from < 0 || to <= from) throw new AssertionError(start + " -> " + end);
        return source.substring(from, to);
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(projectRoot().resolve(path)), StandardCharsets.UTF_8);
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
