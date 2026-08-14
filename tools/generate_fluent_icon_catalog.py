#!/usr/bin/env python3
"""Generate the curated offline Fluent icon catalog used by the KX11 pickers.

The input is the unpacked official @fluentui/svg-icons npm package. Only filled 24 px SVGs
made exclusively from paths are accepted; every generated Android vector keeps a pinned source
comment. Run this script deliberately when updating the pinned package, never during an app build.
"""

from __future__ import annotations

import argparse
import html
import re
import xml.etree.ElementTree as ET
from pathlib import Path


CATEGORIES: dict[str, list[str]] = {
    "Интерфейс": [
        "add", "add_circle", "add_square", "subtract", "subtract_circle", "dismiss",
        "dismiss_circle", "checkmark", "checkmark_circle", "edit", "delete", "save",
        "copy", "share", "search", "filter", "settings", "more_horizontal",
        "more_vertical", "arrow_left", "arrow_right", "arrow_up", "arrow_down",
        "arrow_sync", "arrow_download", "arrow_upload", "arrow_import", "arrow_export",
        "arrow_clockwise", "arrow_counterclockwise", "arrow_expand", "arrow_collapse_all",
        "arrow_maximize", "arrow_minimize", "arrow_move", "text_sort_ascending", "grid", "grid_dots",
        "apps", "apps_list", "list", "panel_left", "panel_right", "window",
        "window_multiple", "eye", "eye_off", "color", "color_fill", "paint_brush",
        "paint_bucket", "sparkle", "star", "star_off", "heart",
    ],
    "Медиа": [
        "play", "play_circle", "pause", "pause_circle", "stop", "record", "record_stop",
        "previous", "next", "skip_back_10", "skip_forward_10", "skip_forward_30",
        "music_note_2", "album", "album_add", "headphones", "headphones_sound_wave",
        "speaker_0", "speaker_1", "speaker_2", "speaker_mute", "speaker_off",
        "speaker_bluetooth", "mic", "mic_off", "mic_record", "video", "video_360",
        "video_clip", "video_multiple", "video_off", "video_settings", "camera",
        "camera_add", "camera_off", "camera_switch", "image", "image_multiple", "image_edit",
        "live", "sound_wave_circle", "heart", "heart_off", "star", "bookmark",
    ],
    "Связь": [
        "call", "call_end", "call_missed", "call_inbound", "call_outbound", "call_forward",
        "call_transfer", "call_add", "call_checkmark", "chat", "chat_multiple", "chat_add",
        "chat_video", "chat_history", "chat_warning", "mail", "mail_inbox", "mail_add",
        "mail_alert", "mail_clock", "mail_checkmark", "send", "people", "person",
        "person_add", "contact_card", "voicemail", "comment", "comment_multiple",
        "comment_add", "comment_mention", "phone", "news", "globe", "attach", "link",
        "scan_qr_code", "calendar", "calendar_today", "calendar_chat", "calendar_person",
    ],
    "Навигация": [
        "map", "map_drive", "location", "location_add", "location_live", "location_off",
        "location_ripple", "location_settings", "location_target_square", "compass_northwest",
        "compass_true_north", "directions", "navigation", "navigation_person", "road",
        "road_cone", "earth", "earth_leaf", "globe_location", "globe_search",
        "branch_fork", "branch_fork_hint", "target", "target_add", "target_arrow",
        "scan_object", "scan_camera", "weather_duststorm", "arrow_routing",
        "arrow_routing_rectangle_multiple",
    ],
    "Автомобиль": [
        "vehicle_car", "vehicle_car_collision", "vehicle_car_parking", "vehicle_car_profile",
        "vehicle_cable_car", "vehicle_car_profile_ltr_clock", "vehicle_truck",
        "vehicle_truck_profile", "vehicle_truck_checkmark", "vehicle_truck_bag", "vehicle_bus",
        "vehicle_cab", "vehicle_motorcycle", "vehicle_bicycle", "vehicle_tractor", "gas",
        "gas_pump", "engine", "seat", "road", "road_cone", "flash", "flash_auto",
        "key", "toolbox", "wrench", "wrench_screwdriver", "navigation", "map_drive",
        "shield_checkmark", "battery_charge", "temperature", "camera", "weather_snowflake",
    ],
    "Умный дом": [
        "home", "home_add", "home_garage", "home_heart", "home_person", "home_more",
        "building_home", "door", "door_arrow_left", "door_tag", "lock_closed", "lock_open",
        "key", "lightbulb", "lightbulb_circle", "lightbulb_pulse", "plug_connected",
        "plug_connected_settings", "plug_disconnected", "power", "temperature", "water",
        "drop", "fire", "fireplace", "camera_dome", "wifi_1", "wifi_2", "wifi_3",
        "wifi_4", "router", "router_off", "bluetooth", "bluetooth_connected",
        "bluetooth_disabled", "battery_0", "battery_5", "battery_10", "battery_charge",
        "clock_alarm", "clock_warning", "presence_available", "presence_away", "presence_busy",
        "leaf_two", "animal_dog", "weather_sunny", "weather_cloudy", "weather_rain",
        "weather_snowflake", "weather_thunderstorm",
    ],
    "Безопасность": [
        "shield", "shield_checkmark", "shield_dismiss", "shield_error", "shield_lock",
        "shield_keyhole", "shield_prohibited", "shield_question", "shield_settings",
        "shield_task", "lock_closed", "lock_open", "lock_shield", "key", "key_multiple",
        "alert", "alert_badge", "alert_off", "alert_on", "alert_urgent", "info",
        "error_circle", "question_circle", "checkmark_circle", "dismiss_circle", "fingerprint",
        "password", "incognito", "certificate", "scan_person", "eye", "eye_off",
        "flashlight", "flashlight_off", "cloud_checkmark", "cloud_error", "person_warning",
    ],
    "Работа и быт": [
        "briefcase", "briefcase_medical", "toolbox", "wrench", "wrench_screwdriver",
        "calculator", "document", "document_add", "document_edit", "document_save",
        "document_search", "document_pdf", "document_signature", "folder", "folder_open",
        "folder_add", "clipboard", "clipboard_checkmark", "clipboard_task", "code",
        "code_block", "database", "database_search", "print", "scan", "ruler", "compose",
        "organization", "building", "building_bank", "building_factory", "building_shop",
        "cart", "shopping_bag", "wallet", "wallet_credit_card", "payment", "receipt",
        "receipt_money", "money", "money_hand", "gift", "trophy", "book", "book_open",
        "learning_app", "pill", "food", "food_apple", "food_cake", "food_pizza",
    ],
}


TOKENS = {
    "add": "добавить", "subtract": "убрать", "dismiss": "закрыть", "checkmark": "готово",
    "edit": "изменить", "delete": "удалить", "save": "сохранить", "copy": "копировать",
    "share": "поделиться", "search": "поиск", "filter": "фильтр", "settings": "настройки",
    "more": "ещё", "horizontal": "горизонтально", "vertical": "вертикально",
    "arrow": "стрелка", "left": "влево", "right": "вправо", "up": "вверх",
    "down": "вниз", "sync": "синхронизация", "download": "скачать", "upload": "загрузить",
    "import": "импорт", "export": "экспорт", "clockwise": "по часовой",
    "counterclockwise": "против часовой", "expand": "развернуть", "collapse": "свернуть",
    "all": "всё", "maximize": "увеличить", "minimize": "уменьшить", "move": "переместить",
    "reorder": "порядок", "grid": "сетка", "dots": "точки", "apps": "приложения",
    "list": "список", "panel": "панель", "window": "окно", "multiple": "несколько",
    "eye": "видимость", "off": "выключено", "color": "цвет", "fill": "заливка",
    "paint": "краска", "brush": "кисть", "bucket": "ведро", "sparkle": "эффект",
    "star": "звезда", "heart": "сердце", "play": "воспроизвести", "pause": "пауза",
    "stop": "стоп", "record": "запись", "previous": "предыдущий", "next": "следующий",
    "skip": "перемотка", "back": "назад", "forward": "вперёд", "music": "музыка",
    "note": "нота", "album": "альбом", "headphones": "наушники", "sound": "звук",
    "wave": "волна", "speaker": "динамик", "mute": "без звука", "mic": "микрофон",
    "video": "видео", "clip": "клип", "camera": "камера", "image": "изображение",
    "live": "эфир", "bookmark": "закладка", "call": "звонок", "end": "завершить",
    "missed": "пропущенный", "inbound": "входящий", "outbound": "исходящий",
    "transfer": "перевести", "chat": "чат", "history": "история", "warning": "опасность",
    "mail": "почта", "inbox": "входящие", "send": "отправить", "people": "люди",
    "person": "человек", "contact": "контакт", "card": "карточка", "voicemail": "автоответчик",
    "comment": "комментарий", "mention": "упоминание", "phone": "телефон", "news": "новости",
    "globe": "мир", "attach": "вложение", "link": "ссылка", "qr": "QR", "code": "код",
    "calendar": "календарь", "today": "сегодня", "map": "карта", "drive": "поездка",
    "location": "геопозиция", "ripple": "сигнал", "target": "цель", "square": "квадрат",
    "compass": "компас", "true": "точный", "north": "север", "northwest": "северо-запад",
    "directions": "маршрут", "navigation": "навигация", "road": "дорога", "cone": "конус",
    "earth": "земля", "branch": "развилка", "fork": "ветка", "scan": "сканировать",
    "object": "объект", "routing": "маршрутизация", "rectangle": "прямоугольник",
    "vehicle": "транспорт", "car": "автомобиль", "cable": "канатный",
    "collision": "авария", "parking": "парковка",
    "profile": "профиль", "clock": "часы", "truck": "грузовик", "bus": "автобус",
    "cab": "такси", "motorcycle": "мотоцикл", "bicycle": "велосипед", "tractor": "трактор",
    "gas": "топливо", "pump": "заправка", "engine": "двигатель", "seat": "сиденье",
    "flash": "вспышка", "auto": "авто", "key": "ключ", "toolbox": "инструменты",
    "wrench": "ключ", "screwdriver": "отвёртка", "battery": "батарея", "charge": "зарядка",
    "temperature": "температура", "home": "дом", "garage": "гараж", "building": "здание",
    "door": "дверь", "lock": "замок", "closed": "закрыт", "open": "открыт",
    "lightbulb": "лампа", "pulse": "пульс", "plug": "розетка", "connected": "подключено",
    "disconnected": "отключено", "power": "питание", "water": "вода", "drop": "капля",
    "fire": "огонь", "fireplace": "камин", "dome": "купол", "wifi": "Wi‑Fi",
    "router": "роутер", "bluetooth": "Bluetooth", "alarm": "тревога", "presence": "присутствие",
    "available": "доступен", "away": "нет дома", "busy": "занят", "leaf": "лист",
    "animal": "животное", "dog": "собака", "weather": "погода", "sunny": "солнечно",
    "cloudy": "облачно", "rain": "дождь", "snowflake": "снег", "thunderstorm": "гроза",
    "shield": "защита", "error": "ошибка", "keyhole": "скважина", "prohibited": "запрещено",
    "question": "вопрос", "task": "задача", "alert": "предупреждение", "badge": "метка",
    "urgent": "срочно", "info": "информация", "circle": "круг", "fingerprint": "отпечаток",
    "password": "пароль", "incognito": "инкогнито", "certificate": "сертификат",
    "flashlight": "фонарь", "cloud": "облако", "briefcase": "портфель", "medical": "медицина",
    "calculator": "калькулятор", "document": "документ", "pdf": "PDF", "signature": "подпись",
    "folder": "папка", "clipboard": "буфер", "database": "база данных", "print": "печать",
    "ruler": "линейка", "compose": "создать", "organization": "организация", "bank": "банк",
    "factory": "завод", "shop": "магазин", "cart": "корзина", "shopping": "покупки",
    "bag": "сумка", "wallet": "кошелёк", "credit": "кредит", "payment": "оплата",
    "receipt": "чек", "money": "деньги", "hand": "рука", "gift": "подарок",
    "trophy": "награда", "book": "книга", "learning": "обучение", "app": "приложение",
    "pill": "лекарство", "food": "еда", "apple": "яблоко", "cake": "торт", "pizza": "пицца",
    "duststorm": "пыль", "disabled": "отключено", "check": "проверка",
    "text": "текст", "sort": "сортировка", "ascending": "по возрастанию",
    "switch": "переключить", "hint": "подсказка", "ltr": "слева направо",
    "tag": "метка", "two": "двойной", "on": "включено", "block": "блок",
}


def label_for(name: str) -> str:
    words = [TOKENS.get(token, token.replace("_", " ")) for token in name.split("_")]
    label = " ".join(word for word in words if not word.isdigit())
    return label[:1].upper() + label[1:]


def android_vector(svg_path: Path, source_name: str) -> str:
    root = ET.parse(svg_path).getroot()
    view_box = root.attrib.get("viewBox")
    if view_box != "0 0 24 24":
        raise ValueError(f"{source_name}: unexpected viewBox {view_box}")
    paths = []
    for child in root:
        tag = child.tag.rsplit("}", 1)[-1]
        if tag != "path":
            raise ValueError(f"{source_name}: unsupported SVG element {tag}")
        if "transform" in child.attrib or child.attrib.get("fill") == "none":
            raise ValueError(f"{source_name}: unsupported transformed/stroked path")
        data = child.attrib.get("d")
        if not data:
            raise ValueError(f"{source_name}: empty path")
        fill_type = " android:fillType=\"evenOdd\"" if (
            child.attrib.get("fill-rule") == "evenodd" or child.attrib.get("clip-rule") == "evenodd"
        ) else ""
        opacity = child.attrib.get("opacity") or child.attrib.get("fill-opacity")
        alpha = f" android:fillAlpha=\"{html.escape(opacity)}\"" if opacity else ""
        paths.append(
            f"    <path android:fillColor=\"@android:color/white\"{fill_type}{alpha}\n"
            f"        android:pathData=\"{html.escape(data, quote=True)}\" />"
        )
    if not paths:
        raise ValueError(f"{source_name}: no paths")
    return (
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
        f"<!-- Microsoft Fluent UI System Icons 1.1.328 ({source_name}, filled 24px); MIT. -->\n"
        "<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
        "    android:width=\"24dp\" android:height=\"24dp\"\n"
        "    android:viewportWidth=\"24\" android:viewportHeight=\"24\">\n"
        + "\n".join(paths)
        + "\n</vector>\n"
    )


def java_source(entries: list[tuple[str, str, str]]) -> str:
    preset_rows = []
    resource_rows = []
    for category, name, label in entries:
        preset_rows.append(
            f'            result.add(new LauncherIconResolver.Preset("fluent_{name}", '
            f'"{label}", "{category}", "{name.replace("_", " ")}"));'
        )
        resource_rows.append(
            f'            case "fluent_{name}": return R.drawable.ic_fluent_{name};'
        )
    return f'''/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dezz.status.widget.R;

/** Generated curated Microsoft Fluent UI System Icons 1.1.328 catalog, loaded on demand. */
public final class FluentIconCatalog {{
    private static final int ICON_COUNT = {len(entries)};

    private FluentIconCatalog() {{}}

    @NonNull static List<LauncherIconResolver.Preset> presets() {{
        return PresetHolder.PRESETS;
    }}

    @DrawableRes static int resource(@Nullable String key) {{
        if (key == null) return 0;
        switch (key) {{
{chr(10).join(resource_rows)}
            default: return 0;
        }}
    }}

    @NonNull private static List<LauncherIconResolver.Preset> buildPresets() {{
        List<LauncherIconResolver.Preset> result = new ArrayList<>(ICON_COUNT);
{chr(10).join(preset_rows)}
        return Collections.unmodifiableList(result);
    }}

    private static final class PresetHolder {{
        @NonNull static final List<LauncherIconResolver.Preset> PRESETS = buildPresets();
    }}
}}
'''


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True,
                        help="unpacked @fluentui/svg-icons package root")
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    icons_dir = args.source / "icons"
    drawable_dir = args.repo / "app/src/main/res/drawable"
    java_file = args.repo / (
        "app/src/main/java/dezz/status/widget/launcher/FluentIconCatalog.java"
    )

    seen: set[str] = set()
    entries: list[tuple[str, str, str]] = []
    for category, names in CATEGORIES.items():
        for name in names:
            if name in seen:
                continue
            seen.add(name)
            source = icons_dir / f"{name}_24_filled.svg"
            if not source.is_file():
                raise FileNotFoundError(source)
            output = drawable_dir / f"ic_fluent_{name}.xml"
            output.write_text(android_vector(source, name), encoding="utf-8")
            entries.append((category, name, label_for(name)))

    java_file.write_text(java_source(entries), encoding="utf-8")
    print(f"Generated {len(entries)} Fluent icons and {java_file.relative_to(args.repo)}")


if __name__ == "__main__":
    main()
