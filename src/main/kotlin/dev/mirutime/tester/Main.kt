package dev.mirutime.tester

import dev.mirutime.core.api.PluginDescriptor
import dev.mirutime.core.api.SUPPORTED_PLUGIN_API
import dev.mirutime.core.engine.adapter.LuaPluginSourceFactory
import dev.mirutime.core.installer.ScriptMetadataParser
import dev.mirutime.core.installer.definedFunctions
import dev.mirutime.core.installer.toPluginMetadata
import dev.mirutime.core.network.HostOptionsRegistry
import dev.mirutime.core.network.NetworkTraceEvent
import dev.mirutime.core.network.PluginHttpClient
import dev.mirutime.core.network.createEngine
import dev.mirutime.core.network.security.InetAddressHostResolver
import dev.mirutime.core.network.security.SsrfGuard
import java.io.File
import java.security.MessageDigest
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

private val USAGE = """
    |plugin-tester — NoveLA Lua plugin debugger
    |
    |Usage: plugin-tester <plugin.lua> [options]
    |
    |Options:
    |  --url <url>           Book URL for detail tests
    |  --chapter-url <url>   Chapter URL for text/page tests
    |  --cf                  No-op: CF bypass is always auto (cookies + on-demand solver)
    |  --auth                 Enable authenticated testing (CF bypass + cookies)
    |
    |Examples:
    |  plugin-tester my-plugin.lua
    |  plugin-tester my-plugin.lua --url https://example.com/book/123
    |  plugin-tester my-plugin.lua --url https://example.com/book/123 --cf
""".trimMargin()

// The 12 entry points the old tool listed under "Functions defined" — kept in
// its order so the Plugin Info block diffs 1:1 against old-tool logs. The
// introspection set below is wider (TestRunner checks the extras too).
private val DISPLAY_FUNCTIONS = listOf(
    "getCatalogList", "getCatalogSearch", "getCatalogFiltered",
    "getBookTitle", "getBookCoverImageUrl", "getBookDescription",
    "getBookStatus", "getBookRating", "getChapterList",
    "getChapterText", "getPageList", "getFilterList",
)

private val EXTRA_FUNCTIONS = listOf(
    "getBookGenres", "getBookLastUpdate", "getBookChapterCount", "getBookExtras",
    "parsePage", "getVideoList",
)

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println(USAGE)
        exitProcess(1)
    }

    val pluginFile = File(args[0])
    if (!pluginFile.isFile) {
        System.err.println("Plugin file not found: ${args[0]}")
        exitProcess(1)
    }

    var bookUrl: String? = null
    var chapterUrl: String? = null
    var useAuth = false
    var i = 1
    while (i < args.size) {
        when (args[i]) {
            "--url" -> { bookUrl = args.getOrNull(i + 1); i += 2 }
            "--chapter-url" -> { chapterUrl = args.getOrNull(i + 1); i += 2 }
            // No-op: CF bypass is always auto (cookies + on-demand solver).
            "--cf" -> i++
            "--auth" -> { useAuth = true; i++ }
            else -> i++
        }
    }

    val code = pluginFile.readText(Charsets.UTF_8)

    println()
    println("┌──────────────────────────────────────────────────────────────┐")
    println("│  plugin-tester — NoveLA Lua Plugin Debugger                  │")
    println("└──────────────────────────────────────────────────────────────┘")
    println()
    println("  Plugin:    ${pluginFile.name} (${pluginFile.length()} bytes)")
    println("  Book URL:  ${bookUrl ?: "(not provided)"}")
    println("  Chapter:   ${chapterUrl ?: "(not provided)"}")
    println("  CF bypass: auto (cookies from .cf_cookies.json, solver on demand)")
    println("  Auth mode:  ${if (useAuth) "enabled (CF bypass for full test)" else "disabled"}")
    println()

    // ── Host wiring ────────────────────────────────────────────────────────
    // Everything below is the lib's own seams: the tester adds no Lua and no
    // HTTP code of its own, it only supplies desktop implementations of the
    // host interfaces the app implements on Android.
    val logger = ConsoleLogger(echo = false)
    val cookieStore = FileCookieStore()
    val keyValueStore = MemoryKeyValueStore()
    val fileSystem = LocalFileSystem()
    val systemInfo = DesktopSystemInfo()
    val hostOptions = HostOptionsRegistry()
    val solver = CfSolver(cookieStore, hostOptions)
    // A cf_clearance cookie only passes with the UA that obtained it: replay
    // the per-host UAs recorded in .cf_cookies.json from earlier runs.
    cookieStore.userAgentEntries().forEach { (domain, ua) ->
        hostOptions.setUserAgent(domain, CfSolver.SOLVER_OWNER, ua)
    }
    val engine = createEngine()
    val ssrfGuard = SsrfGuard(InetAddressHostResolver())

    // One sink for BOTH clients (the plugin's own http_get and the tester's
    // raw fetches): the NETWORK TRACE section shows one chronological list.
    // Emitted from the runBlocking coroutine, so a plain list is enough.
    val traceEvents = mutableListOf<NetworkTraceEvent>()
    val traceSink: (NetworkTraceEvent) -> Unit = { traceEvents.add(it) }

    // ── Metadata + entry points ────────────────────────────────────────────
    val loadStart = System.currentTimeMillis()
    val scriptMeta = ScriptMetadataParser.parse(code)
    val sha256 = sha256Hex(code)
    val metadata = scriptMeta.toPluginMetadata(
        fallbackId = pluginFile.nameWithoutExtension,
        api = SUPPORTED_PLUGIN_API,
        sha256 = sha256,
    )
    val defined = definedFunctions(code, DISPLAY_FUNCTIONS + EXTRA_FUNCTIONS)

    // ── Plugin Info ────────────────────────────────────────────────────────
    println("── Plugin Info ────────────────────────────────────────────────")
    println("  Name:          ${scriptMeta.name.orEmpty()}")
    println("  Version:       ${scriptMeta.version.orEmpty()}")
    println("  Base URL:      ${scriptMeta.baseUrl.orEmpty()}")
    println("  Language:      ${scriptMeta.language.orEmpty().ifBlank { "?" }}")
    println("  Content type:  ${scriptMeta.contentType.orEmpty().ifBlank { "novel (default)" }}")

    val factory = LuaPluginSourceFactory(
        systemInfo = systemInfo,
        cookieStore = cookieStore,
        keyValueStore = keyValueStore,
        logger = logger,
        engine = engine,
        fileSystem = fileSystem,
        libDir = resolveLibDir(fileSystem),
        hostOptions = hostOptions,
        solver = solver,
        ssrfGuard = ssrfGuard,
        trace = traceSink,
    )

    // Load time measures the REAL load: factory.create() runs the chunk with
    // every binding installed, the same path the app takes on plugin install.
    val source = try {
        factory.create(PluginDescriptor(metadata, code))
    } catch (e: Exception) {
        System.err.println()
        System.err.println("╔══════════════════════════════════════════════════════════════╗")
        System.err.println("║  LOAD FAILED                                               ║")
        System.err.println("╚══════════════════════════════════════════════════════════════╝")
        System.err.println("  ${e.message}")
        System.err.println()
        exitProcess(1)
    }
    val loadMs = System.currentTimeMillis() - loadStart
    println("  Load time:     ${loadMs}ms")
    println()

    val present = DISPLAY_FUNCTIONS.filter { it in defined }
    val missing = DISPLAY_FUNCTIONS.filter { it !in defined }
    println("  Functions defined: ${present.joinToString(", ")}")
    if (missing.isNotEmpty()) {
        println("  Functions missing: ${missing.joinToString(", ")}")
    }
    println()

    // ── Tests ──────────────────────────────────────────────────────────────
    // Second PluginHttpClient for raw cross-checks: shares the engine, the
    // cookie jar and the trace sink with the plugin's client, but owns its own
    // CfGate/CfDetector — the factory cannot accept a shared gate (ceiling:
    // a CF challenge may be solved once per client in one run).
    val raw = PluginHttpClient(
        metadata,
        systemInfo,
        cookieStore,
        logger,
        engine,
        solver = solver,
        ssrfGuard = ssrfGuard,
        hostOptions = hostOptions,
        trace = traceSink,
    )
    val runner = TestRunner(source, raw, scriptMeta, defined, traceEvents)
    val results = runBlocking { runner.runAll(bookUrl, chapterUrl) }

    // ── Test Results ───────────────────────────────────────────────────────
    var passed = 0
    var failed = 0
    var totalMs = 0L

    for (r in results) {
        totalMs += r.durationMs
        val tag = if (r.passed) "PASS" else "FAIL"
        val color = if (r.passed) "" else " ←"

        println("── ${r.name} [$tag] (${r.durationMs}ms)$color ──")

        for (err in r.errors) {
            println("  ✗ $err")
        }
        for ((k, v) in r.details) {
            println("  · $k: $v")
        }

        println()
        if (r.passed) passed++ else failed++
    }

    // ── Summary ────────────────────────────────────────────────────────────
    println("══════════════════════════════════════════════════════════════")
    val verdict = if (failed == 0) "ALL PASSED" else "$failed FAILED"
    println("  $passed/${results.size} passed  ·  $verdict  ·  ${totalMs}ms total")
    println("══════════════════════════════════════════════════════════════")
    println()

    // ── Network Trace ──────────────────────────────────────────────────────
    // Same block shape as the old tool. Divergences carried by the lib's
    // NetworkTraceEvent: no `by: caller` field (hop N is printed instead), no
    // DDoS-Guard detector, body preview not captured (size only).
    if (traceEvents.isNotEmpty()) {
        println("══════════════════════════════════════════════════════════════")
        println("  NETWORK TRACE — ${traceEvents.size} requests")
        println("══════════════════════════════════════════════════════════════")
        println()

        for ((idx, entry) in traceEvents.withIndex()) {
            val status = when {
                entry.cfBlocked -> "⚠ CF-BLOCKED"
                entry.status in 200..299 -> "✓ ${entry.status}"
                entry.status in 300..399 -> "↗ ${entry.status}"
                else -> "✗ ${entry.status}"
            }

            println("  [${idx + 1}] ${entry.method} ${truncateUrl(entry.url)}")
            println(
                "      → $status  (${entry.bodyBytes}B, ${entry.durationMs}ms)  hop ${entry.redirectHop}",
            )

            if (entry.requestHeaders.isNotEmpty()) {
                println("      Request headers:")
                for ((k, v) in entry.requestHeaders.entries.sortedBy { it.key }) {
                    if (k.equals("cookie", ignoreCase = true)) {
                        val preview = v.take(80) + if (v.length > 80) "..." else ""
                        println("        $k: $preview")
                    } else {
                        println("        $k: $v")
                    }
                }
            }

            if (entry.responseHeaders.isNotEmpty()) {
                println("      Response headers:")
                for ((k, values) in entry.responseHeaders.entries.sortedBy { it.key }) {
                    for (v in values) {
                        when {
                            k.equals("set-cookie", ignoreCase = true) -> {
                                val preview = v.take(100) + if (v.length > 100) "..." else ""
                                println("        $k: $preview")
                            }
                            k.equals("location", ignoreCase = true) ||
                                k.equals("content-type", ignoreCase = true) -> println("        $k: $v")
                        }
                    }
                }
            }

            if (entry.bodyBytes > 0) {
                println("      Body: ${entry.bodyBytes}B (preview not captured)")
            }
            println()
        }
    }

    if (failed > 0) exitProcess(1)
}

// Shared libs live in the corpus repo under subfolders (libs/family,
// libs/helpers, ...), while the lib's LibraryLoader resolves the FLAT path
// <libDir>/<id>.lua — the same shape the app installs on device. Flatten a
// copy into build/lua_libs (gitignored, rebuilt by `clean`) unless the host
// points LUA_LIBS_DIR at a directory it maintains itself.
// ceiling: basenames are assumed unique across subfolders (true for the
// corpus today); a future collision needs an explicit per-id mapping.
private fun resolveLibDir(fileSystem: LocalFileSystem): String {
    val explicit = System.getenv("LUA_LIBS_DIR")?.takeIf { it.isNotBlank() }
    if (explicit != null) return explicit

    val userDir = File(System.getProperty("user.dir"))
    val target = File(userDir, "build/lua_libs")
    val roots = listOf(
        File(userDir, "../../external-sources/libs"),
        File(userDir, "../external-sources/libs"),
    )
    val source = roots.firstOrNull { it.isDirectory } ?: run {
        System.err.println("[LIBS] external-sources/libs not found — require_lib will fail")
        return target.absolutePath
    }

    val files = source.walkTopDown().filter { it.isFile && it.extension == "lua" }.toList()
    if (files.isEmpty()) {
        System.err.println("[LIBS] no .lua files under $source")
        return target.absolutePath
    }
    target.mkdirs()
    for (file in files) {
        val dest = File(target, file.name)
        if (!dest.exists() || dest.length() != file.length()) {
            file.copyTo(dest, overwrite = true)
        }
    }
    return target.absolutePath
}

private fun sha256Hex(text: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

private fun truncateUrl(url: String): String {
    val short = url.removePrefix("https://").removePrefix("http://")
    return if (short.length > 100) short.take(100) + "..." else short
}
