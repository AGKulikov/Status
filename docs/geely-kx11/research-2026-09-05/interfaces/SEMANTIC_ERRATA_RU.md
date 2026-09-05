# Семантические исправления к замороженному индексу интерфейсов

**Эти исправления обязательны при чтении страниц исходного индекса.** Значения, ключи, порядок и хэши страниц сохранены. Исправляется смысл классификации, а не исходные декларации.

## Повторные числа не всегда являются алиасами

909 деклараций содержат 808 различных чисел и 101 повторное числовое объявление, включая алиасы и совпадения разных API-контекстов. Числовое равенство само по себе не доказывает семантическую эквивалентность.

Четыре конкретных совпадения разных контекстов:

| Число | Конфигурация ICarInfo | Тип группы ISensorGroup |
|---|---|---|
| `0x00800100` | `CONFIG_INFO_TEM` (`adaptapi:12`) | `SENSOR_GROUP_TYPE_GYRO` (`adaptapi:251`) |
| `0x00800200` | `CONFIG_INFO_FINGERPRINT` (`adaptapi:5`) | `SENSOR_GROUP_TYPE_ACCE` (`adaptapi:250`) |
| `0x00800300` | `CONFIG_INFO_DVR` (`adaptapi:1`) | `SENSOR_GROUP_TYPE_PULES` (`adaptapi:252`) |
| `0x00800400` | `CONFIG_INFO_DVR_INNERCAM` (`adaptapi:2`) | `SENSOR_GROUP_TYPE_W4M` (`adaptapi:253`) |

Имена API-классов полностью указаны в [машиночитаемых исправлениях](semantic_errata.json). Это не четыре пары взаимозаменяемых функций. В частности, CONFIG_INFO_DVR и SENSOR_GROUP_TYPE_PULES используют одно число в разных контекстах.

## Исправление широкой роли function_id_declaration

Для **792** Adapt-записей роль `function_id_declaration` была назначена слишком широко по имени и классу. Её следует читать как **`function_named_declaration_unresolved`**: имя похоже на обозначение функции, но назначение каждой декларации этим не доказано. Из этих 792 записей для **20** ниже установлена более точная роль `state_value_declaration`; для остальных **772** сохраняется исправленная общая роль. Отдельная доказанная связь с реализацией функции остаётся самостоятельным более сильным свидетельством.

У всех 20 записей класс объявления — `Lcom/ecarx/xui/adaptapi/car/vehicle/IVehicle;`. Это значения семейств состояний цифрового ключа; их наличие не подтверждает установленное оборудование или работающий цифровой ключ.

| entry_key | Объявление | Значение |
|---|---|---:|
| `adaptapi:713` | `SETTING_FUNC_DIGITAL_KEY_SUSPENSION_IDLE` | 0 |
| `adaptapi:714` | `SETTING_FUNC_DIGITAL_KEY_SUSPENSION_REJECT_BNCM` | 5 |
| `adaptapi:715` | `SETTING_FUNC_DIGITAL_KEY_SUSPENSION_REJECT_CARMOD` | 3 |
| `adaptapi:716` | `SETTING_FUNC_DIGITAL_KEY_SUSPENSION_REJECT_KEY_NOT_EXIST` | 4 |
| `adaptapi:717` | `SETTING_FUNC_DIGITAL_KEY_SUSPENSION_REJECT_USGMOD` | 2 |
| `adaptapi:718` | `SETTING_FUNC_DIGITAL_KEY_SUSPENSION_SUCCESS` | 1 |
| `adaptapi:720` | `SETTING_FUNC_DIGITAL_KEY_TERMINATION_IDLE` | 0 |
| `adaptapi:721` | `SETTING_FUNC_DIGITAL_KEY_TERMINATION_REJECT_BNCM` | 6 |
| `adaptapi:722` | `SETTING_FUNC_DIGITAL_KEY_TERMINATION_REJECT_CARMOD` | 3 |
| `adaptapi:723` | `SETTING_FUNC_DIGITAL_KEY_TERMINATION_REJECT_KEY_NOT_EXIST` | 4 |
| `adaptapi:724` | `SETTING_FUNC_DIGITAL_KEY_TERMINATION_REJECT_NO_KEY` | 5 |
| `adaptapi:725` | `SETTING_FUNC_DIGITAL_KEY_TERMINATION_REJECT_USGMOD` | 2 |
| `adaptapi:726` | `SETTING_FUNC_DIGITAL_KEY_TERMINATION_SUCCESS` | 1 |
| `adaptapi:728` | `SETTING_FUNC_DIGITAL_KEY_UNPAIR_IDLE` | 0 |
| `adaptapi:729` | `SETTING_FUNC_DIGITAL_KEY_UNPAIR_REJECT_BNCM` | 6 |
| `adaptapi:730` | `SETTING_FUNC_DIGITAL_KEY_UNPAIR_REJECT_CARMOD` | 3 |
| `adaptapi:731` | `SETTING_FUNC_DIGITAL_KEY_UNPAIR_REJECT_NO_KEY` | 5 |
| `adaptapi:732` | `SETTING_FUNC_DIGITAL_KEY_UNPAIR_REJECT_NO_MOBILE` | 4 |
| `adaptapi:733` | `SETTING_FUNC_DIGITAL_KEY_UNPAIR_REJECT_USGMOD` | 2 |
| `adaptapi:734` | `SETTING_FUNC_DIGITAL_KEY_UNPAIR_SUCCESS` | 1 |

## Правило применения

При чтении сначала применить 20 явных переопределений; затем для оставшихся записей прежней роли использовать `function_named_declaration_unresolved`. Поле `id` замороженного JSON хранит числовое значение объявления и не гарантирует, что это самостоятельный идентификатор вызываемой функции.

Соединять данные нужно по пространству, классу, имени и `entry_key`; одного числа недостаточно. Эти исправления не являются полным аудитом семантики всех деклараций SDK.

Счётчики **4440 / 909 / 808 / 16082** не изменяются. SHA-256 исходного `entry_index.json`: `cd2d0823c33072015977b2fef236f29fcc843194a792d25e3d0e8ca736cc9964`.

Воспроизведение:

```bash
python3 build_semantic_errata.py /path/to/entry_index.json /path/to/output
```
