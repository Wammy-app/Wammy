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
import java.net.URLEncoder

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
        val url = if (page == 1) "$baseUrl/all" else "$baseUrl/all/$page"
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val doc = response.asJsoup()
        val novels = doc.select("a.list-body, .novel-item a").mapNotNull { a ->
            val href = a.attr("href")
            if (!href.contains("/novel/")) return@mapNotNull null
            val title = a.attr("title").ifBlank { a.selectFirst(".novel-title")?.text() ?: a.text() }
            val cover = a.selectFirst("img")?.let { img ->
                img.attr("data-src").ifBlank { img.attr("src") }
            }?.let { if (it.startsWith("http")) it else "$baseUrl$it" }

            SManga.create().apply {
                this.url = href
                this.title = title
                this.thumbnail_url = cover
            }
        }.distinctBy { it.url }
        val hasNext = doc.select("a:contains(Next), .pagination .next").isNotEmpty()
        return MangasPage(novels, hasNext)
    }

    // =========================== Latest ===========================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/latest" else "$baseUrl/latest/$page"
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // =========================== Search ===========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$baseUrl/search?keyword=$encoded"
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // =========================== Details ===========================

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = response.asJsoup()
        return SManga.create().apply {
            title = doc.selectFirst("h1.novel-title, .info h1, h1")?.text() ?: ""
            author = doc.select(".author .property-item, a[href*='/author/']").text()
            genre = doc.select(".categories a, .genre a").joinToString(", ") { it.text() }
            description = doc.selectFirst(".description, .summary, .intro")?.text()?.trim()
            thumbnail_url = doc.selectFirst(".novel-cover img, .cover img")?.let { img ->
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
        val chaptersUrl = if (manga.url.contains("tab=chapters")) "$baseUrl${manga.url}" else "$baseUrl${manga.url}?tab=chapters"
        val resp = client.newCall(GET(chaptersUrl, headers)).awaitSuccess()
        val doc = resp.asJsoup()

        val novelId = manga.url.substringAfterLast("/").substringBefore(".html")
        val chapters = doc.select("a[href*='_${novelId}_'], a[href*='_']").filter { a ->
            a.attr("href").matches(Regex(".*/novel/.*_\\d+\\.html.*"))
        }.mapIndexed { index, a ->
            SChapter.create().apply {
                url = a.attr("href")
                name = a.attr("title").ifBlank { a.text() }
                chapter_number = (index + 1).toFloat()
            }
        }.distinctBy { it.url }

        return chapters.reversed()
    }

    override fun chapterListParse(response: Response): List<SChapter> = emptyList()

    // =========================== Chapter Content ===========================

    override suspend fun fetchPageText(page: Page): String {
        val url = if (page.url.startsWith("http")) page.url else "$baseUrl${page.url}"
        val resp = client.newCall(GET(url, headers)).awaitSuccess()
        val doc = resp.asJsoup()
        val contentEl = doc.selectFirst(".chapter-content") ?: throw IllegalStateException("Chapter content not found on Wuxia Box")
        val title = doc.selectFirst("h1, h2, .chapter-title")?.text()?.let { "<h3>$it</h3>\n" } ?: ""
        return title + cleanHtmlContent(contentEl)
    }
}
