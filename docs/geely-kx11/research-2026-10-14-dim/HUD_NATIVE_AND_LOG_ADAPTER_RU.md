# HUD native chain и штатный экспорт QNX — 14.10.2026

STATIC, без исполнения исследуемых бинарников и без соединения с автомобилем.
Крылья: OPEN. Ни один перечисленный адрес не является готовым patch offset.

## Первичные данные

Восстановлен `KX11-HU-Route-20260810-070911-29994-1234567.zip`:
SHA256 `e797f93892e2369b33248de620aa1c1bf907434a6334376cbf568127f12475b5`.
Совпадает с отдельным пользовательским .sha256;397 entries, ZIP CRC ошибок нет.

| Файл внутри native/ | Размер | SHA256 |
|---|---:|---|
| maps/libkx11_lib.so |285256|778a86916dceb94f79b47c939539a13053f9f58dd4c7a5bff5f5a1df5a60138f|
| bin/qnxslogadapter |136216|9fa96efd4cfeb9468a87112d42f83fe12c239a59896a78928a2724ee547afe13|

Уточнение к SLOG2_FORMAT_RU.md: каталог `native/maps` содержит в данном
архиве реальные ELF, а не только текстовые maps. Это проверено по байтам и
ELF parser. Предыдущее предупреждение не было основанием считать файл отсутствующим.
SHA библиотеки совпадает с ранее сохранённым source inventory.

## Прослеженный выход libkx11_lib

Зависимости: liblog/libc/libm/libdl. Экспортированы функции геометрических
преобразований, фильтрации полос, объектов, стрелок и калибровки HUD.
Отсутствие графических импортов само по себе не исключает любой косвенный renderer;
вывод ниже основан на конкретных вызовах и выходных данных.

Get_NAVI0x1cba0 при разрешённом обновлении вызывает Draw_Navi_new и возвращает
через семь указателей числовые поля: naviID,x,y,status,width,height,flip.
Ветвь0x1cc54..0x1cc84 копирует поля структуры; OutputData0x1cd80 также копирует
числовые структуры ADAS_LD/ADAS_Obj/NAVI. Это не передача framebuffer.
Встроенная диагностическая строка подтверждает имена полей OutputNAVI.

В июльской libnative-lib.so функция
Java_com_ecarx_hud_jni_JniUtils_Output_1Data_1NAVI0x1534 вызывает Get_NAVI,
затем находит Java NAVIInfoBean и заполняет те же семь short-полей через JNI.
Разные даты архивов ограничивают утверждение о runtime: это сопоставленные
статические ABI, не новый захват исполнения.

В HUD DEX:

1. ARHUDPresenter (com.test.h7$k.run,0x11ab28) получает InputNAVI,
   InputADASLD,InputVehicle и вызывает JniUtils.a.
2. JniUtils.a0x121da4 возвращает NAVIInfoBean через Output_Data_NAVI.
3. Callback com.test.g7.m реализован ARHUDFragment.m0x122e04.
4. При status1 выбирается SurfaceViewAnimation по naviID; затем
   com.test.t8.a0x1244e4 задаёт RelativeLayout width,height,leftMargin,topMargin
   и View.setRotation(flip). Это обычное размещение Android view.

Таким образом эта ветвь доведена до Android HUD view: библиотека рассчитывает
положение и состояние навигационного изображения. Называть её самостоятельным
слоем крыльев оснований нет. Другие функции библиотеки ещё не разобраны полностью;
ограничивать весь файл одним Get_NAVI также нельзя.

## Почему штатный QNX log adapter не пассивный reader

Найденный qnxslogadapter — Binder/SOME-IP сервис. Строки описывают операции
stop logging/umount на стороне QNX, обратное start/mount и clear slog.
Подтверждены вызовы system@plt0x4e08 из участка0x96bc..0x9714:
создание /mnt/userdata, umount -f, fsck_msdos -p /dev/block/vdp,
изменение прав и mount -t vfat -o ro,uid=1000 /dev/block/vdp /mnt/userdata.
Наличие ro в последнем mount не делает всю цепь read-only.

Это объясняет возможную архитектурную причину отсутствия Android
/mnt/userdata/DLT вне штатного экспорта: раздел может оставаться у QNX.
Причина ошибки конкретного октябрьского чтения этим не доказана; права и
отсутствие пути по-прежнему различать нельзя без дополнительного наблюдения.

Не запускать adapter и не вызывать его export/clear как часть пассивного сбора.
Следующий безопасный путь остаётся чтением уже существующих bounded QNX slog2
файлов; для Android — полный bounded snapshot композиции DIM+HUD.
