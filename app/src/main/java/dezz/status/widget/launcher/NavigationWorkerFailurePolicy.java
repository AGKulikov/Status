/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

/** Classifies failures at the exported navigation worker boundary. */
final class NavigationWorkerFailurePolicy {
    private NavigationWorkerFailurePolicy() {}

    /**
     * VM failures mean the process cannot safely continue. {@link ExceptionInInitializerError},
     * other linkage failures and malformed-Parcelable runtime exceptions are isolated and logged
     * by the receiver because they can be caused entirely by an untrusted broadcast payload.
     */
    static boolean isFatal(Throwable failure) {
        return failure instanceof VirtualMachineError || failure instanceof ThreadDeath;
    }

    static void rethrowIfFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError) {
            throw (VirtualMachineError) failure;
        }
        if (failure instanceof ThreadDeath) {
            throw (ThreadDeath) failure;
        }
    }
}
