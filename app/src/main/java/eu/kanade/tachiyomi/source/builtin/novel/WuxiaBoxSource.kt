package eu.kanade.tachiyomi.source.builtin.novel

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import java.net.URI

/**
 * Built-in native source for Wuxia Box (wuxiabox.com).
 * High-speed chapter extraction and clean paragraph text rendering.
 */
class WuxiaBoxSource(
    override val id: Long = 80030004L,
    override val baseUrl: String = "https://www.wuxiabox.com",
) : BuiltinNovelSource() {

    override val name: String = "Wuxia Box"
    override val supportsLatest: Boolean = true

    // =========================== Popular ===========================

    override fun popularMangaRequest(page: Int): Request {
        val pageIndex = (page - 1).coerceAtLeast(0)
        return GET("$baseUrl/list/all/all-onclick-$pageIndex.html", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val doc = response.asJsoup()
        val novels = doc.select("li.novel-item").mapNotNull { li ->
            val a = li.selectFirst("a") ?: return@mapNotNull null
            val href = a.attr("href")
            if (!href.contains("/novel/")) return@mapNotNull null
            val title = a.attr("title").ifBlank { li.selectFirst(".novel-title")?.text() ?: a.text() }.trim()
            val cover = li.selectFirst("img")?.let { img ->
                img.attr("data-src").ifBlank { img.attr("src") }
            }?.let { if (it.startsWith("http")) it else "$baseUrl$it" }

            val cleanUrl = if (href.startsWith("http")) {
                val uri = URI(href)
                uri.path + if (uri.query != null) "?${uri.query}" else ""
            } else {
                href
            }

            SManga.create().apply {
                this.url = cleanUrl
                this.title = title
                this.thumbnail_url = cover
            }
        }.distinctBy { it.url }

        val hasNext = doc.select(".pagination a:contains(>), a:contains(Next)").isNotEmpty()
        return MangasPage(novels, hasNext)
    }

    // =========================== Latest ===========================

    override fun latestUpdatesRequest(page: Int): Request {
        val pageIndex = (page - 1).coerceAtLeast(0)
        return GET("$baseUrl/list/all/all-newstime-$pageIndex.html", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // =========================== Search ===========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val formBody = FormBody.Builder()
            .add("show", "title")
            .add("tempid", "1")
            .add("tbname", "news")
            .add("keyboard", query)
            .build()
        return POST("$baseUrl/e/search/index.php", headers, formBody)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // =========================== Details ===========================

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = response.asJsoup()
        return SManga.create().apply {
            title = doc.selectFirst("h1.novel-title, .info h1, h1")?.text() ?: ""
            author = doc.select(".author .property-item, a[href*='/author/'], .novel-info .author").text()
            genre = doc.select(".categories a, .genre a, .tag a").joinToString(", ") { it.text() }
            description = doc.selectFirst(".description, .summary, .intro")?.text()?.trim()
            thumbnail_url = doc.selectFirst(".novel-cover img, .cover img, .cover-wrap img")?.let { img ->
                img.attr("data-src").ifBlank { img.attr("src") }
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

        val chapters = doc.select("ul.chapter-list li a, ul.chapter-list a, .chapter-list a").mapIndexed { index, a ->
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

        return chapters
    }

    override fun chapterListParse(response: Response): List<SChapter> = emptyList()

    // =========================== Chapter Content ===========================

    override suspend fun fetchPageText(page: Page): String {
        val url = resolveUrl(page.url)
        val resp = client.newCall(GET(url, headers)).awaitSuccess()
        val doc = resp.asJsoup()

        val content = doc.selectFirst(".chapter-content, .read-content, #chapter-content")
            ?: throw IllegalStateException("Chapter content not found on Wuxia Box")

        val title = doc.selectFirst("h1.chapter-title, h1")?.text()?.let { "<h3>$it</h3>\n" } ?: ""
        return title + cleanHtmlContent(content)
    }
}
