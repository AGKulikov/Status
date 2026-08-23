/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class MediaAutoResumeRouteKickContractTest {
    @Test public void duePlanIsRetriedWhenTheAudioRouteBecomesUsable() throws Exception {
        String controller = read("launcher/MediaAutoResumeController.java");
        String receiver = read("BluetoothIntegrationWakeReceiver.java");
        assertTrue(controller.contains("event=audio_route_kick"));
        assertTrue(controller.contains("schedule(app, bootToken, attempt, 50L"));
        assertTrue(receiver.contains("BluetoothProfile.STATE_CONNECTED"));
        assertTrue(receiver.contains("MediaAutoResumeController.onAudioRouteReady"));
    }

    private static String read(String relative) throws Exception {
        return new String(Files.readAllBytes(projectRoot().resolve(
                "app/src/main/java/dezz/status/widget/" + relative)),
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
