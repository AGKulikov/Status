#!/bin/bash
set -u
SCRIPT_DIR="$(cd -- "$(dirname "$0")" && pwd)"
PYTHON_BIN=""
for candidate in "$(command -v python3 2>/dev/null || true)" /opt/homebrew/bin/python3 /usr/local/bin/python3 /usr/bin/python3; do
  if [ -n "$candidate" ] && [ -x "$candidate" ] && "$candidate" -c 'import sys; raise SystemExit(0 if sys.version_info >= (3,8) else 1)' >/dev/null 2>&1; then
    PYTHON_BIN="$candidate"
    break
  fi
done
if [ -z "$PYTHON_BIN" ]; then
  echo "Не найден Python 3.8 или новее. Порядок установки указан в README_RU.md."
  read -r -p "Нажмите Enter, чтобы закрыть окно. " _
  exit 2
fi
"$PYTHON_BIN" "$SCRIPT_DIR/collect_missing.py" "$@"
result=$?
if [ "$result" -eq 0 ]; then
  echo "Сбор по плану завершён. Путь архива указан выше."
elif [ "$result" -eq 3 ]; then
  echo "Собраны доступные данные. Причины пропусков включены в архив — пришлите его тоже."
else
  echo "Запуск завершился с кодом $result. Сохранённый архив, если он создан, указан выше."
fi
read -r -p "Нажмите Enter, чтобы закрыть окно. " _
exit "$result"
