/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher.information;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import dezz.status.widget.integration.ConnectorType;
import dezz.status.widget.integration.SourceBinding;

public final class InformationPanelConfigStoreTest {
    @Test public void roundTripPreservesMixedSourcesAndGridAppearance() throws Exception {
        InformationPanelConfig source = new InformationPanelConfig();
        source.columns = 5;
        source.rows = 4;
        source.backgroundColor = "#123456";
        source.backgroundAlpha = 177;

        InformationPanelConfig.Item vehicle = InformationPanelConfig.Item.vehicle(
                "ISensor.ambient_temp", "Температура снаружи", "°C", "temperature");
        source.add(vehicle);
        InformationPanelConfig.Item placedVehicle = source.find(vehicle.id);
        placedVehicle.column = 2;
        placedVehicle.row = 1;
        placedVehicle.columnSpan = 2;
        placedVehicle.scalePercent = 145;
        placedVehicle.visibility = InformationPanelConfig.Visibility.WHEN_KNOWN;

        SourceBinding binding = new SourceBinding(ConnectorType.HOME_ASSISTANT, "default",
                "sensor.garage_temperature", "attributes.temperature",
                SourceBinding.PRESENTATION_TEMPERATURE, "°C");
        InformationPanelConfig.Item smartHome = InformationPanelConfig.Item.connector(
                binding, "Температура гаража", "°C", "sensor temperature");
        smartHome.iconKey = "temperature";
        smartHome.labelOverride = "Гараж";
        smartHome.showLabel = false;
        source.add(smartHome);

        InformationPanelConfig decoded = InformationPanelConfigStore.decode(
                InformationPanelConfigStore.encode(source).toString());

        assertEquals(5, decoded.columns);
        assertEquals(4, decoded.rows);
        assertEquals("#123456", decoded.backgroundColor);
        assertEquals(177, decoded.backgroundAlpha);
        assertEquals(2, decoded.mutableItems().size());
        InformationPanelConfig.Item restoredVehicle = decoded.find(vehicle.id);
        assertNotNull(restoredVehicle);
        assertEquals(2, restoredVehicle.column);
        assertEquals(1, restoredVehicle.row);
        assertEquals(2, restoredVehicle.columnSpan);
        assertEquals(145, restoredVehicle.scalePercent);
        assertEquals(InformationPanelConfig.Visibility.WHEN_KNOWN,
                restoredVehicle.visibility);
        InformationPanelConfig.Item restoredSmartHome = decoded.find(smartHome.id);
        assertNotNull(restoredSmartHome);
        assertEquals(binding, restoredSmartHome.binding);
        assertEquals("Гараж", restoredSmartHome.labelOverride);
        assertEquals("temperature", restoredSmartHome.iconKey);
        assertFalse(restoredSmartHome.showLabel);
    }

    @Test public void normalizeClampsGeometryAndDropsDuplicateIds() {
        InformationPanelConfig config = new InformationPanelConfig();
        config.columns = 99;
        config.rows = -2;
        InformationPanelConfig.Item first = InformationPanelConfig.Item.restored(
                "duplicate", InformationPanelConfig.SourceKind.SYSTEM, "system.time", null,
                "Время", "", "clock");
        first.column = 99;
        first.row = 99;
        first.columnSpan = 99;
        first.rowSpan = 99;
        first.scalePercent = 999;
        InformationPanelConfig.Item second = first.copy();
        config.mutableItems().add(first);
        config.mutableItems().add(second);

        config.normalize();

        assertEquals(8, config.columns);
        assertEquals(1, config.rows);
        assertEquals(1, config.mutableItems().size());
        InformationPanelConfig.Item remaining = config.mutableItems().get(0);
        assertEquals(0, remaining.row);
        assertEquals(0, remaining.column);
        assertEquals(8, remaining.columnSpan);
        assertEquals(1, remaining.rowSpan);
        assertEquals(InformationPanelConfig.MAX_SCALE, remaining.scalePercent);
    }

    @Test public void fullGridGrowsInsteadOfOverlappingNewStatus() {
        InformationPanelConfig config = new InformationPanelConfig();
        config.columns = 1;
        config.rows = 1;
        InformationPanelConfig.Item first = InformationPanelConfig.Item.system(
                "system.time", "Время", "", "clock");
        InformationPanelConfig.Item second = InformationPanelConfig.Item.system(
                "system.date", "Дата", "", "date");

        config.add(first);
        config.add(second);

        assertEquals(2, config.rows);
        assertEquals(0, config.find(first.id).row);
        assertEquals(1, config.find(second.id).row);
        assertTrue(config.find(second.id).enabled);
    }

    @Test public void manualCollisionRelocatesLaterItemDeterministically() {
        InformationPanelConfig config = new InformationPanelConfig();
        config.columns = 2;
        config.rows = 1;
        InformationPanelConfig.Item first = InformationPanelConfig.Item.system(
                "system.time", "Время", "", "clock");
        InformationPanelConfig.Item second = InformationPanelConfig.Item.system(
                "system.date", "Дата", "", "date");
        first.column = 0;
        first.row = 0;
        second.column = 0;
        second.row = 0;
        config.mutableItems().add(first);
        config.mutableItems().add(second);

        config.normalize();

        assertEquals(0, config.find(first.id).column);
        assertEquals(0, config.find(first.id).row);
        assertEquals(1, config.find(second.id).column);
        assertEquals(0, config.find(second.id).row);
    }

    @Test public void invalidOrFutureJsonFallsBackWithoutPhantomBindings() {
        InformationPanelConfig malformed = InformationPanelConfigStore.decode(
                "{\"version\":1,\"items\":[{\"id\":\"x\",\"sourceKind\":\"CONNECTOR\","
                        + "\"sourceLabel\":\"bad\",\"binding\":{\"connectorType\":\"NOPE\","
                        + "\"resourceId\":\"sensor.x\"}}]}");
        assertEquals(1, malformed.mutableItems().size());
        assertNull(malformed.mutableItems().get(0).binding);
        assertFalse(malformed.mutableItems().get(0).enabled);

        InformationPanelConfig future = InformationPanelConfigStore.decode(
                "{\"version\":99,\"items\":[{\"id\":\"x\"}]}");
        assertTrue(future.mutableItems().isEmpty());
    }
}
