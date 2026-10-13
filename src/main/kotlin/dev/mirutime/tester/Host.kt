package dev.mirutime.tester

import dev.mirutime.core.host.Cookie
import dev.mirutime.core.host.CookieStore
import dev.mirutime.core.host.FileSystem
import dev.mirutime.core.host.KeyValueStore
import dev.mirutime.core.host.Logger
import dev.mirutime.core.host.SystemInfo
import dev.mirutime.core.network.UserAgentPresets
import java.io.File
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

// ── Logger ───────────────────────────────────────────────────────────────────
// Diagnostics go to STDERR so stdout stays a stable, diffable report — the old
// tool relied on the same property when comparing two runs.
class ConsoleLogger(private val echo: Boolean = true) : Logger {
    override fun d(tag: String, msg: String) {
        if (echo) System.err.println("[$tag] $msg")
    }

    override fun w(tag: String, msg: String) {
        System.err.println("[$tag] WARN $msg")
    }

    override fun e(tag: String, msg: String, t: Throwable?) {
        System.err.println("[$tag] ERROR $msg${t?.let { " — ${it.message}" } ?: ""}")
    }
}

// ── SystemInfo ───────────────────────────────────────────────────────────────
class DesktopSystemInfo(
    override val defaultUserAgent: String = UserAgentPresets.CHROME_150_WINDOWS,
    override val acceptLanguage: String = defaultAcceptLanguage(),
) : SystemInfo

private fun defaultAcceptLanguage(): String {
    val language = Locale.getDefault().language.ifBlank { "en" }
    return "$language-$language;q=0.9,en;q=0.8"
}

// ── KeyValueStore ────────────────────────────────────────────────────────────
// Preferences/localStorage live only for the run: a tester has no state worth
// keeping between runs, and a reset store keeps results reproducible.
class MemoryKeyValueStore : KeyValueStore {
    private val data = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()

    override fun get(namespace: String, key: String): String? = data[namespace]?.get(key)

    override fun set(namespace: String, key: String, value: String) {
        data.getOrPut(namespace) { ConcurrentHashMap() }[key] = value
    }

    override fun remove(namespace: String, key: String) {
        data[namespace]?.remove(key)
    }
}

// ── FileSystem ───────────────────────────────────────────────────────────────
// The lib hands over relative paths (libDir "lua_libs"), so everything resolves
// against user.dir — the same anchor the old tool used for cf_solve.py and
// .cf_cookies.json.
class LocalFileSystem : FileSystem {
    override fun read(path: String): ByteArray? = resolve(path).takeIf { it.isFile }?.readBytes()

    override fun write(path: String, data: ByteArray) {
        resolve(path).apply { parentFile?.mkdirs() }.writeBytes(data)
    }

    override fun exists(path: String): Boolean = resolve(path).exists()

    override fun delete(path: String) {
        resolve(path).delete()
    }

    override fun rename(from: String, to: String) {
        val target = resolve(to)
        target.parentFile?.mkdirs()
        resolve(from).renameTo(target)
    }

    override fun list(dir: String): List<String> =
        resolve(dir).list()?.sorted()?.map { "$dir/$it" }.orEmpty()

    private fun resolve(path: String): File {
        val file = File(path)
        return if (file.isAbsolute) file else File(System.getProperty("user.dir"), path)
    }
}

// ── CookieStore ──────────────────────────────────────────────────────────────
// Persists to .cf_cookies.json in the working directory in the SAME shape the
// old tool wrote ({cookies:{domain:[...]}, userAgents:{domain:ua}}), so an
// existing cf_clearance jar keeps working unchanged. Loaded once at start and
// rewritten whenever a response or the solver changes the jar.
// ceiling: no Max-Age/Expires enforcement — a cookie lives for the run, like
// the old tool's fixed one-hour TTL; Secure/HttpOnly are recorded, not checked.
class FileCookieStore(
    private val file: File = File(System.getProperty("user.dir"), ".cf_cookies.json"),
) : CookieStore {

    private data class StoredCookie(
        val name: String,
        val value: String,
        val domain: String,
        val path: String,
        val secure: Boolean,
        val httpOnly: Boolean,
    )

    private val byDomain = ConcurrentHashMap<String, MutableList<StoredCookie>>()
    private val userAgents = ConcurrentHashMap<String, String>()

    init {
        load()
    }

    fun userAgentFor(domain: String): String? = userAgents[domain]

    // Snapshot of the per-host UAs recorded in the file — Main replays them
    // into HostOptionsRegistry so a stored cf_clearance keeps its UA.
    fun userAgentEntries(): Map<String, String> = userAgents.toMap()

    fun putUserAgent(domain: String, userAgent: String) {
        userAgents[domain] = userAgent
        save()
    }

    override suspend fun get(url: String): List<Cookie> {
        val host = hostOf(url)
        if (host.isEmpty()) return emptyList()
        return byDomain.entries
            .filter { (domain, _) -> matches(domain, host) }
            .flatMap { it.value }
            .map { Cookie(it.name, it.value, it.domain, it.path) }
    }

    override suspend fun setFromResponse(url: String, setCookieHeaders: List<String>) {
        val host = hostOf(url)
        for (header in setCookieHeaders) {
            val parsed = parseSetCookie(header, host) ?: continue
            val bucket = byDomain.getOrPut(parsed.domain) { mutableListOf() }
            synchronized(bucket) {
                bucket.removeAll { it.name == parsed.name && it.path == parsed.path }
                bucket.add(parsed)
            }
        }
        save()
    }

    override suspend fun clear(host: String) {
        byDomain.keys.filter { matches(it, host) }.forEach { byDomain.remove(it) }
        save()
    }

    // RFC 6265 suffix match: exact host, a leading-dot entry, or a parent
    // domain the host belongs to. Replaces the old tool's ad-hoc matcher; the
    // only visible effect is which cookies ride along, not the report shape.
    private fun matches(domain: String, host: String): Boolean {
        val bare = domain.removePrefix(".")
        return host == bare || host.endsWith(".$bare")
    }

    private fun hostOf(url: String): String = try {
        (URI(url).host ?: "").lowercase().removePrefix("www.")
    } catch (_: Exception) {
        ""
    }

    private fun parseSetCookie(header: String, fallbackHost: String): StoredCookie? {
        val parts = header.split(';')
        val first = parts.first()
        val separator = first.indexOf('=')
        if (separator <= 0) return null
        val name = first.substring(0, separator).trim()
        if (name.isEmpty()) return null
        var domain = ""
        var path = ""
        var secure = false
        var httpOnly = false
        for (part in parts.drop(1)) {
            val idx = part.indexOf('=')
            val key = (if (idx >= 0) part.substring(0, idx) else part).trim().lowercase()
            val raw = if (idx >= 0) part.substring(idx + 1).trim() else ""
            when (key) {
                "domain" -> domain = raw.removePrefix(".").lowercase()
                "path" -> path = raw
                "secure" -> secure = true
                "httponly" -> httpOnly = true
            }
        }
        return StoredCookie(
            name = name,
            value = first.substring(separator + 1).trim(),
            domain = domain.ifBlank { fallbackHost },
            path = path.ifBlank { "/" },
            secure = secure,
            httpOnly = httpOnly,
        )
    }

    private fun load() {
        if (!file.isFile) return
        val text = runCatching { file.readText() }.getOrNull() ?: return
        val root = runCatching { COOKIE_JSON.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        runCatching { root.getValue("cookies").jsonObject }.getOrNull()?.forEach { (domain, value) ->
            val parsed = runCatching { value.jsonArray }.getOrNull().orEmpty().mapNotNull { entry ->
                val obj = runCatching { entry.jsonObject }.getOrNull() ?: return@mapNotNull null
                StoredCookie(
                    name = obj.text("name") ?: return@mapNotNull null,
                    value = obj.text("value") ?: return@mapNotNull null,
                    domain = obj.text("domain")?.removePrefix(".") ?: domain,
                    path = obj.text("path") ?: "/",
                    secure = obj.text("secure")?.toBoolean() ?: false,
                    httpOnly = obj.text("httpOnly")?.toBoolean() ?: false,
                )
            }
            if (parsed.isNotEmpty()) byDomain[domain] = parsed.toMutableList()
        }
        runCatching { root.getValue("userAgents").jsonObject }.getOrNull()?.forEach { (domain, value) ->
            runCatching { value.jsonPrimitive.content }.getOrNull()?.let { userAgents[domain] = it }
        }
        if (byDomain.isNotEmpty()) {
            System.err.println("[CF] Loaded ${byDomain.size} domains from ${file.name}")
        }
    }

    private fun save() {
        val document: JsonElement = buildJsonObject {
            put("cookies", buildJsonObject {
                for ((domain, cookies) in byDomain) {
                    put(
                        domain,
                        buildJsonArray {
                            for (cookie in cookies) {
                                add(
                                    buildJsonObject {
                                        put("name", cookie.name)
                                        put("value", cookie.value)
                                        put("domain", cookie.domain)
                                        put("path", cookie.path)
                                        put("secure", cookie.secure.toString())
                                        put("httpOnly", cookie.httpOnly.toString())
                                    },
                                )
                            }
                        },
                    )
                }
            })
            put("userAgents", buildJsonObject {
                for ((domain, ua) in userAgents) put(domain, ua)
            })
        }
        runCatching { file.writeText(document.toString()) }
            .onFailure { System.err.println("[CF] Failed to save cookies: ${it.message}") }
    }

    private fun JsonObject.text(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()
}

private val COOKIE_JSON = Json { ignoreUnknownKeys = true }
