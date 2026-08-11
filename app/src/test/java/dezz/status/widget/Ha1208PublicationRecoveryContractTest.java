/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Release gate for the crash-consistent per-F04 publication incarnation beacon. */
public final class Ha1208PublicationRecoveryContractTest {
    @Test public void fixedF04StartsWithNoObservableNonceBeforeServiceSuccess()
            throws Exception {
        String transport = transport();
        String start = between(transport,
                "private boolean startGeelyAncsAdvertising",
                "/** Derives a retry-stable candidate");
        assertTrue(start.contains("useStaticDiagnosticNamespace();"));
        assertTrue(start.contains("clearPreparedAdvertising();"));
        assertTrue(start.contains("openGattServer();"));
        assertFalse(start.contains("rotateManagedIncomingDiagnosticNamespace"));
        assertFalse(start.contains("addManufacturerData"));
        assertFalse(start.contains("publicationNonceFrame"));

        String open = between(transport,
                "private void openGattServer", "private void startPreparedAdvertising");
        int invalidate = open.indexOf("invalidateDiagnosticServicePublication();");
        int candidate = open.indexOf("preparePendingManagedIncomingPublicationNonce()");
        int bindObject = open.indexOf("pendingDiagnosticServicePublication = service;");
        int bindToken = open.indexOf(
                "pendingDiagnosticServicePublicationToken = "
                        + "serverDiagnosticServicePublicationToken;");
        int addService = open.indexOf("gattServer.addService(service)");
        assertTrue(invalidate >= 0);
        assertTrue(candidate > invalidate);
        assertTrue(bindObject > candidate);
        assertTrue(bindToken > bindObject);
        assertTrue(addService > bindToken);
        assertFalse(open.contains("prepareManagedIncomingAdvertising("));
    }

    @Test public void exactSuccessCommitsThenBuildsAndAdvertisesOnce() throws Exception {
        String callback = between(transport(),
                "public void onServiceAdded", "public void onConnectionStateChange");
        int exactObject = callback.indexOf("service != pending");
        int exactToken = callback.indexOf(
                "pendingToken != serverDiagnosticServicePublicationToken", exactObject);
        int staleReturn = callback.indexOf("return;", exactToken);
        int success = callback.indexOf("status != GATT_SUCCESS", staleReturn);
        int commit = callback.indexOf(
                "commitManagedIncomingPublicationNonce(service, pendingToken)", success);
        int build = callback.indexOf("prepareManagedIncomingAdvertising(", commit);
        int publishObject = callback.indexOf(
                "publishedDiagnosticServicePublication = service;", build);
        int clearPendingObject = callback.indexOf(
                "pendingDiagnosticServicePublication = null;", publishObject);
        int advertise = callback.indexOf("startPreparedAdvertising();", clearPendingObject);
        assertTrue(exactObject >= 0);
        assertTrue(exactToken > exactObject);
        assertTrue(staleReturn > exactToken);
        assertTrue(success > staleReturn);
        assertTrue(commit > success);
        assertTrue(build > commit);
        assertTrue(publishObject > build);
        assertTrue(clearPendingObject > publishObject);
        assertTrue(advertise > clearPendingObject);

        String commitBarrier = between(transport(),
                "private boolean commitManagedIncomingPublicationNonce",
                "/** Builds the exact-budget v2 ADV");
        int serviceIdentity = commitBarrier.indexOf(
                "service != pendingDiagnosticServicePublication");
        int tokenIdentity = commitBarrier.indexOf(
                "publicationToken != pendingDiagnosticServicePublicationToken");
        int durableCommit = commitBarrier.indexOf(".commit();");
        int abort = commitBarrier.indexOf("if (!committed)", durableCommit);
        int publishNonce = commitBarrier.indexOf(
                "publishedManagedIncomingPublicationNonce = candidate;", abort);
        int clearCandidate = commitBarrier.indexOf(
                "pendingManagedIncomingPublicationNonce = 0;", publishNonce);
        assertTrue(serviceIdentity >= 0);
        assertTrue(tokenIdentity > serviceIdentity);
        assertTrue(durableCommit > tokenIdentity);
        assertTrue(abort > durableCommit);
        assertTrue(publishNonce > abort);
        assertTrue(clearCandidate > publishNonce);
        assertFalse(commitBarrier.contains(".apply()"));
        assertFalse(commitBarrier.contains("startPreparedAdvertising"));
    }

    @Test public void v2ManufacturerAndV1ScanFallbackRespectLegacyPacketBudget()
            throws Exception {
        String build = between(transport(),
                "private void prepareManagedIncomingAdvertising",
                "/** Allocates one persistent namespace");
        assertTrue(build.contains("publicationNonceFrame("));
        assertTrue(build.contains("legacyNamespaceFrame("));
        assertTrue(build.contains("addServiceUuid(new ParcelUuid("));
        assertTrue(build.contains("addManufacturerData("));
        assertTrue(build.contains("addServiceData("));
        assertTrue(build.contains("Flags (3) + 128-bit service AD (18) "
                + "+ manufacturer AD/company/frame (10) = 31 bytes"));
        assertTrue(build.contains("LOCAL_LOGICAL_NAME.getBytes(StandardCharsets.UTF_8)"));

        String policy = project("app/src/main/java/dezz/status/widget/phone/transport/"
                + "ManagedIncomingPublicationPolicy.java");
        assertTrue(policy.contains("PUBLICATION_NONCE_PROTOCOL = 2"));
        assertTrue(policy.contains("LEGACY_NAMESPACE_PROTOCOL = 1"));
        assertTrue(policy.contains("MAX_PUBLICATION_NONCE = 0xFFFFFE"));
        assertTrue(policy.contains("RESERVED_PUBLICATION_NONCE = 0xFFFFFF"));
        assertTrue(policy.contains("new byte[]{\n                (byte) "
                + "PUBLICATION_NONCE_PROTOCOL"));
    }

    @Test public void invalidationClearsOnlyVolatileNonceAndOrdinaryReconnectRetainsIt()
            throws Exception {
        String transport = transport();
        String invalidation = between(transport,
                "private void invalidateDiagnosticServicePublication",
                "/** Returns the accepted token");
        assertTrue(invalidation.contains("pendingManagedIncomingPublicationNonce = 0;"));
        assertTrue(invalidation.contains("publishedManagedIncomingPublicationNonce = 0;"));
        assertFalse(invalidation.contains("MANAGED_INCOMING_PUBLICATION_NONCE"));
        assertFalse(invalidation.contains("SharedPreferences"));

        String preserve = between(transport,
                "private void preserveManagedIncomingPublicationAfterLinkLoss",
                "private static String deviceKey");
        assertFalse(preserve.contains("preparePendingManagedIncomingPublicationNonce"));
        assertFalse(preserve.contains("commitManagedIncomingPublicationNonce"));
        assertFalse(preserve.contains("stopAdvertising()"));
        assertFalse(preserve.contains("closeGattServer()"));
    }

    @Test public void advertiseCallbacksOwnOneExactPublicationAndCannotCrossRestart()
            throws Exception {
        String transport = transport();
        assertTrue(transport.contains(
                "private PublicationAdvertiseCallback activeAdvertiseCallback;"));
        String start = between(transport,
                "private void startPreparedAdvertising",
                "private void clearPreparedAdvertising");
        int captureToken = start.indexOf(
                "long publicationToken = publishedDiagnosticServicePublicationToken;");
        int captureNonce = start.indexOf(
                "int publicationNonce = publishedManagedIncomingPublicationNonce;");
        int captureAdvertiser = start.indexOf(
                "BluetoothLeAdvertiser ownerAdvertiser = advertiser;", captureNonce);
        int callback = start.indexOf(
                "new PublicationAdvertiseCallback(", captureAdvertiser);
        int makeActive = start.indexOf("activeAdvertiseCallback = callback;", callback);
        int frameworkStart = start.indexOf(
                "preparedScanResponse, callback)", makeActive);
        assertTrue(captureToken >= 0);
        assertTrue(captureNonce > captureToken);
        assertTrue(captureAdvertiser > captureNonce);
        assertTrue(callback > captureAdvertiser);
        assertTrue(makeActive > callback);
        assertTrue(frameworkStart > makeActive);
        assertTrue(start.contains("ownerAdvertiser.startAdvertising("));
        String startFailure = start.substring(
                start.indexOf("catch (RuntimeException failure)"));
        int failureClose = startFailure.indexOf("closeGattServer();");
        assertTrue(failureClose >= 0);
        assertFalse(startFailure.substring(0, failureClose).contains(
                "activeAdvertiseCallback = null"));

        String owner = between(transport,
                "advertiseCallbackAction(PublicationAdvertiseCallback callback",
                "private void handleAdvertiseStartSuccess");
        assertTrue(owner.contains("activeAdvertiseCallback == callback"));
        assertTrue(owner.contains("callback.publicationToken"));
        assertTrue(owner.contains("callback.publicationNonce"));
        assertTrue(owner.contains("publishedDiagnosticServicePublicationToken"));
        assertTrue(owner.contains("publishedManagedIncomingPublicationNonce"));
        assertTrue(owner.contains("callback.startOutcomeHandled"));

        String success = between(transport,
                "private void handleAdvertiseStartSuccess",
                "private void handleAdvertiseStartFailure");
        int staleSuccess = success.indexOf("OBSERVE_STALE");
        int staleSuccessReturn = success.indexOf("return;", staleSuccess);
        int currentSuccessMutation = success.indexOf("advertisingPending = false;");
        assertTrue(staleSuccess >= 0);
        assertTrue(staleSuccessReturn > staleSuccess);
        assertTrue(currentSuccessMutation > staleSuccessReturn);
        assertTrue(success.contains(
                "callback.ownerAdvertiser.stopAdvertising(callback)"));
        assertTrue(success.contains("IGNORE_DUPLICATE"));

        String failure = between(transport,
                "private void handleAdvertiseStartFailure",
                "private boolean sendGattServerResponse");
        int staleFailure = failure.indexOf("OBSERVE_STALE");
        int staleFailureReturn = failure.indexOf("return;", staleFailure);
        int currentClose = failure.indexOf("closeGattServer();", staleFailureReturn);
        int correctRestart = failure.indexOf(
                "scheduleManagedIncomingPublicationRestartIfNeeded(", currentClose);
        int genericState = failure.indexOf("state(\"ADVERTISE_FAILED_\"", correctRestart);
        assertTrue(staleFailure >= 0);
        assertTrue(staleFailureReturn > staleFailure);
        assertTrue(currentClose > staleFailureReturn);
        assertTrue(correctRestart > currentClose);
        assertTrue(genericState > correctRestart);
        assertTrue(failure.contains("IGNORE_DUPLICATE"));

        String stop = between(transport,
                "public void stopAdvertising", "private void connectToAdvertisingIphone");
        assertTrue(stop.contains(
                "PublicationAdvertiseCallback callback = activeAdvertiseCallback;"));
        assertTrue(stop.contains("activeAdvertiseCallback = null;"));
        assertTrue(stop.contains(
                "callback.ownerAdvertiser.stopAdvertising(callback)"));

        String close = between(transport,
                "private void closeGattServer", "private long armDiscoveryOperation");
        assertTrue(close.contains(
                "PublicationAdvertiseCallback callback = activeAdvertiseCallback;"));
        int detach = close.indexOf("activeAdvertiseCallback = null;");
        int exactStop = close.indexOf(
                "callback.ownerAdvertiser.stopAdvertising(callback)", detach);
        int invalidate = close.indexOf(
                "invalidateDiagnosticServicePublication();", exactStop);
        assertTrue(detach >= 0);
        assertTrue(exactStop > detach);
        assertTrue(invalidate > exactStop);
    }

    @Test public void legacyDiagnosticCannotInheritManagedPublicationMode()
            throws Exception {
        String diagnostic = between(transport(),
                "public void startIncomingConnectionTest", "public void stopAdvertising");
        int disconnect = diagnostic.indexOf("disconnect();");
        int managedOff = diagnostic.indexOf("managedIncomingMode = false;", disconnect);
        int preparedLegacy = diagnostic.indexOf(
                "preparedAdvertiseData = primary.build();", managedOff);
        int open = diagnostic.indexOf("openGattServer();", preparedLegacy);
        assertTrue(disconnect >= 0);
        assertTrue(managedOff > disconnect);
        assertTrue(preparedLegacy > managedOff);
        assertTrue(open > preparedLegacy);
    }

    @Test public void everyPublicationFailureArmsExactRestartBeforeGenericState()
            throws Exception {
        String transport = transport();
        String open = between(transport,
                "private void openGattServer", "private void startPreparedAdvertising");
        assertRestartBeforeState(open, "if (gattServer == null)",
                "state(\"GATT_SERVER_UNAVAILABLE\")");
        assertRestartBeforeState(open,
                "managedIncomingMode && !preparePendingManagedIncomingPublicationNonce()",
                "state(\"F04_PUBLICATION_NONCE_PREPARE_FAILED\")");
        assertRestartBeforeState(open,
                "!informationAdded || !controlAdded || !secureAdded",
                "state(\"GATT_CHARACTERISTIC_ADD_FAILED\")");
        assertRestartBeforeState(open, "if (!accepted)",
                "state(\"GATT_SERVICE_ADD_START_FAILED\")");

        String start = between(transport,
                "private void startPreparedAdvertising",
                "private void clearPreparedAdvertising");
        assertRestartBeforeState(start, "catch (RuntimeException failure)",
                "state(\"ADVERTISE_EXCEPTION\")");

        String callback = between(transport,
                "public void onServiceAdded", "public void onConnectionStateChange");
        assertRestartBeforeState(callback, "if (status != GATT_SUCCESS)",
                "state(\"GATT_SERVICE_ADD_FAILED_\" + status)");
        assertRestartBeforeState(callback,
                "if (!commitManagedIncomingPublicationNonce(service, pendingToken))",
                "state(\"F04_PUBLICATION_NONCE_COMMIT_FAILED\")");
        assertRestartBeforeState(callback, "catch (RuntimeException failure)",
                "state(\"F04_PUBLICATION_BEACON_BUILD_FAILED\")");
    }

    @Test public void releaseIdentityAdvancesWithoutChangingInstallationIdentity()
            throws Exception {
        String build = rootProject("build.gradle");
        assertTrue(build.contains("return 'v2.8.2-ha1208'"));
        String manifest = project("app/src/main/AndroidManifest.xml");
        assertFalse(manifest.contains("package=\""));
        String app = project("app/build.gradle");
        assertTrue(app.contains("applicationId \"ru.natro.statuswidget\""));
        String workflow = project(".github/workflows/verify-ha1208.yml");
        assertTrue(workflow.contains("work/ha1208-publication-recovery"));
        assertTrue(workflow.contains("VERSION_NAME: 'v2.8.2-ha1208'"));
        assertTrue(workflow.contains("VERSION_CODE: '208021208'"));
        assertTrue(workflow.contains("ha1208-source-bundle"));
        assertTrue(workflow.contains("ha1208-unsigned-release"));
        assertTrue(workflow.contains("ha1208-android-build-tools"));
    }

    @Test public void matchedHelper43HasSeparateMacGateAndBothRoleSmokes()
            throws Exception {
        String manifest = project("release-manifests/HA1208.md");
        assertTrue(manifest.contains("bundle ID `ru.natro.kx11ancshelper`"));
        assertTrue(manifest.contains("build `43`"));
        assertTrue(manifest.contains("version `43.0`"));
        assertTrue(manifest.contains("Helper: Central"));
        assertTrue(manifest.contains("Status Widget: `iphone_central`"));
        assertTrue(manifest.contains("Peripheral/Peripheral"));
        assertTrue(manifest.contains("07:01:41.556"));
        assertTrue(manifest.contains("07:01:45.523"));
        assertTrue(manifest.contains("subscribers=0"));

        String helperWorkflow = project(".github/workflows/verify-helper-v43.yml");
        assertTrue(helperWorkflow.contains("runs-on: macos-15"));
        assertTrue(helperWorkflow.contains(
                "PRODUCT_BUNDLE_IDENTIFIER = ru.natro.kx11ancshelper"));
        assertTrue(helperWorkflow.contains("MARKETING_VERSION = 43.0"));
        assertTrue(helperWorkflow.contains("CURRENT_PROJECT_VERSION = 43"));
        assertTrue(helperWorkflow.contains("verify-v43-contract.sh"));
    }

    private static String transport() throws Exception {
        return project(
                "app/src/main/java/dezz/status/widget/phone/transport/IphoneAncsTransport.java");
    }

    private static String project(String relative) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 8 && current != null;
             depth++, current = current.getParent()) {
            Path candidate = current.resolve(relative).normalize();
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("Project file not found: " + relative);
    }

    /** Avoids resolving app/build.gradle when Gradle runs unit tests with app/ as cwd. */
    private static String rootProject(String relative) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 8 && current != null;
             depth++, current = current.getParent()) {
            Path candidate = current.resolve(relative).normalize();
            if (Files.isRegularFile(candidate)
                    && Files.isDirectory(current.resolve("app"))) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("Root project file not found: " + relative);
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + Math.max(1, start.length()));
        if (from < 0 || to < 0 || to <= from) {
            throw new AssertionError("Missing source markers: " + start + " -> " + end);
        }
        return source.substring(from, to);
    }

    private static void assertRestartBeforeState(String source,
                                                 String failureMarker,
                                                 String stateMarker) {
        int failure = source.indexOf(failureMarker);
        int restart = source.indexOf(
                "scheduleManagedIncomingPublicationRestartIfNeeded(", failure);
        int state = source.indexOf(stateMarker, restart);
        assertTrue("Missing failure marker: " + failureMarker, failure >= 0);
        assertTrue("Missing exact managed restart after: " + failureMarker,
                restart > failure);
        assertTrue("Generic state preceded exact restart: " + stateMarker,
                state > restart);
    }
}
