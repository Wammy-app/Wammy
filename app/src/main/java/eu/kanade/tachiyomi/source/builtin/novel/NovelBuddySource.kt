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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import java.net.URLEncoder

/**
 * Built-in native source for NovelBuddy (novelbuddy.com).
 * High-performance Next.js payload extractor and full text reader integration.
 */
class NovelBuddySource(
    override val id: Long = 80030003L,
    override val baseUrl: String = "https://novelbuddy.com",
) : BuiltinNovelSource() {

    override val name: String = "NovelBuddy"
    override val supportsLatest: Boolean = true

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // =========================== Popular ===========================

    override fun popularMangaRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/ranking" else "$baseUrl/ranking?page=$page"
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val doc = response.asJsoup()
        val nextData = doc.selectFirst("script#__NEXT_DATA__")?.data()
        if (nextData != null) {
            val root = runCatching { json.parseToJsonElement(nextData).jsonObject }.getOrNull()
            val pageProps = root?.get("props")?.jsonObject?.get("pageProps")?.jsonObject
            val items = pageProps?.get("initialItems")?.jsonArray
                ?: pageProps?.get("items")?.jsonArray
            if (items != null) {
                val novels = items.mapNotNull { itemEl ->
                    val obj = itemEl.jsonObject
                    val slug = obj["slug"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val name = obj["name"]?.jsonPrimitive?.content ?: slug
                    val cover = obj["cover"]?.jsonPrimitive?.content
                    val coverUrl = cover?.let { if (it.startsWith("http")) it else "$baseUrl$it" }
                    SManga.create().apply {
                        this.url = "/$slug"
                        this.title = name
                        this.thumbnail_url = coverUrl
                    }
                }
                val hasNext = pageProps?.get("initialPagination")?.jsonObject?.get("has_next")?.jsonPrimitive?.content?.toBoolean() ?: false
                return MangasPage(novels, hasNext)
            }
        }

        // Fallback SSR parsing
        val novels = doc.select("a[href^='/'][class*='novel'], .novel-item a, article a").mapNotNull { a ->
            val href = a.attr("href")
            if (href.count { it == '/' } != 1 || href.startsWith("/browse") || href.startsWith("/genre")) return@mapNotNull null
            val title = a.attr("title").ifBlank { a.text() }
            if (title.isBlank()) return@mapNotNull null
            SManga.create().apply {
                this.url = href
                this.title = title
            }
        }.distinctBy { it.url }
        return MangasPage(novels, false)
    }

    // =========================== Latest ===========================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/latest" else "$baseUrl/latest?page=$page"
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // =========================== Search ===========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = if (page == 1) "$baseUrl/search?q=$encoded" else "$baseUrl/search?q=$encoded&page=$page"
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // =========================== Details ===========================

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = response.asJsoup()
        val nextData = doc.selectFirst("script#__NEXT_DATA__")?.data()
        if (nextData != null) {
            val root = runCatching { json.parseToJsonElement(nextData).jsonObject }.getOrNull()
            val novelObj = root?.get("props")?.jsonObject?.get("pageProps")?.jsonObject?.get("novel")?.jsonObject
            if (novelObj != null) {
                return SManga.create().apply {
                    title = novelObj["name"]?.jsonPrimitive?.content ?: ""
                    author = novelObj["author"]?.jsonPrimitive?.content
                    description = novelObj["summary"]?.jsonPrimitive?.content
                    val cover = novelObj["cover"]?.jsonPrimitive?.content
                    thumbnail_url = cover?.let { if (it.startsWith("http")) it else "$baseUrl$it" }
                    val genres = novelObj["genres"]?.jsonArray?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content }
                    genre = genres?.joinToString(", ")
                    val statusStr = novelObj["status"]?.jsonPrimitive?.content ?: ""
                    status = if (statusStr.contains("completed", ignoreCase = true)) SManga.COMPLETED else SManga.ONGOING
                    initialized = true
                }
            }
        }

        // SSR Fallback
        return SManga.create().apply {
            title = doc.selectFirst("h1, h2")?.text() ?: ""
            description = doc.selectFirst(".summary, .synopsis, p")?.text()
            initialized = true
        }
    }

    // =========================== Chapter List ===========================

    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        val url = resolveUrl(manga.url)
        val resp = client.newCall(GET(url, headers)).awaitSuccess()
        val doc = resp.asJsoup()
        val nextData = doc.selectFirst("script#__NEXT_DATA__")?.data()
        if (nextData != null) {
            val root = runCatching { json.parseToJsonElement(nextData).jsonObject }.getOrNull()
            val pageProps = root?.get("props")?.jsonObject?.get("pageProps")?.jsonObject
            val chapters = pageProps?.get("chapters")?.jsonArray
                ?: pageProps?.get("novel")?.jsonObject?.get("chapters")?.jsonArray
            if (chapters != null) {
                return chapters.mapIndexed { index, chEl ->
                    val ch = chEl.jsonObject
                    val chUrl = ch["url"]?.jsonPrimitive?.content ?: ""
                    val chName = ch["name"]?.jsonPrimitive?.content ?: "Chapter ${index + 1}"
                    val chNum = ch["number"]?.jsonPrimitive?.content?.toFloatOrNull() ?: (index + 1).toFloat()
                    SChapter.create().apply {
                        this.url = chUrl
                        this.name = chName
                        this.chapter_number = chNum
                    }
                }.reversed()
            }
        }

        // Fallback SSR chapters
        return doc.select("a[href*='/chapter-']").mapIndexed { index, a ->
            SChapter.create().apply {
                this.url = a.attr("href")
                this.name = a.text()
                this.chapter_number = (index + 1).toFloat()
            }
        }.reversed()
    }

    override fun chapterListParse(response: Response): List<SChapter> = emptyList()

    // =========================== Chapter Content ===========================

    override suspend fun fetchPageText(page: Page): String {
        val url = resolveUrl(page.url)
        val resp = client.newCall(GET(url, headers)).awaitSuccess()
        val doc = resp.asJsoup()
        val contentEl = doc.selectFirst(".novel-tts-content, article.novel-reader-content, #main-content")
            ?: throw IllegalStateException("Chapter content not found on NovelBuddy")
        val title = doc.selectFirst("h2, h1")?.text()?.let { "<h3>$it</h3>\n" } ?: ""
        return title + cleanHtmlContent(contentEl)
    }
}
