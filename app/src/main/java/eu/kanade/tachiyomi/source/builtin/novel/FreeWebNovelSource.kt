package eu.kanade.tachiyomi.source.builtin.novel

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.net.URLEncoder

/**
 * Built-in native source for FreeWebNovel (freewebnovel.com).
 * High-speed chapter extraction via direct AJAX endpoints and full text parsing.
 */
class FreeWebNovelSource(
    override val id: Long = 80030001L,
    override val baseUrl: String = "https://freewebnovel.com",
) : BuiltinNovelSource() {

    override val name: String = "FreeWebNovel"
    override val supportsLatest: Boolean = true

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // =========================== Popular ===========================

    override fun popularMangaRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/sort/most-popular" else "$baseUrl/sort/most-popular/$page"
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val doc = response.asJsoup()
        val novels = doc.select(".con").mapNotNull { el ->
            val titleEl = el.selectFirst("h3.tit a") ?: return@mapNotNull null
            val href = titleEl.attr("href")
            val title = titleEl.attr("title").ifBlank { titleEl.text() }
            val cover = el.selectFirst(".pic img")?.let { img ->
                img.attr("data-src").ifBlank { img.attr("src") }
            }?.let { if (it.startsWith("http")) it else "$baseUrl$it" }

            SManga.create().apply {
                this.url = href
                this.title = title
                this.thumbnail_url = cover
            }
        }
        val hasNext = doc.select("a:contains(Next), .page a:contains(Next)").isNotEmpty()
        return MangasPage(novels, hasNext)
    }

    // =========================== Latest ===========================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/sort/latest-release" else "$baseUrl/sort/latest-release/$page"
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
            title = doc.selectFirst("h1.tit, .m-desc h1")?.text() ?: ""
            author = doc.select(".m-desc .item:has(.glyphicon-user) .right a, .m-desc .right a[href*='/author/']").text()
            genre = doc.select(".m-desc .item:has(.glyphicon-th-list) .right a, .m-desc a[href*='/genre/']").joinToString(", ") { it.text() }
            description = doc.selectFirst(".m-desc .inner, .m-desc .desc-text")?.text()?.trim()
            thumbnail_url = doc.selectFirst(".pic img")?.let { img ->
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
        val mangaPath = manga.url.removePrefix("/")
        val firstPageUrl = "$baseUrl/$mangaPath?ajax=chapters&page=1&pageSize=200"
        val ajaxHeaders = headers.newBuilder()
            .add("X-Requested-With", "XMLHttpRequest")
            .build()

        val firstResp = client.newCall(GET(firstPageUrl, ajaxHeaders)).awaitSuccess()
        val bodyStr = firstResp.body.string()

        val jsonObj = json.parseToJsonElement(bodyStr).jsonObject
        val totalPages = jsonObj["totalPage"]?.jsonPrimitive?.intOrNull ?: 1
        val firstHtml = jsonObj["html"]?.jsonPrimitive?.content ?: ""

        val allChapters = mutableListOf<SChapter>()
        allChapters.addAll(parseChaptersFromHtml(firstHtml))

        // Sequentially paginate remaining pages if multi-page
        for (page in 2..totalPages) {
            val pageUrl = "$baseUrl/$mangaPath?ajax=chapters&page=$page&pageSize=200"
            val resp = runCatching { client.newCall(GET(pageUrl, ajaxHeaders)).awaitSuccess() }.getOrNull()
            if (resp != null) {
                val pageObj = runCatching { json.parseToJsonElement(resp.body.string()).jsonObject }.getOrNull()
                val pageHtml = pageObj?.get("html")?.jsonPrimitive?.content ?: ""
                allChapters.addAll(parseChaptersFromHtml(pageHtml))
            }
        }

        // tachiyomi expects chapter 1 at the end of the list or indexed properly
        return allChapters.reversed()
    }

    private fun parseChaptersFromHtml(html: String): List<SChapter> {
        val doc = Jsoup.parseBodyFragment(html)
        return doc.select("li a").mapIndexed { index, a ->
            SChapter.create().apply {
                url = a.attr("href")
                name = a.attr("title").ifBlank { a.text() }
                chapter_number = (index + 1).toFloat()
            }
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> = emptyList()

    // =========================== Chapter Content ===========================

    override suspend fun fetchPageText(page: Page): String {
        val url = if (page.url.startsWith("http")) page.url else "$baseUrl${page.url}"
        val resp = client.newCall(GET(url, headers)).awaitSuccess()
        val doc = resp.asJsoup()
        val articleEl = doc.selectFirst("#article") ?: throw IllegalStateException("Article content not found on FreeWebNovel")
        val heading = articleEl.selectFirst("h4, h3, h2")?.text()?.let { "<h3>$it</h3>\n" } ?: ""
        return heading + cleanHtmlContent(articleEl)
    }
}
