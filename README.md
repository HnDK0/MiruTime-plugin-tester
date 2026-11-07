> English: [README_EN.md](./README_EN.md)

# mirutime-plugin-tester

CLI-тул для отладки Lua-плагинов NoveLA: гоняет плагин через **ту же** либу
(`dev.mirutime.core`) и тот же HTTP-стек, что и приложение — второго Lua-движка
и второго HTTP-клиента в туле нет. Код тула когда-то был модулем
`:plugin-tester` в `MiruTime-Core` и вынесен отдельно: ядро остаётся чистым, тул
потребляет либу **опубликованным JVM-артефактом**.

Репозиторий: https://github.com/HnDK0/MiruTime-plugin-tester
(клонирование: `git clone https://github.com/HnDK0/MiruTime-plugin-tester.git`).

## Требования

- JDK 17+ (тулчейн Gradle — 21).
- `python3` + `DrissionPage` + браузер Brave — только для CF-bypass
  (опционально; без них тул просто деградирует в `CfChallengeRequired`).
- Сеть при первом запуске (или после `--refresh-dependencies`): тул скачивает
  Maven-репозиторий ядра из GitHub-релиза MiruTime.

## Версия mirutime-core

Версию не надо писать вручную: `settings.gradle.kts` скачивает
`mirutime-core-maven-<version>.zip` из релиза `nightly` в `HnDK0/MiruTime`,
а если там нет — из последнего опубликованного релиза. Zip распаковывается в
`build/core-repo`, версия пишется в `build/core-version.txt`. JitPack больше
не используется.

- Повторный запуск берёт кэш и не ходит в сеть. Принудительно обновить:
  `./gradlew --refresh-dependencies run ...`.
- Пока не вышел первый nightly с core-ассетами, сборка упадёт с понятной
  ошибкой — это ожидаемо.
- Draft-релиз не виден до публикации: fallback смотрит только на
  опубликованные релизы.

## Локальная разработка без нового релиза

Опубликовать ядро из монорепо и указать версию явно:

```bash
cd /Users/mac/Documents/GitHub/MiruTime
ANDROID_HOME=~/Library/Android/sdk ./gradlew :mirutime-core:publishToMavenLocal \
  -Pgroup=com.github.HnDK0.MiruTime -Pversion=0.1-local

cd /Users/mac/Documents/GitHub/MiruTime-plugin-tester
./gradlew -PmirutimeCoreVersion=0.1-local run --args="<plugin.lua>"
```

С `-PmirutimeCoreVersion` сеть не используется, артефакт берётся из
`mavenLocal()`.

## Запуск

```bash
./gradlew --console=plain -q run --args="<plugin.lua> [--url U] [--chapter-url U]"
```

| Аргумент | Смысл |
|---|---|
| `<plugin.lua>` | обязательный, путь к скрипту |
| `--url <url>` | URL книги для detail-тестов (иначе автоподбор из каталога) |
| `--chapter-url <url>` | URL главы (иначе автоподбор из списка глав) |
| `--auth` | authenticated-режим (CF bypass + cookies) |
| `--cf` | no-op: CF-bypass всегда автоматический |

Переменные окружения: `LUA_LIBS_DIR` — каталог для `require_lib` (иначе тул сам
копирует `*.lua` из `../external-sources/libs` в `build/lua_libs`),
`CF_SOLVE_SCRIPT` — путь к CF-скрипту (иначе `./cf_solve.py` в корне проекта).

## Exit codes

| Код | Когда |
|---|---|
| `0` | все тесты прошли (`N/N passed · ALL PASSED`) |
| `1` | нет аргументов (печатает usage), файл не найден, `LOAD FAILED`, либо любой тест `FAIL` |

Через Gradle ненулевой код валит задачу: `BUILD FAILED` с
`finished with non-zero exit value 1`.

## .cf_cookies.json

Куки CF-обхода вместе с User-Agent сохраняются в `.cf_cookies.json` в корне
проекта (файл в `.gitignore`, runtime-state, не коммитить). При следующем запуске
UA воспроизводится — `cf_clearance` валиден только с тем же UA.

## Лицензия

GPL-3.0-or-later © 2026 HnDK0, см. LICENSE.
