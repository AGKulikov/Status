/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.media;

/** Independent parameters per press: changing an action never destroys previously entered fields. */
public final class ButtonBinding {
    public static final ButtonBinding EMPTY = new ButtonBinding(0, "", "", "", "");
    public final ButtonAction action;
    public final String application, command, packageName, shortcutJson;
    public ButtonBinding(int action, String application, String command, String packageName, String shortcutJson) {
        this.action = ButtonAction.fromId(action);
        this.application = clean(application); this.command = clean(command);
        this.packageName = clean(packageName); this.shortcutJson = clean(shortcutJson);
    }
    /** Empty parameters are retained when switching actions, but cannot be saved as runnable assignments. */
    public String validationError() {
        switch (action.parameter) {
            case APP:
                String component = application.trim();
                int open = component.lastIndexOf('['), close = component.lastIndexOf(']');
                if (open >= 0 && close > open) component = component.substring(open + 1, close);
                String[] parts = component.split("/", -1);
                if (parts.length != 2 || parts[0].trim().isEmpty() || parts[1].trim().isEmpty())
                    return "Выберите приложение";
                break;
            case BROADCAST: case ACTIVITY:
                try { ButtonIntentSpec.parse(command, packageName, action.parameter == ButtonAction.Parameter.ACTIVITY); }
                catch (IllegalArgumentException invalid) { return invalid.getMessage(); }
                if (action.parameter == ButtonAction.Parameter.ACTIVITY && packageName.trim().isEmpty())
                    return "Укажите пакет приложения";
                break;
            case COMMAND: if (command.trim().isEmpty()) return "Введите команду"; break;
            case PHONE: if (command.trim().isEmpty()) return "Введите номер телефона"; break;
            case SHORTCUT: if (shortcutJson.trim().isEmpty()) return "Выберите действие меню водителя"; break;
            default: break;
        }
        return "";
    }
    private static String clean(String value) { return value == null ? "" : value; }
}
