package eu.kanade.tachiyomi.source.builtin.reimanga

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
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Built-in native ReiManga source (reimanga.net).
 * Uses direct JSON endpoints with HTML scrape fallback.
 */
@Suppress("DEPRECATION")
class ReiMangaSource(
    override val id: Long = 80010011L,
    override val lang: String = "en",
) : HttpSource() {

    override val name: String = "ReiManga"
    override val baseUrl: String = "https://reimanga.net"
    override val supportsLatest: Boolean = true

    override val client: OkHttpClient
        get() = network.client

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("Referer", "$baseUrl/")

    // =========================== Popular ===========================

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/api/manga/trending?limit=24&full=1", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val bodyStr = response.body.string()
        val mangas = mutableListOf<SManga>()

        try {
            val json = if (bodyStr.trim().startsWith("[")) {
                JSONArray(bodyStr)
            } else {
                JSONObject(bodyStr).optJSONArray("data") ?: JSONArray()
            }

            for (i in 0 until json.length()) {
                val item = json.getJSONObject(i)
                mangas.add(parseMangaFromApi(item))
            }
        } catch (_: Exception) {
            // Fallback: parse as HTML if JSON failed
            val doc = response.asJsoup(bodyStr)
            doc.select("a[href*='/manga/']").forEach { link ->
                val href = link.attr("abs:href")
                if (href.matches(Regex(".*/manga/[^/]+$"))) {
                    val title = link.select("h3, .title").text().ifEmpty { link.attr("title") }
                    val img = link.select("img").first()
                    val cover = img?.attr("abs:src")?.ifEmpty { img.attr("abs:data-src") }
                    val manga = SManga.create().apply {
                        this.title = title.ifEmpty { "Unknown" }
                        setUrlWithoutDomain(href)
                        thumbnail_url = cover
                    }
                    mangas.add(manga)
                }
            }
        }

        return MangasPage(mangas.distinctBy { it.url }, false)
    }

    // =========================== Latest ===========================

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/api/manga/latest-updates?limit=24&page=$page", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // =========================== Search ===========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        return GET("$baseUrl/api/manga/search/advanced?title=$encoded&limit=24&page=$page", headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // =========================== Details ===========================

    override fun mangaDetailsRequest(manga: SManga): Request {
        val numericId = extractNumericId(manga.url)
        return if (numericId.isNotBlank()) {
            GET("$baseUrl/api/manga/$numericId", headers)
        } else {
            GET(baseUrl + manga.url, headers)
        }
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val bodyStr = response.body.string()
        val manga = SManga.create()

        if (bodyStr.trim().startsWith("{")) {
            try {
                val json = JSONObject(bodyStr)
                val m = json.optJSONObject("manga") ?: json
                val id = m.optString("id", "")

                manga.title = m.optString("title", "Unknown").trim()
                manga.description = m.optString("description", "").trim()
                manga.status = if (m.optInt("status", 0) == 1) SManga.COMPLETED else SManga.ONGOING
                manga.thumbnail_url = "$baseUrl/covers/$id/thumbnail.webp"

                val genresArray = m.optJSONArray("genres")
                if (genresArray != null) {
                    val list = mutableListOf<String>()
                    for (i in 0 until genresArray.length()) {
                        val g = genresArray.getJSONObject(i)
                        val name = g.optString("name")
                        if (name.isNotBlank()) list.add(name)
                    }
                    manga.genre = list.joinToString(", ").ifEmpty { null }
                }

                val authorsArray = m.optJSONArray("authors")
                if (authorsArray != null) {
                    val list = mutableListOf<String>()
                    for (i in 0 until authorsArray.length()) {
                        val a = authorsArray.getJSONObject(i)
                        val name = a.optString("name")
                        if (name.isNotBlank()) list.add(name)
                    }
                    manga.author = list.joinToString(", ").ifEmpty { null }
                }

                return manga
            } catch (_: Exception) {
                // fall through to HTML parse
            }
        }

        // HTML fallback
        val doc = response.asJsoup(bodyStr)
        manga.title = doc.select("h1").first()?.text()?.trim() ?: "Unknown"
        manga.description = doc.select(".description, .summary, p").first()?.text()?.trim()
        manga.status = SManga.ONGOING
        val img = doc.select(".cover img, img[src*='covers/']").first()
        manga.thumbnail_url = img?.attr("abs:src")?.ifEmpty { img.attr("abs:data-src") }

        return manga
    }

    // =========================== Chapters ===========================

    override fun chapterListRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url, headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val chapters = mutableListOf<SChapter>()

        // Scrape chapter links: e.g. /manga/{slug}/{chapterNum}
        document.select("a[href*='/manga/']").forEach { link ->
            val href = link.attr("abs:href")
            val match = Regex("/manga/[^/]+/([0-9.]+)").find(href)
            if (match != null) {
                val chNumStr = match.groupValues[1]
                val chapter = SChapter.create().apply {
                    this.name = link.text().trim().ifEmpty { "Chapter $chNumStr" }
                    this.setUrlWithoutDomain(href)
                    this.chapter_number = chNumStr.toFloatOrNull() ?: -1f
                }
                chapters.add(chapter)
            }
        }

        return chapters.distinctBy { it.url }.sortedByDescending { it.chapter_number }
    }

    // =========================== Pages ===========================

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val pages = mutableListOf<Page>()
        val imgElements = document.select("img[src*='covers/'], img[src*='reimanga.net'], img[src*='cdn'], .reader img")

        var index = 0
        imgElements.forEach { img ->
            val src = img.attr("abs:src").ifEmpty { img.attr("abs:data-src") }
            if (src.isNotBlank() && !src.contains("logo") && !src.contains("avatar") && !src.contains("icon")) {
                pages.add(Page(index++, "", src))
            }
        }
        return pages.distinctBy { it.imageUrl }
    }

    override fun imageUrlParse(response: Response): String =
        throw UnsupportedOperationException("Not used with direct Page image URLs")

    // =========================== Helpers ===========================

    private fun parseMangaFromApi(item: JSONObject): SManga {
        val manga = SManga.create()
        val id = item.optString("id", "")
        val nameUrl = item.optString("name_url", item.optString("slug", id))
        val slug = "$nameUrl-$id"

        manga.title = item.optString("title", nameUrl).trim()
        manga.url = "/manga/$slug"
        manga.thumbnail_url = "$baseUrl/covers/$id/thumbnail.webp"
        return manga
    }

    private fun extractNumericId(url: String): String {
        return Regex("-(\\d+)$").find(url)?.groupValues?.get(1)
            ?: Regex("/(\\d+)$").find(url)?.groupValues?.get(1)
            ?: ""
    }
}
