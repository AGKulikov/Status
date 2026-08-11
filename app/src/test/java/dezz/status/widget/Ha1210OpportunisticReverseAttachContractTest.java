/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Release gate for the HA1210 Pie opportunistic reverse-client adoption. */
public final class Ha1210OpportunisticReverseAttachContractTest {
    @Test public void exactPostReadyTupleGetsOnePieOpportunisticRegistration()
            throws Exception {
        String transport = transport();
        assertTrue(transport.contains(
                "HA1210 Pie opportunistic reverse attach enabled"));

        String attach = between(transport,
                "private void startIncomingDirectAttach",
                "/**\n     * Android 9 light-greylist overload");
        assertTrue(attach.contains("canStartIncomingClientAttach("));
        assertTrue(attach.contains("ownsCapturedFirstAttachAuthorization("));
        assertTrue(attach.contains("reverseClientOpenAction("));
        assertTrue(attach.contains(
                "incomingOpportunisticAttachAttemptedForCurrentTuple = true;"));
        assertTrue(attach.contains("connectGattOpportunisticOnPie(device)"));
        assertTrue(attach.contains("activeClientAutoConnect = false;"));
        assertTrue(attach.contains("activeClientOpportunistic = true;"));
        assertTrue(attach.contains("INCOMING_CLIENT_ATTACH_MAX_ATTEMPTS"));
        assertFalse(attach.contains("device.connectGatt("));
        assertFalse(attach.contains("scheduleIncomingClientAttachRetry("));
        assertFalse(attach.contains("scheduleDirectFallback("));

        String hidden = between(transport,
                "private BluetoothGatt connectGattOpportunisticOnPie",
                "/** Close-only unregister");
        assertTrue(hidden.contains(
                "Build.VERSION.SDK_INT != Build.VERSION_CODES.P"));
        assertTrue(hidden.contains("BluetoothDevice.class.getMethod("));
        assertTrue(hidden.contains("BluetoothGattCallback.class"));
        assertTrue(hidden.contains("boolean.class"));
        assertTrue(hidden.contains("Handler.class"));
        assertTrue(hidden.contains("method.invoke("));
        int invoke = hidden.indexOf("method.invoke(");
        int context = hidden.indexOf("context,", invoke);
        int autoConnectFalse = hidden.indexOf("false,", context);
        int callback = hidden.indexOf("gattCallback,", autoConnectFalse);
        int transportLe = hidden.indexOf("BluetoothDevice.TRANSPORT_LE,", callback);
        int opportunisticTrue = hidden.indexOf("true,", transportLe);
        int phy = hidden.indexOf("BluetoothDevice.PHY_LE_1M_MASK,", opportunisticTrue);
        int handler = hidden.indexOf("main);", phy);
        assertTrue(invoke >= 0);
        assertTrue(context > invoke);
        assertTrue(autoConnectFalse > context);
        assertTrue(callback > autoConnectFalse);
        assertTrue(transportLe > callback);
        assertTrue(opportunisticTrue > transportLe);
        assertTrue(phy > opportunisticTrue);
        assertTrue(handler > phy);
        assertFalse(hidden.contains(".connectGatt("));
    }

    @Test public void unavailableSilentAndFailedObserversHaveNoFallbackOrRetry()
            throws Exception {
        String transport = transport();
        String attach = between(transport,
                "private void startIncomingDirectAttach",
                "/**\n     * Android 9 light-greylist overload");
        assertTrue(attach.contains("OPPORTUNISTIC GATT UNAVAILABLE · LINK KEPT"));
        assertTrue(attach.contains("unregisterNeverEstablishedOpportunisticGatt(expected)"));
        assertTrue(attach.contains("Same-tuple retry/public fallback запрещены"));

        String retry = between(transport,
                "private void scheduleIncomingClientAttachRetry",
                "private void recoverIncomingClientRole");
        assertTrue(retry.contains("Same-tuple clientIf retry запрещён"));
        assertFalse(retry.contains("startSamePeerAttach("));
        assertFalse(retry.contains("connectGatt("));
        assertFalse(retry.contains("main.postDelayed("));

        String unregister = between(transport,
                "private void unregisterNeverEstablishedOpportunisticGatt",
                "private void scheduleDirectFallback");
        assertTrue(unregister.contains("ownsCurrentIncomingClientAttempt(expected)"));
        assertTrue(unregister.contains("closeClientGatt(expected);"));
        assertFalse(unregister.contains("disconnect("));

        String close = between(transport,
                "private void closeClientGatt",
                "private boolean ensureAdapter");
        assertTrue(close.contains("callbackGatt.close();"));
        assertFalse(close.contains("disconnect("));
        assertTrue(close.contains("activeClientOpportunistic = false;"));

        String passiveFailure = between(transport,
                "private boolean retireOpportunisticObserverWithoutLinkMutation",
                "private void cancelClientAttemptCallbacks");
        assertTrue(passiveFailure.contains("closeClientGatt(expected);"));
        assertTrue(passiveFailure.contains("PAIR/B3/READY proofs and F04 publication kept"));
        assertFalse(passiveFailure.contains("resetIncomingSecurityAfterClientLoss("));
        assertFalse(passiveFailure.contains("clearIncomingPairProof("));
        assertFalse(passiveFailure.contains("disconnect("));

        assertPassiveClientFailurePrecedesPhysicalReset(transport,
                "private void poisonRssiProbeChannelAndRearm",
                "private boolean ownsServerFacadeHandoffProbe");
        assertPassiveClientFailurePrecedesPhysicalReset(transport,
                "private void poisonMandatoryDescriptorChannelAndRecover",
                "/** A discovery timeout is ambiguous");
        assertPassiveClientFailurePrecedesPhysicalReset(transport,
                "private void poisonDiscoveryChannelAndRecover",
                "private boolean ownsServerFacadeHandoffProbe");
    }

    @Test public void opportunisticOwnerCannotReachAnyPhysicalReconnectOrDisconnectSink()
            throws Exception {
        String transport = transport();

        String persistent = between(transport,
                "private void awaitPersistentGattReconnect",
                "/**\n     * Reuses the already registered Android GATT owner");
        assertTrue(persistent.indexOf("activeClientOpportunistic")
                < persistent.indexOf("rearmPersistentGattOwner("));

        String rawRearm = between(transport,
                "private void rearmPersistentGattOwner",
                "/** Re-discovers changed services");
        assertTrue(rawRearm.indexOf("activeClientOpportunistic")
                < rawRearm.indexOf("expected.connect()"));

        String reverseRearm = between(transport,
                "private void awaitIncomingBackgroundOwner",
                "private boolean retireOpportunisticObserverWithoutLinkMutation");
        assertTrue(reverseRearm.indexOf("activeClientOpportunistic")
                < reverseRearm.indexOf("rearmPersistentGattOwner("));

        String loss = between(transport,
                "private void recoverEstablishedIncomingClientAfterCallbackLoss",
                "private boolean confirmPendingServerFacadeHandoff");
        assertTrue(loss.indexOf("activeClientOpportunistic")
                < loss.indexOf("awaitIncomingBackgroundOwner("));
        assertTrue(loss.contains("retireOpportunisticObserverWithoutLinkMutation("));

        String serverDisconnect = between(transport,
                "private void handleServerFacadeDisconnected",
                "/** Clears only per-link state");
        assertTrue(serverDisconnect.indexOf("activeClientOpportunistic")
                < serverDisconnect.indexOf("current.disconnect()"));
        assertTrue(serverDisconnect.contains("if (!passiveOpportunisticOwner)"));

        assertEquals(4, occurrences(transport, ".disconnect();"));
        int from = 0;
        while ((from = transport.indexOf(".disconnect();", from)) >= 0) {
            int start = Math.max(0, from - 320);
            assertTrue(transport.substring(start, from).contains(
                    "if (!passiveOpportunisticOwner)"));
            from += ".disconnect();".length();
        }
    }

    @Test public void allSharedGattCallbacksUseOneFifoPreservingDispatcher()
            throws Exception {
        String transport = transport();
        String helper = between(transport,
                "private void dispatchGattCallback",
                "private final BluetoothGattCallback gattCallback");
        int policy = helper.indexOf(
                "AncsRecoveryPolicy.gattCallbackDispatchAction(");
        int sameLooper = helper.indexOf(
                "Looper.myLooper() == main.getLooper()", policy);
        int inline = helper.indexOf(
                "GattCallbackDispatchAction.INLINE", sameLooper);
        int runInline = helper.indexOf("callback.run();", inline);
        int post = helper.indexOf("main.post(callback);", runInline);
        assertTrue(policy >= 0);
        assertTrue(sameLooper > policy);
        assertTrue(inline > sameLooper);
        assertTrue(runInline > inline);
        assertTrue(post > runInline);

        String callbacks = between(transport,
                "private final BluetoothGattCallback gattCallback",
                "private final BroadcastReceiver bondReceiver");
        assertEquals(7, occurrences(callbacks, "dispatchGattCallback("));
        assertFalse(callbacks.contains("main.post("));
        assertTrue(callbacks.contains("public void onConnectionStateChange("));
        assertTrue(callbacks.contains("public void onServicesDiscovered("));
        assertTrue(callbacks.contains("public void onDescriptorWrite("));
        assertTrue(callbacks.contains("public void onCharacteristicChanged("));
        assertTrue(callbacks.contains("public void onCharacteristicWrite("));
        assertTrue(callbacks.contains("public void onCharacteristicRead("));
        assertTrue(callbacks.contains("public void onReadRemoteRssi("));

        String connection = between(transport,
                "public void onConnectionStateChange(BluetoothGatt callbackGatt",
                "public void onServicesDiscovered(BluetoothGatt callbackGatt");
        int runnable = connection.indexOf("Runnable dispatch = () -> {");
        int connected = connection.indexOf(
                "newState == BluetoothProfile.STATE_CONNECTED", runnable);
        int cancelTimeout = connection.indexOf("cancelConnectTimeout();", connected);
        int dispatch = connection.indexOf("dispatchGattCallback(dispatch);", cancelTimeout);
        assertTrue(runnable >= 0);
        assertTrue(connected > runnable);
        assertTrue(cancelTimeout > connected);
        assertTrue(dispatch > cancelTimeout);

        String changed = between(transport,
                "public void onCharacteristicChanged(",
                "public void onCharacteristicWrite(");
        assertTrue(changed.indexOf(".clone()")
                < changed.indexOf("dispatchGattCallback("));
        String read = between(transport,
                "public void onCharacteristicRead(",
                "public void onReadRemoteRssi(");
        assertTrue(read.indexOf(".clone()")
                < read.indexOf("dispatchGattCallback("));
    }

    @Test public void freshEpochRetiresOldObserverAndAloneRefillsBudget()
            throws Exception {
        String transport = transport();
        String fresh = between(transport,
                "private void beginFreshIncomingSecurityEpoch",
                "/** Invalidates all server proofs");
        int passive = fresh.indexOf("activeClientOpportunistic");
        int closeOnly = fresh.indexOf("closeClientGatt(staleOpportunisticObserver)", passive);
        int epoch = fresh.indexOf("incomingSecurityEpoch++", closeOnly);
        assertTrue(passive >= 0);
        assertTrue(closeOnly > passive);
        assertTrue(epoch > closeOnly);
        assertFalse(fresh.contains("disconnect("));

        assertEquals(1, occurrences(transport,
                "incomingOpportunisticAttachAttemptedForCurrentTuple = false;"));
        String clearPair = between(transport,
                "private void clearIncomingPairProof",
                "private void clearIncomingReadyAttachLatch");
        assertTrue(clearPair.contains(
                "incomingOpportunisticAttachAttemptedForCurrentTuple = false;"));
    }

    @Test public void releaseIdentityAndAndroidWorkflowAdvanceTogether() throws Exception {
        String build = project("build.gradle");
        String workflow = project(".github/workflows/verify-ha1210.yml");
        String manifest = project("release-manifests/HA1210.md");
        assertTrue(build.contains("return 'v2.8.2-ha1210'"));
        assertTrue(workflow.contains("work/ha1210-opportunistic-ancs"));
        assertTrue(workflow.contains("VERSION_NAME: 'v2.8.2-ha1210'"));
        assertTrue(workflow.contains("VERSION_CODE: '208021210'"));
        assertTrue(workflow.contains("Ha1210OpportunisticReverseAttachContractTest"));
        assertTrue(manifest.contains("v2.8.2-ha1210"));
        assertTrue(manifest.contains("208021210"));
        assertTrue(manifest.contains("autoConnect=false"));
        assertTrue(manifest.contains("opportunistic=true"));
    }

    @Test public void releaseIsMatchedToSeparatelyVerifiedHelperV44() throws Exception {
        String readme = project("ios/KX11-iPhone-ANCS-Helper-v44/README.md");
        String xcode = project("ios/KX11-iPhone-ANCS-Helper-v44/"
                + "KX11ANCSHelper.xcodeproj/project.pbxproj");
        String helperWorkflow = project(".github/workflows/verify-helper-v44.yml");
        String manifest = project("release-manifests/HA1210.md");
        assertTrue(readme.contains("matched to Status Widget `v2.8.2-ha1210`"));
        assertTrue(readme.contains("ru.natro.kx11ancshelper"));
        assertTrue(xcode.contains("CURRENT_PROJECT_VERSION = 44;"));
        assertTrue(xcode.contains("MARKETING_VERSION = 44.0;"));
        assertTrue(xcode.contains("PRODUCT_BUNDLE_IDENTIFIER = ru.natro.kx11ancshelper;"));
        assertTrue(helperWorkflow.contains("ios/KX11-iPhone-ANCS-Helper-v44"));
        assertTrue(helperWorkflow.contains("verify-v44-contract.sh"));
        assertTrue(manifest.contains("Helper v44"));
        assertTrue(manifest.contains("separate macOS"));
    }

    private static String transport() throws Exception {
        return project("app/src/main/java/dezz/status/widget/phone/transport/"
                + "IphoneAncsTransport.java");
    }

    private static String project(String relative) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(relative);
            if (Files.exists(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Project file not found: " + relative);
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        assertTrue("missing start: " + start, from >= 0);
        assertTrue("missing end: " + end, to > from);
        return source.substring(from, to);
    }

    private static int occurrences(String source, String needle) {
        int count = 0;
        int from = 0;
        while ((from = source.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }

    private static void assertPassiveClientFailurePrecedesPhysicalReset(
            String source, String start, String end) {
        String method = between(source, start, end);
        int passive = method.indexOf("if (managedIncomingMode && activeClientOpportunistic)");
        int closeOnly = method.indexOf("closeClientGatt(expected);", passive);
        int kept = method.indexOf("server/proofs/F04 kept", closeOnly);
        int terminalReturn = method.indexOf("return;", kept);
        int physicalReset = method.indexOf("resetIncomingSecurityAfterClientLoss(", terminalReturn);
        assertTrue(passive >= 0);
        assertTrue(closeOnly > passive);
        assertTrue(kept > closeOnly);
        assertTrue(terminalReturn > kept);
        assertTrue(physicalReset > terminalReturn);
    }
}
