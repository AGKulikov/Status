package dezz.status.widget.launcher;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SmartHomeIconResolverTest {
    @Test public void homeAssistantDomainAndDeviceClassSelectSemanticIcon() {
        assertEquals("temperature", SmartHomeIconResolver.suggest(
                "sensor", "temperature", "", "Улица"));
        assertEquals("motion", SmartHomeIconResolver.suggest(
                "binary_sensor", "motion", "", "Коридор"));
        assertEquals("garage", SmartHomeIconResolver.suggest(
                "cover", "garage_door", "", "Въезд"));
    }

    @Test public void sprutAndMqttNamesUseSameHeuristic() {
        assertEquals("thermostat", SmartHomeIconResolver.suggest(
                "", "", "Thermostat", "Конвектор"));
        assertEquals("water", SmartHomeIconResolver.suggest(
                "", "", "bool", "Котельная протечка"));
        assertEquals("battery", SmartHomeIconResolver.suggest(
                "", "", "number", "Датчик battery"));
    }

    @Test public void unknownDeviceKeepsGenericSmartHomeIcon() {
        assertEquals("devices", SmartHomeIconResolver.suggest("sensor", "", "", "abc"));
    }
}
