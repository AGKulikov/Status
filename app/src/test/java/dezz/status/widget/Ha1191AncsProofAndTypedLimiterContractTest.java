/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** HA1191 regressions derived from the 17:49 handoff race and the KX11 byte-array trace. */
public final class Ha1191AncsProofAndTypedLimiterContractTest {
    @Test public void limiterAggregatesUseVendorBytesAccessorWithGlobalArea() throws Exception {
        String catalog = project(
                "app/src/main/java/dezz/status/widget/car/EcarxAdasSignalCatalog.java");
        String fallback = project(
                "app/src/geely/java/dezz/status/widget/car/EcarxSignalFallback.java");

        assertTrue(catalog.contains("BINARY_DISCOVERY_PROPERTY_IDS"));
        assertTrue(catalog.contains("33287"));
        assertTrue(catalog.contains("33292"));
        assertTrue(catalog.contains("33462"));
        assertTrue(catalog.contains("33655"));
        assertTrue(fallback.contains("ECARX_GLOBAL_AREA = 1"));
        assertTrue(fallback.contains("getBytesProperty"));
        assertTrue(fallback.contains("bytesReader.invoke(manager, propertyId, ECARX_GLOBAL_AREA)"));
        assertTrue(fallback.contains("listener.onAdasBinarySignal"));
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

}
