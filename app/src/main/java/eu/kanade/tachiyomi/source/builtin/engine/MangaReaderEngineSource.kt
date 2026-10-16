package eu.kanade.tachiyomi.source.builtin.engine

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Generic MangaReader Engine implementation.
 * Powers 200+ reader sites with standard chapter tables and fast image extractors.
 */
@Suppress("DEPRECATION")
open class MangaReaderEngineSource(
    override val id: Long,
    override val name: String,
    override val baseUrl: String,
    override val lang: String = "en",
) : ParsedHttpSource() {

    override val supportsLatest: Boolean = true

    override val client: OkHttpClient
        get() = network.client

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("Referer", "$baseUrl/")

    // =========================== Popular ===========================

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/manga/?order=popular&page=$page", headers)
    }

    override fun popularMangaSelector(): String =
        ".bsx, .listupd .bs, .animepost"

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        val link = element.select("a").first()
        manga.setUrlWithoutDomain(link?.attr("abs:href") ?: "")

        val title = element.select(".tt, .title, .ttx, h4, h3, .bigor .tt").first()?.text()
            ?: link?.attr("title")
            ?: "Unknown"
        manga.title = title.trim()

        val img = element.select("img").first()
        manga.thumbnail_url = extractImageUrl(img)
        return manga
    }

    override fun popularMangaNextPageSelector(): String =
        ".hpage a.r, a.nextpostslink, .pagination .next, a[rel=next]"

    // =========================== Latest ===========================

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/manga/?order=update&page=$page", headers)
    }

    override fun latestUpdatesSelector(): String = popularMangaSelector()

    override fun latestUpdatesFromElement(element: Element): SManga = popularMangaFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularMangaNextPageSelector()

    // =========================== Search ===========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        return GET("$baseUrl/page/$page/?s=$encoded", headers)
    }

    override fun searchMangaSelector(): String = popularMangaSelector()

    override fun searchMangaFromElement(element: Element): SManga = popularMangaFromElement(element)

    override fun searchMangaNextPageSelector(): String = popularMangaNextPageSelector()

    // =========================== Details ===========================

    override fun mangaDetailsParse(document: Document): SManga {
        val manga = SManga.create()

        val title = document.select(".entry-title, h1.entry-title, .infox h1").first()?.text()?.trim()
        if (!title.isNullOrEmpty()) {
            manga.title = title
        }

        manga.author = document.select(".spe span:contains(Author), .infox .fmed:contains(Author), .tsinfo .imptdt:contains(Author)")
            .text()
            .replace("Author:", "", ignoreCase = true)
            .trim()
            .ifEmpty { null }

        manga.description = document.select(".entry-content, .desc, .summary, .wd-full p")
            .first()
            ?.text()
            ?.trim()

        manga.genre = document.select(".seriestugenre a, .mgen a, .genres-container a")
            .eachText()
            .joinToString(", ")
            .ifEmpty { null }

        val statusText = document.select(".spe span:contains(Status), .infox .fmed:contains(Status), .tsinfo .imptdt:contains(Status)")
            .text()
            .lowercase()
        manga.status = when {
            statusText.contains("completed") -> SManga.COMPLETED
            statusText.contains("hiatus") -> SManga.ON_HIATUS
            statusText.contains("dropped") -> SManga.CANCELLED
            else -> SManga.ONGOING
        }

        val img = document.select(".thumb img, .infox img, .bigcover img").first()
        manga.thumbnail_url = extractImageUrl(img)

        return manga
    }

    // =========================== Chapters ===========================

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val elements = document.select("#chapterlist li, .clstyle li, .eph-num, ul.clstyle li")

        return elements.mapNotNull { element ->
            val link = element.select("a").first() ?: return@mapNotNull null
            val chapter = SChapter.create()
            chapter.name = element.select(".chapternum, .lchx, span.name").first()?.text()?.trim()
                ?: link.text().trim()
            chapter.setUrlWithoutDomain(link.attr("abs:href"))

            val dateText = element.select(".chapterdate, span.date").text().trim()
            chapter.date_upload = parseChapterDate(dateText)
            chapter
        }
    }

    override fun chapterFromElement(element: Element): SChapter =
        throw UnsupportedOperationException("Parsed in chapterListParse")

    override fun chapterListSelector(): String =
        throw UnsupportedOperationException("Parsed in chapterListParse")

    // =========================== Pages ===========================

    override fun pageListParse(document: Document): List<Page> {
        val pages = mutableListOf<Page>()
        val imgElements = document.select("#readerarea img, .reading-content img, #readingarea img")

        var index = 0
        imgElements.forEach { img ->
            val src = extractImageUrl(img)
            if (!src.isNullOrBlank() && !src.contains("banner", ignoreCase = true)) {
                pages.add(Page(index++, "", src))
            }
        }
        return pages
    }

    override fun imageUrlParse(document: Document): String =
        throw UnsupportedOperationException("Not used with direct Page image URLs")

    // =========================== Helpers ===========================

    private fun extractImageUrl(element: Element?): String? {
        if (element == null) return null
        return listOf(
            element.attr("abs:data-src"),
            element.attr("abs:data-lazy-src"),
            element.attr("abs:src"),
        ).firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
    }

    private fun parseChapterDate(dateStr: String): Long {
        if (dateStr.isBlank()) return 0L
        val lower = dateStr.lowercase()
        val now = Calendar.getInstance()

        return try {
            when {
                lower.contains("ago") || lower.contains("mins") || lower.contains("hours") || lower.contains("days") -> {
                    val number = Regex("\\d+").find(dateStr)?.value?.toIntOrNull() ?: 1
                    when {
                        lower.contains("sec") -> now.apply { add(Calendar.SECOND, -number) }.timeInMillis
                        lower.contains("min") -> now.apply { add(Calendar.MINUTE, -number) }.timeInMillis
                        lower.contains("hour") -> now.apply { add(Calendar.HOUR_OF_DAY, -number) }.timeInMillis
                        lower.contains("day") -> now.apply { add(Calendar.DAY_OF_YEAR, -number) }.timeInMillis
                        lower.contains("week") -> now.apply { add(Calendar.WEEK_OF_YEAR, -number) }.timeInMillis
                        lower.contains("month") -> now.apply { add(Calendar.MONTH, -number) }.timeInMillis
                        else -> 0L
                    }
                }
                else -> {
                    val formats = listOf(
                        SimpleDateFormat("MMMM d, yyyy", Locale.US),
                        SimpleDateFormat("MMM d, yyyy", Locale.US),
                        SimpleDateFormat("yyyy-MM-dd", Locale.US),
                    )
                    for (fmt in formats) {
                        try {
                            return fmt.parse(dateStr)?.time ?: continue
                        } catch (_: Exception) {
                            // try next
                        }
                    }
                    0L
                }
            }
        } catch (_: Exception) {
            0L
        }
    }
}
