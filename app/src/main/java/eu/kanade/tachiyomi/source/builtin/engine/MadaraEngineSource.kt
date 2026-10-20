package eu.kanade.tachiyomi.source.builtin.engine

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.parseHumanCount
import eu.kanade.tachiyomi.source.model.rating
import eu.kanade.tachiyomi.source.model.views
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
    val mangaSubpath: String = when {
        baseUrl.contains("toonily", ignoreCase = true) -> "webtoons"
        baseUrl.contains("flame", ignoreCase = true) || baseUrl.contains("asura", ignoreCase = true) -> "series"
        baseUrl.contains("novelmic", ignoreCase = true) -> "novel"
        else -> "manga"
    },
) : ParsedHttpSource() {

    override val supportsLatest: Boolean = true

    override val client: OkHttpClient by lazy {
        val builder = network.client.newBuilder()
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))

        if (baseUrl.contains("yaksha", ignoreCase = true)) {
            builder.addInterceptor { chain ->
                val response = chain.proceed(chain.request())
                if (response.code != 403) return@addInterceptor response
                response.close()

                try {
                    Thread.sleep(1500L)
                    val challengeResponse = chain.proceed(GET("$baseUrl/hcdn-cgi/jschallenge", headers))
                    val bodyStr = challengeResponse.body.string()
                    val token = Regex("""name="token"\s+value="([^"]+)"""").find(bodyStr)?.groups?.get(1)?.value
                    if (!token.isNullOrEmpty() && token != "nil") {
                        val md = java.security.MessageDigest.getInstance("SHA-256")
                        val hash = md.digest(token.toByteArray()).fold("") { str, it -> str + "%02x".format(it) }
                        val form = FormBody.Builder().add("challenge", hash).build()
                        val validate = chain.proceed(POST("$baseUrl/hcdn-cgi/jschallenge-validate", headers, form))
                        validate.close()
                    }
                } catch (_: Exception) {}

                chain.proceed(chain.request())
            }
        }

        builder.build()
    }

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("Referer", "$baseUrl/")

    // =========================== Popular ===========================

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/$mangaSubpath/page/$page/?m_orderby=views", headers)
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

        val author = element.select(".post-author a, .author a, .mg_author a, .post-on a").text().trim()
        if (author.isNotBlank()) {
            manga.author = author
        }
        val scoreText = element.select(".post-total-rating .score, .score, [itemprop='ratingValue']").text().trim()
        val rawScore = scoreText.toDoubleOrNull()
        if (rawScore != null && rawScore > 0.0) {
            manga.rating = if (rawScore <= 5.0) rawScore * 2.0 else rawScore
        }

        val viewsText = element.select(".post-views, .views, .view, .post-views-count, .item-views, .font-meta:contains(views)").text().trim()
        if (viewsText.isNotBlank()) {
            parseHumanCount(viewsText)?.let { manga.views = it }
        }

        return manga
    }

    override fun popularMangaNextPageSelector(): String =
        ".nav-previous, .next, a.nextpostslink, .wp-pagenavi a.next"

    // =========================== Latest ===========================

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/$mangaSubpath/page/$page/?m_orderby=latest", headers)
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

        val score = document.select(".post-total-rating .score, .score, [itemprop='ratingValue']").text().trim().toDoubleOrNull()
        if (score != null && score > 0.0) {
            manga.rating = if (score <= 5.0) score * 2.0 else score
        }
        val votes = document.select(".vote-count, .post-total-rating .total-votes, [itemprop='ratingCount']").text().trim().replace(Regex("[^0-9]"), "").toLongOrNull()
        if (votes != null && votes > 0) {
            manga.views = votes
        }

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
