/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.popup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import dezz.status.widget.integration.ConnectorType;
import dezz.status.widget.integration.SourceBinding;

public final class SmartHomeTileColorPolicyTest {
    @Test public void allSmartHomeConnectorsUseTheLiveStateColour() {
        for (ConnectorType type : new ConnectorType[]{
                ConnectorType.HOME_ASSISTANT, ConnectorType.SPRUTHUB, ConnectorType.MQTT}) {
            SourceBinding binding = binding(type);
            assertTrue(SmartHomeTileColorPolicy.applies(binding));
            assertEquals("#FFFFB300", SmartHomeTileColorPolicy.contentColor(
                    binding, "#FFFFFFFF", "#FFFFB300"));
        }
    }

    @Test public void phoneAndStaticTilesKeepTheirConfiguredColours() {
        SourceBinding phone = binding(ConnectorType.PHONE);
        assertFalse(SmartHomeTileColorPolicy.applies(phone));
        assertEquals("#FF00AAFF", SmartHomeTileColorPolicy.contentColor(
                phone, "#FF00AAFF", "#FFFFB300"));
        assertEquals("#FF00AAFF", SmartHomeTileColorPolicy.contentColor(
                SourceBinding.unbound(), "#FF00AAFF", "#FFFFB300"));
    }

    private static SourceBinding binding(ConnectorType type) {
        return new SourceBinding(type, SourceBinding.DEFAULT_CONNECTOR_ID,
                "device.value", "", SourceBinding.PRESENTATION_RAW, "");
    }
}
