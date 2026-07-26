/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dezz.status.widget.integration.ConnectorType;
import dezz.status.widget.integration.ConnectorValue;

public final class PhoneStatusBarPolicyTest {
    @Test public void scalarCatalogHasStableOrderIdsPathsAndRussianLabels() {
        List<PhoneStatusBarPolicy.StatusItem> items =
                PhoneStatusBarPolicy.statusItems();
        assertEquals(Arrays.asList(
                        "connected",
                        "battery.level",
                        "battery.charging",
                        "network.available",
                        "network.operator",
                        "network.type",
                        "network.signal",
                        "network.roaming",
                        "notifications.count",
                        "messages.unread",
                        "diagnostics.ancs",
                        "diagnostics.sms",
                        "diagnostics.last_error"),
                PhoneStatusBarPolicy.statusIds());
        assertEquals(items.size(), PhoneStatusBarPolicy.statusIds().size());
        for (PhoneStatusBarPolicy.StatusItem item : items) {
            assertEquals(item.resourceId, item.id);
            assertEquals("", item.valuePath);
            assertTrue(!item.label.trim().isEmpty());
            assertEquals(item, PhoneStatusBarPolicy.statusItem(item.id));
        }
        assertEquals("iPhone подключён", items.get(0).label);
        assertEquals("Ошибка подключения iPhone", items.get(items.size() - 1).label);
        assertThrows(UnsupportedOperationException.class,
                () -> items.add(items.get(0)));
    }

    @Test public void notificationFieldCatalogAndCsvAreCanonicalAndFailClosed() {
        List<PhoneStatusBarPolicy.NotificationField> fields =
                PhoneStatusBarPolicy.notificationFields();
        assertEquals(Arrays.asList("application", "topic", "text"),
                PhoneStatusBarPolicy.notificationFieldIds());
        assertEquals("Приложение", fields.get(0).label);
        assertEquals("@value.topic", fields.get(1).valuePath);

        Set<String> parsed = PhoneStatusBarPolicy.parseIds(
                " text,unknown,application,text,TOPIC,topic ",
                PhoneStatusBarPolicy.notificationFieldIds());
        assertEquals(new LinkedHashSet<>(Arrays.asList("application", "topic", "text")),
                parsed);
        assertEquals("application,topic,text",
                PhoneStatusBarPolicy.serializeIds(parsed,
                        PhoneStatusBarPolicy.notificationFieldIds()));
        assertEquals("", PhoneStatusBarPolicy.serializeIds(
                Arrays.asList("unknown", null),
                PhoneStatusBarPolicy.notificationFieldIds()));
        assertThrows(UnsupportedOperationException.class, () -> parsed.add("unknown"));
    }

    @Test public void scalarFormattingIsStrictBoundedAndOneLine() {
        assertEquals("iPhone подключён", display("connected", true));
        assertEquals("iPhone отключён", display("connected", false));
        assertEquals("АКБ 74%", display("battery.level", 74));
        assertEquals("Сигнал 12.5%", display("network.signal", 12.5d));
        assertEquals("iPhone заряжается", display("battery.charging", true));
        assertEquals("Без роуминга", display("network.roaming", false));
        assertEquals("Увед. 3", display("notifications.count", 3L));
        assertEquals("Оператор Orange RO 5G",
                display("network.operator", " Orange\nRO\t5G "));

        assertNull(display("battery.level", 101));
        assertNull(display("notifications.count", 1.5d));
        assertNull(display("network.operator", Collections.singletonMap("x", "y")));
        assertNull(display("diagnostics.last_error", "\n\t"));
    }

    @Test public void scalarFormattingRejectsWrongOrUnavailableConnectorValues() {
        PhoneStatusBarPolicy.StatusItem connected =
                PhoneStatusBarPolicy.statusItem("connected");
        assertNotNull(connected);
        assertNull(PhoneStatusBarPolicy.display(connected,
                value(ConnectorType.HOME_ASSISTANT, "connected", true,
                        true, true, true)));
        assertNull(PhoneStatusBarPolicy.display(connected,
                value(ConnectorType.PHONE, "battery.level", true,
                        true, true, true)));
        assertNull(PhoneStatusBarPolicy.display(connected,
                value(ConnectorType.PHONE, "connected", true,
                        false, true, true)));
        assertNull(PhoneStatusBarPolicy.display(connected,
                value(ConnectorType.PHONE, "connected", true,
                        true, false, true)));
        assertNull(PhoneStatusBarPolicy.display(connected,
                value(ConnectorType.PHONE, "connected", true,
                        true, true, false)));
        assertNull(PhoneStatusBarPolicy.display("unknown", current("connected", true)));
    }

    @Test public void latestNotificationUsesStableKeyAndSelectedCanonicalFields() {
        ConnectorValue latest = latest(notificationMap(
                4_294_967_295L, 1_721_234_567_890L,
                "Дом", "Дом\n", "Ворота в комнате «Улица» закрыты"));
        PhoneStatusBarPolicy.NotificationPresentation presentation =
                PhoneStatusBarPolicy.notification(latest, new LinkedHashSet<>(
                        Arrays.asList("text", "application", "topic")));

        assertNotNull(presentation);
        assertEquals("4294967295+1721234567890", presentation.key);
        assertEquals(4_294_967_295L, presentation.uid);
        assertEquals(1_721_234_567_890L, presentation.receivedAt);
        assertEquals("Дом", presentation.application);
        assertEquals("Дом", presentation.topic);
        assertEquals("Ворота в комнате «Улица» закрыты", presentation.body);
        assertEquals("Дом · Ворота в комнате «Улица» закрыты", presentation.text);
        assertEquals("4294967295+1721234567890",
                PhoneStatusBarPolicy.notificationKey(latest.rawValue));
        assertNull(PhoneStatusBarPolicy.notificationKey(Collections.emptyMap()));
    }

    @Test public void notificationSelectionControlsApplicationTopicAndBody() {
        ConnectorValue latest = latest(notificationMap(
                42L, 99L, "Маркет", "Встречают по обувке", "Пенки и щётки"));

        PhoneStatusBarPolicy.NotificationPresentation textOnly =
                PhoneStatusBarPolicy.notification(latest,
                        Collections.singleton("text"));
        assertNotNull(textOnly);
        assertEquals("", textOnly.application);
        assertEquals("", textOnly.topic);
        assertEquals("Пенки и щётки", textOnly.body);
        assertEquals("Пенки и щётки", textOnly.text);

        PhoneStatusBarPolicy.NotificationPresentation appOnly =
                PhoneStatusBarPolicy.notification(latest,
                        Collections.singleton("application"));
        assertNotNull(appOnly);
        assertEquals("Маркет", appOnly.application);
        assertEquals("", appOnly.text);
    }

    @Test public void notificationRejectsStaleUnavailableWrongAndIncompleteValues() {
        Map<String, Object> complete = notificationMap(
                7L, 100L, "Дом", "Дом", "Закрыто");
        Set<String> all = new LinkedHashSet<>(
                PhoneStatusBarPolicy.notificationFieldIds());
        assertNull(PhoneStatusBarPolicy.notification(
                value(ConnectorType.PHONE, "notifications.latest", complete,
                        false, true, true), all));
        assertNull(PhoneStatusBarPolicy.notification(
                value(ConnectorType.PHONE, "notifications.latest", complete,
                        true, false, true), all));
        assertNull(PhoneStatusBarPolicy.notification(
                value(ConnectorType.PHONE, "notifications.latest", complete,
                        true, true, false), all));
        assertNull(PhoneStatusBarPolicy.notification(
                value(ConnectorType.HOME_ASSISTANT, "notifications.latest", complete,
                        true, true, true), all));
        assertNull(PhoneStatusBarPolicy.notification(
                current("notifications.items", complete), all));
        assertNull(PhoneStatusBarPolicy.notification(latest(complete),
                Collections.emptySet()));

        for (String missing : Arrays.asList(
                "uid", "received_at", "application")) {
            Map<String, Object> incomplete = new LinkedHashMap<>(complete);
            incomplete.remove(missing);
            assertNull("Missing " + missing,
                    PhoneStatusBarPolicy.notification(latest(incomplete), all));
        }
        Map<String, Object> privacySafe = new LinkedHashMap<>(complete);
        privacySafe.remove("topic");
        privacySafe.remove("text");
        PhoneStatusBarPolicy.NotificationPresentation applicationOnly =
                PhoneStatusBarPolicy.notification(latest(privacySafe), all);
        assertNotNull(applicationOnly);
        assertEquals("Дом", applicationOnly.application);
        assertEquals("", applicationOnly.text);
        Map<String, Object> legacyAliases = new LinkedHashMap<>();
        legacyAliases.put("uid", 1L);
        legacyAliases.put("received_at", 2L);
        legacyAliases.put("app_name", "Дом");
        legacyAliases.put("title", "Дом");
        legacyAliases.put("message", "Закрыто");
        assertNull(PhoneStatusBarPolicy.notification(latest(legacyAliases), all));
    }

    private static String display(String resourceId, Object raw) {
        return PhoneStatusBarPolicy.display(resourceId, current(resourceId, raw));
    }

    private static ConnectorValue current(String resourceId, Object raw) {
        return value(ConnectorType.PHONE, resourceId, raw, true, true, true);
    }

    private static ConnectorValue latest(Map<String, Object> raw) {
        return current("notifications.latest", raw);
    }

    private static ConnectorValue value(ConnectorType type, String resourceId, Object raw,
                                        boolean fresh, boolean available, boolean readable) {
        return new ConnectorValue(type, "default", resourceId, raw, fresh, available,
                readable, false, "", "", Collections.emptyMap(), 1L);
    }

    private static Map<String, Object> notificationMap(long uid, long receivedAt,
                                                       String application, String topic,
                                                       String text) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("uid", uid);
        result.put("received_at", receivedAt);
        result.put("application", application);
        result.put("topic", topic);
        result.put("text", text);
        return result;
    }
}
