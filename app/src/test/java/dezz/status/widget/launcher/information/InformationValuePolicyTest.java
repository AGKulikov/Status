/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher.information;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class InformationValuePolicyTest {
    @Test public void connectorRequiresFreshAvailableReadableNonNullValue() {
        assertTrue(InformationValuePolicy.isConnectorKnown(true, true, true, 21.5));
        assertFalse(InformationValuePolicy.isConnectorKnown(false, true, true, 21.5));
        assertFalse(InformationValuePolicy.isConnectorKnown(true, false, true, 21.5));
        assertFalse(InformationValuePolicy.isConnectorKnown(true, true, false, 21.5));
        assertFalse(InformationValuePolicy.isConnectorKnown(true, true, true, null));
    }

    @Test public void visibilityModesNeverTreatUnknownAsActiveOrInactive() {
        assertTrue(InformationValuePolicy.isVisible(
                InformationPanelConfig.Visibility.ALWAYS, false, false));
        assertFalse(InformationValuePolicy.isVisible(
                InformationPanelConfig.Visibility.WHEN_KNOWN, false, false));
        assertTrue(InformationValuePolicy.isVisible(
                InformationPanelConfig.Visibility.WHEN_KNOWN, true, false));
        assertFalse(InformationValuePolicy.isVisible(
                InformationPanelConfig.Visibility.WHEN_ACTIVE, false, true));
        assertTrue(InformationValuePolicy.isVisible(
                InformationPanelConfig.Visibility.WHEN_ACTIVE, true, true));
        assertFalse(InformationValuePolicy.isVisible(
                InformationPanelConfig.Visibility.WHEN_INACTIVE, false, false));
        assertTrue(InformationValuePolicy.isVisible(
                InformationPanelConfig.Visibility.WHEN_INACTIVE, true, false));
    }

    @Test public void activityUnderstandsBooleanNumericAndCommonDeviceStates() {
        assertTrue(InformationValuePolicy.isActive(true));
        assertTrue(InformationValuePolicy.isActive(1));
        assertTrue(InformationValuePolicy.isActive("open"));
        assertTrue(InformationValuePolicy.isActive("playing"));
        assertFalse(InformationValuePolicy.isActive(false));
        assertFalse(InformationValuePolicy.isActive(0));
        assertFalse(InformationValuePolicy.isActive("off"));
        assertFalse(InformationValuePolicy.isActive("closed"));
        assertFalse(InformationValuePolicy.isActive("unknown"));
        assertFalse(InformationValuePolicy.isActive(null));
    }
}
