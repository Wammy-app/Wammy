package eu.kanade.tachiyomi.source.builtin.webtoon

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
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Built-in native Webtoons source for LINE Webtoon (webtoons.com).
 * Includes automatic Referer spoofing for webtoon-phinf CDN images.
 */
@Suppress("DEPRECATION")
class WebtoonsSource(
    override val id: Long = 80010010L,
    override val lang: String = "en",
) : ParsedHttpSource() {

    override val name: String = "Webtoons"
    override val baseUrl: String = "https://www.webtoons.com"
    override val supportsLatest: Boolean = true

    override val client: OkHttpClient
        get() = network.client

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("Referer", "$baseUrl/")

    override fun imageRequest(page: Page): Request {
        val newHeaders = headersBuilder()
            .set("Referer", "$baseUrl/")
            .build()
        return GET(page.imageUrl!!, newHeaders)
    }

    // =========================== Popular ===========================

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/en/top", headers)
    }

    override fun popularMangaSelector(): String =
        "div.ranking_lst ul li, ul.webtoon_lst li, ul.card_lst li"

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        val link = element.select("a").first()
        manga.setUrlWithoutDomain(link?.attr("abs:href") ?: "")

        val title = element.select(".subj, .tit, p.subj").first()?.text()
            ?: link?.attr("title")
            ?: "Unknown"
        manga.title = title.trim()

        val img = element.select("img").first()
        manga.thumbnail_url = img?.attr("abs:src")?.ifEmpty { img.attr("abs:data-url") }
        return manga
    }

    override fun popularMangaNextPageSelector(): String? = null

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
        return MangasPage(mangas, false)
    }

    // =========================== Latest ===========================

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/en/dailySchedule", headers)
    }

    override fun latestUpdatesSelector(): String =
        "ul.s_daily_lst li, div.daily_lst ul li"

    override fun latestUpdatesFromElement(element: Element): SManga = popularMangaFromElement(element)

    override fun latestUpdatesNextPageSelector(): String? = null

    // =========================== Search ===========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        return GET("$baseUrl/en/search?keyword=$encoded", headers)
    }

    override fun searchMangaSelector(): String =
        "ul.card_lst li, div.card_wrap ul li, div.search_result ul li"

    override fun searchMangaFromElement(element: Element): SManga = popularMangaFromElement(element)

    override fun searchMangaNextPageSelector(): String? = null

    // =========================== Details ===========================

    override fun mangaDetailsParse(document: Document): SManga {
        val manga = SManga.create()

        val title = document.select("h1.subj, .subj, div.info h1").first()?.text()?.trim()
        if (!title.isNullOrEmpty()) {
            manga.title = title
        }

        manga.author = document.select("div.author_area, div.author, a.author").first()?.text()?.trim()
        manga.description = document.select("p.summary, div.summary, .desc").first()?.text()?.trim()
        manga.genre = document.select("h2.genre, .genre, a.genre").first()?.text()?.trim()
        manga.status = SManga.ONGOING

        val img = document.select("div.detail_header img, .pic img, div.poster img").first()
        manga.thumbnail_url = img?.attr("abs:src")?.ifEmpty { img.attr("abs:data-url") }

        return manga
    }

    // =========================== Chapters ===========================

    override fun chapterListRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url, headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val elements = document.select("div.detail_body ul li[id^=episode_], ul._episode_list li")
        val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)

        return elements.mapNotNull { element ->
            val link = element.select("a").first() ?: return@mapNotNull null
            val chapter = SChapter.create()
            chapter.name = element.select(".sub_title .subj, span.subj, .name").first()?.text()?.trim()
                ?: link.text().trim()
            chapter.setUrlWithoutDomain(link.attr("abs:href"))

            val dateStr = element.select(".date, span.date").text().trim()
            if (dateStr.isNotBlank()) {
                try {
                    chapter.date_upload = dateFormat.parse(dateStr)?.time ?: 0L
                } catch (_: Exception) {
                    chapter.date_upload = 0L
                }
            }
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
        val imgElements = document.select("div._imageList img, div.viewer_img img, div#_viewerBox img")

        var index = 0
        imgElements.forEach { img ->
            val src = img.attr("abs:data-url").ifEmpty { img.attr("abs:src") }
            if (src.isNotBlank() && !src.contains("bg_trans", ignoreCase = true)) {
                pages.add(Page(index++, "", src))
            }
        }
        return pages
    }

    override fun imageUrlParse(document: Document): String =
        throw UnsupportedOperationException("Not used with direct Page image URLs")
}
