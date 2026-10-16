package eu.kanade.tachiyomi.source.builtin.weebcentral

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
import java.net.URLEncoder
import java.time.Instant

/**
 * Built-in native WeebCentral source (weebcentral.com).
 * Scrapes HTML listings and full-chapter endpoints natively.
 */
@Suppress("DEPRECATION")
class WeebCentralSource(
    override val id: Long = 80010005L,
    override val lang: String = "en",
) : ParsedHttpSource() {

    override val name: String = "WeebCentral"
    override val baseUrl: String = "https://weebcentral.com"
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
            .set("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            .build()
        return GET(page.imageUrl!!, imgHeaders)
    }

    // =========================== Popular ===========================

    override fun popularMangaRequest(page: Int): Request {
        val offset = (page - 1) * FETCH_LIMIT
        return GET("$baseUrl/search/data?sort=Popularity&order=Descending&limit=$FETCH_LIMIT&offset=$offset&display_mode=Full%20Display", headers)
    }

    override fun popularMangaSelector(): String = "article > section > a"

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        manga.setUrlWithoutDomain(element.attr("abs:href"))
        manga.title = element.select("div:not([class]):last-child").first()?.text()?.trim() ?: "Unknown"

        val srcset = element.select("source").first()?.attr("srcset") ?: ""
        val src = element.select("img").first()?.attr("abs:src") ?: ""
        manga.thumbnail_url = if (srcset.isNotBlank()) srcset.replace("small", "normal") else src
        return manga
    }

    override fun popularMangaNextPageSelector(): String? = "button"

    // =========================== Latest ===========================

    override fun latestUpdatesRequest(page: Int): Request {
        val offset = (page - 1) * FETCH_LIMIT
        return GET("$baseUrl/search/data?sort=Latest%20Updates&order=Descending&limit=$FETCH_LIMIT&offset=$offset&display_mode=Full%20Display", headers)
    }

    override fun latestUpdatesSelector(): String = popularMangaSelector()

    override fun latestUpdatesFromElement(element: Element): SManga = popularMangaFromElement(element)

    override fun latestUpdatesNextPageSelector(): String? = popularMangaNextPageSelector()

    // =========================== Search ===========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val offset = (page - 1) * FETCH_LIMIT
        val cleanQuery = query.replace(Regex("[!#:(),-]"), " ").trim()
        val encodedQuery = URLEncoder.encode(cleanQuery, "UTF-8")
        return GET("$baseUrl/search/data?text=$encodedQuery&limit=$FETCH_LIMIT&offset=$offset&display_mode=Full%20Display", headers)
    }

    override fun searchMangaSelector(): String = popularMangaSelector()

    override fun searchMangaFromElement(element: Element): SManga = popularMangaFromElement(element)

    override fun searchMangaNextPageSelector(): String? = popularMangaNextPageSelector()

    // =========================== Details ===========================

    override fun mangaDetailsParse(document: Document): SManga {
        val manga = SManga.create()
        val sections = document.select("section[x-data] > section")

        if (sections.isNotEmpty()) {
            val s0 = sections[0]
            val srcset = s0.select("source").first()?.attr("srcset") ?: ""
            val src = s0.select("img").first()?.attr("abs:src") ?: ""
            manga.thumbnail_url = if (srcset.isNotBlank()) srcset.replace("small", "normal") else src

            manga.author = s0.select("ul > li:has(strong:contains(Author)) > span > a").joinToString(", ") { it.text().trim() }
            manga.genre = s0.select("ul > li:has(strong:contains(Tag),strong:contains(Type)) a").joinToString(", ") { it.text().trim() }

            val statusText = s0.selectFirst("ul > li:has(strong:contains(Status)) > a")?.text()?.lowercase() ?: ""
            manga.status = when (statusText) {
                "ongoing" -> SManga.ONGOING
                "complete", "completed" -> SManga.COMPLETED
                "hiatus" -> SManga.ON_HIATUS
                "canceled", "cancelled" -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }
        }

        if (sections.size > 1) {
            val s1 = sections[1]
            manga.title = s1.selectFirst("h1")?.text()?.trim() ?: ""
            manga.description = s1.selectFirst("li:has(strong:contains(Description)) > p")?.text()?.trim()
        }

        manga.setUrlWithoutDomain(document.location())
        manga.initialized = true
        return manga
    }

    // =========================== Chapters ===========================

    override fun chapterListRequest(manga: SManga): Request {
        val cleanUrl = manga.url.removePrefix("/")
        val segments = cleanUrl.split("/")
        val seriesId = if (segments.size > 1) segments[1] else cleanUrl.substringAfterLast("/")
        return GET("$baseUrl/series/$seriesId/full-chapter-list", headers)
    }

    override fun chapterListSelector(): String = "div[x-data] > a"

    override fun chapterFromElement(element: Element): SChapter {
        val chapter = SChapter.create()
        chapter.setUrlWithoutDomain(element.attr("abs:href"))

        val title = element.selectFirst("span.flex > span")?.text() ?: element.text()
        chapter.name = title.trim()

        val timeEl = element.selectFirst("time[datetime]")
        if (timeEl != null) {
            val dtStr = timeEl.attr("datetime")
            chapter.date_upload = runCatching {
                Instant.parse(dtStr).toEpochMilli()
            }.getOrDefault(0L)
        }

        val isOfficial = element.select("img").any { it.attr("src").contains("official", ignoreCase = true) }
        chapter.scanlator = if (isOfficial) "Official" else null
        return chapter
    }

    // =========================== Pages ===========================

    override fun pageListRequest(chapter: SChapter): Request {
        val url = "$baseUrl${chapter.url}/images?is_prev=False&reading_style=long_strip"
        return GET(url, headers)
    }

    override fun pageListParse(document: Document): List<Page> {
        val pages = mutableListOf<Page>()
        val imgElements = document.select("section[x-data~=scroll] > img, #readerarea img, .reader img")

        imgElements.forEachIndexed { index, img ->
            val src = img.attr("abs:src")
            if (src.isNotBlank()) {
                pages.add(Page(index, imageUrl = src))
            }
        }
        return pages
    }

    override fun imageUrlParse(document: Document): String = ""

    companion object {
        private const val FETCH_LIMIT = 32
    }
}
