/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.phone;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PhoneSprutPresenceExporterTest {
    @Test
    public void retriesUseBoundedBackoff() {
        assertEquals(1_000L, PhoneSprutPresenceExporter.retryDelayMillis(1));
        assertEquals(2_000L, PhoneSprutPresenceExporter.retryDelayMillis(2));
        assertEquals(5_000L, PhoneSprutPresenceExporter.retryDelayMillis(3));
        assertEquals(10_000L, PhoneSprutPresenceExporter.retryDelayMillis(4));
        assertEquals(30_000L, PhoneSprutPresenceExporter.retryDelayMillis(5));
        assertEquals(30_000L, PhoneSprutPresenceExporter.retryDelayMillis(100));
    }

    @Test
    public void newerDisconnectAlwaysWinsOverConnectedCompletion() {
        assertEquals(PhoneSprutPresenceExporter.CompletionDisposition.SEND_LATEST,
                PhoneSprutPresenceExporter.completionDisposition(
                        false, false, false, true));
        assertEquals(PhoneSprutPresenceExporter.CompletionDisposition.SEND_LATEST,
                PhoneSprutPresenceExporter.completionDisposition(
                        false, true, false, true));
    }

    @Test
    public void authoritativeDivergenceForcesReconciliation() {
        assertEquals(PhoneSprutPresenceExporter.CompletionDisposition.RECONCILE_AUTHORITATIVE,
                PhoneSprutPresenceExporter.completionDisposition(
                        false, true, true, true));
    }

    @Test
    public void failedWriteRetriesLatestState() {
        assertEquals(PhoneSprutPresenceExporter.CompletionDisposition.RETRY_LATEST,
                PhoneSprutPresenceExporter.completionDisposition(
                        true, false, false, false));
    }

    @Test
    public void matchingSnapshotCannotSettleOppositePendingWrite() {
        assertFalse(PhoneSprutPresenceExporter.canSettleMatchingAuthoritative(
                true, true, false));
        assertTrue(PhoneSprutPresenceExporter.canSettleMatchingAuthoritative(
                true, false, false));
        assertTrue(PhoneSprutPresenceExporter.canSettleMatchingAuthoritative(
                false, true, false));
    }

    @Test
    public void shutdownAndPhoneChangeCleanupCannotTrustAStaleFalseSnapshot() throws Exception {
        String source = readSource();
        int start = source.indexOf("private void writeBestEffortFalse");
        int end = source.indexOf("\n    @NonNull\n    private static Object resolveDesiredValue",
                start);
        assertTrue(start >= 0);
        assertTrue(end > start);
        String method = source.substring(start, end);
        assertTrue(method.contains("resolveDesiredValue(target, false)"));
        assertFalse(method.contains("logicalEquals(target.currentValue(), false)"));
        assertTrue(source.contains(
                "!Objects.equals(oldTarget, parsed) || phoneChanged"));
    }

    @Test
    public void booleanComparisonAcceptsLegacySwitchRepresentations() {
        assertTrue(PhoneSprutPresenceExporter.logicalEquals(true, true));
        assertTrue(PhoneSprutPresenceExporter.logicalEquals(1, true));
        assertTrue(PhoneSprutPresenceExporter.logicalEquals("on", true));
        assertTrue(PhoneSprutPresenceExporter.logicalEquals(0L, false));
        assertTrue(PhoneSprutPresenceExporter.logicalEquals("выкл", false));
        assertFalse(PhoneSprutPresenceExporter.logicalEquals("unknown", false));
    }

    @Test
    public void selectedDeviceIdentityIsComparedCanonically() {
        assertEquals("AA:BB:CC:DD:EE:FF",
                PhoneSprutPresenceExporter.normalizeAddress(" aa:bb:cc:dd:ee:ff "));
        assertEquals("", PhoneSprutPresenceExporter.normalizeAddress(null));
    }

    private static String readSource() throws Exception {
        Path file = Paths.get("src/main/java/dezz/status/widget/phone/"
                + "PhoneSprutPresenceExporter.java");
        if (!Files.exists(file)) {
            file = Paths.get("app/src/main/java/dezz/status/widget/phone/"
                    + "PhoneSprutPresenceExporter.java");
        }
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
