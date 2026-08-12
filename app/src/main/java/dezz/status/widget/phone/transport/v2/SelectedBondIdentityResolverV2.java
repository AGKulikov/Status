/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

import java.util.Locale;

/**
 * Pure, fail-closed identity contract for attributing one Android BLE facade to the explicitly
 * selected system bond.
 *
 * <p>Android 9 public APIs do not expose the IRK or an API which maps an observed resolvable
 * private address back to a bonded identity address.  Consequently, a different observed
 * address is never accepted as the selected bond.  A previously learned installation UUID plus
 * an active encrypted link is necessary continuity evidence, but the UUID is not a secret and
 * Android does not bind that link to the selected bond; those facts therefore cannot repair a
 * missing identity mapping.  Device name and the existence of some other bond are deliberately
 * not inputs.
 * </p>
 */
public final class SelectedBondIdentityResolverV2 {
    public enum Path {
        EXACT_SELECTED_ADDRESS,
        REJECTED
    }

    public enum Failure {
        NONE,
        INVALID_SELECTED_ADDRESS,
        SELECTED_BOND_MISSING,
        SELECTED_BOND_AMBIGUOUS,
        OBSERVED_FACADE_INVALID,
        OBSERVED_FACADE_NOT_BONDED,
        ROTATED_ADDRESS_BOOTSTRAP_UNPROVABLE,
        ROTATED_ADDRESS_PUBLIC_IDENTITY_UNPROVABLE,
        ANCHORED_HELPER_ID_INVALID,
        OBSERVED_HELPER_ID_INVALID,
        HELPER_ID_CONFLICT,
        ENCRYPTED_ATTRIBUTE_NOT_PROVEN,
        ACTIVE_OWNER_MISSING,
        ACTIVE_OWNER_AMBIGUOUS
    }

    /** Immutable pre-connect/pre-H decision. A non-rejected candidate still has no authority. */
    public static final class Candidate {
        public final Path path;
        public final Failure failure;
        public final String selectedSystemBondAddress;
        public final String observedAddress;
        public final String anchoredHelperInstallationId;
        public final String detail;

        private Candidate(Path path, Failure failure, String selectedSystemBondAddress,
                          String observedAddress, String anchoredHelperInstallationId,
                          String detail) {
            this.path = path;
            this.failure = failure;
            this.selectedSystemBondAddress = selectedSystemBondAddress;
            this.observedAddress = observedAddress;
            this.anchoredHelperInstallationId = anchoredHelperInstallationId;
            this.detail = detail;
        }

        /** True only means that this facade may proceed to encrypted H proof. */
        public boolean mayProceedToEncryptedProof() {
            return path != Path.REJECTED;
        }

        public boolean isFirstBootstrap() {
            return anchoredHelperInstallationId.isEmpty();
        }
    }

    /** Final authority decision after the encrypted H access on the exact active facade. */
    public static final class Decision {
        public final boolean proven;
        public final Path path;
        public final Failure failure;
        public final String helperInstallationId;
        public final String detail;

        private Decision(boolean proven, Path path, Failure failure,
                         String helperInstallationId, String detail) {
            this.proven = proven;
            this.path = path;
            this.failure = failure;
            this.helperInstallationId = helperInstallationId;
            this.detail = detail;
        }
    }

    private SelectedBondIdentityResolverV2() {
    }

    /**
     * Starts attribution from public information available before H is accepted.
     *
     * @param selectedSystemBondMatches exact address matches in getBondedDevices(); must be one
     */
    public static Candidate begin(String selectedSystemBondAddress,
                                  int selectedSystemBondMatches,
                                  String observedAddress,
                                  boolean observedFacadeBonded,
                                  String anchoredHelperInstallationId) {
        String selected = canonicalAddress(selectedSystemBondAddress);
        String observed = canonicalAddress(observedAddress);
        String anchored = canonicalHelperId(anchoredHelperInstallationId);
        boolean anchorSupplied = anchoredHelperInstallationId != null
                && !anchoredHelperInstallationId.trim().isEmpty();

        if (selected.isEmpty()) {
            return rejected(Failure.INVALID_SELECTED_ADDRESS, selected, observed, anchored,
                    "selected system bond address is invalid; reselect the iPhone bond");
        }
        if (selectedSystemBondMatches == 0) {
            return rejected(Failure.SELECTED_BOND_MISSING, selected, observed, anchored,
                    "selected system bond is absent; reselect or restore the existing selected "
                            + "system bond without deleting the pair");
        }
        if (selectedSystemBondMatches != 1) {
            return rejected(Failure.SELECTED_BOND_AMBIGUOUS, selected, observed, anchored,
                    "selected system bond is not uniquely exposed; reselect or restore the "
                            + "existing selected bond without deleting the pair");
        }
        if (observed.isEmpty()) {
            return rejected(Failure.OBSERVED_FACADE_INVALID, selected, observed, anchored,
                    "BLE callback has no valid public facade address");
        }
        if (anchorSupplied && anchored.isEmpty()) {
            return rejected(Failure.ANCHORED_HELPER_ID_INVALID, selected, observed, anchored,
                    "stored Helper installation UUID is invalid; explicitly reset identity");
        }
        if (!selected.equals(observed)) {
            if (anchored.isEmpty()) {
                return rejected(Failure.ROTATED_ADDRESS_BOOTSTRAP_UNPROVABLE,
                        selected, observed, anchored,
                        "Android 9 public APIs cannot attribute this private/rotated address "
                                + "during first bootstrap; the vendor must provide direct "
                                + "identity mapping");
            }
            return rejected(Failure.ROTATED_ADDRESS_PUBLIC_IDENTITY_UNPROVABLE,
                    selected, observed, anchored,
                    "Android 9 public APIs cannot map this private/rotated address to the "
                            + "selected bond; vendor identity mapping is required, and the "
                            + "learned Helper UUID is continuity evidence, not bond proof");
        }
        if (!observedFacadeBonded) {
            return rejected(Failure.OBSERVED_FACADE_NOT_BONDED, selected, observed, anchored,
                    "exact selected facade is not bonded; restore the existing selected "
                            + "system bond without deleting the pair");
        }
        return new Candidate(Path.EXACT_SELECTED_ADDRESS, Failure.NONE, selected, observed,
                anchored, "exact selected system-bond facade; encrypted H still required");
    }

    /**
     * Completes attribution only after H was read/written through the encrypted characteristic
     * on this candidate's exact callback owner.  Bond presence is deliberately rechecked here.
     */
    public static Decision complete(Candidate candidate,
                                    String observedHelperInstallationId,
                                    int selectedSystemBondMatchesNow,
                                    int activeEncryptedOwnerMatches,
                                    boolean encryptedAttributeAccessProven) {
        if (candidate == null) {
            return denied(Path.REJECTED, Failure.OBSERVED_FACADE_INVALID, "",
                    "selected-bond attribution candidate is missing");
        }
        if (!candidate.mayProceedToEncryptedProof()) {
            return denied(candidate.path, candidate.failure, "", candidate.detail);
        }
        if (selectedSystemBondMatchesNow == 0) {
            return denied(candidate.path, Failure.SELECTED_BOND_MISSING, "",
                    "selected system bond disappeared before encrypted H proof");
        }
        if (selectedSystemBondMatchesNow != 1) {
            return denied(candidate.path, Failure.SELECTED_BOND_AMBIGUOUS, "",
                    "selected system bond became ambiguous before encrypted H proof");
        }
        if (!encryptedAttributeAccessProven) {
            return denied(candidate.path, Failure.ENCRYPTED_ATTRIBUTE_NOT_PROVEN, "",
                    "encrypted H characteristic access was not proven");
        }
        if (activeEncryptedOwnerMatches == 0) {
            return denied(candidate.path, Failure.ACTIVE_OWNER_MISSING, "",
                    "no active encrypted owner matches the exact callback facade");
        }
        if (activeEncryptedOwnerMatches != 1) {
            return denied(candidate.path, Failure.ACTIVE_OWNER_AMBIGUOUS, "",
                    "more than one active encrypted owner matches the callback facade");
        }
        String observedHelper = canonicalHelperId(observedHelperInstallationId);
        if (observedHelper.isEmpty()) {
            return denied(candidate.path, Failure.OBSERVED_HELPER_ID_INVALID, "",
                    "encrypted H frame has no canonical non-zero installation UUID");
        }
        if (!candidate.anchoredHelperInstallationId.isEmpty()
                && !candidate.anchoredHelperInstallationId.equals(observedHelper)) {
            return denied(candidate.path, Failure.HELPER_ID_CONFLICT, observedHelper,
                    "encrypted H UUID conflicts with the Helper identity anchored to the "
                            + "selected system bond");
        }
        return new Decision(true, candidate.path, Failure.NONE, observedHelper,
                "exact selected bond, anchored Helper UUID, and encrypted H owner proven");
    }

    private static Candidate rejected(Failure failure, String selected, String observed,
                                      String anchored, String detail) {
        return new Candidate(Path.REJECTED, failure, selected, observed, anchored, detail);
    }

    private static Decision denied(Path path, Failure failure, String helper, String detail) {
        return new Decision(false, path, failure, helper, detail);
    }

    private static String canonicalAddress(String value) {
        if (value == null) return "";
        String candidate = value.trim().toLowerCase(Locale.US);
        if (candidate.length() != 17) return "";
        for (int index = 0; index < candidate.length(); index++) {
            char valueAt = candidate.charAt(index);
            if (index % 3 == 2) {
                if (valueAt != ':') return "";
            } else if ((valueAt < '0' || valueAt > '9')
                    && (valueAt < 'a' || valueAt > 'f')) {
                return "";
            }
        }
        return candidate;
    }

    private static String canonicalHelperId(String value) {
        if (value == null || value.trim().isEmpty()) return "";
        java.util.UUID parsed = IphoneBleInstallationIdentityV2.parseCanonical(value);
        return parsed == null ? "" : parsed.toString();
    }
}
