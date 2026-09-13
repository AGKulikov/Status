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
  echo "Не найден Python 3.8 или новее. Используйте окружение сборщика поездок."
  read -r -p "Нажмите Enter, чтобы закрыть окно. " _
  exit 2
fi
"$PYTHON_BIN" "$SCRIPT_DIR/collect_maps.py" "$@"
result=$?
if [ "$result" -ne 0 ]; then
  echo "Сбор завершился частично. Пришлите созданный ZIP: доступные данные и причина сохранены."
fi
read -r -p "Нажмите Enter, чтобы закрыть окно. " _
exit "$result"
