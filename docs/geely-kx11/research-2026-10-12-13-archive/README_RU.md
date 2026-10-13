# Разборы кастомной прошивки и DIM за12–13.10.2026

Дополнение13.10: найден ещё один пропуск — [QNX graphics findings](qnx-oct12/findings.txt).
Итого32 архивных отчёта из9 архивов. Хеши дополнения и новый транспортный разбор:
[QNX panel route](../research-2026-10-13-dim/QNX_PANEL_ROUTE_RU.md).
Далее сохранено описание первоначальной публикации31/8; её манифест не расширен
задним числом, происхождение32-го отчёта приведено в дополнении.

Аудит выявил пропуск публикации накопленных исследований update и DIM.
Здесь добавлен31 отчёт из8 найденных архивов. Исходники Natro3.1.4,
требования выпуска и последний разбор driver/MCU ранее уже были опубликованы.
Область аудита — исследования прошивки и DIM этой задачи, не другие беседы.

## Указатель всех отчётов

- [update/VIRBOX_FINDINGS.txt](update/VIRBOX_FINDINGS.txt)
- [update/full_audit/SETTINGS_CATALOGUE_RU.txt](update/full_audit/SETTINGS_CATALOGUE_RU.txt)
- [update/full_audit/README.txt](update/full_audit/README.txt)
- [update/full_audit/XCSettings2/UI_FUNCTIONS_RU.txt](update/full_audit/XCSettings2/UI_FUNCTIONS_RU.txt)
- [update/full_audit/GMCSoft/RUNNERS_RU.txt](update/full_audit/GMCSoft/RUNNERS_RU.txt)
- [update/README.txt](update/README.txt)
- [update/deep_audit/COVERAGE_AND_NEXT_RU.txt](update/deep_audit/COVERAGE_AND_NEXT_RU.txt)
- [update/deep_audit/README.txt](update/deep_audit/README.txt)
- [update/deep_audit/SETTINGS_RESOLVED_INDEX_RU.txt](update/deep_audit/SETTINGS_RESOLVED_INDEX_RU.txt)
- [update/deep_audit/audio_seat_climate/README_RU.txt](update/deep_audit/audio_seat_climate/README_RU.txt)
- [update/deep_audit/climate_reset/README_RU.txt](update/deep_audit/climate_reset/README_RU.txt)
- [update/deep_audit/parameters/KANZI_PROTOCOL_RU.txt](update/deep_audit/parameters/KANZI_PROTOCOL_RU.txt)
- [update/deep_audit/parameters/README_RU.txt](update/deep_audit/parameters/README_RU.txt)
- [update/deep_audit/parameters/UI_PARAMETERS_RU.txt](update/deep_audit/parameters/UI_PARAMETERS_RU.txt)
- [update/deep_audit/runtime_comparison/README_RU.txt](update/deep_audit/runtime_comparison/README_RU.txt)
- [update/deep_audit/settings_versions/README_RU.txt](update/deep_audit/settings_versions/README_RU.txt)
- [update/deep_audit/system_handlers/README_RU.txt](update/deep_audit/system_handlers/README_RU.txt)
- [update/deep_audit/voice_commands/README_RU.txt](update/deep_audit/voice_commands/README_RU.txt)
- [dim-oct12/Natro-DIM-log-comparison-2026-10-12.txt](dim-oct12/Natro-DIM-log-comparison-2026-10-12.txt)
- [dim-oct12/Natro-DIM-UART-parameters.txt](dim-oct12/Natro-DIM-UART-parameters.txt)
- [dim-oct12/Natro-DIM-protocol-analysis-2026-10-12.txt](dim-oct12/Natro-DIM-protocol-analysis-2026-10-12.txt)
- [dim-oct12/Natro-DIM-white-bar-resource-trace.txt](dim-oct12/Natro-DIM-white-bar-resource-trace.txt)
- [dim-oct12/Natro-DIM-receiver-collection.txt](dim-oct12/Natro-DIM-receiver-collection.txt)
- [dim-oct12/Natro-DIM-file-analysis-2026-10-12.txt](dim-oct12/Natro-DIM-file-analysis-2026-10-12.txt)
- [dim-oct13/DIM-complete-chains-2026-10-13.txt](dim-oct13/DIM-complete-chains-2026-10-13.txt)
- [dim-oct13/DIM-findings-2026-10-13.txt](dim-oct13/DIM-findings-2026-10-13.txt)
- [dim-oct13/DIM-handler-check-2026-10-13.txt](dim-oct13/DIM-handler-check-2026-10-13.txt)
- [dim-oct13/DIM-missing-driver-2026-10-13.txt](dim-oct13/DIM-missing-driver-2026-10-13.txt)
- [dim-oct13/DIM-receiver-dispatch-2026-10-13.txt](dim-oct13/DIM-receiver-dispatch-2026-10-13.txt)
- [dim-oct13/DIM-receiver-search-2026-10-13.txt](dim-oct13/DIM-receiver-search-2026-10-13.txt)
- [dim-oct13/DIM-wings-parameters-2026-10-13.txt](dim-oct13/DIM-wings-parameters-2026-10-13.txt)

## Актуальные уточнения старых выводов

- Требование получить dim_dtc.ko уже выполнено новым дампом. Драйвер пересылает
  ttydim→ttyHS2; получение файла не означает нахождения графического получателя.
- Прежняя невозможность разобрать VLE частично преодолена: MCU A сопоставлен
  с ELF, разобран SPI-диспетчер. Он не является доказанным UART-получателем DIM.
- Новый DIM-service и libuart.so совпали с прошлым дампом. Совпадение других
  компонентов с текущей машиной по-прежнему не подтверждено.
- Однобайтовый payload и len=8 не означают восемь отдельных настроек. Поздний
  native-анализ обнаружил копирование8байт при исходном векторе длины1.
  Возможное чтение за логической границей не доказано причиной «крыльев».
- SettingsObserver fullMap/dimwallpaper имитирует входящие события Android;
  это не прямая команда физической приборке.
- Белая полоса DimMenu, голубой край карты Natro и штатные нижние «крылья»
  имеют разные проверяемые цепочки. Голубую кромку рисует drawMapVignette;
  отдельного выключателя в3.1.4 нет, чёрный цвет лишь меняет её оттенок.
- Числа272/297 ключей и378/219 обращений относятся к стадиям исследования,
  не количеству автомобильных функций. Уточнённый deep_audit имеет приоритет.

[Последний разбор реального драйвера и MCU](../research-2026-10-13-dim/README_RU.md).
[Карта оставшихся пробелов](update/deep_audit/COVERAGE_AND_NEXT_RU.txt).

## Доказательства и границы публикации

[PUBLICATION_MANIFEST.json](PUBLICATION_MANIFEST.json) связывает каждый отчёт
с архивом и исходным путём, содержит SHA256 архива, исходного и публикуемого текста.
29 текстов сохранены побайтово; в2 удалены только строки конкретных ключей
упаковки/расшифровки, номера строк отмечены. Первичные файлы не изменены.
Update получен повторно в текущей версии и совпал с ранее исследованным архивом.

Внутренние ссылки отчётов на отсутствующие здесь JSON/байткод/логи относятся
к соответствующему исходному архиву. APK, ELF, DEX, сырые журналы, массовые
листинги, ключи и сторонние инструменты не перепубликуются. Эта публикация
не означает повторного запуска всех анализаторов или проверки на автомобиле.

## Следующее направление

1. Установить физического получателя ttyHS2 по загрузочным конфигурациям,
   serializer/link и составу обновлений. Не отождествлять его с любым MCU
   или cluster_controller только по имени.
2. Найти образ этой цели, принимающий79 6C и отвечающий79 6D; проследить
   команды14/8 до выбора режима и графики. Проверенный SPI-диспетчер
   не подменяет этот участок цепочки.
3. Сопоставить код с уже имеющимися логами/видео. Если образа цели нет,
   назвать конкретный недостающий компонент и способ получения.
   Повтор нынешнего Android-дампа без новой цели не нужен.
4. Только после подтверждения отдельного управления оформлением готовить
   обратимое изменение. Замена режима не гарантирует сохранение карты.
5. Отдельная задача Natro — явное отключение цветной кромки карты;
   она не требует вмешательства в прошивку приборки.
