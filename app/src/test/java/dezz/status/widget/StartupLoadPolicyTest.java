/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Executable truth table for the coalesced, low-contention boot lane. */
public final class StartupLoadPolicyTest {
    @Test public void lockedBootRecordsQuietButStartsNoHeavySurface() {
        assertEquals(4_500L, StartupLoadPolicy.quietWindowMillis(
                StartupLoadPolicy.Trigger.LOCKED_BOOT, 0L));
        assertFalse(StartupLoadPolicy.schedulesIntegrationHost(
                StartupLoadPolicy.Trigger.LOCKED_BOOT));
        assertFalse(StartupLoadPolicy.schedulesClimate(
                StartupLoadPolicy.Trigger.LOCKED_BOOT));
    }

    @Test public void normalAndQuickBootUseOneStagedHostThenClimateLane() {
        assertEquals(4_500L, StartupLoadPolicy.quietWindowMillis(
                StartupLoadPolicy.Trigger.BOOT_COMPLETED, 0L));
        assertEquals(1_500L, StartupLoadPolicy.quietWindowMillis(
                StartupLoadPolicy.Trigger.QUICK_BOOT, 0L));
        for (StartupLoadPolicy.Trigger trigger : new StartupLoadPolicy.Trigger[]{
                StartupLoadPolicy.Trigger.BOOT_COMPLETED,
                StartupLoadPolicy.Trigger.QUICK_BOOT}) {
            assertTrue(StartupLoadPolicy.schedulesIntegrationHost(trigger));
            assertTrue(StartupLoadPolicy.schedulesClimate(trigger));
        }
        assertEquals(7_500L, StartupLoadPolicy.CLIMATE_AFTER_HOST_MS);
        assertTrue(StartupLoadPolicy.schedulesMediaPlan(
                StartupLoadPolicy.Trigger.BOOT_COMPLETED));
        assertTrue(StartupLoadPolicy.schedulesMediaPlan(
                StartupLoadPolicy.Trigger.QUICK_BOOT));
    }

    @Test public void unlockRefreshesCredentialsWithoutReplayingVisualSurfaces() {
        StartupLoadPolicy.Trigger unlock = StartupLoadPolicy.Trigger.USER_UNLOCKED;
        assertTrue(StartupLoadPolicy.opensCredentialGate(unlock));
        assertTrue(StartupLoadPolicy.schedulesIntegrationHost(unlock));
        assertFalse(StartupLoadPolicy.schedulesClimate(unlock));
        assertEquals(750L, StartupLoadPolicy.quietWindowMillis(unlock, 50_000L));
    }

    @Test public void packageReplacementIsBoundedButNeverTreatedAsLockedBoot() {
        StartupLoadPolicy.Trigger replacement = StartupLoadPolicy.Trigger.PACKAGE_REPLACED;
        assertEquals(750L, StartupLoadPolicy.quietWindowMillis(replacement, 50_000L));
        assertTrue(StartupLoadPolicy.schedulesIntegrationHost(replacement));
        assertTrue(StartupLoadPolicy.schedulesClimate(replacement));
        assertFalse(StartupLoadPolicy.opensCredentialGate(replacement));
    }

    @Test public void staleOrCorruptDeadlinesCannotFreezeStartup() {
        long now = 1_000_000L;
        assertEquals(4_000L,
                StartupLoadPolicy.remainingQuietMillis(now, now + 4_000L));
        assertEquals(0L,
                StartupLoadPolicy.remainingQuietMillis(now, now - 1L));
        assertEquals(0L, StartupLoadPolicy.remainingQuietMillis(
                now, now + StartupLoadPolicy.MAX_VALID_QUIET_MS + 1L));
    }

    @Test public void vendorFallbackAndMediaCannotJoinTheBootBurst() {
        assertEquals(4_500L, StartupLoadPolicy.COLD_BOOT_RUNTIME_TARGET_ELAPSED_MS);
        assertTrue(StartupLoadPolicy.LAUNCHER_RUNTIME_AFTER_HOST_MS > 0L);
        assertTrue(StartupLoadPolicy.CLIMATE_AFTER_HOST_MS
                > StartupLoadPolicy.LAUNCHER_RUNTIME_AFTER_HOST_MS);
        assertTrue(StartupLoadPolicy.MEDIA_AUTO_RESUME_MIN_MS
                > StartupLoadPolicy.CLIMATE_AFTER_HOST_MS);
        assertTrue(StartupLoadPolicy.HUD_FALLBACK_DELAY_MS
                > StartupLoadPolicy.MEDIA_AUTO_RESUME_MIN_MS);
    }

    @Test public void earlyHomeAndBootGenerationCannotBypassTheRuntimeQuietLane() {
        assertEquals(4_500L, StartupLoadPolicy.earlyBootQuietMillis(0L));
        assertEquals(1L, StartupLoadPolicy.earlyBootQuietMillis(4_499L));
        assertEquals(0L, StartupLoadPolicy.earlyBootQuietMillis(4_500L));
        assertTrue(StartupLoadPolicy.isNewBootGeneration(41, 40));
        assertFalse(StartupLoadPolicy.isNewBootGeneration(41, 41));
        assertFalse(StartupLoadPolicy.isNewBootGeneration(-1, 41));
    }

    @Test public void lateBootBroadcastDoesNotAddAnotherFixedColdBootDelay() {
        assertEquals(1_000L, StartupLoadPolicy.quietWindowMillis(
                StartupLoadPolicy.Trigger.BOOT_COMPLETED, 4_000L));
        assertEquals(StartupLoadPolicy.BOOT_EVENT_SETTLE_MS,
                StartupLoadPolicy.quietWindowMillis(
                        StartupLoadPolicy.Trigger.BOOT_COMPLETED, 20_000L));
        assertEquals(StartupLoadPolicy.QUICK_BOOT_QUIET_MS,
                StartupLoadPolicy.quietWindowMillis(
                        StartupLoadPolicy.Trigger.QUICK_BOOT, 2_000_000L));
    }

    @Test public void visibleSurfacesLeadButHeavyLanesRemainSeparated() {
        assertEquals(400L, StartupLoadPolicy.MAIN_PROCESS_SETTLE_MS);
        assertEquals(2_500L, StartupLoadPolicy.LAUNCHER_RUNTIME_AFTER_HOST_MS);
        assertTrue(StartupLoadPolicy.CLIMATE_AFTER_HOST_MS
                > StartupLoadPolicy.LAUNCHER_RUNTIME_AFTER_HOST_MS);
    }

    @Test public void phaseLaneDeadlinesUseMonotonicBoundedDurations() {
        long now = 250_000L;
        assertEquals(26_000L,
                StartupLoadPolicy.remainingStartupLaneMillis(now, now + 26_000L));
        assertEquals(0L,
                StartupLoadPolicy.remainingStartupLaneMillis(now, now - 1L));
        assertEquals(0L, StartupLoadPolicy.remainingStartupLaneMillis(now,
                now + StartupLoadPolicy.MAX_VALID_STARTUP_LANE_MS + 1L));
    }
}
