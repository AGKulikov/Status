/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.settings;

/** Stable editor destinations. Names are presentation; saved navigation uses the enum name. */
public enum SettingsSection {
    MAIN("Основное"), CONTENT("Состав"), APPEARANCE("Оформление"),
    POSITION("Положение"), BEHAVIOR("Поведение"), ADVANCED("Дополнительно");
    public final String title;
    SettingsSection(String title) { this.title = title; }
}
