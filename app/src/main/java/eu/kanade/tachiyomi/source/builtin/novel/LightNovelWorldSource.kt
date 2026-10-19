package eu.kanade.tachiyomi.source.builtin.novel

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder

/**
 * Built-in native source for LightNovelWorld (lightnovelworld.com).
 * High-speed autonomous chapter extraction and structured text parsing.
 */
class LightNovelWorldSource(
    override val id: Long = 80030012L,
    override val baseUrl: String = "https://www.lightnovelworld.com",
) : BuiltinNovelSource() {

    override val name: String = "LightNovelWorld"
    override val supportsLatest: Boolean = true

    // =========================== Popular ===========================

    override fun popularMangaRequest(page: Int): Request {
        val url = if (page <= 1) "$baseUrl/browse/genre-all-25060123/order-popular/status-all" else "$baseUrl/browse/genre-all-25060123/order-popular/status-all?page=$page"
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val doc = response.asJsoup()
        return parseNovelsFromDoc(doc)
    }

    // =========================== Latest ===========================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page <= 1) "$baseUrl/browse/genre-all-25060123/order-updated/status-all" else "$baseUrl/browse/genre-all-25060123/order-updated/status-all?page=$page"
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // =========================== Search ===========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = if (page <= 1) "$baseUrl/search?keyword=$encoded" else "$baseUrl/search?keyword=$encoded&page=$page"
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val doc = response.asJsoup()
        return parseNovelsFromDoc(doc)
    }

    // =========================== Helper Parsing ===========================

    private fun parseNovelsFromDoc(doc: Document): MangasPage {
        val novels = doc.select("li.novel-item, .novel-list .novel-item").mapNotNull { el ->
            val titleEl = el.selectFirst("h4.novel-title a, .novel-title a, a[title]") ?: return@mapNotNull null
            val href = titleEl.attr("href").trim()
            if (href.isBlank()) return@mapNotNull null

            val cleanUrl = if (href.startsWith("http")) {
                val uri = URI(href)
                uri.path + if (uri.query != null) "?${uri.query}" else ""
            } else {
                href
            }

            val title = titleEl.attr("title").ifBlank { titleEl.text() }.trim()
            val cover = el.selectFirst("img.cover, .novel-cover img, img")?.let { img ->
                img.attr("data-src").ifBlank { img.attr("src") }
            }?.let { resolveUrl(it) }

            SManga.create().apply {
                this.url = cleanUrl
                this.title = title
                this.thumbnail_url = cover
            }
        }.distinctBy { it.url }

        val hasNext = doc.select(".pagination li.active + li:not(.disabled) a, .pagination a:contains(>), a:contains(Next)").isNotEmpty()
        return MangasPage(novels, hasNext)
    }

    // =========================== Manga Details ===========================

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = response.asJsoup()
        return SManga.create().apply {
            title = doc.selectFirst(".novel-title, h1.novel-title, h1")?.text()?.trim().orEmpty()
            author = doc.select(".author .name, .novel-info .author a, .author a").eachText().joinToString()
            description = doc.selectFirst(".summary .content, .novel-summary, .summary")?.text()?.trim()
            genre = doc.select(".categories a, .genre a, .genres a").eachText().joinToString()
            status = when {
                doc.select(".header-stats span:contains(Completed), .novel-info span:contains(Completed)").isNotEmpty() -> SManga.COMPLETED
                else -> SManga.ONGOING
            }
            thumbnail_url = doc.selectFirst(".novel-cover img, .cover img, img.cover")?.let { img ->
                img.attr("data-src").ifBlank { img.attr("src") }
            }?.let { resolveUrl(it) }
        }
    }

    // =========================== Chapters ===========================

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = response.asJsoup()
        val chapterElements = doc.select(".chapter-list li a, ul.chapter-list a, .list-chapter li a")

        return chapterElements.mapIndexedNotNull { index, a ->
            val href = a.attr("href").trim()
            if (href.isBlank()) return@mapIndexedNotNull null
            val cleanUrl = if (href.startsWith("http")) {
                val uri = URI(href)
                uri.path + if (uri.query != null) "?${uri.query}" else ""
            } else {
                href
            }

            val chapterName = a.attr("title").ifBlank { a.text() }.trim()
            SChapter.create().apply {
                this.url = cleanUrl
                this.name = chapterName
                this.chapter_number = (index + 1).toFloat()
            }
        }.reversed()
    }

    // =========================== Chapter Reading Text ===========================

    override suspend fun fetchPageText(page: Page): String {
        val url = resolveUrl(page.url)
        val response = client.newCall(GET(url, headers)).awaitSuccess()
        val doc = response.asJsoup()

        val content = doc.selectFirst("#chapter-container, .chapter-content, #novel-content, #chr-content")
            ?: throw Exception("Could not find chapter content on $url")

        return cleanHtmlContent(content)
    }
}
