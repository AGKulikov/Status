/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dezz.status.hudlab;

/**
 * Stable cross-package contract between HUD Lab and the disposable cluster marker APK.
 */
final class ClusterProbeContract {
    static final String CONTROLLER_PACKAGE = "dezz.status.hudlab29";
    static final String PROBE_PACKAGE = "dezz.status.hudlab.clusterprobe";
    static final String PROBE_ACTIVITY =
            "dezz.status.hudlab.clusterprobe.ClusterProbeActivity";

    static final String ACTION_STATE =
            "dezz.status.hudlab29.action.CLUSTER_PROBE_STATE";
    static final String EXTRA_DURATION_MS = "duration_ms";
    static final String EXTRA_LAUNCH_TOKEN = "launch_token";
    static final String EXTRA_EVENT = "event";
    static final String EXTRA_STATE = "state";
    static final String EXTRA_SOURCE_PACKAGE = "source_package";
    static final String EXTRA_DISPLAY_ID = "display_id";

    static final String EVENT_STARTED = "STARTED";
    static final String EVENT_RESUMED = "RESUMED";
    static final String EVENT_STOPPED = "STOPPED";
    static final String EVENT_UPDATED = "UPDATED";

    private ClusterProbeContract() {
    }
}
