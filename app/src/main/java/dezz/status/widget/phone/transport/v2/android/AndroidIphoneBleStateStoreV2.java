/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2.android;

import java.util.Objects;

import dezz.status.widget.Preferences;
import dezz.status.widget.phone.transport.v2.IphoneDualTransportStateStoreV2;
import dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentRecordV2;

/** Device-local durable state used by the Android ANCS v2 runtime. */
public final class AndroidIphoneBleStateStoreV2 implements IphoneDualTransportStateStoreV2 {
    private final Preferences preferences;

    public AndroidIphoneBleStateStoreV2(Preferences preferences) {
        this.preferences = Objects.requireNonNull(preferences, "preferences");
    }

    @Override public String switchSnapshot() {
        return preferences.phoneBleV2SwitchSnapshot();
    }

    @Override public boolean hasSwitchSnapshot() {
        return preferences.hasPhoneBleV2SwitchSnapshot();
    }

    /** Must complete before the coordinator executes the effect covered by this snapshot. */
    @Override public void persistSwitchSnapshot(String encodedSnapshot) {
        if (!preferences.commitPhoneBleV2SwitchSnapshot(
                Objects.requireNonNull(encodedSnapshot, "encodedSnapshot"))) {
            throw new IllegalStateException("ANCS v2 switch snapshot was not durable");
        }
        if (!encodedSnapshot.equals(preferences.phoneBleV2SwitchSnapshot())) {
            throw new IllegalStateException("ANCS v2 switch snapshot durability mismatch");
        }
    }

    @Override public String androidInstallationId() {
        return preferences.phoneBleV2AndroidInstallationId();
    }

    @Override public boolean commitAndroidInstallationId(String canonicalUuid) {
        return preferences.commitPhoneBleV2AndroidInstallationId(canonicalUuid);
    }

    @Override public String helperInstallationId() {
        return preferences.phoneBleV2HelperInstallationId();
    }

    @Override public boolean commitHelperInstallationId(String canonicalUuid) {
        return preferences.commitPhoneBleV2HelperInstallationId(canonicalUuid);
    }

    @Override public boolean hasRouteAEnrollment(String selectedClassicAddress,
                                                 String androidInstallationId) {
        IphoneLeEnrollmentRecordV2 pending = IphoneLeEnrollmentRecordV2.validForSelectedClassic(
                preferences.phoneBleV2PendingEnrollmentRecord(), selectedClassicAddress);
        if (pending != null && pending.matchesBinding(selectedClassicAddress,
                pending.helperInstallationId.toString(), androidInstallationId)) {
            return true;
        }
        IphoneLeEnrollmentRecordV2 active = IphoneLeEnrollmentRecordV2.validForSelectedClassic(
                preferences.phoneBleV2EnrollmentRecord(), selectedClassicAddress);
        return active != null && active.matchesBinding(selectedClassicAddress,
                active.helperInstallationId.toString(), androidInstallationId);
    }
}
