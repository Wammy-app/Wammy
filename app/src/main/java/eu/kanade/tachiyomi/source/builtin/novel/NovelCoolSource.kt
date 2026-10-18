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
import java.net.URI
import java.net.URLEncoder

/**
 * Built-in native source for NovelCool (novelcool.com).
 * Extensive library with server-rendered catalogs and clean reading pipelines.
 */
class NovelCoolSource(
    override val id: Long = 80030006L,
    override val baseUrl: String = "https://novelcool.com",
) : BuiltinNovelSource() {

    override val name: String = "NovelCool"
    override val supportsLatest: Boolean = true

    // =========================== Popular ===========================

    override fun popularMangaRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/category/popular.html" else "$baseUrl/category/popular.html?page=$page"
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val doc = response.asJsoup()
        val novels = doc.select(".book-item").mapNotNull { el ->
            val link = el.selectFirst("a[itemprop='url']") ?: return@mapNotNull null
            val href = link.attr("href")
            val cleanUrl = if (href.startsWith("http")) {
                val uri = URI(href)
                uri.path + if (uri.query != null) "?${uri.query}" else ""
            } else {
                href
            }
            val title = el.selectFirst(".book-pic")?.attr("title")?.ifBlank { link.text() } ?: link.text()
            val cover = el.selectFirst("img")?.let { img ->
                img.attr("cover_url").ifBlank {
                    img.attr("lazy_url").ifBlank { img.attr("src") }
                }
            }?.let { if (it.startsWith("http")) it else "$baseUrl$it" }

            SManga.create().apply {
                this.url = cleanUrl
                this.title = title
                this.thumbnail_url = cover
            }
        }.distinctBy { it.url }

        val hasNext = doc.select("a.page-item:contains(Next), a:contains(Next)").isNotEmpty()
        return MangasPage(novels, hasNext)
    }

    // =========================== Latest ===========================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/category/latest.html" else "$baseUrl/category/latest.html?page=$page"
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // =========================== Search ===========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = if (page == 1) "$baseUrl/search/?name=$encoded" else "$baseUrl/search/?name=$encoded&page=$page"
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // =========================== Details ===========================

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = response.asJsoup()
        return SManga.create().apply {
            title = doc.selectFirst("h1, .book-info-title, .book-pic")?.text() ?: ""
            author = doc.select(".bookinfo-author a, a[href*='author']").text().replace("Author:", "").trim()
            genre = doc.select(".book-tags a, .book-category a").joinToString(", ") { it.text() }
            description = doc.selectFirst(".bookinfo-summary span[itemprop='description'], .bookinfo-summary")?.text()?.trim()
            thumbnail_url = doc.selectFirst(".book-pic img")?.let { img ->
                img.attr("cover_url").ifBlank { img.attr("lazy_url").ifBlank { img.attr("src") } }
            }?.let { if (it.startsWith("http")) it else "$baseUrl$it" }
            status = when {
                doc.text().contains("Completed", ignoreCase = true) -> SManga.COMPLETED
                else -> SManga.ONGOING
            }
            initialized = true
        }
    }

    // =========================== Chapter List ===========================

    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        val url = resolveUrl(manga.url)
        val resp = client.newCall(GET(url, headers)).awaitSuccess()
        val doc = resp.asJsoup()

        val chapters = doc.select(".chp-item a, .chapter-item a").mapIndexed { index, a ->
            val href = a.attr("href")
            val cleanUrl = if (href.startsWith("http")) {
                val uri = URI(href)
                uri.path + if (uri.query != null) "?${uri.query}" else ""
            } else {
                href
            }
            SChapter.create().apply {
                this.url = cleanUrl
                this.name = a.attr("title").ifBlank { a.text().trim() }
                this.chapter_number = (index + 1).toFloat()
            }
        }.distinctBy { it.url }

        return chapters.reversed()
    }

    override fun chapterListParse(response: Response): List<SChapter> = emptyList()

    // =========================== Chapter Content ===========================

    override suspend fun fetchPageText(page: Page): String {
        val url = resolveUrl(page.url)
        val resp = client.newCall(GET(url, headers)).awaitSuccess()
        val doc = resp.asJsoup()

        val contentEl = doc.selectFirst(".chapter-reading-section, .chapter-content, .site-content")
            ?: throw IllegalStateException("Chapter content not found on NovelCool")

        val title = doc.selectFirst("h1, .chapter-title")?.text()?.let { "<h3>$it</h3>\n" } ?: ""
        return title + cleanHtmlContent(contentEl)
    }
}
