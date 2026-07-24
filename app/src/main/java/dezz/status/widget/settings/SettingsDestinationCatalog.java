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
    public static final String ACTION_EDIT_HOME_LAYOUT = "action.edit_home_layout";
    public static final String ACTION_PERMISSIONS = "action.permissions";
    public static final String ACTION_EXPORT = "action.export";
    public static final String ACTION_IMPORT = "action.import";
    public static final String ACTION_RESET = "action.reset";

    public enum Group {
        STATUS("status", "Строка состояния",
                "Положение, состав и оформление верхней строки", "status"),
        HOME("home", "Домашний экран",
                "HOME, компоновка и содержимое панелей", "home"),
        PANELS("panels", "Панели",
                "Медиа, навигация, климат, датчики и быстрые действия", "panels"),
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

        values.add(activity("home_behavior", Group.HOME, "Поведение HOME",
                "Полноэкранный режим, фон, сетка и шаг привязки",
                "home", "dezz.status.widget.LauncherSettingsActivity",
                "лаунчер", "домашний экран", "фон", "сетка", "полноэкранный"));
        values.add(action("home_layout", Group.HOME, "Компоновка на реальном HOME",
                "Перетаскивание и изменение размера панелей за любой угол",
                "layout", ACTION_EDIT_HOME_LAYOUT,
                "редактор", "размер", "позиция", "панель", "сетка"));
        values.add(activity("home_panel_content", Group.HOME, "Состав панелей",
                "Порядок, видимость и масштаб элементов внутри панелей",
                "panels", "dezz.status.widget.PanelElementSettingsActivity",
                "содержимое", "элементы", "порядок", "масштаб"));

        values.add(activity("panel_apps", Group.PANELS, "Избранные приложения",
                "Список, порядок, подписи и размеры иконок приложений",
                "apps", "dezz.status.widget.FavoriteAppsSettingsActivity",
                "приложения", "иконки", "избранное"));
        values.add(activity("panel_media", Group.PANELS, "Медиапанель",
                "Сетка, элементы, бегущие строки, обложка, громкость и цвета",
                "media", "dezz.status.widget.MediaPanelSettingsActivity",
                "музыка", "трек", "альбом", "артист", "обложка", "громкость", "яндекс"));
        values.add(activity("panel_navigation", Group.PANELS, "Навигация",
                "Сетка маршрута, манёвры, полосы, ETA и редактор на HOME",
                "navigation", "dezz.status.widget.NavigationPanelSettingsActivity",
                "маршрут", "маневр", "полосы", "eta", "яндекс навигатор"));
        values.add(activity("panel_routes", Group.PANELS, "Избранные места",
                "Домой, работа и другие кнопки маршрутов, иконки и оформление",
                "routes", "dezz.status.widget.FavoriteRoutesSettingsActivity",
                "места", "домой", "работа", "координаты", "маршруты"));
        values.add(activity("panel_climate", Group.PANELS, "Климат",
                "Элементы, сетка, уровни, резервирование экрана и внешний оверлей",
                "climate", "dezz.status.widget.ClimatePanelSettingsActivity",
                "кондиционер", "вентилятор", "авто", "сиденья", "руль", "резервирование"));
        values.add(activity("panel_vehicle", Group.PANELS, "Информация об автомобиле",
                "Внутренние датчики, вычисляемые показатели, подписи и цвета",
                "vehicle", "dezz.status.widget.VehicleInfoPanelSettingsActivity",
                "hud", "телеметрия", "топливо", "температура", "датчики"));
        values.add(activity("panel_information", Group.PANELS, "Панель «Информация»",
                "Своя сетка статусов автомобиля, магнитолы и устройств умного дома",
                "information", "dezz.status.widget.InformationPanelSettingsActivity",
                "датчики", "статусы", "сетка", "home assistant", "sprut", "mqtt",
                "информация"));
        values.add(activity("panel_actions", Group.PANELS, "Кнопки и умный дом",
                "Индивидуальная сетка, размеры и позиции кнопок — редактирование на HOME и в настройках",
                "actions", "dezz.status.widget.LauncherShortcutSettingsActivity",
                "быстрые действия", "ворота", "свет", "функции", "иконки",
                "сетка", "размер", "позиция", "расположение", "home", "редактор",
                "настройки", "редактирование",
                "столбцы", "ряды", "показывать", "скрыть"));
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
        values.add(activity("app_about", Group.APP, "Диагностика и о приложении",
                "Версия, соединения и данные автомобиля → Sprut.hub",
                "about", "dezz.status.widget.AboutActivity",
                "версия", "диагностика", "ошибки", "соединение"));
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
