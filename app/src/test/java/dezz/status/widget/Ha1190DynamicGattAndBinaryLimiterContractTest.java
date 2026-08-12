/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Retained binary limiter aggregate coverage for the car runtime. */
public final class Ha1190DynamicGattAndBinaryLimiterContractTest {
    @Test public void binaryAggregatesUseDeclaredGettersAndFastDiagnosticPolling()
            throws Exception {
        String fallback = project(
                "app/src/geely/java/dezz/status/widget/car/EcarxSignalFallback.java");
        String discovery = between(fallback,
                "private static Set<String> discoverBinaryCallbackGetterNames",
                "private static boolean isIntegerReturnType");
        String reads = between(fallback,
                "private ReadResult readCurrentValues",
                "private void handleCallbackArguments");

        assertTrue(fallback.contains("RECORDER_HEALTH_READ_MILLIS = 250L"));
        assertTrue(discovery.contains("method.getReturnType() == byte[].class"));
        assertTrue(discovery.contains("SignalId_"));
        assertTrue(fallback.contains("binaryRecorderGetterNames.putAll"));
        assertTrue(reads.contains("readBinaryCurrentValues(manager)"));
        assertTrue(reads.contains("findTwoIntMethod(manager.getClass(), \"getBytesProperty\")"));
        assertTrue(reads.contains("bytesReader.invoke(manager, propertyId, ECARX_GLOBAL_AREA)"));
        assertTrue(reads.contains("EcarxSignalDecoder.coerceByteArray(value)"));
        assertFalse(reads.contains("reader.getReturnType() != byte[].class"));
        assertTrue(reads.contains("reader.invoke(manager)"));
        assertTrue(fallback.contains("listener.onAdasBinarySignal"));
        assertTrue(fallback.contains("adasRecorderDemand ? RECORDER_HEALTH_READ_MILLIS"));
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
