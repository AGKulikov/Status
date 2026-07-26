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
            source("battery.level", "", "Заряд iPhone", "number", "%",
                    "батарея battery level"),
            source("battery.charging", "", "Зарядка iPhone", "boolean", "",
                    "заряжается charging power"),
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
        return value.resolveValue(path);
    }

    /** Resolves the exact scalar selected from a structured PHONE resource. */
    @Nullable
    public static Object displayValue(@NonNull ConnectorValue value,
                                      @NonNull String valuePath) {
        return value.resolveValue(valuePath);
    }

    @NonNull
    private static Source source(@NonNull String resourceId, @NonNull String valuePath,
                                 @NonNull String label, @NonNull String valueType,
                                 @NonNull String unit, @NonNull String searchHint) {
        return new Source(resourceId, valuePath, label, valueType, unit, searchHint);
    }
}
