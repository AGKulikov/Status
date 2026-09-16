/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.media;

/** IDs/order verified against vyj.<clinit>/lsu.ka in MConfig 46.1, not JADX enum names. */
public enum ButtonAction {
    NONE(0, "Без действия"), APP(1, "Запустить приложение", Parameter.APP),
    PASSENGER_APP(2, "Запустить приложение на дисплее пассажира", Parameter.APP),
    DRIVER_APP(3, "Запустить приложение на дисплее водителя", Parameter.APP),
    BROADCAST(4, "Отправить намерение (broadcast)", Parameter.BROADCAST),
    ACTIVITY(31, "Отправить намерение (activity)", Parameter.ACTIVITY),
    ADB(32, "ADB команда", Parameter.COMMAND), HOME(5, "Домой"), BACK(6, "Назад"),
    RECENTS(7, "Недавние"), LAST_APP(8, "Последнее приложение"),
    FORCE_STOP(9, "Принудительная остановка"), PASSENGER_HOME(10, "PHOME"),
    PASSENGER_POWER(11, "Вкл/выкл экран пассажира"),
    THEME(12, "Сменить тему (день/ночь/авто)"), SCREENSAVER(13, "Заставка экрана"),
    CLIMATE(14, "Климат"), APP_DRAWER(15, "Меню приложений"), CAMERA(28, "Камера 360"),
    CALL(34, "Позвонить на номер", Parameter.PHONE), FULLSCREEN(16, "Полноэкранный"),
    TEMPERATURE(17, "Температура салона"), BT_MEDIA(18, "BT медиа"),
    WIFI(19, "Подключение WiFi"), SOURCE(20, "Мультимедийный режим"),
    RESTART(29, "Перезагрузка системы"), COMFORT(21, "Режим Комфорт"),
    ADAPTIVE(22, "Режим Адаптивный"), ECO(23, "Режим Экономичный"),
    SPORT(24, "Режим Спорт"), OFFROAD(25, "Режим Внедорожный"),
    SAND(26, "Режим Песок"), SNOW(27, "Режим Снег"),
    MNAVI_ASSISTANT(30, "Alice (mNavi)"), DASHBOARD(33, "Dashboard"),
    DRIVER_MENU(100, "Действие меню экрана водителя", Parameter.SHORTCUT),
    DRIVE_SHOW(101, "Показать меню режимов — без переключения"),
    DRIVE_PREV_1(102, "Режимы вождения: назад на 1"),
    DRIVE_PREV_2(103, "Режимы вождения: назад на 2"),
    DRIVE_PREV_3(104, "Режимы вождения: назад на 3"),
    DRIVE_NEXT_1(105, "Режимы вождения: вперёд на 1"),
    DRIVE_NEXT_2(106, "Режимы вождения: вперёд на 2"),
    DRIVE_NEXT_3(107, "Режимы вождения: вперёд на 3");
    public enum Parameter { NONE, APP, BROADCAST, ACTIVITY, COMMAND, PHONE, SHORTCUT }
    public final int id;
    public final String title;
    public final Parameter parameter;
    ButtonAction(int id, String title) { this(id, title, Parameter.NONE); }
    ButtonAction(int id, String title, Parameter parameter) {
        this.id = id; this.title = title; this.parameter = parameter;
    }
    public static ButtonAction fromId(int id) {
        for (ButtonAction action : values()) if (action.id == id) return action;
        return NONE;
    }
    @Override public String toString() { return title; }
}
