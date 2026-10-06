/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.media;

/** Coalesces startup and package replacement without treating a failed transport as success. */
public final class ButtonRestoreGate {
    public enum Next { NONE, RETRY, FORCE }
    private boolean active, completed, pendingReplacement;
    private int attempts;
    public synchronized boolean begin(boolean replacement) {
        if (active) { pendingReplacement |= replacement; return false; }
        if (replacement) { completed = false; attempts = 0; }
        if (completed || attempts >= 3) return false;
        active = true; attempts++; return true;
    }
    public synchronized Next finish(boolean success) {
        return finish(success, true);
    }
    public synchronized Next finish(boolean success, boolean retryable) {
        if (!active) return Next.NONE;
        active = false; completed = success || !retryable;
        if (pendingReplacement) { pendingReplacement = false; return Next.FORCE; }
        return !success && retryable && attempts < 3 ? Next.RETRY : Next.NONE;
    }
    public synchronized int attempts() { return attempts; }
}
