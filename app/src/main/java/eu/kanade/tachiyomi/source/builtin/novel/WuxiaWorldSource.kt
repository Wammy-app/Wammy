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
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder

/**
 * Built-in native source for Wuxiaworld (wuxiaworld.site).
 * Madara WordPress-based novel engine supporting AJAX chapter lists and sanitized text rendering.
 */
class WuxiaWorldSource(
    override val id: Long = 80030010L,
    override val baseUrl: String = "https://wuxiaworld.site",
) : BuiltinNovelSource() {

    override val name: String = "Wuxiaworld"
    override val supportsLatest: Boolean = true

    // =========================== Popular ===========================

    override fun popularMangaRequest(page: Int): Request {
        val url = if (page <= 1) "$baseUrl/novel/?m_orderby=views" else "$baseUrl/novel/page/$page/?m_orderby=views"
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val doc = response.asJsoup()
        return parseNovelsFromDoc(doc)
    }

    // =========================== Latest ===========================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page <= 1) "$baseUrl/novel/?m_orderby=latest" else "$baseUrl/novel/page/$page/?m_orderby=latest"
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // =========================== Search ===========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = if (page <= 1) "$baseUrl/?s=$encoded&post_type=wp-manga" else "$baseUrl/page/$page/?s=$encoded&post_type=wp-manga"
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val doc = response.asJsoup()
        return parseNovelsFromDoc(doc)
    }

    // =========================== Helper Parsing ===========================

    private fun parseNovelsFromDoc(doc: Document): MangasPage {
        val novels = doc.select("div.page-item-detail, .c-tabs-item__content").mapNotNull { el ->
            val titleEl = el.selectFirst(".post-title h3 a, .post-title h5 a, .post-title a") ?: return@mapNotNull null
            val href = titleEl.attr("href").trim()
            if (href.isBlank()) return@mapNotNull null

            val cleanUrl = if (href.startsWith("http")) {
                val uri = URI(href)
                uri.path + if (uri.query != null) "?${uri.query}" else ""
            } else {
                href
            }

            val title = titleEl.text().trim()
            val cover = el.selectFirst(".item-thumb img")?.let { img ->
                img.attr("data-src")
                    .ifBlank { img.attr("data-lazy-src") }
                    .ifBlank { img.attr("srcset").split(" ").firstOrNull() ?: "" }
                    .ifBlank { img.attr("src") }
            }?.let { resolveUrl(it) }

            SManga.create().apply {
                this.url = cleanUrl
                this.title = title
                this.thumbnail_url = cover
            }
        }.distinctBy { it.url }

        val hasNext = doc.select(".nav-previous a, a:contains(Next), a.next, .pagination .next").isNotEmpty()
        return MangasPage(novels, hasNext)
    }

    // =========================== Manga Details ===========================

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = response.asJsoup()
        return SManga.create().apply {
            title = doc.selectFirst(".post-title h1, .post-title")?.text()?.trim().orEmpty()
            author = doc.select(".author-content a, .post-content_item:has(.summary-heading:contains(Author)) a").eachText().joinToString()
            description = doc.selectFirst(".description-summary .summary__content, .summary__content")?.text()?.trim()
            genre = doc.select(".genres-content a").eachText().joinToString()
            status = when {
                doc.select(".post-status .summary-content:contains(Completed)").isNotEmpty() -> SManga.COMPLETED
                else -> SManga.ONGOING
            }
            thumbnail_url = doc.selectFirst(".summary_image img")?.let { img ->
                img.attr("data-src")
                    .ifBlank { img.attr("data-lazy-src") }
                    .ifBlank { img.attr("src") }
            }?.let { resolveUrl(it) }
        }
    }

    // =========================== Chapters ===========================

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = response.asJsoup()
        var chapterElements = doc.select("li.wp-manga-chapter a")

        // Madara AJAX fallback if chapters aren't rendered statically
        if (chapterElements.isEmpty()) {
            val novelUrl = response.request.url.toString()
            val ajaxUrl = if (novelUrl.endsWith("/")) "${novelUrl}ajax/chapters/" else "$novelUrl/ajax/chapters/"
            val postBody = FormBody.Builder().build()
            val ajaxResp = runCatching { client.newCall(POST(ajaxUrl, headers, postBody)).execute() }.getOrNull()
            if (ajaxResp != null && ajaxResp.isSuccessful) {
                val ajaxDoc = ajaxResp.asJsoup()
                chapterElements = ajaxDoc.select("li.wp-manga-chapter a")
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

            val chapterName = a.text().trim()
            SChapter.create().apply {
                this.url = cleanUrl
                this.name = chapterName
                this.chapter_number = (index + 1).toFloat()
            }
        }.distinctBy { it.url }
    }

    // =========================== Chapter Reading Text ===========================

    override suspend fun fetchPageText(page: Page): String {
        val url = resolveUrl(page.url)
        val response = client.newCall(GET(url, headers)).awaitSuccess()
        val doc = response.asJsoup()

        val content = doc.selectFirst(".reading-content .text-left, .reading-content, .entry-content, .reading-manga")
            ?: throw Exception("Could not find chapter content on $url")

        return cleanHtmlContent(content)
    }
}
