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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import java.net.URLEncoder

/**
 * Built-in native source for MVLEMPYR (mvlempyr.io).
 * High-speed JSON novel database and full chapter reader integration.
 */
class MvlEmpyrSource(
    override val id: Long = 80030008L,
    override val baseUrl: String = "https://www.mvlempyr.io",
) : BuiltinNovelSource() {

    override val name: String = "MVLEMPYR"
    override val supportsLatest: Boolean = true

    private val apiUrl = "https://chap.heliosarchive.online/wp-json/wp/v2/mvl-novels"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // =========================== Popular ===========================

    override fun popularMangaRequest(page: Int): Request {
        val url = "$apiUrl?page=$page&per_page=20&orderby=monthly-rank"
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val bodyStr = response.body.string()
        val array = runCatching { json.parseToJsonElement(bodyStr).jsonArray }.getOrNull()
            ?: return MangasPage(emptyList(), false)

        val novels = array.mapNotNull { el ->
            val obj = el.jsonObject
            val slug = obj["slug"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.content ?: slug
            val novelCode = obj["novel-code"]?.jsonPrimitive?.intOrNull

            val coverUrl = if (novelCode != null) {
                "https://assets.mvlempyr.app/images/600/$novelCode.webp"
            } else null

            SManga.create().apply {
                this.url = "/novel/$slug"
                this.title = name
                this.thumbnail_url = coverUrl
            }
        }

        val totalPages = response.header("X-WP-TotalPages")?.toIntOrNull() ?: 1
        val currentPage = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        return MangasPage(novels, currentPage < totalPages)
    }

    // =========================== Latest ===========================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$apiUrl?page=$page&per_page=20&orderby=modified"
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // =========================== Search ===========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$apiUrl?search=$encoded&page=$page&per_page=20"
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // =========================== Details ===========================

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = response.asJsoup()
        return SManga.create().apply {
            title = doc.selectFirst(".novel-title2, h2.collection-title, h1")?.text() ?: ""
            author = doc.select(".mobileauthorname, .auth-name").text().trim()
            genre = doc.select(".g-t-link div").joinToString(", ") { it.text() }
            description = doc.selectFirst(".synopsis, .novelsynopsis")?.text()?.trim()

            val cover = doc.selectFirst(".novel-image2, .collection-image")?.attr("src")
            thumbnail_url = cover?.let { if (it.startsWith("http")) it else "$baseUrl$it" }

            status = when {
                doc.text().contains("Completed", ignoreCase = true) -> SManga.COMPLETED
                else -> SManga.ONGOING
            }
            initialized = true
        }
    }

    // =========================== Chapter List ===========================

    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        val slug = manga.url.removePrefix("/novel/").trimEnd('/')

        // Query metadata from API to obtain novel-code and total-chapters
        val apiReq = GET("$apiUrl?slug=$slug", headers)
        val apiResp = runCatching { client.newCall(apiReq).awaitSuccess() }.getOrNull()
        if (apiResp != null) {
            val bodyStr = apiResp.body.string()
            val array = runCatching { json.parseToJsonElement(bodyStr).jsonArray }.getOrNull()
            val novelObj = array?.firstOrNull()?.jsonObject
            val novelCode = novelObj?.get("novel-code")?.jsonPrimitive?.intOrNull
            val totalChapters = novelObj?.get("total-chapters")?.jsonPrimitive?.intOrNull

            if (novelCode != null && totalChapters != null && totalChapters > 0) {
                val chapters = mutableListOf<SChapter>()
                for (i in 1..totalChapters) {
                    chapters.add(
                        SChapter.create().apply {
                            this.url = "/chapter/$novelCode-$i"
                            this.name = "Chapter $i"
                            this.chapter_number = i.toFloat()
                        },
                    )
                }
                return chapters.reversed()
            }
        }

        // Fallback: scrape from novel page
        val url = if (manga.url.startsWith("http")) manga.url else "$baseUrl${manga.url}"
        val pageResp = client.newCall(GET(url, headers)).awaitSuccess()
        val doc = pageResp.asJsoup()
        val chapters = doc.select("a[href*='/chapter/']").mapIndexed { index, a ->
            SChapter.create().apply {
                this.url = a.attr("href")
                this.name = a.text().ifBlank { "Chapter ${index + 1}" }
                this.chapter_number = (index + 1).toFloat()
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

        val contentEl = doc.selectFirst("#chapter, #chapter-content, .ChapterContent")
            ?: throw IllegalStateException("Chapter content not found on MVLEMPYR")

        val title = doc.selectFirst("h1, h2, .ChapterTitle")?.text()?.let { "<h3>$it</h3>\n" } ?: ""
        return title + cleanHtmlContent(contentEl)
    }
}
