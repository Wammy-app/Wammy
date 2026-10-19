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
 * Built-in native source for Novel Bin (novelbin.com).
 * High-speed autonomous chapter extraction and clean paragraph text rendering.
 */
class NovelBinSource(
    override val id: Long = 80030009L,
    override val baseUrl: String = "https://novelbin.com",
) : BuiltinNovelSource() {

    override val name: String = "Novel Bin"
    override val supportsLatest: Boolean = true

    // =========================== Popular ===========================

    override fun popularMangaRequest(page: Int): Request {
        val url = if (page <= 1) "$baseUrl/sort/novelbin-popular" else "$baseUrl/sort/novelbin-popular?page=$page"
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val doc = response.asJsoup()
        return parseNovelsFromDoc(doc)
    }

    // =========================== Latest ===========================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page <= 1) "$baseUrl/sort/novelbin-daily-update" else "$baseUrl/sort/novelbin-daily-update?page=$page"
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // =========================== Search ===========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = if (page <= 1) "$baseUrl/search?keyword=$encoded" else "$baseUrl/search?keyword=$encoded&page=$page"
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // =========================== Helper Parsing ===========================

    private fun parseNovelsFromDoc(doc: Document): MangasPage {
        val novels = doc.select(".list-novel .row, div.col-novel, div[itemscope]").mapNotNull { el ->
            val titleEl = el.selectFirst("h3.novel-title a, .col-title h3 a, h3 a") ?: return@mapNotNull null
            val href = titleEl.attr("href").trim()
            if (href.isBlank()) return@mapNotNull null

            val cleanUrl = if (href.startsWith("http")) {
                val uri = URI(href)
                uri.path + if (uri.query != null) "?${uri.query}" else ""
            } else {
                href
            }

            val title = titleEl.attr("title").ifBlank { titleEl.text() }.trim()
            val cover = el.selectFirst("img.cover, .col-novel img, img")?.let { img ->
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
            title = doc.selectFirst("h3.title, .title")?.text()?.trim().orEmpty()
            author = doc.select("ul.info li:has(h3:contains(Author)) a, .info-meta a[href*='/novelbin-author/'], .author a").eachText().joinToString()
            description = doc.selectFirst("#novel-description-content, .desc-text")?.text()?.trim()
            genre = doc.select("ul.info li:has(h3:contains(Genre)) a, .info-meta a[href*='/novelbin-genres/']").eachText().joinToString()
            status = when {
                doc.select("ul.info li:has(h3:contains(Status)) a:contains(Completed)").isNotEmpty() -> SManga.COMPLETED
                else -> SManga.ONGOING
            }
            thumbnail_url = doc.selectFirst(".books .book img, .desc .book img, img.cover")?.let { img ->
                img.attr("data-src").ifBlank { img.attr("src") }
            }?.let { resolveUrl(it) }
        }
    }

    // =========================== Chapters ===========================

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = response.asJsoup()
        var chapterElements = doc.select("#list-chapter li a, .list-chapter li a, ul.list-chapter a")

        // If chapter list is loaded dynamically, check for novelId to fetch AJAX archive
        if (chapterElements.isEmpty()) {
            val novelId = doc.selectFirst("[data-novel-id], input#novelId, input[name='novelId']")?.let {
                it.attr("data-novel-id").ifBlank { it.attr("value") }
            }
            if (!novelId.isNullOrBlank()) {
                val ajaxUrl = "$baseUrl/ajax/chapter-archive?novelId=$novelId"
                val ajaxResp = client.newCall(GET(ajaxUrl, headers)).execute()
                if (ajaxResp.isSuccessful) {
                    val ajaxDoc = ajaxResp.asJsoup()
                    chapterElements = ajaxDoc.select("li a, a[href*='/chapter-']")
                }
            }
        }

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
        }.reversed() // Put latest chapters first for manga/reader list standards
    }

    // =========================== Chapter Reading Text ===========================

    override suspend fun fetchPageText(page: Page): String {
        val url = resolveUrl(page.url)
        val response = client.newCall(GET(url, headers)).awaitSuccess()
        val doc = response.asJsoup()

        val content = doc.selectFirst("#chr-content, #chapter-content, .chr-text, .chapter-c, div[id*='chr-content']")
            ?: throw Exception("Could not find chapter content on $url")

        return cleanHtmlContent(content)
    }
}
