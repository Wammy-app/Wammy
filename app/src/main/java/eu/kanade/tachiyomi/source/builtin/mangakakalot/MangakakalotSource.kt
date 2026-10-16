package eu.kanade.tachiyomi.source.builtin.mangakakalot

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
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
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Built-in native Mangakakalot source (mangakakalot.gg / mangakakalove.com).
 * Scrapes HTML listings and image CDN streams natively.
 */
@Suppress("DEPRECATION")
class MangakakalotSource(
    override val id: Long = 80010002L,
    override val lang: String = "en",
) : ParsedHttpSource() {

    override val name: String = "Mangakakalot"
    override val baseUrl: String = "https://www.mangakakalot.gg"
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
        ":is(div.truyen-list > div.list-truyen-item-wrap, div.comic-list > .list-comic-item-wrap):has(a[data-id]), .story_item, .list-truyen-item-wrap"

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        val link = element.select("h3 a, a.story_name, .story_item_right h3 a, a").first()
        val img = element.select("img").first()

        manga.title = link?.text()?.trim() ?: img?.attr("alt")?.trim() ?: "Unknown"
        manga.setUrlWithoutDomain(link?.attr("abs:href") ?: "")
        manga.thumbnail_url = img?.attr("abs:src")?.ifBlank { img.attr("abs:data-src") }
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
        ".panel_story_list .story_item, div.list-truyen-item-wrap, div.list-comic-item-wrap, :is(div.truyen-list > div.list-truyen-item-wrap, div.comic-list > .list-comic-item-wrap):has(a[data-id])"

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

        manga.setUrlWithoutDomain(document.location())
        manga.initialized = true
        return manga
    }

    // =========================== Chapters ===========================

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
