# QNX: видеоканал DIM и граница поиска UART receiver

13.10.2026. Только чтение пользовательских архивов. Автомобильные команды не
отправлялись, APK и прошивка не менялись. Августовские QNX-снимки не объявляются
тождественными текущей кастомной прошивке: соответствие требует свежих хешей.

## Подтверждённая конфигурация

Из KX11-QNX-Deep-Evidence.zip, qnx/files/bin/qcdisplaycfg.xml, SHA256
`dcf30e51241bb57086c4f814bc8f7c17e96e91f90d19d796b68ae9acbd0a6b1e`.
Активные XML-элементы разобраны отдельно от комментариев.

| Клиент LA_GVM 0x7815 | QDI display | Слой / Z | PanelLibrary |
|---|---:|---|---|
| port3, DIM+HUD | 1 | 16 / 6 | MAX96755_QC_0_UART |
| port4, PSD | 2 | 17 / 7 | MAX96755_QC_1_UART |
| port1/2, CSD | 3 | 14/15, Z1/4 | DP_COMMON_QC_UART |

Указан корень библиотек /lib64/. Инвентарь qnx/inventory/all-paths.txt
(SHA256 `e5d284c51a9d01762b2ed99b75a0ee6483e4b3465048864b7c9704d8ac0cc45c`)
содержит /lib/libMAX96755_QC_0_UART.so, /lib/libMAX96755_QC_1_UART.so и
/lib/libDP_COMMON_QC_UART.so. Эквивалентность /lib и /lib64 не доказана.
Display1 задаёт timing1920x1080; display2 —1920x720. Это транспортная конфигурация,
не доказательство размеров конечной карты и не Android display ID.

Связь DIM+HUD с библиотекой MAX96755 конкретнее прежнего общего указания на UART.
Но библиотека настройки видеопанели ещё не является найденным обработчиком
навигационного режима или renderer «крыльев».

## Порты: что подтверждено и что ещё нельзя объединять

qnx/files/scripts/startup.sh SHA256
`db9d3c055ed19c240546bdeebaadf7a07c291c201707b46ca4c396dd35dfb729`:
devc-quipv3 запускается для QUP9 и QUP14 с400000 бод, затем QUP4 с115200;
после запусков ожидаются /dev/ser2, /dev/ser3 и /dev/ser4 соответственно.
Touch-конфиги относят CSD к ser3, PSD к ser2. Номер ser2 не доказывает связь с ttyHS2.

Новый источник: KX11-QNX-Gap-Evidence-20260815-185143.zip,
qnx/files/ramdisk/linux.config SHA256
`78ec50fdbf5ed785dd019307fa9e1b9b9a70247891edb3d2d67be3c821bccbce`.
Активен passthrough QUP4 MMIO0x890000/IRQ637; также активны QUP13,0,10,16.
Passthrough QUP9 и14 закомментирован. В sa8155-vm-la.dtb есть активный
HS-UART0x890000 с QUP4 GPIO53/54, но нет serial aliases, которые связывали бы его
с ttyHS2. Порядок узлов DTB не является доказательством нумерации Linux tty.
Пересечение QUP4 между конфигами требует проверки фактического запуска/владения;
нельзя объявлять QNX ser4 непосредственным получателем Android ttyHS2.

## Дополнительный поиск

Проверены центральные каталоги шести архивов ниже; payload целевых трёх
panel-библиотек не найден. В текстовых файлах искались ttyHS2/MAX96755/ser4/QUP.
Live-Discovery повторно подтверждает имена двух MAX96755 в listings/lib.txt.
Это отрицательный результат только для проверенных архивов, не всего устройства.

| Архив | SHA256 |
|---|---|
| KX11-QNX-Gap-Evidence-20260815-185143.zip | fc3ccc07151a6b33de8c51ad3b6d630febff29749e4961c4ba086f608d1ed174 |
| KX11-QNX-Live-Discovery.zip | b2742d3106182f8e12fb4dc57a81cc5b5b2081fb73d8b46168d1f95861bf09a8 |
| KX11-QNX-Window-Evidence.zip | b20438bbc2f0a23d3f4e18ccaf89d738aaf61668b2a511d89e8a1cf549c6ffe9 |
| KX11-VP-QNX-Live-Window-Evidence-20260815-173650.zip | a66ead04d4de8ed1b70169e082c49d18b895f764eda570884f37c577f760d563 |
| KX11-VP-QNX-Live-Window-Evidence-20260815-174011.zip | dda269c6fb96209da39499d825f1688b0805f9206190883598f8e09f7390042c |
| KX11-VP-QNX-Boot-Window-Evidence-20260815-171623.zip | 7d5544e1dce45a8a2e2c85a428cbe60c80b0fee23145978c32331ccc12326b7d |

## Следующее необходимое доказательство

1. Read-only sysfs-ссылка /sys/class/tty/ttyHS2/device, uevent и загрузочные
   сообщения UART с текущего устройства: точная привязка ttyHS2 к контроллеру.
2. Байты /lib/libMAX96755_QC_0_UART.so, второго MAX96755 и DP_COMMON из QNX,
   плюс разрешение путей /lib64. Искать open/ioctl/write, имена портов и callbacks.
3. Только после привязки транспорта искать принимающий диспетчер режима и
   отдельное управление оформлением. Выключение FULL/navigation не равно
   выключению «крыльев» с сохранением карты.

## Исправление полноты публикации

После первой консолидации31 отчёта обнаружен пропущенный отчёт findings.txt из
Natro-QNX-graphics-investigation.zip от12.10.2026. Архив SHA256
`0eca006c585bc5d9c2b6a7a04de2c7858ba33fce34b6a43006312bb3d4a42c03`,
отчёт SHA256 `c72dec0f7c71136d1559ee30f92484d1e5095622108a1739f1fbe704d16b9967`.
Добавлена неизменённая копия [findings.txt](../research-2026-10-12-13-archive/qnx-oct12/findings.txt).
Теперь консолидировано32 архивных отчёта из9 архивов; исходный манифест31
сохраняет историческую область, это дополнение документирует32-й отдельно.
Результаты этого старого отчёта не выдаются за новые находки13октября.
