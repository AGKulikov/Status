/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Single source of truth for every user-facing settings destination.
 *
 * <p>The old UI linked the same screen from several unrelated places (About, HOME, automation,
 * panel composition).  Besides being hard to understand, that made it very easy to add a setting
 * to one "hub" and forget the others.  This catalog gives every destination exactly one canonical
 * section.  Contextual deep links from an editor are still allowed, but the root navigation and
 * search are always generated from this one immutable list.</p>
 */
public final class SettingsDestinationCatalog {
    public static final String ACTION_PERMISSIONS = "action.permissions";
    public static final String ACTION_EXPORT = "action.export";
    public static final String ACTION_IMPORT = "action.import";
    public static final String ACTION_RESET = "action.reset";

    public enum Group {
        STATUS("status", "Строка состояния",
                "Положение, состав и оформление верхней строки", "status"),
        HOME("home", "Лаунчер",
                "Все кнопки, виджеты, информация и компоновка в одном разделе", "home"),
        PANELS("panels", "Панели",
                "Панель водителя, HUD и независимые плавающие панели", "panels"),
        SMART_HOME("smart_home", "Умный дом",
                "Подключения Home Assistant, Sprut.hub, MQTT и iPhone", "smart_home"),
        AUTOMATION("automation", "Автоматизация",
                "Сценарии и команды с внешних кнопок", "automation"),
        APP("app", "Приложение",
                "Доступы, резервная копия и диагностика", "app");

        @NonNull public final String id;
        @NonNull public final String title;
        @NonNull public final String subtitle;
        @NonNull public final String icon;

        Group(@NonNull String id, @NonNull String title, @NonNull String subtitle,
              @NonNull String icon) {
            this.id = id;
            this.title = title;
            this.subtitle = subtitle;
            this.icon = icon;
        }

        @NonNull
        public static Group fromId(@Nullable String id) {
            if (id != null) {
                for (Group value : values()) if (value.id.equals(id)) return value;
            }
            return STATUS;
        }
    }

    public static final class Destination {
        @NonNull public final String id;
        @NonNull public final Group group;
        @NonNull public final String title;
        @NonNull public final String subtitle;
        @NonNull public final String icon;
        @Nullable public final String activityClassName;
        @Nullable public final String action;
        @NonNull public final List<String> keywords;

        private Destination(@NonNull String id, @NonNull Group group, @NonNull String title,
                            @NonNull String subtitle, @NonNull String icon,
                            @Nullable String activityClassName, @Nullable String action,
                            @NonNull String... keywords) {
            this.id = id;
            this.group = group;
            this.title = title;
            this.subtitle = subtitle;
            this.icon = icon;
            this.activityClassName = activityClassName;
            this.action = action;
            this.keywords = Collections.unmodifiableList(Arrays.asList(keywords));
        }

        public boolean isActivity() {
            return activityClassName != null;
        }

        public boolean matches(@Nullable String rawQuery) {
            String query = normalize(rawQuery);
            if (query.isEmpty()) return true;
            StringBuilder haystack = new StringBuilder()
                    .append(title).append(' ')
                    .append(subtitle).append(' ')
                    .append(group.title).append(' ')
                    .append(group.subtitle);
            for (String keyword : keywords) haystack.append(' ').append(keyword);
            return normalize(haystack.toString()).contains(query);
        }
    }

    private static final List<Destination> DESTINATIONS;

    static {
        List<Destination> values = new ArrayList<>();

        values.add(activity("status_widget", Group.STATUS, "Строка состояния",
                "Включение, режим, положение, фон, отступы и порядок элементов",
                "status", "dezz.status.widget.MainActivity",
                "виджет", "верхняя строка", "часы", "дата", "wifi", "gps", "bluetooth",
                "размер", "позиция", "прозрачность", "скругление"));
        values.add(activity("status_smart_elements", Group.STATUS,
                "Данные умного дома в строке",
                "Добавление устройств, подписи, правила состояний, цвета и порядок",
                "smart_home", "dezz.status.widget.AutomationSettingsActivity",
                "кирпичики", "элементы", "home assistant", "sprut", "mqtt", "статус"));
        values.add(activity("status_presets", Group.STATUS, "Профили оформления",
                "Сохранение и быстрое переключение вариантов строки",
                "preset", "dezz.status.widget.PresetsActivity",
                "пресеты", "профили", "шаблоны", "оформление"));

        values.add(activity("home_behavior", Group.HOME, "Лаунчер",
                "Один плоский экран: общий пул элементов, компоновка, приложения и медиаплеер",
                "home", "dezz.status.widget.LauncherSettingsActivity",
                "лаунчер", "домашний экран", "фон", "сетка", "полноэкранный",
                "музыка", "медиа", "трек", "маневр", "навигация", "маршрут",
                "климат", "информация", "кнопки", "размеры", "позиции кнопок",
                "столбцы", "все приложения", "скрыть системные", "подложка",
                "горизонтальный ряд"));
        values.add(activity("vehicle_control", Group.HOME, "Пульт автомобиля",
                "Обзор Monjaro и контекстные разделы климата, сидений, автомобиля и комфорта",
                "vehicle", "dezz.status.widget.VehicleControlActivity",
                "автомобиль", "пульт", "monjaro", "климат", "сиденья", "обогрев",
                "вентиляция", "стёкла", "подсветка", "tesla"));
        values.add(activity("panel_floating_climate", Group.PANELS,
                "Плавающая панель климата",
                "Отдельные от HOME оформление, состав, положение и резервирование экрана",
                "climate", "dezz.status.widget.ClimatePanelSettingsActivity",
                "климат", "оверлей", "плавающая", "кондиционер", "вентилятор",
                "сиденья", "руль", "резервирование"));
        values.add(activity("panel_hud", Group.PANELS, "Отдельный HUD-дисплей",
                "Живой редактор с сеткой, стабильный ID дисплея, навигация, автомобиль и умный дом",
                "hud", "dezz.status.widget.HudPanelSettingsActivity",
                "hud", "проекция", "внешний дисплей", "стрелки", "светофоры",
                "полосы", "телеметрия", "умный дом", "сценарии", "сетка"));
        values.add(activity("navigator_window", Group.PANELS,
                "Оконный режим Навигатора",
                "Размер, положение, скругление, прозрачный фон и фиксация окна",
                "popup", "dezz.status.widget.NavigatorWindowSettingsActivity",
                "навигатор", "яндекс навигатор", "оконный режим", "окно",
                "скругление", "углы", "фиксация", "зафиксировать", "ручка",
                "перетаскивание", "уголок", "прозрачный фон"));
        values.add(activity("driver_panel", Group.PANELS, "Панель водителя",
                "Единая боковая панель: до 10 кнопок, Домой, Назад и штатный климат",
                "apps", "dezz.status.widget.DriverPanelSettingsActivity",
                "панель водителя",
                "системные приложения", "домой",
                "назад", "климат", "оверлей", "10 кнопок", "размер иконок"));
        values.add(activity("driver_favorites", Group.PANELS,
                "Избранное водителя",
                "Неограниченные привязанные к кнопкам панели: сетка, границы, действия и автоматизация",
                "apps", "dezz.status.widget.DriverFavoritesSettingsActivity",
                "избранное", "панель водителя", "приложения", "умный дом",
                "дворники", "климат", "долгое нажатие", "границы", "автоматизация"));
        values.add(activity("panel_popup", Group.PANELS, "Плавающие панели",
                "Независимые оверлеи, сетка, размер, положение и плитки",
                "popup", "dezz.status.widget.PopupSettingsActivity",
                "оверлей", "popup", "плавающее окно", "плитки"));

        values.add(activity("connector_ha", Group.SMART_HOME, "Home Assistant",
                "Адрес, токен, актуальный снимок и выбор всех сущностей",
                "ha", "dezz.status.widget.HomeAssistantSettingsActivity",
                "ha", "entity", "сущности", "токен", "websocket"));
        values.add(activity("connector_sprut", Group.SMART_HOME, "Sprut.hub",
                "Подключение, каталог всех устройств и характеристики",
                "sprut", "dezz.status.widget.SprutHubSettingsActivity",
                "spruthub", "хаб", "устройства", "характеристики"));
        values.add(activity("connector_mqtt", Group.SMART_HOME, "MQTT",
                "Брокер, авторизация, топики, QoS и состояние соединения",
                "mqtt", "dezz.status.widget.MqttSettingsActivity",
                "broker", "брокер", "topic", "топик", "qos"));
        values.add(activity("connector_phone", Group.SMART_HOME, "Телефон",
                "Конкретный iPhone по Bluetooth: данные, уведомления, сообщения и присутствие",
                "phone", "dezz.status.widget.PhoneConnectorSettingsActivity",
                "iphone", "айфон", "телефон", "bluetooth", "ancs", "уведомления",
                "сообщения", "sms", "присутствие"));

        values.add(activity("automation_visual", Group.AUTOMATION, "Визуальные сценарии",
                "Триггеры, условия и действия между всеми коннекторами",
                "scenario", "dezz.status.widget.ScenarioSettingsActivity",
                "правила", "триггер", "условие", "действие"));
        values.add(activity("automation_phone_notifications", Group.AUTOMATION,
                "Уведомления телефона",
                "Строка состояния, настраиваемый оверлей, длительность и условия показа",
                "phone", "dezz.status.widget.PhoneNotificationAutomationSettingsActivity",
                "iphone", "ancs", "уведомления", "оверлей", "всплывающие",
                "шрифт", "время", "пассажир"));
        values.add(activity("automation_intent", Group.AUTOMATION,
                "Внешние кнопки и Intent",
                "Команды с кнопок руля и других Android-событий",
                "intent", "dezz.status.widget.IntentScenarioSettingsActivity",
                "руль", "broadcast", "android intent", "команда"));

        values.add(action("app_permissions", Group.APP, "Доступы приложения",
                "Оверлей, уведомления, местоположение, статистика и спецвозможности",
                "permissions", ACTION_PERMISSIONS,
                "разрешения", "notification listener", "usage access", "accessibility"));
        values.add(action("app_export", Group.APP, "Экспорт резервной копии",
                "Сохранить интерфейс, панели и сценарии в JSON; секреты останутся на устройстве",
                "export", ACTION_EXPORT, "backup", "резервная копия", "json"));
        values.add(action("app_import", Group.APP, "Импорт резервной копии",
                "Восстановить несекретные настройки из ранее сохранённого JSON",
                "import", ACTION_IMPORT, "restore", "восстановление", "json"));
        values.add(activity("app_diagnostics", Group.APP, "Отладка и регистратор действий",
                "Цветной журнал, полный стек ошибок и плавающее управление записью событий",
                "diagnostics", "dezz.status.widget.DiagnosticsActivity",
                "отладка", "журнал", "лог", "ошибка", "падение", "красный",
                "предупреждение", "руль", "keycode", "оверлей", "запись", "json", "txt"));
        values.add(activity("app_about", Group.APP, "О приложении и данные автомобиля",
                "Версия, соединения и данные автомобиля → Sprut.hub",
                "about", "dezz.status.widget.AboutActivity",
                "версия", "данные автомобиля", "sprut", "соединение"));
        values.add(action("app_reset", Group.APP, "Сбросить все настройки",
                "Вернуть исходные значения после явного подтверждения",
                "reset", ACTION_RESET, "удалить", "очистить", "по умолчанию"));

        DESTINATIONS = Collections.unmodifiableList(values);
    }

    private SettingsDestinationCatalog() {
    }

    @NonNull
    private static Destination activity(@NonNull String id, @NonNull Group group,
                                        @NonNull String title, @NonNull String subtitle,
                                        @NonNull String icon, @NonNull String className,
                                        @NonNull String... keywords) {
        return new Destination(id, group, title, subtitle, icon, className, null, keywords);
    }

    @NonNull
    private static Destination action(@NonNull String id, @NonNull Group group,
                                      @NonNull String title, @NonNull String subtitle,
                                      @NonNull String icon, @NonNull String action,
                                      @NonNull String... keywords) {
        return new Destination(id, group, title, subtitle, icon, null, action, keywords);
    }

    @NonNull
    public static List<Destination> all() {
        return DESTINATIONS;
    }

    @NonNull
    public static List<Destination> forGroup(@NonNull Group group) {
        List<Destination> matches = new ArrayList<>();
        for (Destination value : DESTINATIONS) if (value.group == group) matches.add(value);
        return Collections.unmodifiableList(matches);
    }

    @NonNull
    public static List<Destination> search(@Nullable String query) {
        List<Destination> matches = new ArrayList<>();
        for (Destination value : DESTINATIONS) if (value.matches(query)) matches.add(value);
        return Collections.unmodifiableList(matches);
    }

    @Nullable
    public static Destination byId(@Nullable String id) {
        if (id == null) return null;
        for (Destination value : DESTINATIONS) if (value.id.equals(id)) return value;
        return null;
    }

    @NonNull
    public static Set<String> activityClassNames() {
        Set<String> values = new LinkedHashSet<>();
        for (Destination value : DESTINATIONS) {
            if (value.activityClassName != null) values.add(value.activityClassName);
        }
        return Collections.unmodifiableSet(values);
    }

    @NonNull
    private static String normalize(@Nullable String value) {
        if (value == null) return "";
        return value.trim().toLowerCase(Locale.ROOT)
                .replace('ё', 'е')
                .replaceAll("\\s+", " ");
    }
}
