/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public final class NavigationWorkerFailurePolicyTest {
    @Test public void malformedPayloadAndClassInitializationFailuresAreRecoverable() {
        assertFalse(NavigationWorkerFailurePolicy.isFatal(new IllegalArgumentException()));
        assertFalse(NavigationWorkerFailurePolicy.isFatal(
                new ExceptionInInitializerError(new IllegalStateException("foreign parcelable"))));
        assertFalse(NavigationWorkerFailurePolicy.isFatal(new NoClassDefFoundError("foreign")));
        assertFalse(NavigationWorkerFailurePolicy.isFatal(new AssertionError("bad input")));
    }

    @Test public void vmFailuresAndThreadDeathRemainFatal() {
        assertTrue(NavigationWorkerFailurePolicy.isFatal(new OutOfMemoryError()));
        assertTrue(NavigationWorkerFailurePolicy.isFatal(new StackOverflowError()));
        assertTrue(NavigationWorkerFailurePolicy.isFatal(new InternalError()));
        assertTrue(NavigationWorkerFailurePolicy.isFatal(new ThreadDeath()));
    }

    @Test public void fatalFailureIsRethrownWithoutWrapping() {
        OutOfMemoryError expected = new OutOfMemoryError("test");
        try {
            NavigationWorkerFailurePolicy.rethrowIfFatal(expected);
            fail("Expected fatal failure to be rethrown");
        } catch (OutOfMemoryError actual) {
            assertSame(expected, actual);
        }
    }
}
