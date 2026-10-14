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
- Локальная публикация либы (см. ниже) — только для dev-цикла с
  незакоммиченными правками; иначе тул берёт её из JitPack.

## Версия mirutime-core (писать её нигде не надо)

Версия либы задана в **единственном месте** — `mirutimeCore` в
`gradle/libs.versions.toml`. По умолчанию там `main-SNAPSHOT`: JitPack
собирает её из последнего коммита `main` либы по запросу, так что тул всегда
берёт свежий код без правок версий (dev-режим; для воспроизводимости так
делать не стоит — сам JitPack от этого предостерегает).

- Зафиксировать тег: в том же `gradle/libs.versions.toml` поставить
  `mirutimeCore = "0.1.0"` (тег либы вида `v0.1.0`).
- Нюанс: Gradle кэширует снапшоты 24 часа — чтобы принудительно подтянуть
  свежий `main-SNAPSHOT`: `./gradlew --refresh-dependencies run ...`.

## Публикация либы (только для локального dev-цикла)

```bash
cd /Users/mac/Documents/GitHub/MiruTime-Core
ANDROID_HOME=~/Library/Android/sdk ./gradlew :mirutime-core:publishToMavenLocal -Pgroup=com.github.HnDK0.MiruTime-Core -Pversion=main-SNAPSHOT
```

Без локальной публикации тул берёт ту же версию из JitPack (`mavenLocal()`
первый в списке репозиториев, JitPack — запасной; в релизе — пин тега или
артефакт GitHub Release, см. §4 README либы).

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
