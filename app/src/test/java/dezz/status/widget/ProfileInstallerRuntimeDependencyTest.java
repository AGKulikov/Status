/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Prevents Android 9 startup crashes caused by ProfileInstaller's optional future dependency. */
public final class ProfileInstallerRuntimeDependencyTest {
    @Test
    public void resolvableFutureIsAvailableAtRuntime() throws ClassNotFoundException {
        Class<?> future = Class.forName("androidx.concurrent.futures.ResolvableFuture");

        assertEquals("androidx.concurrent.futures.ResolvableFuture", future.getName());
    }
}
