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
 * Built-in native source for Novelhall (novelhall.com).
 * Standard Asian translation directory with autonomous chapter reading extraction.
 */
class NovelhallSource(
    override val id: Long = 80030011L,
    override val baseUrl: String = "https://www.novelhall.com",
) : BuiltinNovelSource() {

    override val name: String = "Novelhall"
    override val supportsLatest: Boolean = true

    // =========================== Popular ===========================

    override fun popularMangaRequest(page: Int): Request {
        val pageNum = if (page <= 1) 1 else page
        val url = "$baseUrl/all2022-$pageNum.html"
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val doc = response.asJsoup()
        return parseNovelsFromDoc(doc)
    }

    // =========================== Latest ===========================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page <= 1) "$baseUrl/lastupdate.html" else "$baseUrl/lastupdate-$page.html"
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // =========================== Search ===========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$baseUrl/index.php?s=so&module=book&keyword=$encoded"
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val doc = response.asJsoup()
        return parseNovelsFromDoc(doc)
    }

    // =========================== Helper Parsing ===========================

    private fun parseNovelsFromDoc(doc: Document): MangasPage {
        val elements = doc.select(".btm, tr:has(a[href*='-']), .section-list li, .book-info, li:has(a[href*='-']), .top-list li, ul li:has(img)")
        val novels = elements.mapNotNull { el ->
            val titleEl = el.selectFirst("td:not(.type):not(.hidden-xs) a, td a[href*='-']:not(.chapter):not(.writer), a.name, h2 a, h3 a, a[href*='-']")
                ?: return@mapNotNull null
            val href = titleEl.attr("href").trim()
            if (href.isBlank() || href.startsWith("javascript:") || href.contains("/genre/") || href.contains("/dmca")) {
                return@mapNotNull null
            }

            val cleanUrl = if (href.startsWith("http")) {
                val uri = URI(href)
                uri.path + if (uri.query != null) "?${uri.query}" else ""
            } else {
                href
            }

            val title = titleEl.text().trim()
            if (title.isBlank()) return@mapNotNull null

            val cover = el.selectFirst("img")?.let { img ->
                img.attr("data-src").ifBlank { img.attr("src") }
            }?.let { resolveUrl(it) }

            SManga.create().apply {
                this.url = cleanUrl
                this.title = title
                this.thumbnail_url = cover
            }
        }.distinctBy { it.url }

        val hasNext = doc.select(".page-nav a[rel='next'], .page-nav a:contains(>), .page a:contains(>), .page a:contains(Next), a:contains(Next)").isNotEmpty()
        return MangasPage(novels, hasNext)
    }

    // =========================== Manga Details ===========================

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = response.asJsoup()
        return SManga.create().apply {
            title = doc.selectFirst(".book-info h1, .post-content h1")?.text()?.trim().orEmpty()
            author = doc.select(".total .author, .book-info span:contains(Author) a, .author").text().trim()
            description = doc.selectFirst(".intro, #intro, .js-desc")?.text()?.trim()
            genre = doc.select(".book-info span:contains(Category) a, .total .tags a").eachText().joinToString()
            status = when {
                doc.select(".total:contains(Completed), .book-info:contains(Completed)").isNotEmpty() -> SManga.COMPLETED
                else -> SManga.ONGOING
            }
            thumbnail_url = doc.selectFirst(".book-img img, .post-content img")?.let { img ->
                img.attr("data-src").ifBlank { img.attr("src") }
            }?.let { resolveUrl(it) }
        }
    }

    // =========================== Chapters ===========================

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = response.asJsoup()
        val chapterElements = doc.select("#morelist li a, .book-catalog ul li a, ul.section-list li a")

        return chapterElements.mapIndexedNotNull { index, a ->
            val href = a.attr("href").trim()
            if (href.isBlank()) return@mapIndexedNotNull null
            val cleanUrl = if (href.startsWith("http")) {
                val uri = URI(href)
                uri.path + if (uri.query != null) "?${uri.query}" else ""
            } else {
                href
            }

            val chapterName = a.text().trim()
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

        val content = doc.selectFirst("#htmlContent, .entry-content, .article-content, #content")
            ?: throw Exception("Could not find chapter content on $url")

        return cleanHtmlContent(content)
    }
}
