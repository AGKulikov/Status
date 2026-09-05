/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.security.SecureRandom;
import java.util.UUID;
import org.junit.Test;

public final class IphoneBleIdentityRegistryV2Test {
    private static final String HELPER = "8f04fe8d-11c2-4b3a-9ab7-f4512aa2a21d";
    private static final String FOREIGN = "1c2d3e4f-5061-4273-8495-a6b7c8d9e0f1";

    @Test public void localIdentityIsCommittedAndStable() {
        FakeStore store = new FakeStore();
        IphoneBleIdentityRegistryV2 registry = new IphoneBleIdentityRegistryV2(store);
        UUID first = registry.loadOrCreateAndroidIdentity(new SecureRandom());
        UUID second = registry.loadOrCreateAndroidIdentity(new SecureRandom());

        assertEquals(first, second);
        assertEquals(1, store.androidCommits);
    }

    @Test public void helperCanOnlyBeLearnedByExplicitBootstrap() {
        FakeStore store = new FakeStore();
        IphoneBleIdentityRegistryV2 registry = new IphoneBleIdentityRegistryV2(store);
        assertNull(registry.learnedHelperIdentity());
        expectThrows(IllegalStateException.class,
                () -> registry.acceptHelperIdentity(HELPER, false));

        assertEquals(UUID.fromString(HELPER), registry.acceptHelperIdentity(HELPER, true));
        assertEquals(UUID.fromString(HELPER), registry.acceptHelperIdentity(HELPER, false));
        assertEquals(1, store.helperCommits);
    }

    @Test public void differentHelperNeverReplacesEstablishedIdentity() {
        FakeStore store = new FakeStore();
        store.helper = HELPER;
        IphoneBleIdentityRegistryV2 registry = new IphoneBleIdentityRegistryV2(store);
        expectThrows(IllegalStateException.class,
                () -> registry.acceptHelperIdentity(FOREIGN, true));
        assertEquals(HELPER, store.helper);
        assertEquals(0, store.helperCommits);
    }

    @Test public void persistenceFailureFailsClosed() {
        FakeStore androidFailure = new FakeStore();
        androidFailure.failAndroid = true;
        expectThrows(IllegalStateException.class, () ->
                new IphoneBleIdentityRegistryV2(androidFailure)
                        .loadOrCreateAndroidIdentity(new SecureRandom()));

        FakeStore helperFailure = new FakeStore();
        helperFailure.failHelper = true;
        expectThrows(IllegalStateException.class, () ->
                new IphoneBleIdentityRegistryV2(helperFailure)
                        .acceptHelperIdentity(HELPER, true));
    }

    @Test public void malformedPersistedIdentityIsNotSilentlyRegenerated() {
        FakeStore store = new FakeStore();
        store.android = "broken";
        expectThrows(IllegalStateException.class, () ->
                new IphoneBleIdentityRegistryV2(store)
                        .loadOrCreateAndroidIdentity(new SecureRandom()));
        assertEquals(0, store.androidCommits);
    }

    private static void expectThrows(Class<? extends Throwable> type, Runnable body) {
        try {
            body.run();
        } catch (Throwable thrown) {
            if (type.isInstance(thrown)) return;
            throw new AssertionError("wrong exception " + thrown, thrown);
        }
        throw new AssertionError("expected " + type.getSimpleName());
    }

    private static final class FakeStore implements IphoneBleIdentityRegistryV2.Store {
        String android = "";
        String helper = "";
        boolean failAndroid;
        boolean failHelper;
        int androidCommits;
        int helperCommits;

        @Override public String androidInstallationId() { return android; }
        @Override public boolean commitAndroidInstallationId(String value) {
            androidCommits++;
            if (failAndroid) return false;
            android = value;
            return true;
        }
        @Override public String helperInstallationId() { return helper; }
        @Override public boolean commitHelperInstallationId(String value) {
            helperCommits++;
            if (failHelper) return false;
            helper = value;
            return true;
        }
    }
}
