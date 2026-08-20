/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class SelectedBondIdentityResolverV2Test {
    private static final String SELECTED = "aa:bb:cc:dd:ee:ff";
    private static final String OTHER_BONDED = "10:20:30:40:50:60";
    private static final String HELPER = "34f9515d-8d8d-4ef5-8b42-c052fdbe4e6f";
    private static final String OTHER_HELPER = "c1ce3d6b-cdcb-4672-bd63-77d589c19ddc";

    @Test public void exactSelectedBondFirstBootstrapLearnsOnlyAfterEncryptedUniqueOwner() {
        SelectedBondIdentityResolverV2.Candidate candidate =
                SelectedBondIdentityResolverV2.begin(
                        SELECTED, 1, "AA:BB:CC:DD:EE:FF", true, "");

        assertTrue(candidate.mayProceedToEncryptedProof());
        assertTrue(candidate.isFirstBootstrap());
        assertEquals(SelectedBondIdentityResolverV2.Path.EXACT_SELECTED_ADDRESS,
                candidate.path);

        SelectedBondIdentityResolverV2.Decision decision =
                SelectedBondIdentityResolverV2.complete(
                        candidate, HELPER, 1, 1, true);
        assertTrue(decision.proven);
        assertEquals(HELPER, decision.helperInstallationId);
    }

    @Test public void arbitraryBondedPeerCannotWinFirstBootstrap() {
        SelectedBondIdentityResolverV2.Candidate candidate =
                SelectedBondIdentityResolverV2.begin(
                        SELECTED, 1, OTHER_BONDED, true, "");

        assertFalse(candidate.mayProceedToEncryptedProof());
        assertEquals(SelectedBondIdentityResolverV2.Failure
                        .ROTATED_ADDRESS_BOOTSTRAP_UNPROVABLE,
                candidate.failure);
        assertFalse(SelectedBondIdentityResolverV2.complete(
                candidate, HELPER, 1, 1, true).proven);
    }

    @Test public void learnedUuidAndActiveLinkDoNotGuessRpaOwnership() {
        SelectedBondIdentityResolverV2.Candidate candidate =
                SelectedBondIdentityResolverV2.begin(
                        SELECTED, 1, OTHER_BONDED, false, HELPER);

        assertFalse(candidate.mayProceedToEncryptedProof());
        assertEquals(SelectedBondIdentityResolverV2.Failure
                        .ROTATED_ADDRESS_PUBLIC_IDENTITY_UNPROVABLE,
                candidate.failure);
        assertTrue(candidate.detail.contains("not bond proof"));
        assertFalse(SelectedBondIdentityResolverV2.complete(
                candidate, HELPER, 1, 1, true).proven);
    }

    @Test public void selectedSystemBondMustExistExactlyOnce() {
        SelectedBondIdentityResolverV2.Candidate missing =
                SelectedBondIdentityResolverV2.begin(
                        SELECTED, 0, SELECTED, true, HELPER);
        SelectedBondIdentityResolverV2.Candidate ambiguous =
                SelectedBondIdentityResolverV2.begin(
                        SELECTED, 2, SELECTED, true, HELPER);

        assertEquals(SelectedBondIdentityResolverV2.Failure.SELECTED_BOND_MISSING,
                missing.failure);
        assertEquals(SelectedBondIdentityResolverV2.Failure.SELECTED_BOND_AMBIGUOUS,
                ambiguous.failure);
        assertFalse(missing.mayProceedToEncryptedProof());
        assertFalse(ambiguous.mayProceedToEncryptedProof());
    }

    @Test public void exactAddressStillRequiresBondedFacade() {
        SelectedBondIdentityResolverV2.Candidate candidate =
                SelectedBondIdentityResolverV2.begin(
                        SELECTED, 1, SELECTED, false, HELPER);

        assertEquals(SelectedBondIdentityResolverV2.Failure.OBSERVED_FACADE_NOT_BONDED,
                candidate.failure);
        assertFalse(candidate.mayProceedToEncryptedProof());
    }

    @Test public void encryptedOwnerMustBePresentAndUniqueAtH() {
        SelectedBondIdentityResolverV2.Candidate candidate =
                SelectedBondIdentityResolverV2.begin(
                        SELECTED, 1, SELECTED, true, HELPER);

        assertEquals(SelectedBondIdentityResolverV2.Failure.ACTIVE_OWNER_MISSING,
                SelectedBondIdentityResolverV2.complete(
                        candidate, HELPER, 1, 0, true).failure);
        assertEquals(SelectedBondIdentityResolverV2.Failure.ACTIVE_OWNER_AMBIGUOUS,
                SelectedBondIdentityResolverV2.complete(
                        candidate, HELPER, 1, 2, true).failure);
        assertEquals(SelectedBondIdentityResolverV2.Failure.ENCRYPTED_ATTRIBUTE_NOT_PROVEN,
                SelectedBondIdentityResolverV2.complete(
                        candidate, HELPER, 1, 1, false).failure);
    }

    @Test public void exactDailyFacadeRequiresAnchoredStableUuidMatch() {
        SelectedBondIdentityResolverV2.Candidate candidate =
                SelectedBondIdentityResolverV2.begin(
                        SELECTED, 1, SELECTED, true, HELPER);

        assertTrue(SelectedBondIdentityResolverV2.complete(
                candidate, HELPER, 1, 1, true).proven);
        assertEquals(SelectedBondIdentityResolverV2.Failure.HELPER_ID_CONFLICT,
                SelectedBondIdentityResolverV2.complete(
                        candidate, OTHER_HELPER, 1, 1, true).failure);
        assertEquals(SelectedBondIdentityResolverV2.Failure.SELECTED_BOND_MISSING,
                SelectedBondIdentityResolverV2.complete(
                        candidate, HELPER, 0, 1, true).failure);
    }
}
