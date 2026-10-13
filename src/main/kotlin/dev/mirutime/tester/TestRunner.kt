package dev.mirutime.tester

import com.fleeksoft.ksoup.Ksoup
import dev.mirutime.core.api.BookMeta
import dev.mirutime.core.api.CatalogItem
import dev.mirutime.core.api.ChapterContent
import dev.mirutime.core.api.ContentType
import dev.mirutime.core.api.Outcome
import dev.mirutime.core.api.PluginError
import dev.mirutime.core.api.PluginSource
import dev.mirutime.core.api.VideoStream
import dev.mirutime.core.installer.ScriptMetadata
import dev.mirutime.core.network.NetworkTraceEvent
import dev.mirutime.core.network.PluginHttpClient
import dev.mirutime.core.network.security.ResponseTooLargeException
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import java.net.URI
import java.util.Locale

data class TestResult(
    val name: String,
    val passed: Boolean,
    val errors: List<String> = emptyList(),
    val details: Map<String, Any> = emptyMap(),
    val durationMs: Long = 0,
)

data class RawSiteResponse(
    val code: Int,
    val body: String,
    val headers: Map<String, List<String>>,
)

data class ImageAnalysis(
    val url: String,
    val code: Int,
    val size: Int,
    val contentType: String,
    // Hash of the first 200 bytes: identical hashes across several images mean
    // the site served the same placeholder over and over.
    val firstBytesHash: Int,
)

// A show_error() the plugin raised. log_info/log_error are echoed straight to
// the console by the lib's LogBindings and have no observable sink, so only the
// user-facing signal is collected — divergence vs the old tool, which captured
// all three.
data class PluginEvent(val type: String, val title: String, val message: String)

// Test sections, ported from plugin-tester-jvm's TestRunner. What changed: the
// plugin is driven through PluginSource (typed Outcome results) instead of raw
// Lua values, and raw site fetches go through PluginHttpClient — the same
// client the plugin's own http_get uses. Section names, detail keys and
// PASS/FAIL rules are kept so two runs stay comparable.
class TestRunner(
    private val source: PluginSource,
    private val raw: PluginHttpClient,
    private val meta: ScriptMetadata,
    private val defined: Set<String>,
    private val trace: List<NetworkTraceEvent>,
) {

    private val MAX_PREVIEW = 200
    private val MAX_ITEMS = 5
    private val MIN_CHAPTER_CHARS = 100

    private val events = mutableListOf<PluginEvent>()

    // Log/cache identity for raw fetches — same id the plugin's own requests
    // use, so the NETWORK TRACE reads as one source.
    private val sourceId: String = meta.id ?: "plugin-tester"

    private val isManga: Boolean
        get() = source.metadata.contentType == ContentType.MANGA

    private val isVideo: Boolean
        get() = source.metadata.contentType == ContentType.VIDEO

    suspend fun runAll(bookUrl: String?, chapterUrl: String?): List<TestResult> {
        val results = mutableListOf<TestResult>()

        results += testMetadata()
        val catalogResult = testCatalog()
        results += catalogResult

        var resolvedBookUrl = bookUrl
        var resolvedChapterUrl = chapterUrl

        if (resolvedBookUrl == null) {
            resolvedBookUrl = extractFirstUrl(catalogResult)
            if (resolvedBookUrl != null) {
                results += TestResult(
                    "auto_book_url",
                    true,
                    emptyList(),
                    linkedMapOf("url" to resolvedBookUrl, "source" to "catalog[1].url"),
                )
            }
        }

        if (resolvedBookUrl != null) {
            results += testSearch(extractSearchQuery(catalogResult))
            results += testPagination()
            results += testBook(resolvedBookUrl)

            val chapters = testChapterList(resolvedBookUrl)
            results += chapters

            if (resolvedChapterUrl == null) {
                resolvedChapterUrl = extractFirstChapterUrl(chapters)
                if (resolvedChapterUrl != null) {
                    results += TestResult(
                        "auto_chapter_url",
                        true,
                        emptyList(),
                        linkedMapOf("url" to resolvedChapterUrl, "source" to "chapter_list[1].url"),
                    )
                }
            }

            if (resolvedChapterUrl != null) {
                when {
                    isVideo -> results += testVideoStreams(resolvedChapterUrl)
                    isManga -> results += testPageList(resolvedChapterUrl)
                    else -> results += testChapterText(resolvedChapterUrl)
                }
            }
        }

        if ("getFilterList" in defined) results += testFilters()
        results += testPluginEvents()
        results += testSiteHealth(results)

        return results
    }

    // ── Tests ──────────────────────────────────────────────────────────────

    private fun testMetadata(): TestResult {
        val t0 = System.currentTimeMillis()
        val errors = mutableListOf<String>()
        val details = linkedMapOf<String, Any>()

        val name = meta.name.orEmpty()
        val version = meta.version.orEmpty()
        val base = meta.baseUrl.orEmpty()
        val lang = meta.language.orEmpty()
        val contentType = meta.contentType.orEmpty()
        val icon = meta.icon.orEmpty()

        if (name.isBlank()) errors.add("source_name not defined")
        if (version.isBlank()) errors.add("source_version not defined")
        if (base.isBlank()) errors.add("base_url not defined")

        details["name"] = name.ifBlank { "???" }
        details["version"] = version.ifBlank { "???" }
        details["base_url"] = base.ifBlank { "???" }
        if (lang.isNotBlank()) details["language"] = lang
        if (contentType.isNotBlank()) details["content_type"] = contentType

        if (icon.isNotBlank()) {
            if (!icon.startsWith("http://") && !icon.startsWith("https://")) {
                errors.add("icon is not an absolute URL: '$icon'")
            } else {
                details["icon"] = icon
            }
        } else {
            details["icon_note"] = "icon not defined (required by plugin contract)"
        }

        return TestResult("metadata", errors.isEmpty(), errors, details, System.currentTimeMillis() - t0)
    }

    private suspend fun testCatalog(): TestResult {
        val t0 = System.currentTimeMillis()
        val errors = mutableListOf<String>()
        val details = linkedMapOf<String, Any>()

        if ("getCatalogList" !in defined) {
            errors.add("getCatalogList not defined")
            return TestResult("catalog", false, errors, details, System.currentTimeMillis() - t0)
        }

        // RAW: fetch the catalog page as a browser would, to cross-check the
        // plugin's item count against what the site actually ships.
        val baseUrl = meta.baseUrl.orEmpty()
        var rawCardsFound = 0
        if (baseUrl.isNotBlank()) {
            try {
                val rawResponse = rawFetch(baseUrl)
                details["raw_code"] = rawResponse.code
                val rawIssues = analyzeResponse(rawResponse)
                if (rawIssues.isNotEmpty()) details["raw_issues"] = rawIssues.joinToString(" | ")
                rawCardsFound = Ksoup.parse(rawResponse.body, "")
                    .select(".bookbox, .manga-item, .novel-item, article, .card").size
                details["raw_cards_found"] = rawCardsFound
            } catch (e: Exception) {
                details["raw_fetch_error"] = e.message ?: "unknown"
            }
        }

        when (val outcome = source.catalog(0)) {
            is Outcome.Err -> {
                noteError(outcome.error)
                errors.add("getCatalogList: ${describe(outcome.error)}")
                details["exception_type"] = outcome.error::class.simpleName ?: "PluginError"
            }
            is Outcome.Ok -> {
                val items = outcome.value.items
                details["return_type"] = "CatalogPage"
                details["item_count"] = items.size

                if (rawCardsFound > 0) {
                    if (items.isEmpty()) {
                        errors.add("Site raw HTML shows $rawCardsFound cards, plugin returned 0 items")
                    } else if (items.size != rawCardsFound) {
                        details["card_mismatch"] =
                            "raw HTML ~$rawCardsFound cards vs ${items.size} plugin items (informational)"
                    }
                }

                // The lib types hasNext as Boolean, so the old "hasNext must be
                // boolean" check has nothing left to catch.
                details["hasNext"] = outcome.value.hasNext

                val shown = minOf(items.size, MAX_ITEMS)
                for (i in 1..shown) {
                    val item = items[i - 1]
                    validateCatalogItem(item, i, errors, details)
                    details["item[$i]"] = item.title.ifBlank { "?" }
                    // Old tool overwrote this key each iteration: auto_book_url
                    // picked item[5] while labeled catalog[1].url. Only item 1
                    // feeds it here (matches testChapterList's i == 1 rule).
                    if (i == 1) details["  url"] = item.url.ifBlank { "?" }
                }
                if (items.size > shown) details["... +${items.size - shown} more"] = ""

                if (items.isEmpty()) {
                    errors.add("Catalog returned 0 items")
                    val lastLogs = trace.takeLast(5)
                    if (lastLogs.isNotEmpty()) {
                        details["http_requests"] = "${lastLogs.size} requests made"
                        lastLogs.forEachIndexed { i, entry ->
                            details["  req[${i + 1}]"] =
                                "${entry.method} → ${entry.status} (${entry.bodyBytes}B) cf=${entry.cfBlocked}"
                            details["  url[${i + 1}]"] = entry.url.removePrefix("https://").removePrefix("http://")
                        }
                    }
                }
            }
        }

        return TestResult("catalog", errors.isEmpty(), errors, details, System.currentTimeMillis() - t0)
    }

    private fun extractSearchQuery(catalogResult: TestResult): String {
        val firstTitle = catalogResult.details["item[1]"]?.toString() ?: return "the"
        val words = firstTitle.split(Regex("\\s+")).filter { it.length > 2 }
        val firstWord = words.firstOrNull() ?: "the"
        if (firstWord.length > 10 && firstWord.none { it.isWhitespace() }) {
            val cjk = firstWord.codePoints().anyMatch { cp ->
                val block = Character.UnicodeBlock.of(cp)
                block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                    block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
                    block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
            }
            if (cjk) return firstWord.take(5)
        }
        return firstWord
    }

    private suspend fun testSearch(query: String): TestResult {
        val t0 = System.currentTimeMillis()
        val errors = mutableListOf<String>()
        val details = linkedMapOf<String, Any>()

        if ("getCatalogSearch" !in defined) {
            errors.add("getCatalogSearch not defined")
            return TestResult("search", false, errors, details, System.currentTimeMillis() - t0)
        }

        when (val outcome = source.search(0, query)) {
            is Outcome.Err -> {
                noteError(outcome.error)
                errors.add("getCatalogSearch: ${describe(outcome.error)}")
                details["exception_type"] = outcome.error::class.simpleName ?: "PluginError"
                annotateBlocked(details, outcome.error)
            }
            is Outcome.Ok -> {
                val items = outcome.value.items
                details["query"] = query
                details["return_type"] = "CatalogPage"
                details["item_count"] = items.size

                val shown = minOf(items.size, MAX_ITEMS)
                for (i in 1..shown) {
                    val item = items[i - 1]
                    validateCatalogItem(item, i, errors, details)
                    details["item[$i]"] = item.title.ifBlank { "?" }
                    details["  url"] = item.url.ifBlank { "?" }
                }
                if (items.size > shown) details["... +${items.size - shown} more"] = ""

                if (items.isEmpty()) {
                    val last = trace.lastOrNull()
                    val lastCode = last?.status
                    val cfBlocked = last?.cfBlocked == true || lastCode == 403 || lastCode == 503
                    when {
                        cfBlocked -> details["note"] = "CF-blocked (use --cf to bypass)"
                        lastCode != null && lastCode !in 200..299 ->
                            details["note"] = "HTTP $lastCode — site may require --cf or different URL"
                    }
                    errors.add("Search returned 0 items for '$query' (HTTP ${lastCode ?: "?"})")
                }

                if (outcome.value.hasNext && items.isEmpty()) {
                    details["hasNext_note"] = "hasNext=true but 0 items returned"
                } else {
                    details["hasNext"] = outcome.value.hasNext
                }
            }
        }

        return TestResult("search", errors.isEmpty(), errors, details, System.currentTimeMillis() - t0)
    }

    private suspend fun testPagination(): TestResult {
        val t0 = System.currentTimeMillis()
        val errors = mutableListOf<String>()
        val details = linkedMapOf<String, Any>()

        val page0 = source.catalog(0)
        val page1 = source.catalog(1)
        if (page0 is Outcome.Err || page1 is Outcome.Err) {
            val failed = (page0 as? Outcome.Err)?.error ?: (page1 as? Outcome.Err)!!.error
            noteError(failed)
            errors.add("Could not unwrap items from page 0 or page 1: ${describe(failed)}")
            return TestResult("pagination", false, errors, details, System.currentTimeMillis() - t0)
        }

        val items0 = (page0 as Outcome.Ok).value.items
        val items1 = (page1 as Outcome.Ok).value.items
        details["page0_items"] = items0.size
        details["page1_items"] = items1.size

        if (items0.isNotEmpty() && items1.isNotEmpty()) {
            val title0 = items0.first().title
            val title1 = items1.first().title
            details["page0_first"] = title0
            details["page1_first"] = title1
            val different = items0.first().url != items1.first().url || title0 != title1
            details["pages_differ"] = different
            if (!different && items0.size == items1.size) {
                errors.add("Page 0 and page 1 return identical content — pagination may be broken")
            }
        }

        return TestResult("pagination", errors.isEmpty(), errors, details, System.currentTimeMillis() - t0)
    }

    // One PluginSource.book() call feeds six sections. When it aborts the error
    // names the getter that failed, so the failure is attributed to that
    // section and the rest report the skip; an unattributable error fails all
    // six. Divergence from the old tool (which called each getter alone): the
    // lib aggregates book fields, so getters after the first failure never run.
    private suspend fun testBook(url: String): List<TestResult> {
        val t0 = System.currentTimeMillis()
        val outcome = source.book(url)
        val callMs = System.currentTimeMillis() - t0

        if (outcome is Outcome.Err) {
            noteError(outcome.error)
            return bookErrorResults(outcome.error, callMs, url)
        }
        val book = (outcome as Outcome.Ok).value
        return listOf(
            testBookTitle(book, callMs, url),
            testBookCover(book, url),
            testBookDescription(book, url),
            testBookStatus(book, url),
            testBookRating(book, url),
            testBookExtras(book, url),
        )
    }

    private fun bookErrorResults(error: PluginError, callMs: Long, url: String): List<TestResult> {
        val message = describe(error)
        val attributed = bookSectionOf(error)
        val sections = listOf(
            Triple("book_title", "getBookTitle", BOOK_TITLE_FIELDS),
            Triple("book_cover", "getBookCoverImageUrl", BOOK_COVER_FIELDS),
            Triple("book_description", "getBookDescription", BOOK_DESCRIPTION_FIELDS),
            Triple("book_status", "getBookStatus", BOOK_STATUS_FIELDS),
            Triple("book_rating", "getBookRating", BOOK_RATING_FIELDS),
            Triple("book_extras", "getBookExtras", BOOK_EXTRAS_FIELDS),
        )
        return sections.map { (section, fn, fields) ->
            val details = linkedMapOf<String, Any>()
            details["url"] = url
            val failed = attributed == null || attributed == section || attributed in fields
            if (failed) {
                TestResult(
                    section,
                    false,
                    listOf("$fn: $message"),
                    details,
                    if (section == "book_title") callMs else 0,
                )
            } else {
                details["note"] = "skipped — book() aborted at $attributed"
                TestResult(section, false, emptyList(), details)
            }
        }
    }

    private fun testBookTitle(book: BookMeta, callMs: Long, url: String): TestResult {
        val t0 = System.currentTimeMillis()
        val errors = mutableListOf<String>()
        val details = linkedMapOf<String, Any>()
        details["url"] = url

        if ("getBookTitle" !in defined) {
            errors.add("getBookTitle not defined")
            return TestResult("book_title", false, errors, details, System.currentTimeMillis() - t0)
        }

        details["return_type"] = "String"
        if (book.title.isBlank()) {
            errors.add("getBookTitle returned empty string — page fetch may have failed or selectors are wrong")
        } else {
            details["title"] = book.title
        }

        val duration = callMs + (System.currentTimeMillis() - t0)
        return TestResult("book_title", errors.isEmpty(), errors, details, duration)
    }

    private suspend fun testBookCover(book: BookMeta, url: String): TestResult {
        val t0 = System.currentTimeMillis()
        val errors = mutableListOf<String>()
        val details = linkedMapOf<String, Any>()
        details["url"] = url

        if ("getBookCoverImageUrl" !in defined) {
            errors.add("getBookCoverImageUrl not defined")
            return TestResult("book_cover", false, errors, details, System.currentTimeMillis() - t0)
        }

        details["return_type"] = "String"
        val coverUrl = book.coverUrl
        if (coverUrl.isNullOrBlank()) {
            errors.add("getBookCoverImageUrl returned nil — image selector may be wrong")
        } else if (!coverUrl.startsWith("http://") && !coverUrl.startsWith("https://")) {
            errors.add("getBookCoverImageUrl returned non-absolute URL: '$coverUrl'")
        } else {
            details["cover_url"] = coverUrl
            try {
                val analysis = analyzeImage(coverUrl, url, 0)
                details["cover_img"] = "HTTP ${analysis.code}, ${analysis.size}B, ${analysis.contentType}"
                if (analysis.code !in 200..299) {
                    val last = trace.lastOrNull()
                    if (last?.cfBlocked == true || analysis.code in setOf(403, 503)) {
                        details["cover_img_note"] = "blocked (CF/CDN/auth) — not a plugin bug"
                    } else {
                        errors.add("cover image HTTP ${analysis.code}")
                    }
                }
                if (analysis.size in 1..1024) errors.add("cover image is <1KB (likely placeholder)")
                if (analysis.contentType.contains("text/html")) {
                    errors.add("cover image returned text/html instead of image")
                }
                stubKeywords.firstOrNull { coverUrl.lowercase().contains(it) }?.let {
                    errors.add("cover URL contains stub keyword: $coverUrl")
                }
            } catch (e: Exception) {
                details["cover_img_error"] = e.message ?: "unknown"
            }
        }

        return TestResult("book_cover", errors.isEmpty(), errors, details, System.currentTimeMillis() - t0)
    }

    private suspend fun testBookDescription(book: BookMeta, url: String): TestResult {
        val t0 = System.currentTimeMillis()
        val errors = mutableListOf<String>()
        val details = linkedMapOf<String, Any>()
        details["url"] = url

        if ("getBookDescription" !in defined) {
            errors.add("getBookDescription not defined")
            return TestResult("book_description", false, errors, details, System.currentTimeMillis() - t0)
        }

        details["return_type"] = "String"
        val description = book.description
        if (description == null) {
            errors.add("getBookDescription returned nil — description selector may be wrong")
        } else if (description.isBlank()) {
            if (siteHasDescription(url)) {
                errors.add("getBookDescription returned empty string, but the site has a description")
            } else {
                details["note"] = "site itself provides no description; empty is correct"
            }
        } else {
            details["char_count"] = description.length
            details["preview"] = truncate(description, MAX_PREVIEW)
        }

        return TestResult("book_description", errors.isEmpty(), errors, details, System.currentTimeMillis() - t0)
    }

    private fun testBookStatus(book: BookMeta, url: String): TestResult {
        val t0 = System.currentTimeMillis()
        val errors = mutableListOf<String>()
        val details = linkedMapOf<String, Any>()
        details["url"] = url

        if ("getBookStatus" !in defined) {
            details["note"] = "not defined (optional)"
            return TestResult("book_status", true, emptyList(), details, System.currentTimeMillis() - t0)
        }

        details["return_type"] = "String"
        val status = book.status
        when {
            status == null -> details["note"] = "returned nil (optional field, not a failure)"
            status.isBlank() -> errors.add("getBookStatus returned empty string")
            else -> details["status"] = status
        }

        return TestResult("book_status", errors.isEmpty(), errors, details, System.currentTimeMillis() - t0)
    }

    private fun testBookRating(book: BookMeta, url: String): TestResult {
        val t0 = System.currentTimeMillis()
        val errors = mutableListOf<String>()
        val details = linkedMapOf<String, Any>()
        details["url"] = url

        if ("getBookRating" !in defined) {
            details["note"] = "not defined (optional)"
            return TestResult("book_rating", true, emptyList(), details, System.currentTimeMillis() - t0)
        }

        details["return_type"] = "String"
        val rating = book.rating
        when {
            rating == null -> details["note"] = "returned nil"
            else -> validateBookRating(rating.trim(), errors, details)
        }

        return TestResult("book_rating", errors.isEmpty(), errors, details, System.currentTimeMillis() - t0)
    }

    // Exact mirror of NoveLA's parseBookRating (BookRatingViews.kt):
    // "4.3" -> 4.3; "8.7/10" -> 8.7/10*5 = 4.35; "Rank: 3"/"#12" -> a rank.
    // Anything the app drops (null) counts as an error here.
    private fun validateBookRating(
        s: String,
        errors: MutableList<String>,
        details: LinkedHashMap<String, Any>,
    ) {
        details["raw_value"] = s
        if (s.isEmpty()) {
            errors.add("getBookRating returned empty string")
            return
        }
        val numberMatch = Regex("""\d+(?:[.,]\d+)?""").find(s) ?: run {
            errors.add("getBookRating string '$s': no numeric value (app shows nothing)")
            return
        }
        val numberStr = numberMatch.value.replace(',', '.')
        if (Regex("""\brank\b|#\d+""", RegexOption.IGNORE_CASE).containsMatchIn(s)) {
            val intValue = numberStr.toIntOrNull() ?: run {
                errors.add("getBookRating rank '$s': non-integer value")
                return
            }
            details["rating"] = numberStr
            details["rank"] = intValue
            return
        }
        val value = numberStr.toFloat()
        val scaleMatch = Regex("""/(\d+(?:[.,]\d+)?)""").find(s)
        val normalized = if (scaleMatch != null) {
            val scale = scaleMatch.groupValues[1].replace(',', '.').toFloat()
            if (scale <= 0f || value > scale) {
                errors.add("getBookRating string '$s': invalid scale (app shows nothing)")
                return
            }
            value / scale * 5f
        } else {
            if (value > 5f) {
                errors.add(
                    "getBookRating string '$s': bare value > 5 — app shows nothing; " +
                        "use \"Rating: $value/10\" format for scale 10",
                )
                return
            }
            value
        }
        if (normalized > 5f) {
            errors.add("getBookRating string '$s': normalized score > 5 (app shows nothing)")
            return
        }
        val display = String.format(Locale.US, "%.1f", normalized).trimEnd('0').trimEnd('.')
        details["rating"] = display
        details["rating_score"] = normalized
    }

    private fun testBookExtras(book: BookMeta, url: String): TestResult {
        val t0 = System.currentTimeMillis()
        val errors = mutableListOf<String>()
        val details = linkedMapOf<String, Any>()
        details["url"] = url

        // getBookGenres
        if ("getBookGenres" !in defined) {
            details["genres"] = "not defined (optional)"
        } else {
            when {
                book.genres.isEmpty() -> details["genres"] = "nil (optional)"
                else -> details["genres"] = book.genres.joinToString(", ")
            }
        }

        // getBookLastUpdate
        if ("getBookLastUpdate" !in defined) {
            details["last_update"] = "not defined (optional)"
        } else {
            val updated = book.lastUpdated
            when {
                updated == null -> details["last_update"] = "nil (optional)"
                Regex("""\d{4}-\d{2}-\d{2}""").matches(updated) -> details["last_update"] = updated
                else -> errors.add("getBookLastUpdate returned '$updated', expected YYYY-MM-DD")
            }
        }

        // getBookChapterCount has no dedicated slot in BookMeta — a plugin
        // surfaces the count through BookMeta.extras instead, so the section
        // can only report presence here.
        if ("getBookChapterCount" !in defined) {
            details["chapter_count_fn"] = "not defined (optional)"
        } else {
            details["chapter_count_fn"] = "defined but not a dedicated BookMeta field (use extras)"
        }

        // MiruTime extension absent from the old tool.
        book.author?.takeIf { it.isNotBlank() }?.let { details["author"] = it }

        // BookMeta.extras — arbitrary plugin metadata (MiruTime extension
        // getBookExtras + unknown getBookMeta keys). Informational.
        when {
            book.extras.isNotEmpty() -> for ((k, v) in book.extras) details["extra[$k]"] = v
            "getBookExtras" in defined -> details["extras"] = "getBookExtras defined but returned no extras"
            else -> details["extras"] = "getBookExtras not defined (optional)"
        }

        return TestResult("book_extras", errors.isEmpty(), errors, details, System.currentTimeMillis() - t0)
    }

    private suspend fun testChapterList(url: String): TestResult {
        val t0 = System.currentTimeMillis()
        val errors = mutableListOf<String>()
        val details = linkedMapOf<String, Any>()
        details["url"] = url

        val hasGetChapterList = "getChapterList" in defined
        val hasParsePage = "parsePage" in defined
        if (!hasGetChapterList && !hasParsePage) {
            errors.add("getChapterList not defined")
            return TestResult("chapter_list", false, errors, details, System.currentTimeMillis() - t0)
        }

        details["called"] = if (hasGetChapterList) "getChapterList" else "parsePage"

        when (val outcome = source.chapters(url)) {
            is Outcome.Err -> {
                noteError(outcome.error)
                errors.add("getChapterList: ${describe(outcome.error)}")
                details["exception_type"] = outcome.error::class.simpleName ?: "PluginError"
            }
            is Outcome.Ok -> {
                val chapters = outcome.value
                details["return_type"] = "List<Chapter>"
                details["chapter_count"] = chapters.size

                if (chapters.isEmpty()) {
                    errors.add("Chapter list is empty (0 chapters)")
                    return TestResult("chapter_list", false, errors, details, System.currentTimeMillis() - t0)
                }

                // RAW: compare the plugin's chapter count against raw HTML.
                try {
                    val rawResponse = rawFetch(url)
                    details["raw_code"] = rawResponse.code
                    val rawChapterUrls = extractRawChapters(rawResponse.body, url)
                    if (rawChapterUrls.isNotEmpty()) {
                        details["raw_chapters_found"] = rawChapterUrls.size
                        when {
                            rawChapterUrls.size > 10 && chapters.size < rawChapterUrls.size / 2 -> {
                                details["chapter_mismatch"] =
                                    "plugin returned ${chapters.size} chapters, raw HTML has ${rawChapterUrls.size}"
                                errors.add(
                                    "Chapter list looks truncated: plugin returned ${chapters.size}, " +
                                        "raw HTML has ${rawChapterUrls.size}",
                                )
                            }
                            chapters.size != rawChapterUrls.size -> {
                                details["chapter_mismatch"] =
                                    "plugin returned ${chapters.size} chapters, raw HTML has " +
                                    "${rawChapterUrls.size} (informational)"
                            }
                        }
                    }
                } catch (e: Exception) {
                    details["raw_fetch_error"] = e.message ?: "unknown"
                }

                val seen = mutableSetOf<String>()
                val shown = minOf(chapters.size, MAX_ITEMS)
                for (i in 1..shown) {
                    val chapter = chapters[i - 1]
                    val title = chapter.title
                    val chUrl = chapter.url
                    if (title.isBlank()) errors.add("chapter[$i]: title is empty")
                    if (chUrl.isBlank()) {
                        errors.add("chapter[$i]: url is empty")
                    } else if (!chUrl.startsWith("http://") && !chUrl.startsWith("https://")) {
                        errors.add("chapter[$i]: url is not absolute: '$chUrl'")
                    }
                    if (chUrl.isNotBlank() && !seen.add(chUrl)) errors.add("chapter[$i]: duplicate url '$chUrl'")
                    details["ch[$i]"] = title.ifBlank { "?" }
                    // One key would be overwritten in the loop: only chapter 1
                    // feeds auto_chapter_url.
                    if (i == 1) details["  url"] = chUrl
                }
                if (chapters.size > shown) details["... +${chapters.size - shown} more"] = ""
            }
        }

        return TestResult("chapter_list", errors.isEmpty(), errors, details, System.currentTimeMillis() - t0)
    }

    private suspend fun testChapterText(chapterUrl: String): TestResult {
        val t0 = System.currentTimeMillis()
        val errors = mutableListOf<String>()
        val details = linkedMapOf<String, Any>()
        details["url"] = chapterUrl

        if ("getChapterText" !in defined) {
            errors.add("getChapterText not defined")
            return TestResult("chapter_text", false, errors, details, System.currentTimeMillis() - t0)
        }

        try {
            val response = raw.get(sourceId, chapterUrl)
            val code = response.status.value
            details["http_code"] = code

            if (code !in 200..299) {
                errors.add("HTTP $code: page fetch failed")
                details["body_preview"] = truncate(response.bodyAsText(), 200)
                return TestResult("chapter_text", false, errors, details, System.currentTimeMillis() - t0)
            }

            // The lib dispatches Video > Pages > Text, so a plugin that also
            // defines getPageList is read as pages here — the app's contract.
            when (val outcome = source.chapterContent(response.bodyAsText(), chapterUrl)) {
                is Outcome.Err -> {
                    noteError(outcome.error)
                    errors.add("chapterContent: ${describe(outcome.error)}")
                    details["exception_type"] = outcome.error::class.simpleName ?: "PluginError"
                }
                is Outcome.Ok -> when (val content = outcome.value) {
                    is ChapterContent.Text -> {
                        val s = content.text
                        details["return_type"] = "Text"
                        val cleanLen = s.replace(Regex("\\s+"), "").length
                        details["char_count"] = s.length
                        details["word_count"] = s.split(Regex("\\s+")).size
                        details["content_chars"] = cleanLen
                        if (cleanLen < MIN_CHAPTER_CHARS) {
                            errors.add("Chapter text too short ($cleanLen chars) — likely a stub or blocked content")
                        }
                        if (HTML_MARKUP.containsMatchIn(s)) {
                            errors.add("Chapter text contains HTML markup — plugin returned raw page HTML")
                        }
                        if (s.isBlank()) {
                            errors.add("getChapterText returned empty string")
                        } else {
                            details["preview"] = truncate(s, MAX_PREVIEW)
                        }
                    }
                    is ChapterContent.Pages ->
                        errors.add("chapterContent returned pages, expected string (getChapterText)")
                    is ChapterContent.Video ->
                        errors.add("chapterContent returned video, expected string (getChapterText)")
                }
            }
        } catch (e: Exception) {
            errors.add("getChapterText threw: ${e.message}")
            details["exception_type"] = e::class.simpleName ?: "Exception"
        }

        return TestResult("chapter_text", errors.isEmpty(), errors, details, System.currentTimeMillis() - t0)
    }

    // Video counterpart of testChapterText: the lib dispatches Video > Pages >
    // Text, so a content_type=video plugin reaches getVideoList(url) through
    // the same chapterContent call. Section mirrors chapter_text's shape
    // (raw page fetch + plugin parse) so the two reports stay comparable.
    private suspend fun testVideoStreams(chapterUrl: String): TestResult {
        val t0 = System.currentTimeMillis()
        val errors = mutableListOf<String>()
        val details = linkedMapOf<String, Any>()
        details["url"] = chapterUrl

        if ("getVideoList" !in defined) {
            errors.add("getVideoList not defined")
            return TestResult("video_streams", false, errors, details, System.currentTimeMillis() - t0)
        }

        try {
            // The episode page is informational only: API-driven video plugins
            // (aniliberty, hianimes API) parse the URL and never read this HTML,
            // and SPA routes legitimately 404 outside a browser.
            val episodeHtml = try {
                val page = raw.get(sourceId, chapterUrl)
                details["http_code"] = page.status.value
                page.bodyAsText()
            } catch (e: Exception) {
                details["http_code"] = "fetch failed: ${e.message}"
                ""
            }

            when (val outcome = source.chapterContent(episodeHtml, chapterUrl)) {
                is Outcome.Err -> {
                    noteError(outcome.error)
                    errors.add("chapterContent: ${describe(outcome.error)}")
                    details["exception_type"] = outcome.error::class.simpleName ?: "PluginError"
                }
                is Outcome.Ok -> when (val content = outcome.value) {
                    is ChapterContent.Video -> {
                        val streams = content.streams
                        details["return_type"] = "Video"
                        details["stream_count"] = streams.size
                        if (streams.isEmpty()) {
                            errors.add("getVideoList returned 0 streams")
                        }
                        val shown = minOf(streams.size, MAX_ITEMS)
                        for (i in 1..shown) {
                            val stream = streams[i - 1]
                            if (stream.url.isBlank()) {
                                errors.add("stream[$i]: url is empty")
                            } else if (!stream.url.startsWith("http://") && !stream.url.startsWith("https://")) {
                                errors.add("stream[$i]: url is not absolute: '${stream.url}'")
                            }
                            val label = buildString {
                                stream.quality?.takeIf { it.isNotBlank() }?.let { append(it) }
                                stream.mime?.takeIf { it.isNotBlank() }?.let {
                                    if (isNotEmpty()) append(" · ")
                                    append(it)
                                }
                                if (stream.subtitles.isNotEmpty()) {
                                    if (isNotEmpty()) append(" · ")
                                    append("${stream.subtitles.size} subtitle(s)")
                                }
                            }
                            details["stream[$i]"] = if (label.isEmpty()) stream.url else "$label — ${stream.url}"
                            // First stream only: a live HTTP code + a MIME type
                            // say the URL resolves, not that video comes back —
                            // probe the protocol itself (HLS playlist + first
                            // segment, DASH manifest, direct media via Range).
                            if (i == 1 && stream.url.startsWith("http")) {
                                probeStream(stream, details, errors)
                            }
                        }
                        if (streams.size > shown) details["... +${streams.size - shown} more"] = ""
                    }
                    is ChapterContent.Text ->
                        errors.add("chapterContent returned text, expected video (getVideoList)")
                    is ChapterContent.Pages ->
                        errors.add("chapterContent returned pages, expected video (getVideoList)")
                }
            }
        } catch (e: Exception) {
            errors.add("getVideoList threw: ${e.message}")
            details["exception_type"] = e::class.simpleName ?: "Exception"
        }

        return TestResult("video_streams", errors.isEmpty(), errors, details, System.currentTimeMillis() - t0)
    }

    // Deep probe for stream[1]: HTTP 200 + a MIME type only prove the URL
    // resolves — not that video bytes come back. Protocol-aware checks:
    //   HLS (.m3u8 / mpegurl): playlist must carry #EXTM3U; its first URI is
    //     resolved against the playlist URL and fetched (one master -> media
    //     indirection allowed); the final segment must answer 2xx with a
    //     non-trivial body (>1KB — a real TS/fMP4 segment is always larger).
    //   DASH (.mpd): 2xx + "<MPD" in the body — manifest-only, segments are
    //     not fetched (checking them would mean downloading video).
    //   Direct media: 2xx, Content-Type must not be text/html (the "link
    //     resolves but serves the player page" trap), bytes must arrive.
    // Bounded: every fetch sends Range bytes=0-65535 (a server that ignores
    // Range trips the client's 25MB body cap and fails loudly instead of
    // downloading a whole file); timeoutMs=10s per fetch; redirects follow
    // the lib's SSRF-re-checked follower (MAX_REDIRECTS=20).
    private suspend fun probeStream(
        stream: VideoStream,
        details: MutableMap<String, Any>,
        errors: MutableList<String>,
    ) {
        val probeHeaders = stream.headers + ("Range" to "bytes=0-65535")

        val response = try {
            raw.get(sourceId, stream.url, headers = probeHeaders, timeoutMs = 10_000)
        } catch (e: ResponseTooLargeException) {
            details["stream[1]_probe"] =
                "FAILED: server ignored Range and body exceeded the 25MB client cap — cannot verify content"
            errors.add("stream[1]: probe body exceeds 25MB cap (Range ignored by server) — ${stream.url}")
            return
        } catch (e: Exception) {
            details["stream[1]_probe"] = "FAILED: ${e.message}"
            errors.add("stream[1]: probe failed: ${e.message}")
            return
        }

        val code = response.status.value
        val contentType = response.headers["Content-Type"] ?: "?"
        val body = response.bodyAsBytes()
        // BOM-tolerant sniff: playlists are text and the marker is ASCII.
        val text = body.decodeToString()
            .let { if (it.isNotEmpty() && it[0] == '\uFEFF') it.substring(1) else it }

        val path = stream.url.substringBefore('?').lowercase(Locale.ROOT)
        val isHls = path.endsWith(".m3u8") ||
            contentType.contains("mpegurl", ignoreCase = true) ||
            text.contains("#EXTM3U")
        val isDash = path.endsWith(".mpd") ||
            contentType.contains("dash+xml", ignoreCase = true) ||
            text.contains("<MPD")

        if (isHls) {
            probeHls(stream, code, contentType, text, probeHeaders, details, errors)
        } else if (isDash) {
            details["stream[1]_probe"] =
                "DASH: HTTP $code, $contentType, ${body.size}B (manifest-only check)"
            when {
                code !in 200..299 -> errors.add("stream[1]: HTTP $code — ${stream.url}")
                !text.contains("<MPD") ->
                    errors.add("stream[1]: response is not a DASH manifest (no <MPD>) — ${stream.url}")
            }
        } else {
            details["stream[1]_probe"] =
                "media: HTTP $code, $contentType, ${body.size}B (Range bytes=0-65535)"
            when {
                code !in 200..299 ->
                    errors.add("stream[1]: HTTP $code — ${stream.url}")
                contentType.contains("text/html", ignoreCase = true) ->
                    errors.add(
                        "stream[1]: URL serves an HTML player page, not media " +
                            "(Content-Type: $contentType) — ${stream.url}",
                    )
                body.isEmpty() ->
                    errors.add("stream[1]: 0 bytes received — ${stream.url}")
            }
        }
    }

    // HLS leg of the probe: validate the playlist, then demand bytes from the
    // first media segment. Master playlists point at media playlists — at most
    // one such hop is followed before the URI must be actual media.
    private suspend fun probeHls(
        stream: VideoStream,
        code: Int,
        contentType: String,
        text: String,
        probeHeaders: Map<String, String>,
        details: MutableMap<String, Any>,
        errors: MutableList<String>,
    ) {
        if (code !in 200..299) {
            details["stream[1]_probe"] = "HLS: HTTP $code, $contentType"
            errors.add("stream[1]: HTTP $code — ${stream.url}")
            return
        }
        if (!text.contains("#EXTM3U")) {
            details["stream[1]_probe"] = "HLS: HTTP $code, $contentType, no #EXTM3U marker"
            errors.add("stream[1]: response is not a valid HLS playlist (no #EXTM3U) — ${stream.url}")
            return
        }

        val variantLines = text.lineSequence().count { it.trimStart().startsWith("#EXT-X-STREAM-INF") }
        val segmentLines = text.lineSequence().count { it.trimStart().startsWith("#EXTINF") }
        details["stream[1]_probe"] =
            "HLS: HTTP $code, $contentType, ${text.length}B playlist, " +
                "$variantLines variant line(s), $segmentLines media segment line(s)"

        var playlistUrl = stream.url
        var playlistText = text
        for (hop in 0..1) {
            val firstUri = playlistText.lineSequence()
                .map(String::trim)
                .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
            if (firstUri == null) {
                errors.add("stream[1]: playlist has no segment URIs — $playlistUrl")
                return
            }
            val segmentUrl = try {
                URI(playlistUrl).resolve(firstUri).toString()
            } catch (e: Exception) {
                errors.add("stream[1]: cannot resolve segment URI '$firstUri': ${e.message}")
                return
            }

            val segment = try {
                raw.get(sourceId, segmentUrl, headers = probeHeaders, timeoutMs = 10_000)
            } catch (e: Exception) {
                details["stream[1]_segment"] = "FAILED: ${e.message} — $segmentUrl"
                errors.add("stream[1]: segment fetch failed: ${e.message}")
                return
            }
            val segmentCode = segment.status.value
            val segmentBytes = segment.bodyAsBytes()
            val segmentText = segmentBytes.decodeToString()

            // Master -> media: the first URI was another playlist — keep walking.
            if (segmentCode in 200..299 && segmentText.contains("#EXTM3U")) {
                details["stream[1]_variant"] =
                    "HTTP $segmentCode, ${segment.headers["Content-Type"] ?: "?"}, " +
                        "${segmentBytes.size}B — $segmentUrl"
                playlistUrl = segmentUrl
                playlistText = segmentText
                continue
            }

            details["stream[1]_segment"] = "HTTP $segmentCode, ${segmentBytes.size}B — $segmentUrl"
            when {
                segmentCode !in 200..299 ->
                    errors.add("stream[1]: segment HTTP $segmentCode — $segmentUrl")
                segmentBytes.isEmpty() ->
                    errors.add("stream[1]: segment returned 0 bytes — $segmentUrl")
                segmentBytes.size < 1024 ->
                    errors.add("stream[1]: segment body trivially small (${segmentBytes.size}B) — $segmentUrl")
            }
            return
        }
        errors.add("stream[1]: playlist indirection deeper than 2 levels — ${stream.url}")
    }

    private suspend fun testPageList(chapterUrl: String): TestResult {
        val t0 = System.currentTimeMillis()
        val errors = mutableListOf<String>()
        val details = linkedMapOf<String, Any>()
        details["url"] = chapterUrl

        if ("getPageList" !in defined) {
            errors.add("getPageList not defined")
            return TestResult("page_list", false, errors, details, System.currentTimeMillis() - t0)
        }

        val rawResponse = rawFetch(chapterUrl)
        details["raw_code"] = rawResponse.code

        val rawIssues = analyzeResponse(rawResponse)
        if (rawIssues.isNotEmpty()) {
            details["raw_issues"] = rawIssues.joinToString(" | ")
            errors.addAll(rawIssues.map { "SITE: $it" })
        }

        val rawImageUrls = extractRawImageUrls(rawResponse.body)
        if (rawImageUrls.isNotEmpty()) {
            details["raw_urls_count"] = rawImageUrls.size
            for (i in 1..minOf(rawImageUrls.size, 5)) {
                details["raw_url[$i]"] = rawImageUrls[i - 1]
            }
        }

        val hasReaderInit = rawResponse.body.contains("readerInit(")
        details["raw_has_readerInit"] = hasReaderInit
        val imgTags = Ksoup.parse(rawResponse.body, "").select("img[src]").size
        details["raw_img_tags"] = imgTags
        // SPA-style readers deliver pages via XHR, so raw-URL comparison is
        // meaningless there and is skipped.
        val rawHasPageUrls = hasReaderInit || imgTags > 0
        if (rawImageUrls.isNotEmpty() && !rawHasPageUrls) {
            details["raw_note"] = "Raw HTML has no direct page URLs (JS/API-driven reader) — URL comparison skipped"
        }

        val eventsBefore = events.size
        try {
            details["html_size"] = rawResponse.body.length

            val pageUrls: List<String>
            when (val outcome = source.chapterContent(rawResponse.body, chapterUrl)) {
                is Outcome.Err -> {
                    noteError(outcome.error)
                    errors.add("chapterContent: ${describe(outcome.error)}")
                    details["exception_type"] = outcome.error::class.simpleName ?: "PluginError"
                    return TestResult("page_list", false, errors, details, System.currentTimeMillis() - t0)
                }
                is Outcome.Ok -> when (val content = outcome.value) {
                    is ChapterContent.Pages -> pageUrls = content.pageUrls
                    else -> {
                        errors.add("chapterContent returned ${content::class.simpleName}, expected pages")
                        return TestResult("page_list", false, errors, details, System.currentTimeMillis() - t0)
                    }
                }
            }

            details["return_type"] = "Pages"
            val count = pageUrls.size
            details["page_count"] = count

            val showErrorsDuring = events.subList(eventsBefore, events.size).count { it.type == "show_error" }
            details["show_error_during_pageList"] = showErrorsDuring
            // The plugin flagged a blocked page itself: the raw-side SITE issues
            // are then expected, not extra failures.
            if (showErrorsDuring > 0) errors.removeAll { it.startsWith("SITE: ") }

            if (count == 0) {
                if (showErrorsDuring > 0) {
                    details["NOTE"] =
                        "Plugin returned 0 pages and called show_error $showErrorsDuring time(s) — content blocked (expected)"
                } else {
                    errors.add("Page list is empty")
                    if (rawImageUrls.isNotEmpty()) {
                        details["NOTE"] = "Site returned ${rawImageUrls.size} image URLs in HTML but plugin " +
                            "returned 0 — plugin parsing is broken"
                    }
                }
            } else {
                if (rawHasPageUrls && rawImageUrls.isNotEmpty() && rawImageUrls.size != count) {
                    details["MISMATCH"] = "Site has ${rawImageUrls.size} URLs, plugin returned $count"
                }

                val shown = minOf(count, MAX_ITEMS)
                for (i in 1..shown) {
                    val pluginUrl = pageUrls[i - 1]
                    details["page[$i]"] = pluginUrl
                    if (rawHasPageUrls && rawImageUrls.isNotEmpty() && i <= rawImageUrls.size) {
                        val rawUrl = rawImageUrls[i - 1]
                        if (normalizeImageUrl(rawUrl) != normalizeImageUrl(pluginUrl)) {
                            details["  raw[$i]"] = rawUrl
                            details["  diff[$i]"] = "PLUGIN CHANGED THE URL"
                        } else if (rawUrl != pluginUrl) {
                            details["  note[$i]"] = "query params differ (tokens/expiry) — tolerated"
                        }
                    }
                }
                if (count > shown) details["... +${count - shown} more"] = ""

                val imgTestCount = minOf(count, 5)
                val imageAnalyses = mutableListOf<ImageAnalysis>()
                var imgOk = 0
                var imgFail = 0
                val imgErrors = mutableListOf<String>()

                for (i in 1..imgTestCount) {
                    val imgUrl = pageUrls[i - 1]
                    if (imgUrl.isBlank()) continue
                    try {
                        val analysis = analyzeImage(imgUrl, chapterUrl, i)
                        imageAnalyses += analysis
                        if (analysis.code in 200..299) {
                            imgOk++
                            details["img[$i]"] = "OK (${analysis.code}, ${analysis.size}B) — $imgUrl"
                        } else {
                            imgFail++
                            imgErrors += "img[$i]: HTTP ${analysis.code} — $imgUrl"
                            details["img[$i]"] = "FAIL (${analysis.code}) — $imgUrl"
                        }
                    } catch (e: Exception) {
                        imgFail++
                        imgErrors += "img[$i]: ${e.message} — $imgUrl"
                        details["img[$i]"] = "FAIL (${e::class.simpleName}) — $imgUrl"
                    }
                }

                val stubIssues = detectImageStubs(imageAnalyses)
                val isCdnAuthIssue = imgOk == 0 && imgFail > 0 &&
                    imageAnalyses.filter { it.code !in 200..299 }.map { it.code }.distinct().size == 1
                if (stubIssues.isNotEmpty()) {
                    details["image_issues"] = stubIssues.joinToString(" | ")
                    if (showErrorsDuring > 0 || isCdnAuthIssue) {
                        details["image_issues_note"] = "Image issues expected — " +
                            if (showErrorsDuring > 0) "plugin already reported blocking error"
                            else "CDN/auth issue (all images same error code)"
                    } else {
                        errors.addAll(stubIssues.map { "IMAGE: $it" })
                    }
                }

                details["img_tested"] = imgTestCount
                details["img_ok"] = imgOk
                details["img_fail"] = imgFail

                if (imgFail > 0) {
                    if (showErrorsDuring > 0) {
                        details["img_fail_note"] =
                            "Image failures expected — plugin already reported blocking error"
                    } else {
                        val failCodes = imageAnalyses.filter { it.code !in 200..299 }.map { it.code }.distinct()
                        if (imgOk == 0 && failCodes.size == 1) {
                            details["img_fail_note"] =
                                "All $imgFail images returned HTTP ${failCodes.first()} — CDN/auth issue " +
                                "(not a plugin bug)"
                        } else {
                            errors.add("Image loading: $imgFail/$imgTestCount failed")
                            imgErrors.forEach { errors.add(it) }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            errors.add("getPageList threw: ${e.message}")
            details["exception_type"] = e::class.simpleName ?: "Exception"
        }

        return TestResult("page_list", errors.isEmpty(), errors, details, System.currentTimeMillis() - t0)
    }

    private suspend fun testFilters(): TestResult {
        val t0 = System.currentTimeMillis()
        val errors = mutableListOf<String>()
        val details = linkedMapOf<String, Any>()

        val filters: List<Map<String, String>>
        when (val outcome = source.filters()) {
            is Outcome.Err -> {
                noteError(outcome.error)
                errors.add("getFilterList: ${describe(outcome.error)}")
                return TestResult("filters", false, errors, details, System.currentTimeMillis() - t0)
            }
            is Outcome.Ok -> filters = outcome.value
        }

        details["return_type"] = "List<Map<String,String>>"
        val count = filters.size
        details["filter_count"] = count

        if (count == 0) {
            errors.add("getFilterList returned empty table")
            return TestResult("filters", false, errors, details, System.currentTimeMillis() - t0)
        }

        var totalOptions = 0
        var filtersWithIssues = 0
        val getCatalogFilteredExists = "getCatalogFiltered" in defined
        var filteredCallSucceeded = false
        var filteredCallError = ""

        data class FilterInfo(val index: Int, val key: String, val type: String, val firstValue: String, val label: String)
        val usableFilters = mutableListOf<FilterInfo>()

        for (i in 1..count) {
            val filter = filters[i - 1]
            // The lib flattens the Lua table into dotted keys, so option j is
            // 0-based here where the old tool indexed 1-based Lua arrays.
            val name = filter["label"] ?: filter["name"] ?: ""
            val type = filter["type"] ?: ""
            val key = filter["key"] ?: ""
            val defaultVal = filter["defaultValue"]
            val optionCount = filter["options.count"]?.toIntOrNull() ?: 0

            if (name.isBlank()) {
                details["filter[$i]"] = "MISSING label/name"
                filtersWithIssues++
                continue
            }
            if (type.isBlank()) {
                details["filter[$i]"] = "$name — MISSING type"
                filtersWithIssues++
                continue
            }

            val hasOptions = optionCount > 0
            totalOptions += optionCount
            val issues = mutableListOf<String>()

            if (type !in VALID_FILTER_TYPES) issues.add("unknown type '$type'")
            if (!hasOptions && type in setOf("select", "checkbox", "tristate")) issues.add("no options defined")

            if (hasOptions) {
                val values = mutableSetOf<String>()
                val labels = mutableSetOf<String>()
                var emptyLabels = 0
                var emptyValues = 0
                var duplicates = 0

                for (j in 0 until optionCount) {
                    val value = filter["options.$j.value"] ?: ""
                    val label = filter["options.$j.label"] ?: ""
                    if (value.isBlank()) emptyValues++
                    if (label.isBlank()) emptyLabels++
                    if (!values.add(value)) duplicates++
                    labels += label
                }

                if (emptyValues > 1) issues.add("${emptyValues - 1} options with empty value (first 'All' option excluded)")
                if (emptyLabels > 1) issues.add("${emptyLabels - 1} options with empty label (first option excluded)")
                if (duplicates > 0) issues.add("$duplicates duplicate values")

                val optionLabels = (0 until optionCount).map { filter["options.$it.label"] ?: "?" }
                details["  options[$i]"] = if (optionCount <= 6) {
                    optionLabels.joinToString(", ")
                } else {
                    "${optionLabels.take(3).joinToString(", ")} ... +${optionCount - 3} more"
                }

                // Probe option: first one with a non-empty value (the leading
                // "All"/"Любой" entry normally carries an empty value).
                if (type == "select" && key.isNotBlank() && optionCount >= 2) {
                    for (j in 0 until optionCount) {
                        val value = filter["options.$j.value"] ?: ""
                        if (value.isNotBlank()) {
                            usableFilters += FilterInfo(i, key, type, value, name)
                            break
                        }
                    }
                }
            }

            if (defaultVal != null && hasOptions) {
                val found = (0 until optionCount).any { (filter["options.$it.value"] ?: "") == defaultVal }
                if (!found) issues.add("defaultValue '$defaultVal' not in options")
            }

            val summary = buildString {
                append("$name ($type)")
                if (key.isNotBlank()) append(" key=$key")
                append(" — $optionCount options")
                if (defaultVal == null) append(" noDefault")
            }

            if (issues.isNotEmpty()) {
                details["filter[$i]"] = "$summary ← ISSUES"
                issues.forEachIndexed { index, issue -> details["  issue[$i]-$index"] = issue }
                filtersWithIssues++
            } else {
                details["filter[$i]"] = summary
            }
        }

        details["total_options"] = totalOptions

        if (getCatalogFilteredExists && usableFilters.isNotEmpty() && filtersWithIssues == 0) {
            val probe = usableFilters.first()
            try {
                // PluginSource takes each filter value as a LIST; the old tool
                // passed a bare string because Lua did not care.
                val outcome = source.catalogFiltered(0, mapOf(probe.key to listOf(probe.firstValue)))
                val filteredCount = (outcome as? Outcome.Ok)?.value?.items?.size ?: 0

                details["filtered_call"] = "getCatalogFiltered(0, {${probe.key}='${probe.firstValue}'})"
                details["filtered_items"] = filteredCount
                details["filtered_filter"] = "${probe.label} → ${probe.firstValue}"

                if (outcome is Outcome.Err) {
                    noteError(outcome.error)
                    filteredCallError = describe(outcome.error)
                    details["filtered_test"] = "FAIL — $filteredCallError"
                } else if (filteredCount > 0) {
                    filteredCallSucceeded = true
                    details["filtered_test"] = "PASS — $filteredCount items returned"
                } else {
                    filteredCallError = "getCatalogFiltered returned 0 items (may be valid if filter matches nothing)"
                    details["filtered_test"] = "WARN — 0 items (filter may match nothing)"
                }
            } catch (e: Exception) {
                filteredCallError = "getCatalogFiltered threw: ${e.message}"
                details["filtered_test"] = "FAIL — ${e::class.simpleName}: ${e.message}"
            }
        } else if (!getCatalogFilteredExists) {
            details["filtered_test"] = "SKIPPED — getCatalogFiltered not defined"
        } else if (usableFilters.isEmpty()) {
            details["filtered_test"] = "SKIPPED — no usable select filters"
        }

        if (filtersWithIssues > 0) errors.add("$filtersWithIssues/$count filters have issues (see details)")
        if (filteredCallError.isNotBlank() && !filteredCallSucceeded && "returned 0 items" !in filteredCallError) {
            errors.add("Filter functional test: $filteredCallError")
        }

        return TestResult("filters", errors.isEmpty(), errors, details, System.currentTimeMillis() - t0)
    }

    private fun testPluginEvents(): TestResult {
        val t0 = System.currentTimeMillis()
        val details = linkedMapOf<String, Any>()

        if (events.isEmpty()) {
            details["note"] = "No show_error/log_error/log_info calls detected"
            return TestResult("plugin_events", true, emptyList(), details, System.currentTimeMillis() - t0)
        }

        val errors = events.count { it.type == "show_error" }
        details["total_events"] = events.size
        details["show_error_count"] = errors

        val shown = minOf(events.size, 10)
        for (i in 0 until shown) {
            val event = events[i]
            val prefix = if (event.type == "show_error") "[SHOW_ERROR]" else "[${event.type.uppercase()}]"
            val msg = if (event.title.isNotBlank()) "${event.title}: ${event.message}" else event.message
            details["event[$i]"] = "$prefix $msg"
        }
        if (events.size > shown) details["... +${events.size - shown} more"] = ""
        if (errors > 0) details["summary"] = "Plugin reported $errors error(s) to user via show_error()"

        // plugin_events is informational — never fails the test.
        return TestResult("plugin_events", true, emptyList(), details, System.currentTimeMillis() - t0)
    }

    private fun testSiteHealth(results: List<TestResult>): TestResult {
        val t0 = System.currentTimeMillis()
        val details = linkedMapOf<String, Any>()
        val allIssues = mutableListOf<String>()

        val pluginErrors = events.filter { it.type == "show_error" }
        if (pluginErrors.isNotEmpty()) {
            details["plugin_errors"] = pluginErrors.size
            pluginErrors.forEachIndexed { i, event ->
                details["  plugin_err[$i]"] = "${event.title}: ${event.message}"
            }
            allIssues += pluginErrors.map { "PLUGIN: ${it.title}: ${it.message}" }
        }

        val blockedRequests = trace.filter { it.cfBlocked }
        if (blockedRequests.isNotEmpty()) {
            details["blocked_requests"] = blockedRequests.size
            blockedRequests.forEachIndexed { i, entry ->
                details["  blocked[$i]"] = "CF: ${entry.method} ${entry.url} → ${entry.status}"
            }
            allIssues += blockedRequests.map { "BLOCKED: ${it.method} ${it.url} → ${it.status}" }
        }

        val authTests = results.filter { it.details["raw_issues"]?.toString()?.contains("AUTH_REQUIRED") == true }
        if (authTests.isNotEmpty()) {
            details["auth_required"] = authTests.size
            authTests.forEach { details["  auth[${it.name}]"] = it.details["url"]?.toString() ?: "?" }
            allIssues += authTests.map { "AUTH_REQUIRED: ${it.details["url"]}" }
        }

        val imageTests = results.filter { it.details["image_issues"]?.toString()?.isNotBlank() == true }
        if (imageTests.isNotEmpty()) {
            details["image_issues"] = imageTests.size
            imageTests.forEach { details["  img[${it.name}]"] = it.details["image_issues"].toString() }
            allIssues += imageTests.map { "IMAGE: ${it.details["image_issues"]}" }
        }

        val urlChanges = results.filter { it.details.values.any { v -> v.toString().contains("PLUGIN CHANGED THE URL") } }
        if (urlChanges.isNotEmpty()) {
            details["url_changes"] = urlChanges.size
            urlChanges.forEach { details["  url[${it.name}]"] = "Plugin modified site URLs" }
            allIssues += urlChanges.map { "URL_CHANGE: ${it.name}" }
        }

        details["total_issues"] = allIssues.size

        // site_health is informational — shows a summary, never fails.
        return TestResult("site_health", true, emptyList(), details, System.currentTimeMillis() - t0)
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun noteError(error: PluginError) {
        if (error is PluginError.UserMessage) {
            events += PluginEvent("show_error", error.title, error.message)
        }
    }

    private fun describe(error: PluginError): String = when (error) {
        is PluginError.UserMessage -> "${error.title}: ${error.message}"
        is PluginError.Network -> error.cause
        is PluginError.DnsFailure -> "DNS failure for ${error.host}"
        is PluginError.CfChallengeRequired -> "CF challenge required for ${error.host}"
        is PluginError.ResponseTooLarge -> "response exceeds ${error.limit} bytes"
        is PluginError.UnsupportedApi -> "unsupported plugin API ${error.actual} (requires ${error.required})"
        is PluginError.ScriptError -> error.detail
    }

    private fun annotateBlocked(details: MutableMap<String, Any>, error: PluginError) {
        val code = trace.lastOrNull()?.status
        if (error is PluginError.CfChallengeRequired || code == 403 || code == 503) {
            details["note"] = "CF-blocked (use --cf to bypass)"
        }
    }

    private fun bookSectionOf(error: PluginError): String? {
        val detail = when (error) {
            is PluginError.ScriptError -> error.detail
            is PluginError.UserMessage -> "${error.title} ${error.message}"
            else -> return null
        }
        return BOOK_FIELD_SECTION.entries.firstOrNull { (fields, _) ->
            fields.any { detail.contains(it) }
        }?.value
    }

    private fun extractFirstUrl(result: TestResult): String? =
        result.details["  url"]?.toString()?.takeIf { it.isNotBlank() && it != "?" }

    private fun extractFirstChapterUrl(result: TestResult): String? =
        result.details["  url"]?.toString()?.takeIf { it.isNotBlank() && it != "?" }

    private suspend fun validateCatalogItem(
        item: CatalogItem,
        index: Int,
        errors: MutableList<String>,
        details: MutableMap<String, Any>,
    ) {
        val title = item.title
        val url = item.url
        val cover = item.coverUrl.orEmpty()

        if (title.isBlank()) errors.add("item[$index]: title is empty")
        if (url.isBlank()) {
            errors.add("item[$index]: url is empty")
        } else if (!url.startsWith("http://") && !url.startsWith("https://")) {
            errors.add("item[$index]: url is not absolute: '$url'")
        }
        if (url.any { it.isWhitespace() }) errors.add("item[$index]: url contains whitespace: '$url'")

        if (cover.isNotBlank()) {
            if (!cover.startsWith("http://") && !cover.startsWith("https://")) {
                errors.add("item[$index]: cover URL is not absolute: '$cover'")
            }
            if (cover.any { it.isWhitespace() }) errors.add("item[$index]: cover URL contains whitespace: '$cover'")
            details["  cover"] = cover
            if (cover.startsWith("http://") || cover.startsWith("https://")) {
                try {
                    val analysis = analyzeImage(cover, url, index)
                    details["  cover[$index]"] = "HTTP ${analysis.code}, ${analysis.size}B, ${analysis.contentType}"
                    when {
                        analysis.code !in 200..399 ->
                            errors.add("item[$index]: cover image HTTP ${analysis.code}: '$cover'")
                        analysis.contentType.contains("text/html") ->
                            errors.add("item[$index]: cover returned text/html (CDN/blocked): '$cover'")
                        analysis.size in 1..1024 ->
                            errors.add("item[$index]: cover image <1KB (likely placeholder): '$cover'")
                    }
                    stubKeywords.firstOrNull { cover.lowercase().contains(it) }?.let {
                        errors.add("item[$index]: cover URL contains stub keyword '$it': '$cover'")
                    }
                } catch (e: Exception) {
                    errors.add("item[$index]: cover fetch failed: ${e.message}")
                }
            }
        } else {
            details["  cover"] = "(empty - allowed)"
        }
    }

    private fun truncate(s: String, max: Int): String =
        if (s.length <= max) s else s.take(max) + "…(${s.length} chars)"

    private suspend fun rawFetch(url: String): RawSiteResponse {
        val response = raw.get(sourceId, url)
        val headers = mutableMapOf<String, List<String>>()
        for (name in response.headers.names()) {
            headers[name.lowercase()] = response.headers.getAll(name).orEmpty()
        }
        return RawSiteResponse(response.status.value, response.bodyAsText(), headers)
    }

    private suspend fun siteHasDescription(url: String): Boolean {
        val response = try {
            raw.get(sourceId, url)
        } catch (_: Exception) {
            return false
        }
        if (response.status.value !in 200..399) return false
        val body = response.bodyAsText()
        val lower = body.lowercase()
        val meta = META_DESCRIPTION.findAll(body)
            .mapNotNull { it.groupValues.getOrNull(1)?.trim()?.takeIf { v -> v.isNotEmpty() } }
            .toList()
        if (meta.isNotEmpty()) return true
        return lower.contains("itemprop=\"description\"") &&
            DESCRIPTION_BLOCK.find(body)?.groupValues?.getOrNull(1)?.let { text ->
                text.replace(Regex("<[^>]+>"), " ").trim().isNotEmpty()
            } == true
    }

    private fun analyzeResponse(response: RawSiteResponse): List<String> {
        val issues = mutableListOf<String>()
        val body = response.body.lowercase()
        val headers = response.headers

        val location = headers["location"]?.firstOrNull()
        if (location != null) {
            val locLower = location.lowercase()
            if (listOf("/login", "/auth", "/signin", "/enter").any { locLower.contains(it) }) {
                issues.add("AUTH_REQUIRED: redirect to $location")
            }
        }

        val server = headers["server"]?.firstOrNull()?.lowercase() ?: ""
        if (server.contains("ddos-guard") && response.code in 300..399) {
            issues.add("JS_CHALLENGE: DDoS Guard redirect (code ${response.code})")
        }

        val setCookies = headers["set-cookie"] ?: emptyList()
        if (setCookies.any { it.lowercase().startsWith("sid=") }) {
            issues.add("JOKEN_HANDSHAKE: sid cookie set (bot protection)")
        }

        if (response.code !in 200..399) {
            issues.add("HTTP_ERROR: code ${response.code}")
            return issues
        }

        val hasPasswordInput = body.contains("type=\"password\"") || body.contains("type='password'") ||
            body.contains("password") || body.contains("парол")
        if (response.body.length < 30_000 && hasPasswordInput &&
            (body.contains("login") || body.contains("sign in") ||
                body.contains("войти") || body.contains("авторизац"))
        ) {
            issues.add("AUTH_REQUIRED: login form in response body")
        }

        if (PAID_CONTENT.any { body.contains(it) }) issues.add("PAID_CONTENT: purchase/subscription indicators found")
        if (AGE_RESTRICTED.any { body.contains(it) }) issues.add("AGE_RESTRICTED: age verification required")

        val cfMitigated = headers["cf-mitigated"]?.firstOrNull()?.lowercase() ?: ""
        val strongChallenge = body.contains("cf-chl") || body.contains("cf_chl") ||
            body.contains("attention required") || cfMitigated.contains("challenge") ||
            (body.contains("challenge-platform") && !body.contains("scripts/jsd/main.js"))
        val weakChallenge = listOf("captcha", "recaptcha", "hcaptcha", "checking your browser", "turnstile")
            .any { body.contains(it) }
        if (strongChallenge || (response.body.length < 30_000 && weakChallenge)) {
            issues.add("CAPTCHA: bot challenge detected")
        }

        if (LICENSED.any { body.contains(it) }) issues.add("LICENSED: content removed by rights holder")

        return issues
    }

    private suspend fun analyzeImage(url: String, refererUrl: String, index: Int): ImageAnalysis {
        val response = raw.get(sourceId, url, headers = mapOf("Referer" to refererUrl))
        val bytes = response.bodyAsBytes()
        val contentType = response.headers["Content-Type"] ?: ""
        var hash = 0
        for (i in 0 until minOf(200, bytes.size)) {
            hash = 31 * hash + (bytes[i].toInt() and 0xFF)
        }
        return ImageAnalysis(url, response.status.value, bytes.size, contentType, hash)
    }

    private fun detectImageStubs(images: List<ImageAnalysis>): List<String> {
        val issues = mutableListOf<String>()
        if (images.size < 2) return issues

        val hashes = images.map { it.firstBytesHash }.filter { it != 0 }
        if (hashes.size >= 2 && hashes.distinct().size == 1) {
            issues.add("STUB_DETECTED: all ${images.size} images have identical content (same placeholder)")
        }

        val sizes = images.map { it.size }.filter { it > 0 }
        if (sizes.size >= 2 && sizes.distinct().size == 1) {
            issues.add("SUSPICIOUS: all ${images.size} images have identical size (${sizes.first()} bytes)")
        }

        val tinyImages = images.filter { it.size in 1..1024 }
        if (tinyImages.isNotEmpty()) {
            issues.add("TINY_IMAGES: ${tinyImages.size}/${images.size} images < 1KB (likely placeholders)")
        }

        val htmlResponses = images.filter { it.contentType.contains("text/html") }
        if (htmlResponses.isNotEmpty()) {
            issues.add("CONTENT_TYPE_MISMATCH: ${htmlResponses.size} responses are text/html instead of image")
        }

        for (img in images) {
            val lower = img.url.lowercase()
            stubKeywords.firstOrNull { lower.contains(it) }?.let {
                issues.add("STUB_URL: image URL contains '$it': ${img.url}")
                return@let
            }
        }

        return issues
    }

    // readerInit array first (concatenated CDN path fragments), then a plain
    // image-URL sweep as a fallback.
    private fun extractRawImageUrls(html: String): List<String> {
        val urls = mutableListOf<String>()

        val riStart = html.indexOf("readerInit(", 1, true)
        if (riStart > 0) {
            val arrStart = html.indexOf("[", riStart, true)
            if (arrStart > 0) {
                var depth = 0
                var pos = arrStart
                var arrEnd = -1
                while (pos < html.length) {
                    val c = html[pos]
                    if (c == '[') depth++
                    else if (c == ']') {
                        depth--
                        if (depth == 0) {
                            arrEnd = pos
                            break
                        }
                    }
                    pos++
                }
                if (arrEnd > arrStart) {
                    val pageArray = html.substring(arrStart + 1, arrEnd)
                    var entryStart = 0
                    while (entryStart < pageArray.length) {
                        val s = pageArray.indexOf('[', entryStart, true)
                        if (s < 0) break
                        val e = pageArray.indexOf(']', s + 1, true)
                        if (e < 0) break
                        val entry = pageArray.substring(s + 1, e)
                        val parts = Regex("""'([^']*)'|"([^"]*)"""").findAll(entry)
                            .map { it.groupValues[1].ifEmpty { it.groupValues[2] } }
                            .toList()
                        if (parts.size >= 3) urls.add(parts.joinToString(""))
                        entryStart = e + 1
                    }
                }
            }
            if (urls.isNotEmpty()) return urls
        }

        for (match in IMAGE_URL.findAll(html)) {
            val url = match.value
            val lower = url.lowercase()
            if (!lower.contains("favicon") && !lower.contains("icon") &&
                !lower.contains("/logo") && !lower.contains("logo-")
            ) {
                urls.add(url)
            }
        }

        return urls.distinct()
    }

    // Heuristic chapter-link extraction for the raw-vs-plugin comparison.
    // ponytail: selector-based heuristics — for SPA/JS readers this may find
    // nothing, in which case the raw comparison is skipped.
    private fun extractRawChapters(html: String, baseUrl: String): List<String> {
        val doc = Ksoup.parse(html, baseUrl)
        return doc.select(
            "a[href*='chapter' i], a[href*='chapitre' i], a[href*='glava' i], a[href*='tom' i], " +
                "a[href*='vol' i], .chapter-item a, [class*=chapter] a",
        )
            .map { it.attr("abs:href").ifEmpty { it.attr("href") } }
            .filter { it.startsWith("http") }
            .distinct()
    }

    // Tokenized CDN URLs (?t=, ?u=, one-way.work) change per request — only a
    // difference in path counts as the plugin rewriting the URL.
    private fun normalizeImageUrl(url: String): String {
        val q = url.indexOf('?')
        val f = url.indexOf('#')
        val end = listOf(q, f).filter { it >= 0 }.minOrNull() ?: url.length
        return url.substring(0, end)
    }

    companion object {
        private val VALID_FILTER_TYPES = setOf("select", "checkbox", "tristate", "text", "sort", "genre")

        private val stubKeywords = listOf(
            "placeholder", "stub", "no-cover", "now_printing", "restricted",
            "no_image", "deleted1.png", "deleted2.png",
        )

        // Which book_* section a getter belongs to, used to attribute an
        // aggregated book() failure to one section instead of all six.
        private val BOOK_FIELD_SECTION: Map<List<String>, String> = linkedMapOf(
            listOf("getBookTitle") to "book_title",
            listOf("getBookCoverImageUrl") to "book_cover",
            listOf("getBookDescription") to "book_description",
            listOf("getBookStatus") to "book_status",
            listOf("getBookRating") to "book_rating",
            listOf("getBookGenres", "getBookLastUpdate", "getBookChapterCount", "getBookExtras", "getBookAuthor") to "book_extras",
            listOf("getBookMeta") to "book_title",
        )

        private val BOOK_TITLE_FIELDS = setOf("book_title")
        private val BOOK_COVER_FIELDS = setOf("book_cover")
        private val BOOK_DESCRIPTION_FIELDS = setOf("book_description")
        private val BOOK_STATUS_FIELDS = setOf("book_status")
        private val BOOK_RATING_FIELDS = setOf("book_rating")
        private val BOOK_EXTRAS_FIELDS = setOf("book_extras")

        private val HTML_MARKUP =
            Regex("""<(div|p|span|h[1-6]|img|br|table)[ >]""", RegexOption.IGNORE_CASE)

        private val IMAGE_URL =
            Regex("""https?://[^\s"'<>]+\.(?:jpg|jpeg|png|webp|gif)(?:\?[^\s"'<>]*)?""", RegexOption.IGNORE_CASE)

        private val META_DESCRIPTION = Regex(
            """<meta[^>]+(?:name|property)="(?:description|og:description|twitter:description|og:title)"[^>]*content="([^"]*)"?""",
            RegexOption.IGNORE_CASE,
        )

        private val DESCRIPTION_BLOCK = Regex(
            """itemprop="description"[^>]*>([\s\S]{0,5000}?)</(?:div|p|span|section|article)>""",
            RegexOption.IGNORE_CASE,
        )

        private val PAID_CONTENT = listOf(
            "купить главу", "купить книгу", "купить том", "платный доступ", "платная глава",
            "buy chapter", "purchase chapter", "premium chapter", "pay to read", "unlock chapter",
        )

        private val AGE_RESTRICTED = listOf(
            "подтвердите возраст", "подтвердить возраст", "age verification",
            "you must be 18", "вход только для лиц",
        )

        private val LICENSED = listOf(
            "по требованию правообладателя", "удалено по требованию",
            "removed by rights holder", "removed due to copyright",
            "licensed removal", "dmca takedown",
        )
    }
}
