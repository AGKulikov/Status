/* Copyright © 2025-2026 Dezz; adapted for Natro. SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.servicemode;

import dezz.status.widget.R;

/**
 * Abstraction for executing shell commands on the device.
 * Implemented by {@link AdbTransport}.
 */
public interface ShellTransport extends AutoCloseable {
    /**
     * Human-readable description for UI, e.g. "ADB 127.0.0.1:5555".
     */
    String describe();

    /**
     * Execute a shell command and return its output.
     * Can be called multiple times on the same transport instance.
     */
    String exec(String command) throws Exception;

    /**
     * Close the connection and release resources.
     */
    void close();
}
