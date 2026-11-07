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
- Network on the first run (or after `--refresh-dependencies`): the tool
  downloads the core Maven repository from a MiruTime GitHub release.

## mirutime-core version

You never write it by hand: `settings.gradle.kts` downloads
`mirutime-core-maven-<version>.zip` from the `nightly` release of
`HnDK0/MiruTime`, falling back to the latest published release. The zip is
unpacked into `build/core-repo` and the version into
`build/core-version.txt`. JitPack is no longer used.

- A repeated run uses the cache and does not touch the network. Force a
  refresh: `./gradlew --refresh-dependencies run ...`.
- Until the first nightly with core assets exists, the build fails with a
  clear message — expected.
- Draft releases are invisible until published; the fallback only sees
  published releases.

## Local development without a new release

Publish the core from the monorepo and pass the version explicitly:

```bash
cd /Users/mac/Documents/GitHub/MiruTime
ANDROID_HOME=~/Library/Android/sdk ./gradlew :mirutime-core:publishToMavenLocal \
  -Pgroup=com.github.HnDK0.MiruTime -Pversion=0.1-local

cd /Users/mac/Documents/GitHub/MiruTime-plugin-tester
./gradlew -PmirutimeCoreVersion=0.1-local run --args="<plugin.lua>"
```

With `-PmirutimeCoreVersion` the network is skipped and the artifact comes
from `mavenLocal()`.

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
