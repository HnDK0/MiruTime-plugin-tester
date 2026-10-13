package dev.mirutime.tester

import dev.mirutime.core.host.CfChallenge
import dev.mirutime.core.host.CfChallengeSolver
import dev.mirutime.core.host.CfSolution
import dev.mirutime.core.network.HostOptionsRegistry
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// Cloudflare bypass: shells out to cf_solve.py (python3 + DrissionPage +
// Brave), exactly like the old tool's CfBypass — the solver lives OUTSIDE the
// lib because it needs a real browser, while the lib owns when to call it
// (detect -> gate -> up to 2 x (solve -> retry)).
//
// Script resolution, in order: $CF_SOLVE_SCRIPT, then ./cf_solve.py in the
// working directory (gradle run uses this project's root, where the script
// lives). Cookies land in .cf_cookies.json through FileCookieStore, which the
// lib writes once the solution comes back.
// ceiling: at most MAX_SOLVES_PER_HOST solves per host per process (NoveLA
// MAX_MANUAL_ATTEMPTS=2 parity: the first solve plus one expiry re-solve); a
// third challenge in the same run reports the CF error instead of opening
// another browser.
class CfSolver(
    private val cookieStore: FileCookieStore,
    private val hostOptions: HostOptionsRegistry,
    private val script: File = resolveScript(),
    private val timeoutSeconds: Int = 45,
) : CfChallengeSolver {

    private val solveAttempts = ConcurrentHashMap<String, Int>()

    override suspend fun solve(request: CfChallenge): CfSolution? {
        val host = request.host
        val attempt = solveAttempts.merge(host, 1, Int::plus) ?: 1
        if (attempt > MAX_SOLVES_PER_HOST) {
            System.err.println(
                "[CF] $host already solved $MAX_SOLVES_PER_HOST times this run — not solving again",
            )
            return null
        }
        if (!script.isFile) {
            System.err.println("[CF] solver script not found: ${script.absolutePath}")
            return null
        }
        // NoveLA parity: for an /api/ challenge the browser opens the REFERER
        // page (navigateUrl), not the bare API URL — cf_solve.py receives the
        // already-chosen URL as argv[1], the JSON protocol is unchanged.
        System.err.println(
            "[CF] Solving $host via DrissionPage+Brave (timeout=${timeoutSeconds}s, url=${request.navigateUrl})",
        )
        val startedAt = System.currentTimeMillis()
        val output = runCatching { runSolver(request.navigateUrl) }
            .getOrElse { e ->
                System.err.println("[CF] Failed for $host: ${e.message}")
                return null
            }
        if (output.cookies.isEmpty()) {
            System.err.println("[CF] No cookies obtained for $host")
            return null
        }
        // The UA the browser actually sent is the strongest per-host signal:
        // register it so the lib's retry AND every later request use it.
        output.userAgent?.let { ua ->
            cookieStore.putUserAgent(host, ua)
            hostOptions.setUserAgent(host, SOLVER_OWNER, ua)
        }
        System.err.println(
            "[CF] Solved for $host: ${output.cookies.size} cookies in " +
                "${System.currentTimeMillis() - startedAt}ms",
        )
        return CfSolution(output.cookies, output.userAgent)
    }

    private data class SolverOutput(
        val cookies: Map<String, String>,
        val userAgent: String?,
    )

    private fun runSolver(url: String): SolverOutput {
        // stderr is INHERITED, not piped: DrissionPage is chatty, and a full
        // stderr pipe would deadlock the reader that only drains stdout.
        val process = ProcessBuilder("python3", script.absolutePath, url, timeoutSeconds.toString())
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        val stdout = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw IllegalStateException("python solver exited with code $exitCode")
        }
        return parseSolverOutput(stdout)
    }

    private fun parseSolverOutput(stdout: String): SolverOutput {
        val root = SOLVER_JSON.parseToJsonElement(stdout).jsonObject
        val cookies = root["cookies"]
            ?.let { runCatching { it.jsonArray }.getOrNull() }
            .orEmpty()
            .mapNotNull { entry ->
                val obj = runCatching { entry.jsonObject }.getOrNull() ?: return@mapNotNull null
                val name = obj.text("name") ?: return@mapNotNull null
                val value = obj.text("value") ?: return@mapNotNull null
                name to value
            }
            .toMap()
        val userAgent = root["userAgent"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
        return SolverOutput(cookies, userAgent)
    }

    private fun JsonElement.text(key: String): String? =
        runCatching { jsonObject[key]?.jsonPrimitive?.content }.getOrNull()

    companion object {
        // Owner key for HostOptionsRegistry: one plugin-id-shaped owner so a
        // later clear() only removes entries this solver registered. Main
        // reuses it when replaying UAs from the cookie file.
        const val SOLVER_OWNER = "plugin-tester.cf"

        // NoveLA MAX_MANUAL_ATTEMPTS: first solve + one expiry re-solve.
        const val MAX_SOLVES_PER_HOST = 2

        private val SOLVER_JSON = Json { ignoreUnknownKeys = true }

        private fun resolveScript(): File {
            val explicit = System.getenv("CF_SOLVE_SCRIPT")?.takeIf { it.isNotBlank() }
            if (explicit != null) return File(explicit)
            return File(System.getProperty("user.dir"), "cf_solve.py")
        }
    }
}

// Host of a URL, normalized the same way the lib normalizes per-host keys
// (lowercase, no leading www.) so solver, cookie jar and HostOptionsRegistry
// all agree on one spelling.
internal fun hostOf(url: String): String = try {
    (URI(url).host ?: "").lowercase().removePrefix("www.")
} catch (_: Exception) {
    ""
}
