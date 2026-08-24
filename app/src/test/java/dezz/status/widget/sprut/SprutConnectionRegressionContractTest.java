/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.sprut;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Guards the cloud reconnect fixes added after the HA1132 field regression. */
public final class SprutConnectionRegressionContractTest {
    @Test public void automaticSerialIsSessionOnlyAndClientInfoCannotBlockSnapshot()
            throws Exception {
        String controller = source("SprutHubController.java");
        String selectHub = between(controller,
                "private CompletableFuture<Void> selectHub(",
                "private CompletableFuture<JSONObject> registerClientInfoBestEffort(");

        assertTrue(selectHub.contains("SprutHubSelection.selectForSession("));
        assertTrue(selectHub.contains("selectedSessionSerial = selectedSerial"));
        assertFalse(selectHub.contains("prefs.sprutHubSerial.set("));
        assertTrue(controller.contains("registerClientInfoBestEffort(current)"));
        assertTrue(controller.contains(
                "server.clientInfo was unavailable; continuing with routed snapshot"));
    }

    @Test public void legacyLoginFallbackOnlyCoversTheInitialAuthProbe() throws Exception {
        String controller = source("SprutHubController.java");
        String orchestration = between(controller,
                "private void authenticateAndSync(",
                "private CompletableFuture<String> authenticate(");
        String fallback = between(controller,
                "private CompletableFuture<String> authenticate(",
                "private CompletableFuture<String> authenticateModern(");
        String modernAnswers = between(controller,
                "private CompletableFuture<String> authenticateModern(",
                "private CompletableFuture<String> authenticateLegacy(");

        assertTrue(orchestration.contains("authenticate(current)"));
        assertFalse(orchestration.contains("authenticateLegacy(current)"));
        assertTrue(fallback.contains("SprutProtocolAdapter.buildAuthParams()"));
        assertTrue(fallback.contains("if (current.isOfficialCloud())"));
        assertTrue(fallback.contains("legacy login is not valid"));
        assertTrue(fallback.contains("authenticateLegacy(current)"));
        assertFalse(modernAnswers.contains("authenticateLegacy(current)"));
    }

    @Test public void modernLoginAcceptsPasswordProofChallengeWithoutLoggingItsData()
            throws Exception {
        String controller = source("SprutHubController.java");
        String modernAnswers = between(controller,
                "private CompletableFuture<String> authenticateModern(",
                "private CompletableFuture<String> authenticateLegacy(");

        assertTrue(modernAnswers.contains("\"QUESTION_TYPE_PASSWORD\""));
        assertTrue(modernAnswers.contains("\"QUESTION_TYPE_CHALLENGE\""));
        assertTrue(modernAnswers.contains("SprutCloudChallenge.answer("));
        assertTrue(modernAnswers.contains("CompletableFuture.supplyAsync("));
        assertTrue(modernAnswers.contains("restartCloudChallenge("));
        assertTrue(modernAnswers.contains("hasParserUnsafeProofCharacters(answer)"));
        assertFalse(modernAnswers.contains("JSONObject.quote(answer)"));
        assertFalse(modernAnswers.contains("challengeData +"));
    }

    @Test public void exportedJournalContainsTransportRpcAndStateStages() throws Exception {
        String controller = source("SprutHubController.java");
        String rpc = source("SprutHubRpcClient.java");

        assertTrue(controller.contains("DiagnosticJournal.warn(\"spruthub.state\""));
        assertTrue(rpc.contains("DiagnosticJournal.info(\"spruthub.transport\""));
        assertTrue(rpc.contains("DiagnosticJournal.debug(\"spruthub.rpc\""));
        assertTrue(rpc.contains("response.code()"));
        assertTrue(rpc.contains("diagnosticEndpoint("));
    }

    @Test public void settingsExposeOneTapReturnToAutomaticHubSelection() throws Exception {
        String settings = widgetSource("SprutHubSettingsActivity.java");
        assertTrue(settings.contains(
                "Автовыбор живого хаба (очистить сохранённый serial)"));
        assertTrue(settings.contains("serial.setText(\"\")"));
    }

    private static String source(String file) throws Exception {
        Path root = Paths.get("app", "src", "main", "java", "dezz", "status", "widget",
                "sprut");
        if (!Files.isDirectory(root)) {
            root = Paths.get("src", "main", "java", "dezz", "status", "widget", "sprut");
        }
        return new String(Files.readAllBytes(root.resolve(file)), StandardCharsets.UTF_8);
    }

    private static String widgetSource(String file) throws Exception {
        Path root = Paths.get("app", "src", "main", "java", "dezz", "status", "widget");
        if (!Files.isDirectory(root)) {
            root = Paths.get("src", "main", "java", "dezz", "status", "widget");
        }
        return new String(Files.readAllBytes(root.resolve(file)), StandardCharsets.UTF_8);
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, Math.max(0, from + start.length()));
        if (from < 0 || to <= from) {
            throw new AssertionError("Missing range: " + start + " -> " + end);
        }
        return source.substring(from, to);
    }
}
