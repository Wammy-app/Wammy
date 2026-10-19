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
 * Built-in native source for NovelFull / LightNovelWorld (novelfull.net).
 * High-speed autonomous chapter extraction and structured text parsing.
 */
class LightNovelWorldSource(
    override val id: Long = 80030012L,
    override val baseUrl: String = "https://novelfull.net",
) : BuiltinNovelSource() {

    override val name: String = "NovelFull"
    override val supportsLatest: Boolean = true

    // =========================== Popular ===========================

    override fun popularMangaRequest(page: Int): Request {
        val url = if (page <= 1) "$baseUrl/most-popular" else "$baseUrl/most-popular?page=$page"
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val doc = response.asJsoup()
        return parseNovelsFromDoc(doc)
    }

    // =========================== Latest ===========================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page <= 1) "$baseUrl/latest-release-novel" else "$baseUrl/latest-release-novel?page=$page"
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
        val elements = doc.select(".li-row, .list-novel .row, .col-novel-main .row, .list .row, .row:has(h3.tit), .li")
        val novels = elements.mapNotNull { el ->
            val titleEl = el.selectFirst("h3.tit a, h3 a, .novel-title a") ?: return@mapNotNull null
            val href = titleEl.attr("href").trim()
            if (href.isBlank()) return@mapNotNull null

            val cleanUrl = if (href.startsWith("http")) {
                val uri = URI(href)
                uri.path + if (uri.query != null) "?${uri.query}" else ""
            } else {
                href
            }

            val title = titleEl.attr("title").ifBlank { titleEl.text() }.trim()
            val cover = el.selectFirst(".pic img, img")?.let { img ->
                img.attr("data-src").ifBlank { img.attr("src") }
            }?.let { resolveUrl(it) }

            SManga.create().apply {
                this.url = cleanUrl
                this.title = title
                this.thumbnail_url = cover
            }
        }.distinctBy { it.url }

        val hasNext = doc.select(".pagination li.next:not(.disabled) a, .pagination a:contains(>), a:contains(Next)").isNotEmpty()
        return MangasPage(novels, hasNext)
    }

    // =========================== Manga Details ===========================

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = response.asJsoup()
        return SManga.create().apply {
            title = doc.selectFirst("h1.tit, .m-desc h1, h1")?.text()?.trim().orEmpty()
            author = doc.select(".item a[href*='/author/'], a[href*='/author/']").eachText().joinToString()
            description = doc.selectFirst(".m-desc .txt, .desc .txt, .description")?.text()?.trim()
            genre = doc.select(".item a[href*='/genre/'], a[href*='/genre/']").eachText().joinToString()
            status = when {
                doc.select(".item span.s1:contains(Completed), .item:contains(Completed)").isNotEmpty() -> SManga.COMPLETED
                else -> SManga.ONGOING
            }
            thumbnail_url = doc.selectFirst(".pic img, .book img, img[fetchpriority='high']")?.let { img ->
                img.attr("data-src").ifBlank { img.attr("src") }
            }?.let { resolveUrl(it) }
        }
    }

    // =========================== Chapters ===========================

    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        val detailsUrl = resolveUrl(manga.url)
        val resp = client.newCall(GET(detailsUrl, headers)).awaitSuccess()
        val doc = resp.asJsoup()

        // Check if novelId exists for instant full chapter listing via ajax
        val novelId = doc.selectFirst("[data-novel-id]")?.attr("data-novel-id")?.trim()
        if (!novelId.isNullOrBlank()) {
            val optionUrl = "$baseUrl/ajax-chapter-option?novelId=$novelId"
            val optionResp = runCatching { client.newCall(GET(optionUrl, headers)).awaitSuccess() }.getOrNull()
            if (optionResp != null && optionResp.isSuccessful) {
                val optionDoc = optionResp.asJsoup()
                val options = optionDoc.select("option[value]")
                if (options.isNotEmpty()) {
                    return options.mapIndexedNotNull { index, opt ->
                        val href = opt.attr("value").trim()
                        if (href.isBlank()) return@mapIndexedNotNull null
                        val cleanUrl = if (href.startsWith("http")) {
                            val uri = URI(href)
                            uri.path + if (uri.query != null) "?${uri.query}" else ""
                        } else {
                            href
                        }
                        SChapter.create().apply {
                            this.url = cleanUrl
                            this.name = opt.text().trim()
                            this.chapter_number = (index + 1).toFloat()
                        }
                    }.reversed()
                }
            }
        }

        // Fallback: parse from page DOM
        return chapterListParse(resp)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = response.asJsoup()
        val chapterElements = doc.select(".list-chapter li a, ul.list-chapter a, a[href*='/chapter-']")

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

        val content = doc.selectFirst("#chapter-content, .cha-words, .chapter-text, .txt, #chr-content")
            ?: throw Exception("Could not find chapter content on $url")

        return cleanHtmlContent(content)
    }
}
