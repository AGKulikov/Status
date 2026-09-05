/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class MediaAutoResumeLifecyclePolicyTest {
    @Test public void coldBootSequencePreservesOriginalDelayAnchor() {
        assertTrue(MediaAutoResumeLifecyclePolicy.shouldCoalesce(
                MediaAutoResumeLifecyclePolicy.ACTION_LOCKED_BOOT_COMPLETED,
                MediaAutoResumeLifecyclePolicy.ACTION_BOOT_COMPLETED,
                false, 180_000L));
        assertFalse(MediaAutoResumeLifecyclePolicy.shouldMovePlanAnchor(
                MediaAutoResumeLifecyclePolicy.ACTION_LOCKED_BOOT_COMPLETED,
                MediaAutoResumeLifecyclePolicy.ACTION_BOOT_COMPLETED));
        assertFalse(MediaAutoResumeLifecyclePolicy.shouldMovePlanAnchor(
                MediaAutoResumeLifecyclePolicy.ACTION_BOOT_COMPLETED,
                MediaAutoResumeLifecyclePolicy.ACTION_QUICKBOOT_POWERON));
    }

    @Test public void userUnlockIsTheEarliestPlayerUsableBoundary() {
        assertFalse(MediaAutoResumeLifecyclePolicy.isUsableBoundary(
                MediaAutoResumeLifecyclePolicy.ACTION_LOCKED_BOOT_COMPLETED));
        assertTrue(MediaAutoResumeLifecyclePolicy.isUsableBoundary(
                MediaAutoResumeLifecyclePolicy.ACTION_BOOT_COMPLETED));
        assertTrue(MediaAutoResumeLifecyclePolicy.isLifecycleAction(
                MediaAutoResumeLifecyclePolicy.ACTION_USER_UNLOCKED));
        assertTrue(MediaAutoResumeLifecyclePolicy.isUsableBoundary(
                MediaAutoResumeLifecyclePolicy.ACTION_USER_UNLOCKED));
        assertTrue(MediaAutoResumeLifecyclePolicy.shouldCoalesce(
                MediaAutoResumeLifecyclePolicy.ACTION_LOCKED_BOOT_COMPLETED,
                MediaAutoResumeLifecyclePolicy.ACTION_USER_UNLOCKED,
                false, 180_000L));
    }

    @Test public void ecarxQuickBootBurstNeverRestartsConsumedTimer() {
        assertTrue(MediaAutoResumeLifecyclePolicy.shouldCoalesce(
                MediaAutoResumeLifecyclePolicy.ACTION_BOOT_COMPLETED,
                MediaAutoResumeLifecyclePolicy.ACTION_QUICKBOOT_POWERON,
                false, 10_000L));
        assertTrue(MediaAutoResumeLifecyclePolicy.shouldCoalesce(
                MediaAutoResumeLifecyclePolicy.ACTION_QUICKBOOT_POWERON,
                MediaAutoResumeLifecyclePolicy.ACTION_QUICKBOOT_POWERON,
                false, 119_999L));
    }

    @Test public void quietGapOrNewKernelCreatesNewLifecycle() {
        assertFalse(MediaAutoResumeLifecyclePolicy.shouldCoalesce(
                MediaAutoResumeLifecyclePolicy.ACTION_QUICKBOOT_POWERON,
                MediaAutoResumeLifecyclePolicy.ACTION_QUICKBOOT_POWERON,
                false, 120_001L));
        assertFalse(MediaAutoResumeLifecyclePolicy.shouldCoalesce(
                MediaAutoResumeLifecyclePolicy.ACTION_BOOT_COMPLETED,
                MediaAutoResumeLifecyclePolicy.ACTION_QUICKBOOT_POWERON,
                true, 1_000L));
    }
}
