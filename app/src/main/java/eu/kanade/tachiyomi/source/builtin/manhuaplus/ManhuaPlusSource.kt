package eu.kanade.tachiyomi.source.builtin.manhuaplus

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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * Built-in native autonomous ManhuaPlus source (manhuaplus.org).
 * Uses high-speed HTML parsing and direct AJAX CDN image decoding.
 */
@Suppress("DEPRECATION")
class ManhuaPlusSource(
    override val id: Long = 80010013L,
    override val baseUrl: String = "https://manhuaplus.org",
    override val lang: String = "en",
) : ParsedHttpSource() {

    override val name: String = "ManhuaPlus"
    override val supportsLatest: Boolean = true

    override val client: OkHttpClient
        get() = network.client

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

    override fun imageRequest(page: Page): Request {
        val imgHeaders = headersBuilder().apply {
            add("Accept", "image/avif,image/webp,*/*")
            removeAll("Referer")
        }.build()
        return GET(page.imageUrl!!, imgHeaders)
    }

    // =========================== Popular ===========================

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/ranking/week/$page", headers)
    }

    override fun popularMangaSelector(): String =
        "div.grid > div:has(.b-img), div.grid > div:has(a[href*='/manga/']), article[role=article]"

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        val link = element.selectFirst("a.pt-140p, .item-thumbnail a, h3.post-title a, a[href*='/manga/']")
            ?: element.selectFirst("a")
            ?: throw Exception("No manga link found")

        manga.setUrlWithoutDomain(link.attr("abs:href"))

        val title = element.selectFirst("h3.post-title a, a.clamp, .text-center a")?.text()?.trim()
            ?: element.selectFirst("img")?.attr("alt")?.trim()
            ?: link.text().trim()
        manga.title = title

        val img = element.selectFirst("img")
        val thumb = img?.let {
            it.attr("abs:data-src").ifEmpty { it.attr("abs:src") }
        }
        manga.thumbnail_url = thumb?.takeUnless { it.contains("loading", ignoreCase = true) }

        val scoreText = element.select(".rating .num, .score, [itemprop=ratingValue]").text().trim()
        val numMatch = Regex("""\b(\d+(?:\.\d+)?)\b""").find(scoreText)?.value
        val rawScore = numMatch?.toDoubleOrNull()
        if (rawScore != null && rawScore > 0.0) {
            manga.rating = if (rawScore <= 5.0) rawScore * 2.0 else rawScore
        }
        val viewsText = element.select(".post-views, .views, .view, .span-spc:contains(Views)").text().trim()
        if (viewsText.isNotBlank()) {
            parseHumanCount(viewsText)?.let { manga.views = it }
        }

        return manga
    }

    override fun popularMangaNextPageSelector(): String? = ".blog-pager > span.pagecurrent + span"

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val elements = document.select(popularMangaSelector())
        val mangas = elements.mapNotNull {
            try {
                popularMangaFromElement(it)
            } catch (_: Exception) {
                null
            }
        }.distinctBy { it.url }

        val hasNext = popularMangaNextPageSelector()?.let { selector ->
            document.selectFirst(selector) != null
        } ?: mangas.isNotEmpty()

        return MangasPage(mangas, hasNext)
    }

    // =========================== Latest ===========================

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/all-manga/$page/?sort=last_update&status=0", headers)
    }

    override fun latestUpdatesSelector(): String = popularMangaSelector()

    override fun latestUpdatesFromElement(element: Element): SManga = popularMangaFromElement(element)

    override fun latestUpdatesNextPageSelector(): String? = popularMangaNextPageSelector()

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // =========================== Search ===========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        return GET("$baseUrl/search/$page/?keyword=$encoded", headers)
    }

    override fun searchMangaSelector(): String =
        "article[role=article], div.grid > div:has(.b-img), div.grid > div:has(a[href*='/manga/'])"

    override fun searchMangaFromElement(element: Element): SManga = popularMangaFromElement(element)

    override fun searchMangaNextPageSelector(): String? = popularMangaNextPageSelector()

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // =========================== Details ===========================

    override fun mangaDetailsParse(document: Document): SManga {
        val manga = SManga.create()

        val title = document.selectFirst("h1.fs-20, .a2 header h1, h1")?.text()?.trim()
        if (!title.isNullOrEmpty()) {
            manga.title = title
        }

        manga.description = document.selectFirst("div#syn-target")?.text()?.trim()
            ?: document.selectFirst("p.summary, div.summary")?.text()?.trim()

        val img = document.selectFirst(".a1 figure img, .b-img img, img.lazy")
        manga.thumbnail_url = img?.let {
            it.attr("abs:data-src").ifEmpty { it.attr("abs:src") }
        }

        manga.author = document.selectFirst("div.y6x11p i.fa-user + span.dt, div.author")?.text()?.trim()
            ?.takeUnless { it.equals("updating", ignoreCase = true) }

        manga.genre = document.select(".a2 div > a[rel='tag'].label, .genre a").joinToString { it.text().trim() }

        val statusText = document.selectFirst("div.y6x11p i.fa-rss + span.dt, .status")?.text()?.lowercase() ?: ""
        manga.status = when {
            statusText.contains("ongoing") || statusText.contains("progress") -> SManga.ONGOING
            statusText.contains("completed") || statusText.contains("finished") -> SManga.COMPLETED
            statusText.contains("hiatus") || statusText.contains("on-hold") -> SManga.ON_HIATUS
            statusText.contains("canceled") || statusText.contains("cancelled") -> SManga.CANCELLED
            else -> SManga.ONGOING
        }

        val scoreText = document.selectFirst("#ratingScore, .rating .num, .rating-prc [itemprop=ratingValue], [itemprop=ratingValue]")?.text()?.trim()
        val rawScore = scoreText?.toDoubleOrNull()
        if (rawScore != null && rawScore > 0.0) {
            manga.rating = if (rawScore <= 5.0) rawScore * 2.0 else rawScore
        }
        val viewsText = document.selectFirst(".post-views, .views, .view, .span-spc:contains(Views)")?.text()?.trim()
        if (!viewsText.isNullOrBlank()) {
            parseHumanCount(viewsText)?.let { manga.views = it }
        }

        return manga
    }

    // =========================== Chapters ===========================

    override fun chapterListRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url, headers)
    }

    override fun chapterListSelector(): String = "ul > li.chapter, li.chapter"

    override fun chapterFromElement(element: Element): SChapter {
        val chapter = SChapter.create()
        val link = element.selectFirst("a") ?: throw Exception("Chapter link not found")
        chapter.setUrlWithoutDomain(link.attr("abs:href"))

        val name = link.text().trim().ifEmpty {
            element.attr("data").trim()
        }
        chapter.name = name

        val timeAttr = element.selectFirst("time[datetime]")?.attr("datetime")
        if (!timeAttr.isNullOrBlank()) {
            val epoch = timeAttr.toLongOrNull()
            if (epoch != null) {
                chapter.date_upload = if (epoch < 10000000000L) epoch * 1000L else epoch
            }
        }
        return chapter
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val elements = document.select(chapterListSelector())
        return elements.mapNotNull {
            try {
                chapterFromElement(it)
            } catch (_: Exception) {
                null
            }
        }
    }

    // =========================== Pages ===========================

    override fun pageListParse(document: Document): List<Page> {
        val html = document.html()
        val chapterIdMatch = Regex("""const\s+CHAPTER_ID\s*=\s*(\d+)""").find(html)
            ?: Regex("""chapter_id\s*:\s*(\d+)""").find(html)
            ?: throw Exception("Failed to locate CHAPTER_ID for ManhuaPlus")

        val chapterId = chapterIdMatch.groupValues[1]

        val pageHeaders = headersBuilder().apply {
            add("Accept", "application/json, text/javascript, */*; q=0.01")
            add("X-Requested-With", "XMLHttpRequest")
            add("Referer", document.location().ifEmpty { baseUrl })
        }.build()

        val ajaxResponse = client.newCall(
            GET("$baseUrl/ajax/image/list/chap/$chapterId", pageHeaders),
        ).execute()

        val bodyString = ajaxResponse.body.string()
        val json = JSONObject(bodyString)

        if (!json.optBoolean("status", false)) {
            val msg = json.optString("msg", "Unknown error fetching chapter images")
            throw Exception(msg)
        }

        val renderedHtml = json.optString("html", "")
        val fragment = Jsoup.parseBodyFragment(renderedHtml, baseUrl)

        val separatorElements = fragment.select("div.separator[data-index], div.separator")
        val pages = mutableListOf<Pair<Int, String>>()

        separatorElements.forEachIndexed { defaultIdx, element ->
            val idx = element.attr("data-index").toIntOrNull() ?: defaultIdx
            val link = element.selectFirst("a")?.attr("abs:href")
                ?: element.selectFirst("img")?.attr("abs:src")
                ?: ""

            val lower = link.lowercase()
            if (link.isNotBlank() && !lower.endsWith(".svg") && !lower.contains("loading")) {
                pages.add(idx to link)
            }
        }

        return pages.sortedBy { it.first }.mapIndexed { i, pair ->
            Page(i, "", pair.second)
        }
    }

    override fun imageUrlParse(document: Document): String =
        throw UnsupportedOperationException("Direct image URLs used in pageListParse")
}
