> Русский: [README.md](./README.md)

# mirutime-plugin-tester

CLI tool for debugging NoveLA Lua plugins: runs a plugin through **the same**
library (`dev.mirutime.core`) and HTTP stack as the app — there is no second
Lua engine or second HTTP client in the tool. The tool code used to be the
`:plugin-tester` module in `MiruTime-Core` and was factored out: the core stays
clean, the tool consumes the library as a **published JVM artifact**.

Repository: https://github.com/HnDK0/MiruTime-plugin-tester
(clone: `git clone https://github.com/HnDK0/MiruTime-plugin-tester.git`).

## Requirements

- JDK 17+ (Gradle toolchain — 21).
- `python3` + `DrissionPage` + Brave browser — CF-bypass only
  (optional; without them the tool simply degrades to `CfChallengeRequired`).
- Local library publication (see below) — only for the dev loop with
  uncommitted edits; otherwise the tool takes it from JitPack.

## mirutime-core version (you don't need to write it anywhere)

The library version is set in a **single place** — `mirutimeCore` in
`gradle/libs.versions.toml`. By default it is `main-SNAPSHOT`: JitPack
builds it from the library's latest `main` commit on request, so the tool
always picks up fresh code with no version edits (dev mode; don't do this for
reproducibility — JitPack itself warns against it).

- Pin a tag: in the same `gradle/libs.versions.toml` set
  `mirutimeCore = "v0.1.0"` (JitPack serves the version strictly by tag name,
  including the `v` prefix; without it nothing resolves).
- Nuance: Gradle caches snapshots for 24 hours — to force a fresh
  `main-SNAPSHOT`: `./gradlew --refresh-dependencies run ...`.

## Library publication (local dev loop only)

```bash
cd /Users/mac/Documents/GitHub/MiruTime-Core
ANDROID_HOME=~/Library/Android/sdk ./gradlew :mirutime-core:publishToMavenLocal -Pgroup=com.github.HnDK0.MiruTime-Core -Pversion=main-SNAPSHOT
```

Without local publication the tool takes the same version from JitPack
(`mavenLocal()` first in the repository list, JitPack as fallback; in release —
a tag pin or a GitHub Release artifact, see §4 of the library README).

## Running

```bash
./gradlew --console=plain -q run --args="<plugin.lua> [--url U] [--chapter-url U]"
```

| Argument | Meaning |
|---|---|
| `<plugin.lua>` | required, path to the script |
| `--url <url>` | book URL for detail tests (otherwise auto-picked from the catalog) |
| `--chapter-url <url>` | chapter URL (otherwise auto-picked from the chapter list) |
| `--auth` | authenticated mode (CF bypass + cookies) |
| `--cf` | no-op: CF bypass is always automatic |

Environment variables: `LUA_LIBS_DIR` — directory for `require_lib` (otherwise
the tool copies `*.lua` from `../external-sources/libs` into `build/lua_libs`),
`CF_SOLVE_SCRIPT` — path to the CF script (otherwise `./cf_solve.py` at the
project root).

## Exit codes

| Code | When |
|---|---|
| `0` | all tests passed (`N/N passed · ALL PASSED`) |
| `1` | no arguments (prints usage), file not found, `LOAD FAILED`, or any `FAIL` test |

A non-zero code fails the Gradle task: `BUILD FAILED` with
`finished with non-zero exit value 1`.

## .cf_cookies.json

CF-bypass cookies together with the User-Agent are saved to `.cf_cookies.json`
at the project root (file in `.gitignore`, runtime state, do not commit). On the
next run the UA is replayed — `cf_clearance` is only valid with the same UA.

## License

GPL-3.0-or-later © 2026 HnDK0, see LICENSE.
