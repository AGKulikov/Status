# Исходники выпуска 3.5.1

Ветка `release/natro-3.5.1-source` содержит исходники приложений и инструменты сборки. Они совпадают с коммитом `af3199bc43bfe547729ef802e85a7e833e273b56`. Для выпуска из этой ветки CI в отдельной ветке должен проверить точные коммит и дерево исходников, выполнить полную сборку и записать их в отчёт.

Каталог `release-workflows` содержит снимок исторических сценариев, которые читают тесты. Его дерево Git — `16476dfb949ba0a69b4eb3ff94b9dcf9ceb69219`. Чтобы запустить эти тесты в чистой временной копии исходников, выполните из корня проекта следующие команды в Bash. Требования к JDK, Android SDK и тестовым данным приведены в `release-workflows/build-natro-3.5.1.yml`.

```bash
(
  set -e
  mkdir -p .github
  ln -s ../tests/fixtures/release-workflows .github/workflows
  trap 'unlink .github/workflows' EXIT
  python3 -m unittest discover -s tools/tests -q
)
```
