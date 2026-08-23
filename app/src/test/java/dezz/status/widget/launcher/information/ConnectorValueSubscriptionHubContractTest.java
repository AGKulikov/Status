/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher.information;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class ConnectorValueSubscriptionHubContractTest {
    @Test public void allInformationViewsShareOneUpstreamRegistryListener() throws Exception {
        Path source = Paths.get("app/src/main/java/dezz/status/widget/launcher/information/"
                + "ConnectorValueSubscriptionHub.java");
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        assertTrue(text.contains("Map<WidgetService, Entry>"));
        assertTrue(text.contains("service.addConnectorValueListener(entry.upstream)"));
        assertTrue(text.contains("service.removeConnectorValueListener(entry.upstream)"));
        assertTrue(text.contains("for (Subscriber target : targets)"));
    }
}
