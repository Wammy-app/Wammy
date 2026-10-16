package eu.kanade.tachiyomi.source.builtin.bbato

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Built-in native Bbato / Bato source (bato1.com).
 * Uses direct HTML parsing and AJAX chapter listing.
 */
@Suppress("DEPRECATION")
class BbatoSource(
    override val id: Long = 80010010L,
    override val lang: String = "en",
) : HttpSource() {

    override val name: String = "Bbato"
    override val baseUrl: String = "https://bato1.com"
    override val supportsLatest: Boolean = true

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .build()
    }

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("Referer", "$baseUrl/")

    // =========================== Popular ===========================

    override fun popularMangaRequest(page: Int): Request {
        val path = if (page == 1) "/filter?sort=views" else "/filter?sort=views&page=$page"
        return GET("$baseUrl$path", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage = parseMangasDocument(response.asJsoup())

    // =========================== Latest ===========================

    override fun latestUpdatesRequest(page: Int): Request {
        val path = if (page == 1) "/updated" else "/updated/page/$page"
        return GET("$baseUrl$path", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = parseMangasDocument(response.asJsoup())

    // =========================== Search ===========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
        val path = if (page == 1) "/filter?keyword=$encodedQuery" else "/filter?keyword=$encodedQuery&page=$page"
        return GET("$baseUrl$path", headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseMangasDocument(response.asJsoup())

    private fun parseMangasDocument(doc: Document): MangasPage {
        val mangas = doc.select(".original.card-lg .unit").mapNotNull { element ->
            val poster = element.selectFirst("a.poster") ?: return@mapNotNull null
            val titleEl = element.selectFirst(".info > a") ?: return@mapNotNull null
            val img = poster.selectFirst("img")

            SManga.create().apply {
                setUrlWithoutDomain(poster.attr("abs:href"))
                title = titleEl.text().trim()
                thumbnail_url = extractImageUrl(img)
            }
        }

        val hasNext = doc.selectFirst(".pagination a[rel=next]") != null
        return MangasPage(mangas, hasNext)
    }

    // =========================== Details ===========================

    override fun mangaDetailsRequest(manga: SManga): Request {
        val url = if (manga.url.startsWith("http")) manga.url else "$baseUrl${manga.url}"
        return GET(url, headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = response.asJsoup()
        return SManga.create().apply {
            title = doc.selectFirst("h1[itemprop=name]")?.text()?.trim()
                ?: doc.selectFirst("h1")?.text()?.trim() ?: ""
            author = doc.select(".meta div:has(span:contains(Author)) a").joinToString(", ") { it.text().trim() }
            description = doc.selectFirst(".description")?.text()?.trim()
            genre = doc.select(".meta div:has(span:contains(Genres)) a").joinToString(", ") { it.text().trim() }

            val statusText = doc.selectFirst(".info > p")?.text()?.lowercase() ?: ""
            status = when {
                statusText.contains("ongoing") || statusText.contains("releasing") -> SManga.ONGOING
                statusText.contains("completed") -> SManga.COMPLETED
                statusText.contains("hiatus") -> SManga.ON_HIATUS
                statusText.contains("cancelled") || statusText.contains("discontinued") -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }

            thumbnail_url = extractImageUrl(doc.selectFirst(".poster img"))
            initialized = true
        }
    }

    // =========================== Chapters ===========================

    override fun chapterListRequest(manga: SManga): Request {
        val slug = manga.url.removeSuffix("/").substringAfterLast("/")
        val chapterHeaders = headersBuilder()
            .add("X-Requested-With", "XMLHttpRequest")
            .build()
        return GET("$baseUrl/get-chapter-list?slug=$slug", chapterHeaders)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val bodyStr = response.body.string()
        val chapters = mutableListOf<SChapter>()

        return try {
            val json = JSONObject(bodyStr)
            val dataArray = json.optJSONArray("data") ?: return emptyList()
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

            for (i in 0 until dataArray.length()) {
                val item = dataArray.getJSONObject(i)
                val chapterSlug = item.optString("chapter_slug", "")
                val chapterName = item.optString("chapter_name", "")
                val updatedAt = item.optString("updated_at", "")

                val chapter = SChapter.create().apply {
                    url = "/read/$chapterSlug"
                    name = chapterName.ifBlank { "Chapter ${i + 1}" }
                    date_upload = runCatching {
                        dateFormat.parse(updatedAt)?.time ?: 0L
                    }.getOrDefault(0L)
                }
                chapters.add(chapter)
            }
            chapters
        } catch (_: Exception) {
            emptyList()
        }
    }

    // =========================== Pages ===========================

    override fun pageListRequest(chapter: SChapter): Request {
        val url = if (chapter.url.startsWith("http")) chapter.url else "$baseUrl${chapter.url}"
        return GET(url, headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val doc = response.asJsoup()
        val pages = mutableListOf<Page>()

        val imgElements = doc.select(".pages .page:not(.notice-page) img, #viewer img, .read-container img")
        imgElements.forEachIndexed { index, img ->
            val url = extractImageUrl(img)
            if (url.isNotBlank()) {
                pages.add(Page(index, imageUrl = url))
            }
        }

        return pages
    }

    override fun imageUrlParse(response: Response): String = ""

    private fun extractImageUrl(element: Element?): String {
        if (element == null) return ""
        return element.attr("abs:data-src").ifBlank {
            element.attr("abs:data-lazy-src").ifBlank {
                element.attr("abs:src")
            }
        }.trim()
    }
}
