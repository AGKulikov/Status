/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher.information;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dezz.status.widget.integration.ConnectorType;
import dezz.status.widget.integration.ConnectorValue;
import dezz.status.widget.integration.SourceBinding;

public final class PhoneInformationSourcePolicyTest {
    @Test public void objectResourcesUseReadableScalarFields() {
        assertEquals("@value.app_name",
                PhoneInformationSourcePolicy.valuePath("notifications.latest"));
        assertEquals("@value.display",
                PhoneInformationSourcePolicy.valuePath("messages.latest"));
        assertEquals("@value.name",
                PhoneInformationSourcePolicy.valuePath("diagnostics.last_app"));
        assertEquals("", PhoneInformationSourcePolicy.valuePath("battery.level"));
    }

    @Test public void staticCatalogPreservesLegacyChoicesAndWorksWithoutLiveSnapshot() {
        List<PhoneInformationSourcePolicy.Source> sources =
                PhoneInformationSourcePolicy.catalog();

        assertTrue(sources.size() >= 26);
        assertSource(sources, "connected", "", "iPhone подключён", "boolean", "");
        assertSource(sources, "battery.charging_source", "",
                "Источник статуса зарядки", "string", "");
        assertSource(sources, "battery.external_power", "",
                "Внешнее питание iPhone", "boolean", "");
        assertSource(sources, "call.state", "",
                "Состояние звонка iPhone", "string", "");
        assertSource(sources, "voice_assistant.active", "",
                "Голосовой ассистент iPhone активен", "boolean", "");
        assertSource(sources, "notifications.latest", "@value.app_name",
                "Последнее уведомление", "string", "");
        assertSource(sources, "messages.latest", "@value.display",
                "Последнее сообщение", "string", "");
        assertSource(sources, "diagnostics.last_app", "@value.name",
                "Последнее приложение (диагностика)", "string", "");
        assertSource(sources, "diagnostics.ancs", "",
                "Состояние Apple ANCS", "string", "");
    }

    @Test public void notificationObjectIsExposedAsIndependentRequestedStatuses() {
        List<PhoneInformationSourcePolicy.Source> sources =
                PhoneInformationSourcePolicy.catalog();

        assertSource(sources, "notifications.latest", "@value.application",
                "Приложение последнего уведомления", "string", "");
        assertSource(sources, "notifications.latest", "@value.topic",
                "Тема последнего уведомления", "string", "");
        assertSource(sources, "notifications.latest", "@value.text",
                "Текст последнего уведомления", "string", "");
        assertSource(sources, "notifications.latest", "@value.category",
                "Категория последнего уведомления", "string", "");
        assertSource(sources, "notifications.latest", "@value.date",
                "Дата последнего уведомления", "string", "");
        assertSource(sources, "notifications.latest", "@value.received_at",
                "Время получения последнего уведомления", "number", "мс");
    }

    @Test public void latestMessageObjectExposesUsefulScalarFields() {
        List<PhoneInformationSourcePolicy.Source> sources =
                PhoneInformationSourcePolicy.catalog();

        assertSource(sources, "messages.latest", "@value.sender",
                "Отправитель последнего сообщения", "string", "");
        assertSource(sources, "messages.latest", "@value.body",
                "Текст последнего сообщения", "string", "");
        assertSource(sources, "messages.latest", "@value.date",
                "Дата последнего сообщения", "number", "мс");
        assertSource(sources, "messages.latest", "@value.read",
                "Последнее сообщение прочитано", "boolean", "");
    }

    @Test public void transportObjectsAndCollectionsAreNotOneLineTiles() {
        assertFalse(PhoneInformationSourcePolicy.selectable("diagnostics.device"));
        assertFalse(PhoneInformationSourcePolicy.selectable("notifications.items"));
        assertTrue(PhoneInformationSourcePolicy.selectable("diagnostics.last_app"));
    }

    @Test public void oldEmptyPhoneBindingMigratesWithoutTouchingExplicitPaths() {
        SourceBinding old = phone("notifications.latest", "");
        SourceBinding migrated = PhoneInformationSourcePolicy.migrate(old);
        assertEquals("@value.app_name", migrated.valuePath);

        SourceBinding explicit = phone("notifications.latest", "attributes.custom");
        assertSame(explicit, PhoneInformationSourcePolicy.migrate(explicit));
        SourceBinding scalar = phone("battery.level", "");
        assertSame(scalar, PhoneInformationSourcePolicy.migrate(scalar));
    }

    @Test public void pickerPreviewResolvesTheSameScalarAsSavedBinding() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("app_name", "Telegram");
        raw.put("icon", "chat");
        ConnectorValue value = ConnectorValue.current(ConnectorType.PHONE, "default",
                "notifications.latest", raw, true, true, false,
                "object", "", Collections.emptyMap());

        assertEquals("Telegram", PhoneInformationSourcePolicy.displayValue(value));
        assertEquals("Telegram",
                value.resolveValue(PhoneInformationSourcePolicy.valuePath(value.resourceId)));
    }

    @Test public void explicitNotificationFieldsResolveWithoutSubtitleFallbacks() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("application", "Дом");
        raw.put("topic", "Дом");
        raw.put("text", "Ворота в комнате «Улица» закрыты");
        raw.put("subtitle", "Не использовать");
        ConnectorValue value = ConnectorValue.current(ConnectorType.PHONE, "default",
                "notifications.latest", raw, true, true, false,
                "object", "", Collections.emptyMap());

        assertEquals("Дом",
                PhoneInformationSourcePolicy.displayValue(value, "@value.application"));
        assertEquals("Дом",
                PhoneInformationSourcePolicy.displayValue(value, "@value.topic"));
        assertEquals("Ворота в комнате «Улица» закрыты",
                PhoneInformationSourcePolicy.displayValue(value, "@value.text"));
    }

    @Test public void privacySafeMessageDisplayDoesNotRequireSenderOrBody() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("display", "Сообщения");
        raw.put("icon", "messages");
        ConnectorValue value = ConnectorValue.current(ConnectorType.PHONE, "default",
                "messages.latest", raw, true, true, false,
                "object", "", Collections.emptyMap());

        assertEquals("Сообщения", PhoneInformationSourcePolicy.displayValue(value));
    }

    @Test public void technicalPhoneCodesAreLocalizedForInformationTiles() {
        ConnectorValue chargingSource = ConnectorValue.current(
                ConnectorType.PHONE, "default", "battery.charging_source",
                "ecarx_trend", true, true, false,
                "string", "", Collections.emptyMap());
        ConnectorValue call = ConnectorValue.current(
                ConnectorType.PHONE, "default", "call.state",
                "incoming", true, true, false,
                "string", "", Collections.emptyMap());
        ConnectorValue metadata = ConnectorValue.current(
                ConnectorType.PHONE, "default", "battery.charging_source",
                "android_metadata", true, true, false,
                "string", "", Collections.emptyMap());

        assertEquals("Оценка по ECARX",
                PhoneInformationSourcePolicy.displayValue(chargingSource, ""));
        assertEquals("Входящий",
                PhoneInformationSourcePolicy.displayValue(call, ""));
        assertEquals("Метаданные Android",
                PhoneInformationSourcePolicy.displayValue(metadata, ""));
    }

    private static SourceBinding phone(String resourceId, String valuePath) {
        return new SourceBinding(ConnectorType.PHONE, "default", resourceId, valuePath,
                SourceBinding.PRESENTATION_AUTO, "");
    }

    private static void assertSource(List<PhoneInformationSourcePolicy.Source> sources,
                                     String resourceId, String valuePath, String label,
                                     String valueType, String unit) {
        for (PhoneInformationSourcePolicy.Source source : sources) {
            if (!resourceId.equals(source.resourceId)
                    || !valuePath.equals(source.valuePath)) continue;
            assertEquals(label, source.label);
            assertEquals(valueType, source.valueType);
            assertEquals(unit, source.unit);
            return;
        }
        throw new AssertionError("Missing PHONE source " + resourceId + " " + valuePath);
    }
}
