# Natro 3.1.7 / 208021350: изменения исходников

14.10.2026. Основание — повтор белой полосы, два источника предупреждений о
заряде и просьба продолжить разбор крыльев. База: Natro3.1.6,
commit e66d409c592cde04e4ad9055c97a253cad01f366.

## Заряд телефона

PhoneBatteryLevelPolicy уже выбирала точный BLE BAS/Android-процент перед грубым
Helper/HFP. Но WidgetService сравнивал только battery.level с порогом: при
недоступности прямого источника грубый fallback мог вызвать предупреждение или
перевооружить уже показанное. Это подтверждённая ошибка обработки источника.

Для обоих порогов теперь требуется свежая, доступная пара level/level_source
одного PHONE connector и одного snapshot timestamp. Допущены только целые0…100
от ble_bas, android_broadcast или hfp_ecarx_percent. Грубые и неизвестные данные
не вызывают предупреждение и не сбрасывают latch. Переход источника с неизменным
процентом тоже повторно проверяется. Отложенное уведомление проверяет текущий
точный уровень непосредственно перед показом. Persistent latch записывается
только после реального показа; прежние два порога и гистерезис2% сохранены.

Новые события phone-battery различают источник и фактический local_exact показ
без текста ANCS и идентичности телефона. Журнал3.1.6 подтверждает наличие Helper
и последующего BAS, но не раскрывает содержание ANCS-дубля. Поэтому произвольные
ANCS-уведомления не фильтруются по догадке. Возможный отдельный системный дубль
iOS ещё требует подтверждения; ошибка local fallback и ANCS — не одно утверждение.

## Белая полоса

В журнале1791951101000 на3.1.6 DimMenu завершён и запущен заново, NaviMode3→1;
окно Natro осталось attached/visible/drawn1920×720. Причина завершения не собрана.
При mode1 свежие callbacks не доказали navigation idle и own display foreground;
существующие gates справедливо не принуждали mode3. Убирать эти gates нельзя.

Скрытие штатного overlay в mode3 теперь использует AppOp `ignore` вместо `deny`.
В Android9 MODE_IGNORED допускается в PhoneWindowManager.checkAddPermission, а
WindowState скрывает такое окно. MODE_ERRORED способен вернуть ADD_PERMISSION_DENIED.
Это устраняет жёсткий отказ addWindow как возможный источник OEM-ошибки; связь с
конкретным завершением DimMenu в этом журнале пока не доказана. OEM может иметь
изменения и отдельный systemUID-путь. Проверяется точный readback `ignore`;
сохранённый старый `deny` при старте переустанавливается. Mode1/unknown/выключение
восстанавливают `allow`. Условия возврата mode3 и callbacks3→1 не ослаблены.

Первичные Android9 источники:
[PhoneWindowManager](https://android.googlesource.com/platform/frameworks/base/+/android-9.0.0_r1/services/core/java/com/android/server/policy/PhoneWindowManager.java),
[WindowState](https://android.googlesource.com/platform/frameworks/base/+/android-9.0.0_r1/services/core/java/com/android/server/wm/WindowState.java).

## Проверки и аппаратная граница

Добавлены executable replays настоящих методов WidgetService: coarse→BAS при
одинаковом проценте, отсутствие повторов, recovery+2%, оба порога, неверная/
устаревшая provenance, неуспешная очередь. Controller replay проверяет старый
deny→ignore, mode3/1, burst, внешний сброс AppOp и восстановление allow.
Полный Android CI, подпись и APK описываются отдельным release report после
успешного выполнения. Source/test не заменяет KX11 acceptance.

На машине требуется проверить: readback ignore в mode3 без новой полосы,
штатное меню/volume и возврат allow, переходы сна/питания, два порога по точному
источнику без повторов после reconnect. Исчезновение полосы ещё не подтверждено.
Результат разбора крыльев: [QNX init и UART](../../geely-kx11/research-2026-10-14-dim/README_RU.md).
