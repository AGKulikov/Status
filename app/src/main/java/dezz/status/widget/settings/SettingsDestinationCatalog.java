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
        HOME("home", "Главный экран", "Лаунчеры водителя и пассажира, строка состояния", "home"),
        HUD("hud", "HUD", "Проекция и её независимый редактор", "hud"),
        DRIVER("driver", "Экран водителя", "Пять вариантов приборки и меню DIM", "vehicle"),
        PANELS("panels", "Панели и шторка", "Боковые панели, избранное, климат и оверлеи", "panels"),
        NAVIGATION_MEDIA("navigation_media", "Навигация и музыка", "Окно Навигатора, маршруты и медиаплеер", "navigation"),
        VEHICLE("vehicle", "Автомобиль и кнопки", "Пульт, режимы вождения и физические кнопки", "vehicle"),
        PHONE("phone", "Телефон и обмен", "iPhone, уведомления, иконки и передача файлов", "phone"),
        SMART_HOME("smart_home", "Умный дом", "Home Assistant, Sprut.hub и MQTT", "smart_home"),
        AUTOMATION("automation", "Сценарии и команды", "Условия, действия и внешние команды", "automation"),
        BACKUP("backup", "Копии и профили", "Полная личная копия, восстановление и оформление", "preset"),
        APP("app", "Приложение и обслуживание", "Оформление настроек, доступы, ADB и диагностика", "app");

        /** Legacy deep links remain valid after moving the status row under Home. */
        public static final Group STATUS=HOME;

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
            if ("status".equals(id)) return HOME;
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
        values.add(activity("status_presets", Group.BACKUP, "Профили оформления",
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
        values.add(activity("passenger_home", Group.HOME, "Лаунчер пассажира",
                "Домашний экран пассажира: собственные элементы, фон, компоновка и приложения",
                "home", "dezz.status.widget.PassengerLauncherSettingsActivity",
                "домой пассажир", "пассажирский экран", "лаунчер", "компоновка"));
        values.add(activity("vehicle_control", Group.VEHICLE, "Пульт автомобиля",
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
        values.add(activity("panel_hud", Group.HUD, "Отдельный HUD-дисплей",
                "Живой редактор с сеткой, стабильный ID дисплея, навигация, автомобиль и умный дом",
                "hud", "dezz.status.widget.HudPanelSettingsActivity",
                "hud", "проекция", "внешний дисплей", "стрелки", "светофоры",
                "полосы", "телеметрия", "умный дом", "сценарии", "сетка"));
        values.add(activity("media_buttons", Group.VEHICLE, "Физические кнопки",
                "MEDIA, SRC, DM, остальные кнопки и короткие/долгие нажатия",
                "automation", "dezz.status.widget.MediaButtonsSettingsActivity",
                "media", "mconfig", "руль", "кнопки", "музыка", "src"));
        values.add(activity("panel_instrument_cluster", Group.DRIVER, "Панель приборов",
                "Живой редактор 1920×720, аналоговые и цифровые приборы и независимая карта",
                "vehicle", "dezz.status.widget.InstrumentPanelSettingsActivity",
                "приборка", "панель приборов", "спидометр", "тахометр", "одометр",
                "аналоговый", "цифровой", "карта", "display 2", "dim", "1920 720",
                "белая полоса", "ограничение скорости", "штатный знак", "tsr"));
        values.add(activity("panel_dim_menu", Group.DRIVER, "Меню экрана водителя",
                "Отдельная панель во вкладке навигации с управлением кнопками руля",
                "navigation", "dezz.status.widget.DimMenuPanelSettingsActivity",
                "dim", "mNavi", "руль", "меню водителя", "экран водителя",
                "маршруты", "умный дом", "звонки", "display 2"));
        values.add(activity("panel_system_shade", Group.PANELS, "Системная шторка Natro",
                "Жест сверху, отдельные элементы, кнопки и живая компоновка",
                "panels", "dezz.status.widget.shade.SystemShadeSettingsActivity",
                "шторка", "уведомления", "ecarx", "яркость", "громкость", "медиа",
                "жест сверху", "компоновка"));
        values.add(activity("navigator_window", Group.NAVIGATION_MEDIA,
                "Оконный режим Навигатора",
                "Размер, положение, скругление, прозрачный фон и фиксация окна",
                "popup", "dezz.status.widget.NavigatorWindowSettingsActivity",
                "навигатор", "яндекс навигатор", "оконный режим", "окно",
                "скругление", "углы", "фиксация", "зафиксировать", "ручка",
                "перетаскивание", "уголок", "прозрачный фон"));
        values.add(activity("passenger_favorites", Group.PANELS, "Избранное пассажира",
                "Независимые панели, сетка, кнопки и автозакрытие", "apps",
                "dezz.status.widget.PassengerFavoritesSettingsActivity", "пассажир избранное"));
        values.add(activity("passenger_panel", Group.PANELS, "Панель пассажира",
                "Кнопки и оформление на пассажирском экране", "apps",
                "dezz.status.widget.PassengerPanelSettingsActivity", "пассажир экран боковая панель"));
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
        values.add(activity("connector_phone", Group.PHONE, "Телефон",
                "Конкретный iPhone по Bluetooth: данные, уведомления, сообщения и присутствие",
                "phone", "dezz.status.widget.PhoneConnectorSettingsActivity",
                "iphone", "айфон", "телефон", "bluetooth", "ancs", "уведомления",
                "сообщения", "sms", "присутствие"));

        values.add(activity("automation_visual", Group.AUTOMATION, "Визуальные сценарии",
                "Триггеры, условия и действия между всеми коннекторами",
                "scenario", "dezz.status.widget.ScenarioSettingsActivity",
                "правила", "триггер", "условие", "действие"));
        values.add(activity("automation_phone_notifications", Group.PHONE,
                "Уведомления телефона",
                "Строка состояния, настраиваемый оверлей, длительность и условия показа",
                "phone", "dezz.status.widget.PhoneNotificationAutomationSettingsActivity",
                "iphone", "ancs", "уведомления", "оверлей", "всплывающие",
                "шрифт", "время", "пассажир"));
        values.add(activity("phone_app_icons", Group.PHONE, "Иконки приложений телефона",
                "Все сопоставления, свои PNG/JPEG и приложения без иконок",
                "phone", "dezz.status.widget.PhoneAppIconsActivity",
                "иконки", "значки", "png", "jpeg", "iphone", "уведомления"));
        values.add(activity("automation_intent", Group.AUTOMATION,
                "Внешние кнопки и Intent",
                "Команды с кнопок руля и других Android-событий",
                "intent", "dezz.status.widget.IntentScenarioSettingsActivity",
                "руль", "broadcast", "android intent", "команда"));

        values.add(activity("app_service_mode", Group.APP, "Сервисный режим",
                "Скрытие и восстановление приложений, исключения и возврат по PIN",
                "settings", "dezz.status.widget.servicemode.MainActivity",
                "сервис", "stealth", "скрыть приложения", "восстановить", "PIN"));
        values.add(activity("panel_drive_selector", Group.VEHICLE, "Режимы вождения",
                "Режимы, порядок, карусель, скрытие меню и назначения кнопок",
                "drive_mode", "dezz.status.widget.drivemode.ui.MainActivity",
                "режимы движения", "селектор", "monjaro selector", "эко", "спорт", "DM"));
        values.add(activity("app_adb", Group.APP, "ADB",
                "Терминал, разрешения, специальные возможности и параметры разработчика",
                "diagnostics", "dezz.status.widget.AdbSettingsActivity",
                "adb", "адб", "usb", "терминал", "команды", "mconfig", "мконфиг",
                "разработчик", "gps", "mock_location", "постоянный adb", "проверка установки"));
        values.add(activity("app_transfer", Group.PHONE, "Буфер обмена и файлы",
                "Локальный сервер для iPhone: текст, файлы и команды ADB",
                "import", "dezz.status.widget.LanTransferActivity",
                "буфер", "clipboard", "iphone", "айфон", "модем", "hotspot", "wifi", "lan", "сервер", "файлы", "команды"));
        values.add(action("app_permissions", Group.APP, "Доступы приложения",
                "Оверлей, уведомления, местоположение, статистика и спецвозможности",
                "permissions", ACTION_PERMISSIONS,
                "разрешения", "notification listener", "usage access", "accessibility"));
        values.add(action("app_export", Group.BACKUP, "Экспорт старого JSON (неполная копия)",
                "Сохранить интерфейс, панели и сценарии в JSON; секреты останутся на устройстве",
                "export", ACTION_EXPORT, "backup", "резервная копия", "json"));
        values.add(action("app_import", Group.BACKUP, "Импорт старого JSON (неполная копия)",
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

        values.add(activity("full_backup", Group.BACKUP, "Полная личная копия",
                "Пароль, проверка файла, восстановление и точка отката", "export",
                "dezz.status.widget.FullBackupActivity", "backup", "резервная копия", "пароли", "восстановить", "архив"));
        values.add(activity("settings_appearance", Group.APP, "Оформление настроек",
                "Светлая и тёмная темы, размер текста и образцы элементов", "layout",
                "dezz.status.widget.SettingsAppearanceActivity", "шрифт", "тема", "mconfig", "крупный текст"));
        values.add(activity("navigation_media_panel", Group.NAVIGATION_MEDIA, "Медиаплеер",
                "Приложение музыки, управление, оформление и автовозобновление", "media",
                "dezz.status.widget.MediaPanelSettingsActivity", "музыка", "плеер", "обложка", "автозапуск"));
        values.add(activity("navigation_routes", Group.NAVIGATION_MEDIA, "Любимые маршруты",
                "Адреса и кнопки запуска навигации", "routes",
                "dezz.status.widget.FavoriteRoutesSettingsActivity", "маршруты", "дом", "работа", "адрес"));
        values.add(activity("hud_lca_patch", Group.APP, "Системный патч HUD/LCA",
                "Проверка совместимости, оригинал, режимы и отдельное восстановление", "hud",
                "dezz.status.widget.HudLcaPatchActivity", "hud", "lca", "машинка", "патч", "simple", "ar"));

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
