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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.net.URLEncoder

/**
 * Built-in native source for Baka-Tsuki (baka-tsuki.org).
 * The community light novel translation wiki with volume and chapter translation coverage.
 */
class BakaTsukiSource(
    override val id: Long = 80030007L,
    override val baseUrl: String = "https://www.baka-tsuki.org",
) : BuiltinNovelSource() {

    override val name: String = "Baka-Tsuki"
    override val supportsLatest: Boolean = false

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // =========================== Popular / Catalog ===========================

    override fun popularMangaRequest(page: Int): Request {
        val url = "$baseUrl/project/api.php?action=query&generator=categorymembers&gcmtitle=Category:Light_novel_(English)&gcmlimit=500&prop=pageimages&pithumbsize=360&format=json"
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val bodyStr = response.body.string()
        val jsonRoot = runCatching { json.parseToJsonElement(bodyStr).jsonObject }.getOrNull()
        val pagesObj = jsonRoot?.get("query")?.jsonObject?.get("pages")?.jsonObject

        if (pagesObj == null) {
            // Fallback to HTML if API failed
            val doc = Jsoup.parse(bodyStr, baseUrl)
            val novels = doc.select(".mw-category-group li a").mapNotNull { a ->
                val href = a.attr("href")
                val title = a.text().trim()
                if (title.isBlank() || href.contains("Category:") || href.contains("Special:")) return@mapNotNull null
                SManga.create().apply {
                    this.url = href
                    this.title = title
                }
            }.distinctBy { it.url }
            return MangasPage(novels, false)
        }

        val novels = pagesObj.values.mapNotNull { pageEl ->
            val page = pageEl.jsonObject
            val title = page["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val thumbUrl = page["thumbnail"]?.jsonObject?.get("source")?.jsonPrimitive?.content
            val encodedTitle = URLEncoder.encode(title.replace(" ", "_"), "UTF-8")

            SManga.create().apply {
                this.url = "/project/index.php?title=$encodedTitle"
                this.title = title
                this.thumbnail_url = thumbUrl
            }
        }.sortedBy { it.title }

        return MangasPage(novels, false)
    }

    // =========================== Latest ===========================

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)
    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // =========================== Search ===========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$baseUrl/project/api.php?action=query&generator=search&gsrsearch=$encoded&gsrlimit=50&prop=pageimages&pithumbsize=360&format=json"
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // =========================== Details ===========================

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = response.asJsoup()
        return SManga.create().apply {
            title = doc.selectFirst("h1#firstHeading, h1")?.text() ?: ""
            description = doc.selectFirst("#Story_Synopsis, #Synopsis")
                ?.parent()
                ?.nextElementSibling()
                ?.text()
                ?.trim()

            val authorText = doc.select("li:contains(Author)").firstOrNull()?.text()
            author = authorText?.substringAfter("Author")?.substringBefore("Illustrator")?.replace(":", "")?.trim()

            thumbnail_url = doc.selectFirst("figure img, .thumbimage, .infobox img, a.image img")?.let { img ->
                val src = img.attr("src")
                if (src.startsWith("http")) src else "$baseUrl$src"
            }

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

        val chapters = doc.select("div.mw-parser-output ul li a").mapNotNull { a ->
            val href = a.attr("href")
            val title = a.text().trim()

            // Filter out external redirects, Wikipedia links, categories, and talk pages
            if (!href.startsWith("/project/index.php?title=") ||
                href.contains("action=") ||
                href.contains("User:") ||
                href.contains("Talk:") ||
                href.contains("File:") ||
                href.contains("Category:") ||
                href.contains("Special:") ||
                href.contains("Template:") ||
                href.contains("Illustration", ignoreCase = true) ||
                title.contains("Illustration", ignoreCase = true) ||
                href.contains("Guideline", ignoreCase = true) ||
                title.contains("Guideline", ignoreCase = true) ||
                title.isBlank() ||
                title.equals("Registration", ignoreCase = true) ||
                title.equals("Guidelines", ignoreCase = true) ||
                title.equals("Main Page", ignoreCase = true) ||
                title.startsWith("Swedish", ignoreCase = true) ||
                title.startsWith("French", ignoreCase = true) ||
                title.startsWith("Spanish", ignoreCase = true) ||
                title.startsWith("Indonesian", ignoreCase = true) ||
                title.startsWith("Russian", ignoreCase = true) ||
                title.startsWith("German", ignoreCase = true) ||
                title.startsWith("Italian", ignoreCase = true) ||
                title.startsWith("Portuguese", ignoreCase = true) ||
                title.startsWith("Polish", ignoreCase = true) ||
                title.startsWith("Vietnamese", ignoreCase = true) ||
                title.startsWith("Tagalog", ignoreCase = true)
            ) {
                return@mapNotNull null
            }

            SChapter.create().apply {
                this.url = href
                this.name = title
            }
        }.distinctBy { it.url }

        return chapters.mapIndexed { index, ch ->
            ch.chapter_number = (index + 1).toFloat()
            ch
        }.reversed()
    }

    override fun chapterListParse(response: Response): List<SChapter> = emptyList()

    // =========================== Chapter Content ===========================

    override suspend fun fetchPageText(page: Page): String {
        val url = resolveUrl(page.url)
        val resp = client.newCall(GET(url, headers)).awaitSuccess()
        val doc = resp.asJsoup()

        val content = doc.selectFirst("div#mw-content-text .mw-parser-output, div#mw-content-text")
            ?: throw IllegalStateException("Chapter content not found on Baka-Tsuki")

        // Clean out wiki-specific elements
        content.select(
            ".toc, .mw-editsection, .navbox, .vertical-navbox, figure, .thumb, .infobox, " +
                ".printfooter, #catlinks, .gallery, table:has(a:contains(Return to)), " +
                "td:contains(Return to), a:contains(Return to), .gallerytext, .mw-file-description",
        ).remove()

        val title = doc.selectFirst("h1#firstHeading")?.text()?.let { "<h3>$it</h3>\n" } ?: ""
        return title + cleanHtmlContent(content)
    }
}
