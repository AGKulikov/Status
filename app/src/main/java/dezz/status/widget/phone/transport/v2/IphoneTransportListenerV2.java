/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

/** Listener DTO boundary shared by both v2 routes. */
public interface IphoneTransportListenerV2 {
    /**
     * Bounded, address-free Android platform evidence for field diagnosis.
     *
     * <p>The controller applies its normal diagnostic redaction again before persistence.  A
     * default keeps older test/listener implementations source-compatible.</p>
     */
    default void onPlatformDiagnostic(IphoneBleMode mode, BleRouteEpoch epoch, String detail) {
    }

    void onStatus(IphoneTransportStatusV2 status);

    void onTelemetry(IphoneTelemetryV2 telemetry);

    /**
     * Exact integer percentage from the selected iPhone's standard Bluetooth Battery Service.
     *
     * <p>The Helper telemetry percentage is intentionally a coarse availability fallback.  A
     * default method keeps Route-B and historical listener implementations source-compatible.</p>
     */
    default void onStandardBatteryPercentage(int percentage, String source) {
    }

    /** Raw source event, including REMOVED, category, replay flag, and observation time. */
    void onNotificationEvent(IphoneNotificationEventV2 event);

    void onNotification(IphoneNotificationV2 notification);

    void onAppName(IphoneAppNameV2 appName);

    /** Persist only after encrypted exact-bond bootstrap proof succeeds. */
    void onHelperInstallationIdLearned(String helperInstallationId);

    void onRoleControl(IphoneRoleControlV2 control);

    void onRoleControlWriteResult(IphoneRoleControlV2 control, boolean success);

    /** Input to the role-switch coordinator; it must still verify app-owned owner count is zero. */
    void onLocalTerminal(IphoneBleMode mode, BleRouteEpoch epoch);

    void onError(IphoneTransportErrorV2 error);
}
