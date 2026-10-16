package eu.kanade.tachiyomi.source.builtin.engine

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
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
 * Generic WordPress Madara Engine implementation.
 * Powers hundreds of scanlation and manhwa websites out-of-the-box.
 */
@Suppress("DEPRECATION")
open class MadaraEngineSource(
    override val id: Long,
    override val name: String,
    override val baseUrl: String,
    override val lang: String = "en",
) : ParsedHttpSource() {

    override val supportsLatest: Boolean = true

    override val client: OkHttpClient
        get() = network.client

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
        .add("Referer", "$baseUrl/")

    // =========================== Popular ===========================

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/manga/page/$page/?m_orderby=views", headers)
    }

    override fun popularMangaSelector(): String =
        ".page-item-detail, .c-tabs-item__content, .manga-item, .badge-pos-1"

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        val link = element.select(".post-title a, .item-title a, h3 a, h4 a").first()
            ?: element.select("a").first()

        manga.title = link?.text()?.trim() ?: "Unknown"
        manga.setUrlWithoutDomain(link?.attr("abs:href") ?: "")

        val img = element.select("img").first()
        manga.thumbnail_url = extractImageUrl(img)
        return manga
    }

    override fun popularMangaNextPageSelector(): String =
        ".nav-previous, .next, a.nextpostslink, .wp-pagenavi a.next"

    // =========================== Latest ===========================

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/manga/page/$page/?m_orderby=latest", headers)
    }

    override fun latestUpdatesSelector(): String = popularMangaSelector()

    override fun latestUpdatesFromElement(element: Element): SManga = popularMangaFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularMangaNextPageSelector()

    // =========================== Search ===========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
        return GET("$baseUrl/page/$page/?s=$encodedQuery&post_type=wp-manga&m_orderby=latest", headers)
    }

    override fun searchMangaSelector(): String = popularMangaSelector()

    override fun searchMangaFromElement(element: Element): SManga = popularMangaFromElement(element)

    override fun searchMangaNextPageSelector(): String = popularMangaNextPageSelector()

    // =========================== Details ===========================

    override fun mangaDetailsParse(document: Document): SManga {
        val manga = SManga.create()

        val title = document.select(".post-title h1, .manga-title h1, h1.entry-title").first()?.text()?.trim()
        if (!title.isNullOrEmpty()) {
            manga.title = title
        }

        manga.author = document.select(".author-content a, .artist-content a")
            .eachText()
            .distinct()
            .joinToString(", ")
            .ifEmpty { null }

        manga.description = document.select(".description-summary .summary__content, .manga-excerpt, .manga-summary, .summary-text")
            .first()
            ?.text()
            ?.trim()

        manga.genre = document.select(".genres-content a, .manga-genres a")
            .eachText()
            .joinToString(", ")
            .ifEmpty { null }

        val statusText = document.select(".post-content_item:contains(Status), .post-status:contains(Status)")
            .text()
            .lowercase()
        manga.status = when {
            statusText.contains("completed") || statusText.contains("complete") -> SManga.COMPLETED
            statusText.contains("on hold") || statusText.contains("hiatus") -> SManga.ON_HIATUS
            statusText.contains("canceled") || statusText.contains("cancelled") -> SManga.CANCELLED
            else -> SManga.ONGOING
        }

        val img = document.select(".summary_image img, .manga-poster img, .tab-summary img").first()
        manga.thumbnail_url = extractImageUrl(img)

        return manga
    }

    // =========================== Chapters ===========================

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        var chapterElements = document.select("li.wp-manga-chapter, .chapter-li")

        // Madara sites frequently load chapters via AJAX if not directly embedded
        if (chapterElements.isEmpty()) {
            val mangaUrl = response.request.url.toString().removeSuffix("/")
            val ajaxUrl = "$mangaUrl/ajax/chapters/"
            try {
                val ajaxRequest = POST(
                    ajaxUrl,
                    headers.newBuilder().set("Referer", mangaUrl).build(),
                    body = FormBody.Builder().build(),
                )
                val ajaxResponse = client.newCall(ajaxRequest).execute()
                if (ajaxResponse.isSuccessful) {
                    val ajaxDoc = ajaxResponse.asJsoup()
                    chapterElements = ajaxDoc.select("li.wp-manga-chapter, .chapter-li")
                }
            } catch (_: Exception) {
                // Fall back to original elements if ajax fails
            }
        }

        return chapterElements.mapNotNull { element ->
            val link = element.select("a").first() ?: return@mapNotNull null
            val chapter = SChapter.create()
            chapter.name = link.text().trim()
            chapter.setUrlWithoutDomain(link.attr("abs:href"))

            val dateText = element.select(".chapter-release-date, .c-new-tag, span.time").text().trim()
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
        val imgElements = document.select(".page-break img, .reading-content img, .entry-content img, .wp-manga-chapter-img")

        var index = 0
        imgElements.forEach { img ->
            val src = extractImageUrl(img)
            if (!src.isNullOrBlank() && !src.contains("logo", ignoreCase = true) && !src.contains("banner", ignoreCase = true)) {
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
            element.attr("abs:data-original"),
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
                        SimpleDateFormat("dd/MM/yyyy", Locale.US),
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
