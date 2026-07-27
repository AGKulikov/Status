/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher.information;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import dezz.status.widget.integration.ConnectorType;
import dezz.status.widget.integration.ConnectorValue;
import dezz.status.widget.integration.SourceBinding;

/** Scalar defaults and in-place migration rules for PHONE values shown in Information tiles. */
public final class PhoneInformationSourcePolicy {
    /**
     * One user-selectable scalar inside the PHONE connector.
     *
     * <p>The catalog is deliberately independent from a live {@link ConnectorValue} snapshot:
     * users must be able to configure phone tiles before Bluetooth connects or while the
     * connector is disabled.</p>
     */
    public static final class Source {
        @NonNull public final String resourceId;
        @NonNull public final String valuePath;
        @NonNull public final String label;
        @NonNull public final String valueType;
        @NonNull public final String unit;
        @NonNull public final String searchHint;

        private Source(@NonNull String resourceId, @NonNull String valuePath,
                       @NonNull String label, @NonNull String valueType,
                       @NonNull String unit, @NonNull String searchHint) {
            this.resourceId = resourceId;
            this.valuePath = valuePath;
            this.label = label;
            this.valueType = valueType;
            this.unit = unit;
            this.searchHint = searchHint;
        }
    }

    private static final List<Source> CATALOG = Collections.unmodifiableList(Arrays.asList(
            source("connected", "", "iPhone подключён", "boolean", "",
                    "подключение presence connection"),
            source("device.name", "", "Имя iPhone", "string", "",
                    "имя телефон device name"),
            source("profiles.hfp", "", "Телефонный профиль HFP", "boolean", "",
                    "профиль звонки handsfree hfp"),
            source("profiles.map", "", "Профиль сообщений MAP", "boolean", "",
                    "профиль сообщения sms map"),
            source("profiles.ble", "", "Соединение BLE с iPhone", "boolean", "",
                    "профиль ble gatt"),
            source("profiles.ancs", "", "Apple ANCS готов", "boolean", "",
                    "профиль уведомления ancs ready"),
            source("battery.level", "", "Заряд iPhone", "number", "%",
                    "батарея battery level"),
            source("battery.level_source", "", "Источник уровня заряда", "string", "",
                    "батарея источник battery source"),
            source("battery.charging", "", "Зарядка iPhone", "boolean", "",
                    "заряжается charging power"),
            source("battery.charging_estimated", "",
                    "Статус зарядки рассчитан", "boolean", "",
                    "зарядка точность estimated charging"),
            source("battery.charging_source", "",
                    "Источник статуса зарядки", "string", "",
                    "зарядка источник charging source"),
            source("battery.external_power", "",
                    "Внешнее питание iPhone", "boolean", "",
                    "зарядка кабель беспроводная external power"),
            source("battery.charge_state", "",
                    "Состояние батареи iPhone", "string", "",
                    "зарядка разрядка charging discharging idle"),
            source("battery.charge_level", "",
                    "Оценка уровня батареи iPhone", "string", "",
                    "батарея normal low critical"),
            source("network.available", "", "Сеть iPhone", "boolean", "",
                    "мобильная сеть network available"),
            source("network.operator", "", "Оператор iPhone", "string", "",
                    "оператор carrier network"),
            source("network.type", "", "Тип сети iPhone", "string", "",
                    "тип сети cellular network type"),
            source("network.signal", "", "Сигнал сети iPhone", "number", "%",
                    "уровень сигнала cellular signal"),
            source("network.roaming", "", "Роуминг iPhone", "boolean", "",
                    "роуминг roaming"),
            source("call.active", "", "Активный звонок iPhone", "boolean", "",
                    "звонок вызов call active"),
            source("call.state", "", "Состояние звонка iPhone", "string", "",
                    "звонок входящий исходящий held call state"),
            source("call.direction", "", "Направление звонка iPhone", "string", "",
                    "звонок входящий исходящий call direction"),
            source("call.multiparty", "", "Конференц-связь iPhone", "boolean", "",
                    "звонок конференция multiparty call"),
            source("call.audio", "", "Аудио звонка подключено", "boolean", "",
                    "звонок аудио hfp audio"),
            source("call.audio_state", "", "Состояние аудио звонка", "string", "",
                    "звонок аудио подключение hfp audio state"),
            source("call.audio_wideband", "", "HD-аудио звонка", "boolean", "",
                    "звонок wideband wbs hd audio"),
            source("voice_assistant.active", "",
                    "Голосовой ассистент iPhone активен", "boolean", "",
                    "siri голосовой ассистент voice recognition"),
            source("ringtone.in_band", "",
                    "Рингтон передаётся с iPhone", "boolean", "",
                    "звонок рингтон in band ring"),
            source("notifications.count", "", "Количество уведомлений", "number", "",
                    "уведомления notifications count"),

            // Preserve the original HA1081 choice and its scalar default.
            source("notifications.latest", "@value.app_name", "Последнее уведомление",
                    "string", "", "legacy приложение app notification"),
            source("notifications.latest", "@value.application",
                    "Приложение последнего уведомления", "string", "",
                    "приложение application app notification"),
            source("notifications.latest", "@value.topic",
                    "Тема последнего уведомления", "string", "",
                    "тема заголовок topic title notification"),
            source("notifications.latest", "@value.text",
                    "Текст последнего уведомления", "string", "",
                    "текст сообщение text message body notification"),
            source("notifications.latest", "@value.category",
                    "Категория последнего уведомления", "string", "",
                    "категория category notification"),
            source("notifications.latest", "@value.date",
                    "Дата последнего уведомления", "string", "",
                    "дата Apple ANCS date notification"),
            source("notifications.latest", "@value.received_at",
                    "Время получения последнего уведомления", "number", "мс",
                    "время получения timestamp received at unix milliseconds"),

            source("messages.unread", "", "Непрочитанные сообщения", "number", "",
                    "сообщения unread messages"),
            // Preserve the original privacy-safe summary choice.
            source("messages.latest", "@value.display", "Последнее сообщение",
                    "string", "", "сообщение messages display"),
            source("messages.latest", "@value.sender",
                    "Отправитель последнего сообщения", "string", "",
                    "отправитель sender contact messages"),
            source("messages.latest", "@value.body",
                    "Текст последнего сообщения", "string", "",
                    "текст body message messages"),
            source("messages.latest", "@value.date",
                    "Дата последнего сообщения", "number", "мс",
                    "дата время timestamp date messages unix milliseconds"),
            source("messages.latest", "@value.read",
                    "Последнее сообщение прочитано", "boolean", "",
                    "прочитано read unread messages"),

            source("diagnostics.last_app", "@value.name",
                    "Последнее приложение (диагностика)", "string", "",
                    "диагностика приложение app notification"),
            source("diagnostics.ancs", "", "Состояние Apple ANCS", "string", "",
                    "диагностика Apple ANCS status"),
            source("diagnostics.sms", "", "Состояние SMS/MAP", "string", "",
                    "диагностика SMS MAP status"),
            source("diagnostics.last_error", "", "Ошибка подключения iPhone", "string", "",
                    "диагностика ошибка connection error")
    ));

    private PhoneInformationSourcePolicy() {
    }

    /** Stable PHONE catalog used even when no runtime service/snapshot exists yet. */
    @NonNull
    public static List<Source> catalog() {
        return CATALOG;
    }

    /** Lists and transport-only identity objects are not useful as one-line Information values. */
    public static boolean selectable(@NonNull String resourceId) {
        return !"diagnostics.device".equals(resourceId)
                && !"notifications.items".equals(resourceId);
    }

    /**
     * Uses an explicit reserved prefix for fields inside the connector's primary object. This
     * avoids reinterpreting legacy attribute paths such as {@code value.name}.
     */
    @NonNull
    public static String valuePath(@NonNull String resourceId) {
        switch (resourceId) {
            case "notifications.latest":
                return "@value.app_name";
            case "messages.latest":
                return "@value.display";
            case "diagnostics.last_app":
                return "@value.name";
            default:
                return "";
        }
    }

    /** Upgrades HA1080 tiles whose object binding predated scalar phone defaults. */
    @Nullable
    public static SourceBinding migrate(@Nullable SourceBinding binding) {
        if (binding == null || binding.connectorType != ConnectorType.PHONE
                || !binding.valuePath.isEmpty()) {
            return binding;
        }
        String path = valuePath(binding.resourceId);
        if (path.isEmpty()) return binding;
        return new SourceBinding(binding.connectorType, binding.connectorId,
                binding.resourceId, path, binding.presentation, binding.unitSuffix);
    }

    @Nullable
    public static Object displayValue(@NonNull ConnectorValue value) {
        String path = valuePath(value.resourceId);
        return displayValue(value, path);
    }

    /** Resolves the exact scalar selected from a structured PHONE resource. */
    @Nullable
    public static Object displayValue(@NonNull ConnectorValue value,
                                      @NonNull String valuePath) {
        Object raw = value.resolveValue(valuePath);
        if (!valuePath.isEmpty() || !(raw instanceof CharSequence)) return raw;
        String code = raw.toString();
        switch (value.resourceId + ":" + code) {
            case "battery.level_source:ble_bas":
            case "battery.charging_source:ble_bas":
                return "BLE BAS";
            case "battery.level_source:hfp_ecarx": return "HFP/ECARX";
            case "battery.level_source:android_broadcast": return "Android Bluetooth";
            case "battery.charging_source:hfp_vendor": return "HFP/OEM";
            case "battery.charging_source:android_metadata": return "Метаданные Android";
            case "battery.charging_source:bas_trend": return "Оценка по BLE BAS";
            case "battery.charging_source:hfp_trend": return "Оценка по HFP";
            case "battery.charging_source:ecarx_trend": return "Оценка по ECARX";
            case "battery.charging_source:system_trend": return "Оценка Android";
            case "battery.charge_state:charging": return "Заряжается";
            case "battery.charge_state:discharging": return "Разряжается";
            case "battery.charge_state:idle": return "Зарядка не активна";
            case "battery.charge_state:not_charging": return "Не заряжается";
            case "battery.charge_level:good": return "Нормальный";
            case "battery.charge_level:low": return "Низкий";
            case "battery.charge_level:critical": return "Критический";
            case "call.state:idle": return "Нет вызова";
            case "call.state:active": return "Активен";
            case "call.state:held":
            case "call.state:held_by_response": return "Удержание";
            case "call.state:dialing": return "Набор номера";
            case "call.state:alerting": return "Идут гудки";
            case "call.state:incoming": return "Входящий";
            case "call.state:waiting": return "Ожидающий";
            case "call.direction:incoming": return "Входящий";
            case "call.direction:outgoing": return "Исходящий";
            case "call.audio_state:connected": return "Подключено";
            case "call.audio_state:connecting": return "Подключается";
            case "call.audio_state:disconnected": return "Отключено";
            default: return raw;
        }
    }

    @NonNull
    private static Source source(@NonNull String resourceId, @NonNull String valuePath,
                                 @NonNull String label, @NonNull String valueType,
                                 @NonNull String unit, @NonNull String searchHint) {
        return new Source(resourceId, valuePath, label, valueType, unit, searchHint);
    }
}
