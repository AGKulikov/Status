/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Guards against auxiliary/free-driving data resurrecting an ended Yandex route. */
public final class NavigationRouteLivenessContractTest {
    @Test
    public void notificationWatchdogUsesNotificationPostTimeInsteadOfScanTime()
            throws IOException {
        String repository = source("dezz/status/widget/launcher/NavigationDataRepository.java");
        assertTrue(repository.contains(
                "candidate.sourceKey, true,\n                candidate.postTime"));
        assertTrue(repository.contains(
                "isNotificationCandidateLive(candidate, System.currentTimeMillis())"));
    }

    @Test
    public void speedLimitIsNotPartOfLegacyPrimaryEvidence() throws IOException {
        String repository = source("dezz/status/widget/launcher/NavigationDataRepository.java");
        int start = repository.indexOf("private static boolean hasMainRouteEvidence");
        int end = repository.indexOf("private static boolean updateFromMonjaroNavigation", start);
        String method = repository.substring(start, end);
        assertTrue(method.contains("NavigationRouteStatePolicy.hasPrimaryEvidence"));
        assertFalse(method.contains("PREF_SPEED_LIMIT"));
        assertFalse(method.contains("PREF_MANEUVER_IMAGE_UPDATED_AT"));
    }

    @Test
    public void standaloneSpeedLimitDoesNotRefreshRouteTimestamp() throws IOException {
        String repository = source("dezz/status/widget/launcher/NavigationDataRepository.java");
        int start = repository.indexOf("private static boolean updateStandaloneSpeedLimit");
        int end = repository.indexOf("private static boolean updateTrafficLight", start);
        String method = repository.substring(start, end);
        assertTrue(method.contains("putString(PREF_SPEED_LIMIT, speedLimit)"));
        assertFalse(method.contains("PREF_UPDATED_AT"));
        assertFalse(method.contains("PREF_ROUTE_ACTIVE"));
    }

    @Test
    public void nullableImageExtraCannotTurnSpeedOnlyPacketIntoRoute() throws IOException {
        String repository = source("dezz/status/widget/launcher/NavigationDataRepository.java");
        int start = repository.indexOf("private static boolean updateFromMonjaroNavigation");
        int end = repository.indexOf("private static boolean updateStandaloneSpeedLimit", start);
        String method = repository.substring(start, end);
        int storedImage = method.indexOf("NavigationGraphicStore.saveFromIntent");
        int routeDecision = method.indexOf("boolean routeActiveSignal");
        assertTrue(storedImage >= 0);
        assertTrue(routeDecision > storedImage);
        String decision = method.substring(routeDecision,
                method.indexOf("if (!routeActiveSignal)", routeDecision));
        assertTrue(decision.contains("subtext, imageStored"));
        assertFalse(decision.contains("hasImagePayload"));
        assertTrue(method.indexOf("if (hasImageFlag && !hasImage)")
                < method.indexOf("else if (hasImage || hasImagePayload)"));
    }

    @Test
    public void permanentlyTrueFlagCannotTurnSpeedOnlyPacketIntoRoute() throws IOException {
        String policy = source(
                "dezz/status/widget/launcher/NavigationRouteStatePolicy.java");
        assertTrue(policy.contains("explicitStateStored && !explicitlyActive"));
        assertFalse(policy.contains("if (explicitStateStored) return explicitlyActive"));
    }

    @Test
    public void legacyActiveFlagNeedsPrimaryEvidenceAfterUpgrade() throws IOException {
        String repository = source("dezz/status/widget/launcher/NavigationDataRepository.java");
        assertTrue(repository.contains("PREF_ROUTE_CONFIRMED"));
        assertTrue(repository.contains("prefs.contains(PREF_ROUTE_CONFIRMED)"));
        assertTrue(repository.contains(".putBoolean(PREF_ROUTE_CONFIRMED, true)"));
    }

    private static String source(String relative) throws IOException {
        Path fromRoot = Paths.get("app", "src", "main", "java").resolve(relative);
        Path fromApp = Paths.get("src", "main", "java").resolve(relative);
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
