/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher.information;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import dezz.status.widget.integration.ConnectorType;
import dezz.status.widget.integration.SourceBinding;

public final class InformationIconPolicyTest {
    @Test public void autoIconFollowsInternalAndSmartHomeSemantics() {
        assertEquals("battery", InformationIconPolicy.resolve(
                InformationPanelConfig.Item.system("system.battery.level",
                        "Заряд магнитолы", "%", "battery")));
        assertEquals("temperature", InformationIconPolicy.resolve(
                InformationPanelConfig.Item.vehicle("ISensor.ambient_temp",
                        "Температура снаружи", "°C", "temperature")));
        assertEquals("garage", InformationIconPolicy.resolve(
                InformationPanelConfig.Item.connector(
                        new SourceBinding(ConnectorType.HOME_ASSISTANT, "default",
                                "cover.garage", "", SourceBinding.PRESENTATION_COVER, ""),
                        "Гаражные ворота", "", "garage_door")));
    }

    @Test public void explicitIconAlwaysWinsOverSemanticSuggestion() {
        InformationPanelConfig.Item item = InformationPanelConfig.Item.system(
                "system.battery.level", "Батарея", "%", "battery");
        item.iconKey = "car";
        assertEquals("car", InformationIconPolicy.resolve(item));
    }

    @Test public void phoneSourcesUsePhoneBatteryAndNotificationIcons() {
        assertEquals("phone", InformationIconPolicy.resolve(
                InformationPanelConfig.Item.connector(
                        phone("connected"), "iPhone подключён", "", "boolean")));
        assertEquals("battery", InformationIconPolicy.resolve(
                InformationPanelConfig.Item.connector(
                        phone("battery.level"), "Заряд iPhone", "%", "number")));
        assertEquals("wifi", InformationIconPolicy.resolve(
                InformationPanelConfig.Item.connector(
                        phone("network.type"), "Сеть iPhone", "", "string")));
        assertEquals("bluetooth", InformationIconPolicy.resolve(
                InformationPanelConfig.Item.system(
                        "system.bluetooth", "Bluetooth", "", "bluetooth connection")));
        assertEquals("notification", InformationIconPolicy.resolve(
                InformationPanelConfig.Item.connector(
                        phone("diagnostics.last_app"), "Последнее приложение", "", "object")));
    }

    private static SourceBinding phone(String resourceId) {
        return new SourceBinding(ConnectorType.PHONE, "default", resourceId, "",
                SourceBinding.PRESENTATION_AUTO, "");
    }
}
