/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Retained trunk command and catalog visibility coverage. */
public final class Ha1196AuthorizedAncsAndTrunkCatalogContractTest {
    @Test public void trunkRemainsSelectableButUnsafeWritesStayGated() throws Exception {
        String integration = project(
                "app/src/geely/java/dezz/status/widget/car/GeelyCarIntegration.java");
        String catalog = between(integration,
                "public void requestControlCatalog",
                "static List<CarControlDescriptor.Option> safeAutoFanOptions");
        assertTrue(integration.contains("TRUNK_FUNCTION_ID = 0x21020100"));
        assertTrue(integration.contains("TRUNK_ZONE = 0x20000000"));
        assertTrue(catalog.contains("|| isTrunkDefinition(definition)"));
        assertTrue(catalog.contains(
                "availability = CarControlDescriptor.Availability.UNKNOWN"));
        assertTrue(catalog.contains("values.add(descriptor.withAvailability(availability))"));

        String command = between(integration,
                "private void startControlCommand",
                "private void attemptPulseControl");
        assertTrue(command.contains(
                "availability != CarControlDescriptor.Availability.SUPPORTED"));
        assertTrue(command.contains("completeControlCommand(active, false"));
    }

    private static String project(String relative) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 8 && current != null;
             depth++, current = current.getParent()) {
            if (!Files.isRegularFile(current.resolve("settings.gradle"))) continue;
            Path candidate = current.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("Project file not found: " + relative);
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + Math.max(1, start.length()));
        assertTrue("Missing section start: " + start, from >= 0);
        assertTrue("Missing section end: " + end, to > from);
        return source.substring(from, to);
    }
}
