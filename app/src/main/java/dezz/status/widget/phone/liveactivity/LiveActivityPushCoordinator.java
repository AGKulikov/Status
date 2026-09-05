/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.liveactivity;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.List;

import dezz.status.widget.phone.PhoneConnectionJournal;

/** Owns the one Bluetooth-presence-driven APNs lifecycle for the selected iPhone. */
public final class LiveActivityPushCoordinator {
    public interface ReadinessListener { void onChanged(boolean ready); }

    private static final long START_RETRY_MS = 1_000L;
    private static final int MAX_START_ATTEMPTS = 3;

    private final ApnsCredentialStore credentials;
    private final LiveActivityProvisioningStore provisioning;
    private final LiveActivityPushProtocolV1.Reassembler reassembler =
            new LiveActivityPushProtocolV1.Reassembler();
    private final ApnsLiveActivityClient client = new ApnsLiveActivityClient();
    private final Handler main = new Handler(Looper.getMainLooper());
    @Nullable private ReadinessListener readinessListener;
    private boolean classicConnected;
    private boolean startPermittedThisGeneration;
    private long lifecycleGeneration;
    private boolean lastReady;
    private final boolean[] panelStarted = new boolean[2];
    private final boolean[] panelInFlight = new boolean[2];

    public LiveActivityPushCoordinator(@NonNull Context context) {
        credentials = new ApnsCredentialStore(context);
        provisioning = new LiveActivityProvisioningStore(context);
        lastReady = computeReady();
    }

    public synchronized void setReadinessListener(@Nullable ReadinessListener listener) {
        readinessListener = listener;
        if (listener != null) listener.onChanged(computeReady());
    }

    public synchronized boolean isReady() {
        return computeReady();
    }

    public void acceptFrame(@Nullable byte[] frame) {
        LiveActivityPushProtocolV1.Message message = reassembler.accept(frame);
        if (message == null || !provisioning.accept(message)) return;
        synchronized (this) {
            publishReadinessIfChanged();
            if (classicConnected && startPermittedThisGeneration && computeReady()) {
                startIfPossible(lifecycleGeneration, 0);
            }
        }
        PhoneConnectionJournal.append("live-activity-push",
                "Helper provisioning принято, type=" + message.type);
    }

    public synchronized void onClassicConnectionChanged(boolean connected) {
        if (classicConnected == connected) return;
        classicConnected = connected;
        long exactGeneration = ++lifecycleGeneration;
        Arrays.fill(panelStarted, false);
        Arrays.fill(panelInFlight, false);
        if (connected) {
            // Provisioning learned during this very connection is used from the next Classic
            // edge. Helper may already have created local cards, so starting mid-generation would
            // race those cards and create duplicates.
            startPermittedThisGeneration = computeReady();
            PhoneConnectionJournal.append("live-activity-push",
                    "выбранный iPhone подключён; APNs start разрешён");
            cleanupOldActivitiesThenStart(exactGeneration);
        } else {
            startPermittedThisGeneration = false;
            PhoneConnectionJournal.append("live-activity-push",
                    "выбранный iPhone отключён; отправляется APNs end");
            endActivities(exactGeneration);
        }
    }

    /** Called after settings import/removal without restarting the Bluetooth transport. */
    public synchronized void credentialsChanged() {
        publishReadinessIfChanged();
        if (classicConnected && startPermittedThisGeneration && computeReady()) {
            startIfPossible(lifecycleGeneration, 0);
        }
    }

    private void cleanupOldActivitiesThenStart(long generation) {
        List<LiveActivityProvisioningStore.ActivityToken> old = provisioning.activityTokens();
        if (old.isEmpty()) {
            startIfPossible(generation, 0);
            return;
        }
        // This also covers process recreation while Classic stayed connected: terminate any
        // stale cards first, then create exactly two cards for the current presence generation.
        sendEnds(old, generation, false);
        main.postDelayed(() -> {
            synchronized (LiveActivityPushCoordinator.this) {
                if (classicConnected && lifecycleGeneration == generation) {
                    provisioning.clearActivities();
                    startIfPossible(generation, 0);
                }
            }
        }, 600L);
    }

    private void startIfPossible(long generation, int attempt) {
        if (!classicConnected || lifecycleGeneration != generation || !computeReady()) return;
        ApnsCredentialStore.Credentials exactCredentials = credentials.load();
        byte[] startToken = provisioning.pushToStartToken();
        LiveActivityProvisioningStore.Configuration configuration = provisioning.configuration();
        if (exactCredentials == null || startToken == null || configuration == null
                || !configuration.automaticStart) return;
        for (int panel = 0; panel < 2; panel++) {
            if (panelStarted[panel] || panelInFlight[panel]) continue;
            int exactPanel = panel;
            panelInFlight[panel] = true;
            client.start(exactCredentials, startToken, configuration, panel,
                    (success, status, reason) -> main.post(() -> {
                        synchronized (LiveActivityPushCoordinator.this) {
                            if (!classicConnected || lifecycleGeneration != generation) return;
                            panelInFlight[exactPanel] = false;
                            if (success) panelStarted[exactPanel] = true;
                            PhoneConnectionJournal.append("live-activity-push",
                                    "APNs start panel=" + exactPanel + ", status=" + status
                                            + ", result=" + safeReason(reason));
                            if (!success && attempt + 1 < MAX_START_ATTEMPTS
                                    && retryable(status)) {
                                main.postDelayed(() -> {
                                    synchronized (LiveActivityPushCoordinator.this) {
                                        startIfPossible(generation, attempt + 1);
                                    }
                                }, START_RETRY_MS * (attempt + 1));
                            }
                        }
                    }));
        }
        Arrays.fill(exactCredentials.privateKeyPem, (byte) 0);
        Arrays.fill(startToken, (byte) 0);
    }

    private void endActivities(long generation) {
        List<LiveActivityProvisioningStore.ActivityToken> activities =
                provisioning.activityTokens();
        if (activities.isEmpty()) return;
        sendEnds(activities, generation, true);
    }

    private void sendEnds(@NonNull List<LiveActivityProvisioningStore.ActivityToken> activities,
                          long generation, boolean clearOnSuccess) {
        ApnsCredentialStore.Credentials exactCredentials = credentials.load();
        if (exactCredentials == null) return;
        final int[] pending = {activities.size()};
        final boolean[] accepted = {true};
        for (LiveActivityProvisioningStore.ActivityToken activity : activities) {
            client.end(exactCredentials, activity, (success, status, reason) -> main.post(() -> {
                synchronized (LiveActivityPushCoordinator.this) {
                    boolean terminal = success || status == 410 || status == 404;
                    accepted[0] &= terminal;
                    pending[0]--;
                    PhoneConnectionJournal.append("live-activity-push",
                            "APNs end panel=" + activity.panel + ", status=" + status
                                    + ", result=" + safeReason(reason));
                    if (clearOnSuccess && pending[0] == 0 && accepted[0]
                            && lifecycleGeneration == generation && !classicConnected) {
                        provisioning.clearActivities();
                    }
                }
            }));
        }
        Arrays.fill(exactCredentials.privateKeyPem, (byte) 0);
    }

    private boolean computeReady() {
        return credentials.isConfigured() && provisioning.readyForStart();
    }

    private void publishReadinessIfChanged() {
        boolean ready = computeReady();
        if (ready == lastReady) return;
        lastReady = ready;
        ReadinessListener listener = readinessListener;
        if (listener != null) listener.onChanged(ready);
    }

    private static boolean retryable(int status) {
        return status == 0 || status == 429 || status >= 500;
    }

    @NonNull private static String safeReason(@Nullable String reason) {
        if (reason == null || reason.isEmpty()) return "unknown";
        return reason.replaceAll("[^A-Za-z0-9_.-]", "_");
    }
}
