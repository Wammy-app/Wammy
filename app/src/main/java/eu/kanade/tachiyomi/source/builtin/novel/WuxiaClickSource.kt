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
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import java.net.URLEncoder

/**
 * Built-in native source for WuxiaClick (wuxia.click).
 * High-speed Next.js payload extractor and structured chapter reading.
 */
class WuxiaClickSource(
    override val id: Long = 80030005L,
    override val baseUrl: String = "https://wuxia.click",
) : BuiltinNovelSource() {

    override val name: String = "WuxiaClick"
    override val supportsLatest: Boolean = true

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // =========================== Popular ===========================

    override fun popularMangaRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/rankings" else "$baseUrl/rankings?page=$page"
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val doc = response.asJsoup()
        val nextData = doc.selectFirst("script#__NEXT_DATA__")?.data()
        if (nextData != null) {
            val root = runCatching { json.parseToJsonElement(nextData).jsonObject }.getOrNull()
            val pageProps = root?.get("props")?.jsonObject?.get("pageProps")?.jsonObject
            val items = pageProps?.get("novels")?.jsonArray
                ?: pageProps?.get("items")?.jsonArray
                ?: pageProps?.get("results")?.jsonArray
            if (items != null) {
                val novels = items.mapNotNull { itemEl ->
                    val obj = itemEl.jsonObject
                    val slug = obj["slug"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val title = obj["title"]?.jsonPrimitive?.content ?: slug
                    val coverKey = obj["coverKey"]?.jsonPrimitive?.content
                    val coverOriginal = obj["coverOriginal"]?.jsonPrimitive?.content
                    val coverUrl = when {
                        !coverKey.isNullOrBlank() -> "https://cdn.wuxiaworld.eu/$coverKey-360.webp"
                        !coverOriginal.isNullOrBlank() -> "https://cdn.wuxiaworld.eu/$coverOriginal"
                        else -> null
                    }
                    SManga.create().apply {
                        this.url = "/novel/$slug"
                        this.title = title
                        this.thumbnail_url = coverUrl
                    }
                }
                val hasNext = pageProps?.get("hasNext")?.jsonPrimitive?.content?.toBoolean()
                    ?: (novels.size >= 20)
                return MangasPage(novels, hasNext)
            }
        }

        // SSR Fallback
        val novels = doc.select("a[href^='/novel/']").mapNotNull { a ->
            val href = a.attr("href")
            val title = a.attr("title").ifBlank { a.selectFirst("h2, h3, .title")?.text() ?: a.text() }
            if (title.isBlank() || href == "/novel") return@mapNotNull null
            SManga.create().apply {
                this.url = href
                this.title = title
            }
        }.distinctBy { it.url }
        return MangasPage(novels, false)
    }

    // =========================== Latest ===========================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/updates" else "$baseUrl/updates?page=$page"
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
            val pageProps = root?.get("props")?.jsonObject?.get("pageProps")?.jsonObject
            val novelObj = pageProps?.get("novel")?.jsonObject
            if (novelObj != null) {
                return SManga.create().apply {
                    title = novelObj["title"]?.jsonPrimitive?.content ?: ""
                    author = novelObj["authorName"]?.jsonPrimitive?.content
                    description = novelObj["synopsis"]?.jsonPrimitive?.content
                        ?: novelObj["description"]?.jsonPrimitive?.content
                    val coverKey = novelObj["coverKey"]?.jsonPrimitive?.content
                    val coverOriginal = novelObj["coverOriginal"]?.jsonPrimitive?.content
                    thumbnail_url = when {
                        !coverKey.isNullOrBlank() -> "https://cdn.wuxiaworld.eu/$coverKey-360.webp"
                        !coverOriginal.isNullOrBlank() -> "https://cdn.wuxiaworld.eu/$coverOriginal"
                        else -> doc.selectFirst("img[alt*='cover'], img.aspect-\\[3\\/4\\]")?.attr("src")
                            ?: doc.selectFirst("meta[property='og:image']")?.attr("content")
                    }
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
            title = doc.selectFirst("h1")?.text() ?: ""
            description = doc.selectFirst(".synopsis, .description, p")?.text()
            initialized = true
        }
    }

    // =========================== Chapter List ===========================

    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        val slug = manga.url.removePrefix("/novel/").trimEnd('/')
        val url = if (manga.url.startsWith("http")) manga.url else "$baseUrl/novel/$slug"
        val resp = client.newCall(GET(url, headers)).awaitSuccess()
        val doc = resp.asJsoup()

        val nextData = doc.selectFirst("script#__NEXT_DATA__")?.data()
        if (nextData != null) {
            val root = runCatching { json.parseToJsonElement(nextData).jsonObject }.getOrNull()
            val pageProps = root?.get("props")?.jsonObject?.get("pageProps")?.jsonObject
            val chaptersArray = pageProps?.get("chapters")?.jsonObject?.get("items")?.jsonArray
                ?: pageProps?.get("items")?.jsonArray
            if (chaptersArray != null) {
                return chaptersArray.mapNotNull { chEl ->
                    val obj = chEl.jsonObject
                    val number = obj["number"]?.jsonPrimitive?.floatOrNull ?: return@mapNotNull null
                    val title = obj["title"]?.jsonPrimitive?.content ?: "Chapter ${number.toInt()}"
                    SChapter.create().apply {
                        this.url = "/chapter/$slug-${number.toInt()}"
                        this.name = title
                        this.chapter_number = number
                    }
                }.reversed()
            }
        }

        // Fallback SSR chapter list
        return doc.select("a[href*='/chapter/']").mapIndexed { index, a ->
            SChapter.create().apply {
                this.url = a.attr("href")
                this.name = a.text().ifBlank { "Chapter ${index + 1}" }
                this.chapter_number = (index + 1).toFloat()
            }
        }.reversed()
    }

    override fun chapterListParse(response: Response): List<SChapter> = emptyList()

    // =========================== Chapter Content ===========================

    override suspend fun fetchPageText(page: Page): String {
        val url = if (page.url.startsWith("http")) page.url else "$baseUrl${page.url}"
        val resp = client.newCall(GET(url, headers)).awaitSuccess()
        val doc = resp.asJsoup()

        val nextData = doc.selectFirst("script#__NEXT_DATA__")?.data()
        if (nextData != null) {
            val root = runCatching { json.parseToJsonElement(nextData).jsonObject }.getOrNull()
            val pageProps = root?.get("props")?.jsonObject?.get("pageProps")?.jsonObject
            val chapterObj = pageProps?.get("chapter")?.jsonObject
            val contentArray = chapterObj?.get("content")?.jsonArray
            if (contentArray != null) {
                val paragraphs = contentArray.mapNotNull { it.jsonPrimitive.content }
                if (paragraphs.isNotEmpty()) {
                    return paragraphs.joinToString("\n\n") { p ->
                        "<p>${p.trim()}</p>"
                    }
                }
            }
        }

        val articleEl = doc.selectFirst(".chapter-content, article, #chapter-content")
            ?: throw IllegalStateException("Chapter text not found on WuxiaClick")
        return cleanHtmlContent(articleEl)
    }
}
