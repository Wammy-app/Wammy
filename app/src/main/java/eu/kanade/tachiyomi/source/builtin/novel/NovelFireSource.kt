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
 * Built-in native source for NovelFire (novelfire.net).
 * High-speed clean chapter extraction and direct full text reader integration.
 */
class NovelFireSource(
    override val id: Long = 80030002L,
    override val baseUrl: String = "https://novelfire.net",
) : BuiltinNovelSource() {

    override val name: String = "NovelFire"
    override val supportsLatest: Boolean = true

    // =========================== Popular ===========================

    override fun popularMangaRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/genre-all/sort-popular/status-all/all-novel" else "$baseUrl/genre-all/sort-popular/status-all/all-novel/$page"
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val doc = response.asJsoup()
        val novels = doc.select("li.novel-item").mapNotNull { el ->
            val link = el.selectFirst("a[href*='/book/']") ?: return@mapNotNull null
            val href = link.attr("href")
            val title = el.selectFirst(".novel-title")?.text() ?: link.attr("title")
            val cover = el.selectFirst("img")?.let { img ->
                img.attr("data-src").ifBlank { img.attr("src") }
            }?.let { if (it.startsWith("http")) it else "$baseUrl$it" }

            SManga.create().apply {
                this.url = href
                this.title = title
                this.thumbnail_url = cover
            }
        }
        val hasNext = doc.select("a.page-link[rel=next], .pagination li.next:not(.disabled)").isNotEmpty()
        return MangasPage(novels, hasNext)
    }

    // =========================== Latest ===========================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/genre-all/sort-new/status-all/all-novel" else "$baseUrl/genre-all/sort-new/status-all/all-novel/$page"
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // =========================== Search ===========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = if (page == 1) "$baseUrl/search?keyword=$encoded" else "$baseUrl/search?keyword=$encoded&page=$page"
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // =========================== Details ===========================

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = response.asJsoup()
        return SManga.create().apply {
            title = doc.selectFirst("h1.novel-title")?.text() ?: ""
            author = doc.select(".author .property-item, a[href*='/author/']").text()
            genre = doc.select(".categories a.property-item").joinToString(", ") { it.text() }
            description = doc.selectFirst(".description .inner, .summary .inner, .summary")?.text()?.trim()
            thumbnail_url = doc.selectFirst(".novel-cover img")?.let { img ->
                img.attr("data-src").ifBlank { img.attr("src") }
            }?.let { if (it.startsWith("http")) it else "$baseUrl$it" }
            status = when {
                doc.select(".header-stats strong.completed").isNotEmpty() -> SManga.COMPLETED
                else -> SManga.ONGOING
            }
            initialized = true
        }
    }

    // =========================== Chapter List ===========================

    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        val chaptersUrl = if (manga.url.endsWith("/chapters")) "$baseUrl${manga.url}" else "$baseUrl${manga.url.trimEnd('/')}/chapters"
        val resp = client.newCall(GET(chaptersUrl, headers)).awaitSuccess()
        val doc = resp.asJsoup()
        val chapters = doc.select("ul.chapter-list li a").mapIndexed { index, a ->
            SChapter.create().apply {
                url = a.attr("href")
                name = a.selectFirst(".chapter-title")?.text() ?: a.attr("title").ifBlank { a.text() }
                chapter_number = (index + 1).toFloat()
            }
        }
        return chapters.reversed()
    }

    override fun chapterListParse(response: Response): List<SChapter> = emptyList()

    // =========================== Chapter Content ===========================

    override suspend fun fetchPageText(page: Page): String {
        val url = if (page.url.startsWith("http")) page.url else "$baseUrl${page.url}"
        val resp = client.newCall(GET(url, headers)).awaitSuccess()
        val doc = resp.asJsoup()
        val contentEl = doc.selectFirst("#content") ?: throw IllegalStateException("Chapter content not found on NovelFire")
        val title = doc.selectFirst("h1, h2, h3.chapter-title")?.text()?.let { "<h3>$it</h3>\n" } ?: ""
        return title + cleanHtmlContent(contentEl)
    }
}
