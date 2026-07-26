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
    public void serviceIsNotRequiredWhenEveryConsumerIsDisabled() {
        assertFalse(WidgetServiceStarter.requiresIntegrationHost(
                false, false, false, false));
    }
}
