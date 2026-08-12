/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

import java.security.SecureRandom;
import java.util.Objects;
import java.util.UUID;

/**
 * Fail-closed persistence rules for the two installation identities carried by encrypted H.
 * Address rotation, BLE names, and a newly observed UUID never overwrite established identity.
 */
public final class IphoneBleIdentityRegistryV2 {
    public interface Store {
        String androidInstallationId();

        boolean commitAndroidInstallationId(String canonicalUuid);

        String helperInstallationId();

        boolean commitHelperInstallationId(String canonicalUuid);
    }

    private final Store store;

    public IphoneBleIdentityRegistryV2(Store store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /** Loads the local endpoint identity or durably creates it before any H/advertising effect. */
    public UUID loadOrCreateAndroidIdentity(SecureRandom random) {
        String stored = clean(store.androidInstallationId());
        UUID existing = IphoneBleInstallationIdentityV2.parseCanonical(stored);
        if (existing != null) return existing;
        if (!stored.isEmpty()) {
            throw new IllegalStateException("malformed persisted Android installation identity");
        }

        UUID created = IphoneBleInstallationIdentityV2.generate(random);
        String canonical = IphoneBleInstallationIdentityV2.canonical(created);
        if (!store.commitAndroidInstallationId(canonical)) {
            throw new IllegalStateException("Android installation identity was not durable");
        }
        UUID reread = IphoneBleInstallationIdentityV2.parseCanonical(
                clean(store.androidInstallationId()));
        if (!created.equals(reread)) {
            throw new IllegalStateException("Android installation identity durability mismatch");
        }
        return created;
    }

    /** Returns the exact learned Helper identity, or {@code null} before explicit bootstrap. */
    public UUID learnedHelperIdentity() {
        String stored = clean(store.helperInstallationId());
        if (stored.isEmpty()) return null;
        UUID parsed = IphoneBleInstallationIdentityV2.parseCanonical(stored);
        if (parsed == null) {
            throw new IllegalStateException("malformed persisted Helper installation identity");
        }
        return parsed;
    }

    /**
     * Commits the first Helper H only during explicit bootstrap. A different later H is an
     * identity conflict and requires an explicit user reset; it is never treated as RPA churn.
     */
    public UUID acceptHelperIdentity(String candidate, boolean explicitBootstrap) {
        UUID decoded = IphoneBleInstallationIdentityV2.parseCanonical(clean(candidate));
        if (decoded == null) {
            throw new IllegalArgumentException("Helper installation identity is not canonical");
        }
        UUID existing = learnedHelperIdentity();
        if (existing != null) {
            if (!existing.equals(decoded)) {
                throw new IllegalStateException("Helper installation identity conflict");
            }
            return existing;
        }
        if (!explicitBootstrap) {
            throw new IllegalStateException("daily route cannot learn a new Helper identity");
        }
        String canonical = IphoneBleInstallationIdentityV2.canonical(decoded);
        if (!store.commitHelperInstallationId(canonical)) {
            throw new IllegalStateException("Helper installation identity was not durable");
        }
        UUID reread = learnedHelperIdentity();
        if (!decoded.equals(reread)) {
            throw new IllegalStateException("Helper installation identity durability mismatch");
        }
        return reread;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
