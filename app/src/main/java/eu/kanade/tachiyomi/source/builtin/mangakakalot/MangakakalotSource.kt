package eu.kanade.tachiyomi.source.builtin.mangakakalot

import eu.kanade.tachiyomi.network.GET
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
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Built-in native Mangakakalot source (mangakakalot.gg / mangakakalove.com).
 * Scrapes HTML listings and image CDN streams natively.
 */
@Suppress("DEPRECATION")
class MangakakalotSource(
    override val id: Long = 80010002L,
    override val baseUrl: String = "https://www.mangakakalove.com",
    override val lang: String = "en",
) : ParsedHttpSource() {

    override val name: String = "Mangakakalot"
    override val supportsLatest: Boolean = true

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .build()
    }

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("Referer", "$baseUrl/")

    override fun imageRequest(page: Page): Request {
        val imgHeaders = headersBuilder()
            .set("Referer", "$baseUrl/")
            .set("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            .build()
        return GET(page.imageUrl!!, imgHeaders)
    }

    // =========================== Popular ===========================

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/manga-list/hot-manga?page=$page", headers)
    }

    override fun popularMangaSelector(): String =
        ".list-comic-item-wrap, .popular-carousel-wrap .item, :is(div.truyen-list > div.list-truyen-item-wrap, div.comic-list > .list-comic-item-wrap):has(a[data-id]), .story_item, .list-truyen-item-wrap"

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        val link = element.select("h3 a, a.story_name, .story_item_right h3 a, a.list-story-item, a").first()
        val img = element.select("img").first()

        val title = link?.text()?.trim()?.ifBlank { null }
            ?: img?.attr("alt")?.trim()?.ifBlank { null }
            ?: "Unknown"
        manga.title = title
        manga.setUrlWithoutDomain(link?.attr("abs:href") ?: "")
        manga.thumbnail_url = img?.attr("abs:src")?.ifBlank { img.attr("abs:data-src") }

        val viewsText = element.select(".view, .views, .story_item_right span:contains(View), span:contains(View)").text().trim()
        if (viewsText.isNotBlank()) {
            parseHumanCount(viewsText.substringAfter(":"))?.let { manga.views = it }
        }
        val scoreText = element.select(".score, .rate, [itemprop=ratingValue]").text().trim()
        val rawScore = scoreText.toDoubleOrNull()
        if (rawScore != null && rawScore > 0.0) {
            manga.rating = if (rawScore <= 5.0) rawScore * 2.0 else rawScore
        }

        return manga
    }

    override fun popularMangaNextPageSelector(): String? =
        "div.group_page, div.group-page a:not([href]) + a:not(:contains(Last)), .page_blue.page_current + a"

    // =========================== Latest ===========================

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/manga-list/latest-manga?page=$page", headers)
    }

    override fun latestUpdatesSelector(): String = popularMangaSelector()

    override fun latestUpdatesFromElement(element: Element): SManga = popularMangaFromElement(element)

    override fun latestUpdatesNextPageSelector(): String? = popularMangaNextPageSelector()

    // =========================== Search ===========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val normalizedQuery = query.trim()
            .lowercase(Locale.ENGLISH)
            .replace("[^a-z0-9\\s-]".toRegex(), "")
            .trim()
            .replace("[\\s-]+".toRegex(), "_")
        return GET("$baseUrl/search/story/$normalizedQuery?page=$page", headers)
    }

    override fun searchMangaSelector(): String =
        ".panel_story_list .story_item, .list-comic-item-wrap, div.list-truyen-item-wrap, :is(div.truyen-list > div.list-truyen-item-wrap, div.comic-list > .list-comic-item-wrap):has(a[data-id])"

    override fun searchMangaFromElement(element: Element): SManga = popularMangaFromElement(element)

    override fun searchMangaNextPageSelector(): String? = popularMangaNextPageSelector()

    // =========================== Details ===========================

    override fun mangaDetailsParse(document: Document): SManga {
        val manga = SManga.create()
        val titleEl = document.select("h1, .manga-info-top h1, .story-info-right h1").first()
        manga.title = titleEl?.text()?.trim() ?: ""

        val authorEl = document.select("li:contains(Author) a, .manga-info-top li:contains(Author) a, .table-value a[href*='/author/']").first()
        manga.author = authorEl?.text()?.trim()

        val genres = document.select("li:contains(Genres) a, .manga-info-top li:contains(Genres) a").map { it.text().trim() }
        if (genres.isNotEmpty()) {
            manga.genre = genres.joinToString(", ")
        }

        val statusText = document.select("li:contains(Status), .manga-info-top li:contains(Status)").text().lowercase()
        manga.status = when {
            statusText.contains("ongoing") -> SManga.ONGOING
            statusText.contains("completed") -> SManga.COMPLETED
            statusText.contains("hiatus") -> SManga.ON_HIATUS
            statusText.contains("drop") -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }

        val descEl = document.select("#noidungm, #panel-story-info-description, .story-info-right-extent").first()
        manga.description = descEl?.text()?.removePrefix("Description :")?.removePrefix("Description:")?.trim()

        val imgEl = document.select(".manga-info-pic img, .story-info-left img").first()
        manga.thumbnail_url = imgEl?.attr("abs:src")?.ifBlank { imgEl.attr("abs:data-src") }

        val viewEl = document.selectFirst("li:contains(View), .manga-info-text li:contains(View), .story-info-right-extent p:contains(View)")
        if (viewEl != null) {
            val rawViews = viewEl.text().substringAfter(":").trim()
            parseHumanCount(rawViews)?.let { manga.views = it }
        }
        val rateEl = document.selectFirst("[itemprop=ratingValue], em[property='v:average'], #rate_row_cmd, .rate_num")
        val rawRate = rateEl?.text()?.trim()?.toDoubleOrNull()
        if (rawRate != null && rawRate > 0.0) {
            manga.rating = if (rawRate <= 5.0) rawRate * 2.0 else rawRate
        }

        manga.setUrlWithoutDomain(document.location())
        manga.initialized = true
        return manga
    }

    // =========================== Chapters ===========================

    override fun chapterListParse(response: Response): List<SChapter> {
        val url = response.request.url.toString()
        val slug = url.substringAfter("/manga/").substringBefore("/").substringBefore("?")
        if (slug.isNotBlank()) {
            try {
                val apiUrl = "$baseUrl/api/manga/$slug/chapters?limit=1000"
                val apiRequest = GET(apiUrl, headers)
                val apiResponse = client.newCall(apiRequest).execute()
                if (apiResponse.isSuccessful) {
                    val jsonStr = apiResponse.body.string()
                    val json = org.json.JSONObject(jsonStr)
                    if (json.optBoolean("success", false)) {
                        val data = json.optJSONObject("data")
                        val chaptersJson = data?.optJSONArray("chapters")
                        if (chaptersJson != null && chaptersJson.length() > 0) {
                            val list = mutableListOf<SChapter>()
                            val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.US)
                            for (i in 0 until chaptersJson.length()) {
                                val chObj = chaptersJson.getJSONObject(i)
                                val ch = SChapter.create().apply {
                                    name = chObj.optString("chapter_name", "Chapter")
                                    val chSlug = chObj.optString("chapter_slug", "")
                                    setUrlWithoutDomain("/manga/$slug/$chSlug")
                                    chapter_number = chObj.optDouble("chapter_num", -1.0).toFloat()
                                    val updated = chObj.optString("updated_at", "")
                                    date_upload = try {
                                        dateFormat.parse(updated)?.time ?: 0L
                                    } catch (_: Exception) {
                                        0L
                                    }
                                }
                                list.add(ch)
                            }
                            return list
                        }
                    }
                }
            } catch (_: Exception) {
                // Fallback to HTML parsing below
            }
        }
        return super.chapterListParse(response)
    }

    override fun chapterListSelector(): String =
        ".row-content-chapter li, .chapter-list div.row"

    override fun chapterFromElement(element: Element): SChapter {
        val chapter = SChapter.create()
        val link = element.select("a").first()
        chapter.setUrlWithoutDomain(link?.attr("abs:href") ?: "")
        chapter.name = link?.text()?.trim() ?: "Chapter"

        val timeText = element.select(".chapter-time, span:last-child").first()?.text()
        chapter.date_upload = parseChapterDate(timeText)
        return chapter
    }

    private fun parseChapterDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0L
        val clean = dateStr.trim()
        val formats = listOf(
            SimpleDateFormat("MMM-dd-yyyy", Locale.US),
            SimpleDateFormat("MMM dd, yyyy", Locale.US),
            SimpleDateFormat("MMM dd,yyyy", Locale.US),
            SimpleDateFormat("yyyy-MM-dd", Locale.US),
        )
        for (format in formats) {
            try {
                val parsed = format.parse(clean)
                if (parsed != null) return parsed.time
            } catch (_: Exception) { }
        }
        return 0L
    }

    // =========================== Pages ===========================

    override fun pageListParse(document: Document): List<Page> {
        val pages = mutableListOf<Page>()
        val imgElements = document.select(".container-chapter-reader img, .vung-doc img, #vungdoc img, .reader-area img")

        imgElements.forEachIndexed { index, img ->
            val src = img.attr("abs:src").ifBlank { img.attr("abs:data-src") }
            if (src.isNotBlank()) {
                pages.add(Page(index, imageUrl = src))
            }
        }
        return pages
    }

    override fun imageUrlParse(document: Document): String = ""
}
