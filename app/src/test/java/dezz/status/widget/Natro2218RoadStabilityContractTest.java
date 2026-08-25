/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Source-level guard for the 2026-08-24 ANCS and delayed Yandex road-log fixes. */
public final class Natro2218RoadStabilityContractTest {
    @Test public void releaseIdentityAndMusicBoundaryAreExact() throws Exception {
        String build = read("build.gradle");
        String lifecycle = read("app/src/main/java/dezz/status/widget/launcher/"
                + "MediaAutoResumeLifecyclePolicy.java");
        String controller = read("app/src/main/java/dezz/status/widget/launcher/"
                + "MediaAutoResumeController.java");
        String command = read("app/src/main/java/dezz/status/widget/launcher/"
                + "MediaResumeCommand.java");
        String browser = read("app/src/main/java/dezz/status/widget/launcher/"
                + "YandexMusicBrowserStarter.java");

        assertTrue(build.contains("if (version == '2.2.18')"));
        assertTrue(build.contains("return 208021252"));
        assertTrue(lifecycle.contains("return ACTION_BOOT_COMPLETED.equals(action)"));
        assertTrue(lifecycle.contains("ACTION_LOCKED_BOOT_COMPLETED.equals(previousAction)"));
        assertTrue(controller.contains("MAX_ATTEMPTS = 5"));
        assertTrue(controller.contains("RETRY_DELAY_MS = 10_000L"));
        assertTrue(controller.contains("reason=player_boot_gate"));
        assertFalse(controller.contains("MediaAppLauncher.launchPackage"));
        assertTrue(command.contains("YANDEX_PLAY_KEY_UP_DELAY_MS = 100L"));
        assertTrue(command.contains("YandexMusicBrowserStarter.requestPlay(context)"));
        assertTrue(browser.contains("MusicBrowserService"));
        assertTrue(browser.contains("playExactSessionOnly"));
    }

    @Test public void ancsPreemptsAndRetriesInsteadOfPoisoningHealthyOwner() throws Exception {
        String central = read("app/src/main/java/dezz/status/widget/phone/transport/v2/"
                + "android/AndroidCentralTransportV2.java");
        String route = read("app/src/main/java/dezz/status/widget/phone/transport/v2/"
                + "AndroidCentralRoute.java");

        assertTrue(central.contains("preemptCarRemoteWriteForAncs()"));
        assertTrue(central.contains("ancs_control_deferred reason="));
        assertTrue(central.contains("android_gatt_busy_"));
        assertTrue(central.contains("CAR_REMOTE_ANCS_PAUSE_MS = 250L"));
        assertTrue(central.contains("carRemoteRetryNotBeforeMillis"));
        assertTrue(route.contains("CONNECT_TIMEOUT_MS = 8_000L"));
    }

    @Test public void listenerHubAndSystemShareChooserRemainPresent() throws Exception {
        String panel = read("app/src/main/java/dezz/status/widget/launcher/information/"
                + "InformationPanelView.java");
        String hub = read("app/src/main/java/dezz/status/widget/launcher/information/"
                + "ConnectorValueSubscriptionHub.java");
        String settings = read("app/src/main/java/dezz/status/widget/"
                + "PhoneConnectorSettingsActivity.java");

        assertTrue(panel.contains("ConnectorValueSubscriptionHub.subscribe"));
        assertTrue(panel.contains("ConnectorValueSubscriptionHub.unsubscribe"));
        assertTrue(hub.contains("service.removeConnectorValueListener(entry.upstream)"));
        assertTrue(settings.contains("Intent.createChooser(share"));
        assertTrue(settings.contains("Intent.EXTRA_STREAM"));
    }

    private static String read(String relative) throws Exception {
        return new String(Files.readAllBytes(projectRoot().resolve(relative)),
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
