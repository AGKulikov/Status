# Свежий дамп Natro3.1.8: graphics ELF получены

Источник пользователя14.10.2026:
`Natro-system-1c4ac026-d118-4c5d-a875-d236c87ee2d6.zip`, SHA256
`040d94920daeea24631ba82789e85956924e0138ead7fd38d57dae84f3f037e2`.
Все ZIP CRC проверены; независимо сверены размеры и SHA256 всех20 сохранённых
payloads с manifest:18 системных файлов и2 metadata. Сырьё не публикуется.

## Аппаратный результат сборщика

Android7/9: отсутствуют или недоступны только альтернативные dim_dtc.ko в
/vendor_dlkm и /system; основной /vendor/lib/modules/dim_dtc.ko получен.
QNX11/11: каждый файл имеет collected_double_read. Это подтверждает успешный
сбор новых целей на машине, но не независимый удалённый hash и не atomic snapshot.

| Новая цель | Байты | SHA256 |
|---|---:|---|
| variant.cfg | 10 | b3c82a8bece3438b253e1182c94fb2ce4d11b359108f9b894d93857c3b2fa69f |
| /bin/openwfd_server | 1969280 | 50f52f0714d828ead04227686ea916d488c79562f313c87146ff991e1f3e086c |
| /bin/openwfd_telltale | 18416 | 639d4c28a9e17bbfd0da19601b9aefff94f46fdd46d0a6c538ca542700538e69 |
| /bin/screen | 382788 | afd9ec92d33c4d51d3559b72f911a3abfab4e5d8f850b5809edb76956bd432d0 |
| /lib/libopenwfd_qnx.so | 95184 | 83c812a290fd572018a985be415d28d790e21741b652a139787f746068332ecf |

variant.cfg теперь подтверждён свежими байтами: `KX11_HIGH\n`.
Три panel library, qcdisplaycfg.xml, startup.sh, linux.config совпадают по SHA
с предыдущим дампом. Самый большой новый файл openwfd_server прочитан дважды
за7243мс по локальному diagnostic таймеру; транспорт file GET работает.

## Метаданные: ограничение нашего сборщика

Directory GET /proc и exefile реально работают. До остановки сохранены25 PID,
включая /bin/wfd_be (40984,40985) и /bin/screen (40986). Наличие executable path
не связывает процесс с конкретными пикселями и не раскрывает его аргументы.

На `/proc/40986/mappings` offset64274 получено `o:4ef:4ef:10001`:
0x4ef=1263, суммарно65537байтов. Это intentional limit+1 probe сверх64KiB,
затем `qnx_proc_size_limit`. В этот раз нет Connection refused или timeout;
этап занял585мс. Файл graphics-mappings.txt содержит только заголовок и выбранный
PID, а не полученный64KiB префикс: накопленный procText buffer при exception
не был передан. Последующие PID не прочитаны, поэтому отсутствие других имён
в processes.tsv не означает отсутствия процессов на машине.

Дефект полноты: слишком малый предел mappings и общий выход из process stage
при его превышении. Требуемая следующая правка: сначала закончить bounded
exefile inventory; mappings собирать отдельно, сохранять bounded prefix с явным
truncated статусом, не терять уже прочитанное и оставшиеся имена. Не просто
снимать ограничения или бесконечно увеличивать cap. На момент отчёта код этой
правки и новый APK не выпускались; для анализа уже полученных ELF новый дамп
не требуется. FIELD процессного этапа частично подтверждён, полнота OPEN.

## Первичный статический разбор новых ELF

Все четыре бинарника AArch64 QNX. Выводы основаны на dynamic symbols/imports,
строках и выбранных call sites; это не полный decompile и не физический тест.

- `openwfd_telltale`: QNX_info прямо описывает OpenWFD test application.
  Содержит пути bgr888_128x128_highbeam.rgb и bgr888_128x128_handbrake.rgb.
  main0x15d0: device/port/pipeline enumeration, создание источников через
  helper0x26d8 (вызовы0x1a5c/0x1a88), bind, commit, ROI CRC/VSync цикл.
  Это свидетельство тестовой программы вывода индикаторов, а не найденного
  OEM renderer крыльев. Программа не запускалась.
- `libopenwfd_qnx.so`: wfdCreateDevice_User0x8f80 вызывает
  user_os_utils_send_recv в0x9088; wfdDeviceCommit_User0x922c — в0x92fc.
  send_recv0x6b28 (552байта) вызывает write0x6b74/read0x6b88.
  Есть строка `/dev/openwfd_server`. Это клиентский графический обмен;
  в этих функциях не установлен receiver Android navigation UART.
- `openwfd_server`: main0x2c794 (1576байтов) вызывает resmgr_attach0x2c9e4,
  WFD_ClientMgr_Init0x2cb38, dispatch_create0x2cb68,
  thread_pool_create0x2ccb8 и thread_pool_start0x2cd94. Есть endpoint
  `/dev/openwfd_server`, qcdisplaycfg.xml, display/port/pipeline/commit managers.
  NEEDED: libplanedef.so, libdalconfig.so.1, libhalmdputil_s8155.so,
  libslog2.so.1, libc.so.4. Это графический resource manager; отдельный флаг
  крыльев в рассмотренных путях не установлен. Его полный dispatcher ещё
  не разобран, отсутствие renderer во всей системе не доказано.
- `screen`: NEEDED включает libWFD.so.1, libmtouch-calib.so.1,
  libscrmem.so.1, libmemobj.so.1, libslog2.so.1 и libc.so.4. Текущий PID
  подтверждён exefile, но live mappings обрезаны до сохранения. Нельзя считать
  NEEDED-декларацию доказательством фактически загруженного пути/версии library.

Следующий разбор — resource-manager handlers и связь source/pipeline с
конфигурацией DIM на уже полученных байтах. Связь navigation receiver →
render-state → независимый флаг OEM-крыльев остаётся OPEN. Команды режимов,
raw UART/IPCP и firmware writes не отправлялись. Новый APK этим отчётом
не обещается и физическое отключение крыльев не объявляется.
