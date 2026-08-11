/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Truth table for the shared foreground integration-host lifecycle. */
public final class WidgetServiceStarterPolicyTest {
    @Test
    public void phoneConnectorAloneRequiresAHeadlessHost() {
        assertTrue(WidgetServiceStarter.requiresHeadlessHost(false, false, true));
        assertTrue(WidgetServiceStarter.requiresIntegrationHost(
                false, false, false, true));
    }

    @Test
    public void eachIndependentSurfaceRequiresTheSameHeadlessHost() {
        assertTrue(WidgetServiceStarter.requiresHeadlessHost(true, false, false));
        assertTrue(WidgetServiceStarter.requiresHeadlessHost(false, true, false));
        assertTrue(WidgetServiceStarter.requiresIntegrationHost(
                false, true, false, false));
        assertTrue(WidgetServiceStarter.requiresIntegrationHost(
                false, false, true, false));
    }

    @Test
    public void widgetAloneRequiresAHostButNotTheHeadlessPermissionBypass() {
        assertFalse(WidgetServiceStarter.requiresHeadlessHost(false, false, false));
        assertTrue(WidgetServiceStarter.requiresIntegrationHost(
                true, false, false, false));
    }

    @Test
    public void directConnectorsKeepTheServiceAliveWithoutTheStatusRow() {
        assertTrue(WidgetServiceStarter.requiresHeadlessHost(
                false, false, false, false, true, false));
        assertTrue(WidgetServiceStarter.requiresIntegrationHost(
                false, false, false, false, false, true, false));
        assertTrue(WidgetServiceStarter.requiresIntegrationHost(
                false, false, false, false, false, false, true));
    }

    @Test
    public void serviceIsNotRequiredWhenEveryConsumerIsDisabled() {
        assertFalse(WidgetServiceStarter.requiresIntegrationHost(
                false, false, false, false));
        assertFalse(WidgetServiceStarter.requiresIntegrationHost(
                false, false, false, false, false, false, false));
    }

    @Test
    public void manualOnlyHudDoesNotWakeTheAutomaticBootHost() {
        assertFalse(WidgetServiceStarter.requiresAutomaticHeadlessHost(
                false, true, false, false, false, false, false));
        assertFalse(WidgetServiceStarter.requiresAutomaticIntegrationHost(
                false, false, true, false, false, false, false, false));
        assertTrue(WidgetServiceStarter.requiresAutomaticIntegrationHost(
                false, false, true, true, false, false, false, false));
        // Explicit/manual runtime semantics remain unchanged.
        assertTrue(WidgetServiceStarter.requiresHeadlessHost(false, true, false));
    }
}
