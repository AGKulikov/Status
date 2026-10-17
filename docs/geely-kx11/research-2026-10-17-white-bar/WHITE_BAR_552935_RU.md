# Повтор белой полосы на3.2.4, новые цели QNX и SOURCE-коррекция

Пользователь17.10.2026 в15:03МСК сообщил «Поймал опять белую полосу» и прислал
оба архива. В15:09 попросил сравнить MConfig+48, в15:14 — продолжить крылья и
рассмотреть сбор всего QNX. Это отрицательная полевая приёмка белой полосы.

## Проверенные архивы

| Архив | Байты | SHA-256 |
|---|---:|---|
| Natro-system-5529359674382157964.zip | 2991660 | 40068fa49471f8b959681d4fbc83064e5ccb550ac6e8d5bff8d9a39fad8c9c5a |
| Natro-diagnostics-2355975695365894588.zip | 213182 | 819e3f114ac630681f6fd1e0a4fa1e30848b4c9430b91c1635a0fa6e42812e6d |

CRC81/9entries PASS. У system76/76manifest size/SHA PASS. Версия3.2.4,
su_0, start_wall_ms1792238318075, elapsed83504мс; Android uptime1098.06/1099.03.
QNX33/33files получены, обход3193paths закончен. Доступность qconn подтверждена
при наличии полосы: связь «полоса появляется только при отказе QNX» опровергнута
этим эпизодом. Причина прежнего сетевого отказа не установлена.
Контроль без полосы — [833791 после reboot](../research-2026-10-16-qnx/DUMP_324_833791_RU.md).
Перезагрузка была ДО успешной tcpdump-пробы; promisc не объявляется исправлением.

## Что произошло с режимом и окнами

Session d82-3453/PID3458. В14:58:03.459 Natro прочитал Global=1/SDK=1 вместо3,
выбрал allow, записал AppOp и подтвердил его в14:58:03.703. Панель Natro при
этом alive/started/attached/visible/drawn,display2,1920x720. В14:58:03.706
пересоздана собственная DIM subscription; generation3 подключилась, но свежих
tab/control-center callbacks до конца нового журнала нет. Gate остаётся false.
До сброса были callbacks tab1/2/5 (14:55–14:56), но не center-state; нельзя
объявлять SDK постоянно неработающим или приравнивать default center0 к событию.

| Android window display2 | Без полосы,833791 | С полосой,552935 |
|---|---|---|
| Sys2038 dimmenu,PID4586,token56d7a8b | AppOp visibility=false, surface hidden | surface shown,visible=true |
| Natro InstrumentPanelActivity | visible,[0,0][1920,720] | то же окно и область |
| OEM HUD activity | visible,[0,720][728,906] | то же |
| OEM dimmenu main activity | visible,[728,720][1568,1032] | то же |
| plus.monjaro process | PID3515 | PID11031 |

DIM overlay имеет frame1920x1080, хотя Requested1920x720. Цвет его конкретных
пикселей дамп не содержит. Совместное появление overlay и пользовательской полосы
существенно, но не является пиксельным доказательством renderer-а.
В этом эпизоде нельзя повторять прежний вывод «dimmenu перезапустился»:
PID и tokens совпадают. Инициатор записи режима ещё не наблюдён.
[MConfig48/46.1](MCONFIG_48_COMPARISON_RU.md) содержит подходящий стартовый путь;
это гипотеза для проверки, не установленная причинность.

## Подтверждённая ошибка проверки владельца Natro

Старый InstrumentDisplayOwner отклоняет любой другой видимый stack на display2.
Объединённый display включает нижнюю область HUD: два видимых OEM Activity
находятся целиком ниже Natro, начиная сy720. В14:58:04.219 записано
visible_panel1/visible_other2/own=false. Наличие этих окон само по себе не
означает утрату Natro области[0,0][1920,720].

SOURCE-коррекция использует bounds stack-а точного InstrumentPanelActivity и
отклоняет пересекающиеся чужие stacks; непересекающиеся учитывает отдельно.
Неизвестная/пустая/повреждённая геометрия, неизвестный visible owner, неоднозначный
own stack по-прежнему запрещают восстановление. AOSP28 StackInfo повторяет
один topActivity/visible на каждой task-строке, поэтому брать bounds случайной
task нельзя. Используется stack header, не whitelist OEM-пакетов.
Формат сверён с android-28 ActivityManager.StackInfo.toString:
https://android.googlesource.com/platform/prebuilts/fullsdk/sources/android-28/+/master/android/app/ActivityManager.java

Независимые observed navigation-tab/control-center/engine и generation gates,
ограничение AppOp ignore только mode3, существующий shared DIM owner сохранены.
Эта правка НЕ достаточна для автоматического восстановления текущего эпизода:
navigation_idle=false остаётся. SOURCE/TEST не закрывает FIELD полосы.
SystemProcessEvent дополнен точным plus.monjaro в прежнем пассивном allowlist,
без новых разрешений, команд, raw Intent/reason или изменений MConfig.

## Новые цели для крыльев

Полный namespace inventory уже есть; он не является копией всех данных.
Проверено3193записи,10directory_open_failed; тип/размер многих недиректорий
неизвестны. ENOTDIR не подтверждает обычный файл. Всё подряд из/dev и/proc
не скачивается: там есть устройства, IPC и процессы. /proc/boot отдельно
содержит статические boot-файлы; он не тождественен/proc/PID.

В QNX обнаружены /userdata/DLT/DIM_Slog.log и многочисленные DIM_Slog_Boot ZIP.
Прежний GraphicsEvidence читает Android /mnt/userdata/DLT; это другой путь,
его недоступность не доказывает отсутствие QNX-журналов. Новые SOURCE targets:
- /persist/dim_config и /persist/system/config/cartype.cfg;
- /lib64/libopenwfd_core.so и /lib64/libscreen.so.1;
- /bin/uart-display-config и /usr/bin/swdl_display;
- /proc/boot/static_UI_PSD.png и /proc/boot/img_splash.conf;
- /userdata/DLT/DIM_Slog.log.

Все девять имён наблюдены в текущем inventory, содержимое пока не получено.
Ни swdl_display, ни uart-display-config не запускаются. Старые33targets
сохранены, теперь42. Прежний GET/S_IFREG/stream-to-disk/double-read/hash остаётся;
изменяющийся DIM_Slog сохраняется с changed_during_read, без ложного stable.
Это целевое расширение, НЕ реализация «скачать весь QNX». Архивы DIM_Slog_Boot
и полный снимок обычных файлов остаются следующими возможностями расширения.
Ранее проверенные mask API в libopenwfd.so — stubs; слепой global alpha всего
DIM+HUD не предлагается. Pixel owner/receiver/отдельное скрытие крыльев OPEN.

## Батарея и проверки

14:56:12.045: BAS exact20%, thresholds20/10, popup=true,status=false.
14:56:12.059: warning_presented stage1 route=local_exact и
accepted=true,presentation_confirmed=true. Подтверждён программный путь
предупреждения на20%; физическую видимость пользователь ещё не подтвердил.

Локально:8test_system_file_dump tests PASS (включая настоящий Java parser,
QNX wire/streaming/mappings и native ICU syntax);8методов реальных Java unit
tests owner/lifecycle PASS через assertion adapter, без Android runtime.
Review storage: прежние cache/exports/defaults/adapters; fingerprint обновлён
после review. Полный Android CI, новый APK и FIELD этой правки ещё не выполнены.
