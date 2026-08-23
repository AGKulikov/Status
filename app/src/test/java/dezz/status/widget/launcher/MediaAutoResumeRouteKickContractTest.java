/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
        return new String(Files.readAllBytes(Paths.get(
                "app/src/main/java/dezz/status/widget/" + relative)),
                StandardCharsets.UTF_8);
    }
}
