/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dezz.status.widget.integration.ConnectorType;
import dezz.status.widget.integration.ConnectorValue;

/**
 * Android-independent catalog and presentation policy for the PHONE status-row brick.
 *
 * <p>The connector remains the sole owner of freshness and availability. This class accepts only
 * current, readable values with the exact expected resource/type and converts them to bounded
 * single-line text. Missing legacy fields are never guessed from aliases.</p>
 */
public final class PhoneStatusBarPolicy {
    private static final String LATEST_NOTIFICATION = "notifications.latest";
    private static final long MAX_ANCS_UID = 0xffff_ffffL;
    private static final int MAX_STATUS_TEXT_CODE_POINTS = 320;
    private static final int MAX_APPLICATION_CODE_POINTS = 256;
    private static final int MAX_NOTIFICATION_TEXT_CODE_POINTS = 1_024;

    public static final String FIELD_APPLICATION = "application";
    public static final String FIELD_TOPIC = "topic";
    public static final String FIELD_TEXT = "text";

    private static final List<StatusItem> STATUS_ITEMS = Collections.unmodifiableList(
            Arrays.asList(
                    status("connected", "iPhone подключён", "", Kind.CONNECTION),
                    status("device.name", "Имя iPhone", "iPhone", Kind.TEXT),
                    status("profiles.hfp", "Телефонный профиль HFP", "HFP", Kind.PROFILE),
                    status("profiles.map", "Сообщения MAP", "MAP", Kind.PROFILE),
                    status("profiles.ble", "Соединение BLE", "BLE", Kind.PROFILE),
                    status("profiles.ancs", "Apple ANCS готов", "ANCS", Kind.PROFILE),
                    status("battery.level", "Заряд iPhone", "АКБ", Kind.PERCENT),
                    status("battery.level_source", "Источник уровня заряда",
                            "Источник АКБ", Kind.CODE),
                    status("battery.charging", "Зарядка iPhone", "", Kind.CHARGING),
                    status("battery.charging_estimated", "Точность статуса зарядки",
                            "Статус зарядки", Kind.ESTIMATED),
                    status("battery.charging_source", "Источник статуса зарядки",
                            "Источник зарядки", Kind.CODE),
                    status("battery.external_power", "Внешнее питание iPhone",
                            "", Kind.POWER),
                    status("battery.charge_state", "Состояние батареи iPhone",
                            "АКБ", Kind.CODE),
                    status("battery.charge_level", "Оценка заряда iPhone",
                            "АКБ", Kind.CODE),
                    status("network.available", "Сеть iPhone", "", Kind.NETWORK),
                    status("network.operator", "Оператор iPhone", "Оператор", Kind.TEXT),
                    status("network.type", "Тип сети iPhone", "Сеть", Kind.TEXT),
                    status("network.signal", "Сигнал сети iPhone", "Сигнал", Kind.PERCENT),
                    status("network.roaming", "Роуминг iPhone", "", Kind.ROAMING),
                    status("call.active", "Активный звонок", "", Kind.CALL),
                    status("call.state", "Состояние звонка", "Звонок", Kind.CODE),
                    status("call.direction", "Направление звонка", "Звонок", Kind.CODE),
                    status("call.multiparty", "Конференц-связь",
                            "Конференция", Kind.BOOLEAN),
                    status("call.audio", "Аудио звонка", "", Kind.CALL_AUDIO),
                    status("call.audio_state", "Состояние аудио звонка",
                            "Аудио", Kind.CODE),
                    status("call.audio_wideband", "Широкополосное аудио звонка",
                            "HD-аудио", Kind.BOOLEAN),
                    status("voice_assistant.active", "Голосовой ассистент iPhone",
                            "", Kind.VOICE),
                    status("ringtone.in_band", "Рингтон передаётся с iPhone",
                            "Рингтон с iPhone", Kind.BOOLEAN),
                    status("notifications.count", "Количество уведомлений", "Увед.", Kind.COUNT),
                    status("messages.unread", "Непрочитанные сообщения", "SMS", Kind.COUNT),
                    status("diagnostics.ancs", "Состояние Apple ANCS", "ANCS", Kind.TEXT),
                    status("diagnostics.sms", "Состояние SMS/MAP", "MAP", Kind.TEXT),
                    status("diagnostics.last_error", "Ошибка подключения iPhone", "Ошибка",
                            Kind.TEXT)
            ));

    private static final List<NotificationField> NOTIFICATION_FIELDS =
            Collections.unmodifiableList(Arrays.asList(
                    new NotificationField(FIELD_APPLICATION, "Приложение",
                            "@value.application"),
                    new NotificationField(FIELD_TOPIC, "Тема", "@value.topic"),
                    new NotificationField(FIELD_TEXT, "Текст", "@value.text")
            ));

    private static final List<String> STATUS_IDS = idsOfStatuses();
    private static final List<String> NOTIFICATION_FIELD_IDS = idsOfNotificationFields();
    private static final Map<String, StatusItem> STATUS_BY_ID = statusesById();

    private PhoneStatusBarPolicy() {
    }

    /** Canonical display order. Item ids intentionally equal the persisted PHONE resource ids. */
    public static List<StatusItem> statusItems() {
        return STATUS_ITEMS;
    }

    /** Canonical order for the three independently selectable real-time notification fields. */
    public static List<NotificationField> notificationFields() {
        return NOTIFICATION_FIELDS;
    }

    /** Convenience view used by preference editors and {@link #parseIds}. */
    public static List<String> statusIds() {
        return STATUS_IDS;
    }

    /** Convenience view used by preference editors and {@link #parseIds}. */
    public static List<String> notificationFieldIds() {
        return NOTIFICATION_FIELD_IDS;
    }

    /**
     * Parses a comma-separated selection and returns only known ids in the supplied catalog order.
     * Unknown, empty and duplicate ids are ignored, making corrupted/old preferences fail closed.
     */
    public static Set<String> parseIds(String csv, Collection<String> orderedAllowed) {
        LinkedHashSet<String> requested = new LinkedHashSet<>();
        if (csv != null) {
            for (String token : csv.split(",", -1)) {
                String clean = cleanId(token);
                if (!clean.isEmpty()) requested.add(clean);
            }
        }
        return canonicalSelection(requested, orderedAllowed);
    }

    /**
     * Serializes a selection in the caller-supplied catalog order. Unknown and duplicate ids are
     * omitted, so a round trip always produces a deterministic safe preference value.
     */
    public static String serializeIds(Collection<String> selected,
                                      Collection<String> orderedAllowed) {
        Set<String> canonical = canonicalSelection(selected, orderedAllowed);
        StringBuilder result = new StringBuilder();
        for (String id : canonical) {
            if (result.length() > 0) result.append(',');
            result.append(id);
        }
        return result.toString();
    }

    /** Looks up a scalar item by its persisted stable id. */
    public static StatusItem statusItem(String id) {
        return STATUS_BY_ID.get(cleanId(id));
    }

    /**
     * Formats one exact PHONE value for a one-line status cell.
     *
     * @return bounded display text, or {@code null} for stale, unavailable, unreadable, malformed,
     *     wrong-connector, wrong-resource or out-of-range values
     */
    public static String display(StatusItem item, ConnectorValue value) {
        if (item == null || !eligible(value, item.resourceId)) return null;
        Object raw = value.resolveValue(item.valuePath);
        switch (item.kind) {
            case CONNECTION:
                return booleanText(raw, "iPhone подключён", "iPhone отключён");
            case CHARGING:
                return booleanText(raw, "iPhone заряжается", "iPhone не заряжается");
            case NETWORK:
                return booleanText(raw, "Сеть iPhone доступна", "Сеть iPhone недоступна");
            case ROAMING:
                return booleanText(raw, "Роуминг", "Без роуминга");
            case PROFILE:
                return booleanText(raw, item.displayPrefix + " подключён",
                        item.displayPrefix + " отключён");
            case POWER:
                return booleanText(raw, "Питание iPhone подключено",
                        "Питание iPhone не подключено");
            case ESTIMATED:
                return booleanText(raw, "Статус зарядки рассчитан",
                        "Статус зарядки получен от телефона");
            case CALL:
                return booleanText(raw, "Есть активный звонок", "Нет активного звонка");
            case CALL_AUDIO:
                return booleanText(raw, "Аудио звонка подключено",
                        "Аудио звонка отключено");
            case VOICE:
                return booleanText(raw, "Голосовой ассистент активен",
                        "Голосовой ассистент не активен");
            case BOOLEAN:
                return prefixed(item.displayPrefix,
                        booleanText(raw, "да", "нет"));
            case CODE:
                return prefixed(item.displayPrefix,
                        codeText(item.resourceId, raw));
            case PERCENT:
                return prefixed(item.displayPrefix, formatPercent(raw));
            case COUNT:
                return prefixed(item.displayPrefix, formatCount(raw));
            case TEXT:
                return prefixed(item.displayPrefix,
                        singleLine(raw, MAX_STATUS_TEXT_CODE_POINTS));
            default:
                return null;
        }
    }

    /** Convenience overload for persisted status ids. */
    public static String display(String itemId, ConnectorValue value) {
        return display(statusItem(itemId), value);
    }

    /**
     * Extracts a safe presentation of the latest real-time notification.
     *
     * <p>The stable key changes for a new/modified delivery because it combines ANCS UID with the
     * connector's local {@code received_at}. No fallback to old aliases is attempted. Canonical
     * topic/text may be absent while the privacy toggle is off; in that case an independently
     * selected application can still be presented.</p>
     *
     * @return presentation, or {@code null} when the source/selection/payload is incomplete
     */
    public static NotificationPresentation notification(
            ConnectorValue value, Set<String> selectedFieldIds) {
        if (!eligible(value, LATEST_NOTIFICATION)
                || !(value.rawValue instanceof Map<?, ?>)) {
            return null;
        }
        Set<String> selected = canonicalSelection(
                selectedFieldIds, NOTIFICATION_FIELD_IDS);
        if (selected.isEmpty()) return null;

        Map<?, ?> raw = (Map<?, ?>) value.rawValue;
        Long uid = exactLong(raw.get("uid"), 0L, MAX_ANCS_UID);
        Long receivedAt = exactLong(raw.get("received_at"), 1L, Long.MAX_VALUE);
        if (uid == null || receivedAt == null) return null;

        String application = "";
        String topic = "";
        String body = "";
        if (selected.contains(FIELD_APPLICATION)) {
            if (!raw.containsKey(FIELD_APPLICATION)) return null;
            application = singleLine(raw.get(FIELD_APPLICATION),
                    MAX_APPLICATION_CODE_POINTS);
            if (application == null) return null;
        }
        if (selected.contains(FIELD_TOPIC)) {
            // The connector intentionally omits text fields while its privacy toggle is off.
            // Keep the application-only presentation useful without guessing from legacy
            // title/subtitle aliases; a present canonical field must still have the right type.
            if (raw.containsKey(FIELD_TOPIC)) {
                topic = nullableSingleLine(raw.get(FIELD_TOPIC),
                        MAX_NOTIFICATION_TEXT_CODE_POINTS);
                if (topic == null) return null;
            }
        }
        if (selected.contains(FIELD_TEXT)) {
            if (raw.containsKey(FIELD_TEXT)) {
                body = nullableSingleLine(raw.get(FIELD_TEXT),
                        MAX_NOTIFICATION_TEXT_CODE_POINTS);
                if (body == null) return null;
            }
        }

        String combined = combine(topic, body);
        if (application.isEmpty() && combined.isEmpty()) return null;
        return new NotificationPresentation(
                uid + "+" + receivedAt,
                uid,
                receivedAt,
                application,
                topic,
                body,
                combined);
    }

    /** Stable identity used to suppress replay when removing a newer item reveals an older one. */
    public static String notificationKey(Object rawValue) {
        if (!(rawValue instanceof Map<?, ?>)) return null;
        Map<?, ?> raw = (Map<?, ?>) rawValue;
        Long uid = exactLong(raw.get("uid"), 0L, MAX_ANCS_UID);
        Long receivedAt = exactLong(raw.get("received_at"), 1L, Long.MAX_VALUE);
        return uid == null || receivedAt == null ? null : uid + "+" + receivedAt;
    }

    /** One selectable scalar PHONE value. */
    public static final class StatusItem {
        public final String id;
        public final String resourceId;
        public final String valuePath;
        public final String label;
        private final String displayPrefix;
        private final Kind kind;

        private StatusItem(String id, String resourceId, String valuePath, String label,
                           String displayPrefix, Kind kind) {
            this.id = id;
            this.resourceId = resourceId;
            this.valuePath = valuePath;
            this.label = label;
            this.displayPrefix = displayPrefix;
            this.kind = kind;
        }
    }

    /** One selectable component of the temporary latest-notification presentation. */
    public static final class NotificationField {
        public final String id;
        public final String label;
        public final String valuePath;

        private NotificationField(String id, String label, String valuePath) {
            this.id = id;
            this.label = label;
            this.valuePath = valuePath;
        }
    }

    /** Immutable, already-sanitized text ready for status-row rendering. */
    public static final class NotificationPresentation {
        public final String key;
        public final long uid;
        public final long receivedAt;
        public final String application;
        public final String topic;
        public final String body;
        /** Topic and body combined in their canonical order for one-line rendering. */
        public final String text;

        private NotificationPresentation(String key, long uid, long receivedAt,
                                         String application, String topic, String body,
                                         String text) {
            this.key = key;
            this.uid = uid;
            this.receivedAt = receivedAt;
            this.application = application;
            this.topic = topic;
            this.body = body;
            this.text = text;
        }
    }

    private enum Kind {
        CONNECTION,
        CHARGING,
        NETWORK,
        ROAMING,
        PROFILE,
        POWER,
        ESTIMATED,
        CALL,
        CALL_AUDIO,
        VOICE,
        BOOLEAN,
        CODE,
        PERCENT,
        COUNT,
        TEXT
    }

    private static StatusItem status(String resourceId, String label, String displayPrefix,
                                     Kind kind) {
        return new StatusItem(resourceId, resourceId, "", label, displayPrefix, kind);
    }

    private static boolean eligible(ConnectorValue value, String expectedResourceId) {
        return value != null
                && value.connectorType == ConnectorType.PHONE
                && expectedResourceId.equals(value.resourceId)
                && value.fresh
                && value.available
                && value.readable;
    }

    private static String booleanText(Object raw, String whenTrue, String whenFalse) {
        return raw instanceof Boolean ? ((Boolean) raw ? whenTrue : whenFalse) : null;
    }

    private static String codeText(String resourceId, Object raw) {
        String value = singleLine(raw, MAX_STATUS_TEXT_CODE_POINTS);
        if (value == null) return null;
        switch (resourceId + ":" + value) {
            case "battery.level_source:ble_bas": return "BLE BAS";
            case "battery.level_source:hfp_ecarx": return "HFP/ECARX";
            case "battery.level_source:android_broadcast": return "Android Bluetooth";
            case "battery.charging_source:ble_bas": return "BLE BAS";
            case "battery.charging_source:hfp_vendor": return "HFP/OEM";
            case "battery.charging_source:android_metadata": return "Android metadata";
            case "battery.charging_source:bas_trend": return "изменение BLE BAS";
            case "battery.charging_source:hfp_trend": return "изменение HFP";
            case "battery.charging_source:ecarx_trend": return "изменение ECARX";
            case "battery.charging_source:system_trend": return "изменение Android";
            case "battery.charge_state:charging": return "заряжается";
            case "battery.charge_state:discharging": return "разряжается";
            case "battery.charge_state:idle": return "зарядка не активна";
            case "battery.charge_state:not_charging": return "не заряжается";
            case "battery.charge_level:good": return "нормальный";
            case "battery.charge_level:low": return "низкий";
            case "battery.charge_level:critical": return "критический";
            case "call.state:idle": return "нет вызова";
            case "call.state:active": return "активен";
            case "call.state:held": return "удержание";
            case "call.state:dialing": return "набор номера";
            case "call.state:alerting": return "идут гудки";
            case "call.state:incoming": return "входящий";
            case "call.state:waiting": return "ожидающий";
            case "call.state:held_by_response": return "удержание";
            case "call.direction:incoming": return "входящий";
            case "call.direction:outgoing": return "исходящий";
            case "call.audio_state:connected": return "подключено";
            case "call.audio_state:connecting": return "подключается";
            case "call.audio_state:disconnected": return "отключено";
            default: return value;
        }
    }

    private static String formatPercent(Object raw) {
        Double value = finiteNumber(raw);
        if (value == null || value < 0d || value > 100d) return null;
        return decimal(value) + "%";
    }

    private static String formatCount(Object raw) {
        Long value = exactLong(raw, 0L, Integer.MAX_VALUE);
        return value == null ? null : String.valueOf(value);
    }

    private static String prefixed(String prefix, String value) {
        if (value == null) return null;
        return prefix == null || prefix.isEmpty() ? value : prefix + " " + value;
    }

    private static Double finiteNumber(Object raw) {
        if (!(raw instanceof Number)) return null;
        double value = ((Number) raw).doubleValue();
        return Double.isFinite(value) ? value : null;
    }

    private static Long exactLong(Object raw, long minimum, long maximum) {
        Double number = finiteNumber(raw);
        if (number == null || number < minimum || number > maximum
                || number != Math.rint(number)) {
            return null;
        }
        long value = number.longValue();
        return value < minimum || value > maximum ? null : value;
    }

    private static String decimal(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static String combine(String topic, String body) {
        if (topic.isEmpty()) return body;
        if (body.isEmpty() || topic.equals(body)) return topic;
        return topic + " · " + body;
    }

    /**
     * Requires a non-empty string after normalization.
     */
    private static String singleLine(Object raw, int maxCodePoints) {
        String value = nullableSingleLine(raw, maxCodePoints);
        return value == null || value.isEmpty() ? null : value;
    }

    /**
     * Accepts an explicitly present empty string but rejects non-string data.
     */
    private static String nullableSingleLine(Object raw, int maxCodePoints) {
        if (!(raw instanceof CharSequence)) return null;
        String source = raw.toString();
        StringBuilder result = new StringBuilder(
                Math.min(source.length(), maxCodePoints + 1));
        boolean pendingSpace = false;
        boolean truncated = false;
        int kept = 0;
        int offset = 0;
        while (offset < source.length()) {
            int codePoint = source.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint) || Character.isISOControl(codePoint)) {
                pendingSpace = result.length() > 0;
                continue;
            }
            if (pendingSpace) {
                if (kept >= maxCodePoints) {
                    truncated = true;
                    break;
                }
                result.append(' ');
                kept++;
            }
            pendingSpace = false;
            if (kept >= maxCodePoints) {
                truncated = true;
                break;
            }
            result.appendCodePoint(codePoint);
            kept++;
        }
        if (offset < source.length()) truncated = true;
        if (truncated && maxCodePoints > 0) {
            while (result.codePointCount(0, result.length()) >= maxCodePoints) {
                int last = result.offsetByCodePoints(result.length(), -1);
                result.setLength(last);
            }
            result.append('…');
        }
        return result.toString();
    }

    private static Set<String> canonicalSelection(
            Collection<String> selected, Collection<String> orderedAllowed) {
        if (selected == null || selected.isEmpty()
                || orderedAllowed == null || orderedAllowed.isEmpty()) {
            return Collections.emptySet();
        }
        LinkedHashSet<String> requested = new LinkedHashSet<>();
        for (String value : selected) {
            String clean = cleanId(value);
            if (!clean.isEmpty()) requested.add(clean);
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String allowed : orderedAllowed) {
            String clean = cleanId(allowed);
            if (!clean.isEmpty() && requested.contains(clean)) result.add(clean);
        }
        if (result.isEmpty()) return Collections.emptySet();
        return Collections.unmodifiableSet(result);
    }

    private static String cleanId(String raw) {
        if (raw == null) return "";
        String value = raw.trim();
        if (value.isEmpty() || value.length() > 128) return "";
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!(character >= 'a' && character <= 'z')
                    && !(character >= '0' && character <= '9')
                    && character != '.' && character != '_' && character != '-') {
                return "";
            }
        }
        return value;
    }

    private static List<String> idsOfStatuses() {
        List<String> result = new ArrayList<>(STATUS_ITEMS.size());
        for (StatusItem item : STATUS_ITEMS) result.add(item.id);
        return Collections.unmodifiableList(result);
    }

    private static List<String> idsOfNotificationFields() {
        List<String> result = new ArrayList<>(NOTIFICATION_FIELDS.size());
        for (NotificationField field : NOTIFICATION_FIELDS) result.add(field.id);
        return Collections.unmodifiableList(result);
    }

    private static Map<String, StatusItem> statusesById() {
        LinkedHashMap<String, StatusItem> result = new LinkedHashMap<>();
        for (StatusItem item : STATUS_ITEMS) result.put(item.id, item);
        return Collections.unmodifiableMap(result);
    }
}
