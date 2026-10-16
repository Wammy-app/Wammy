package eu.kanade.tachiyomi.source.builtin.comix

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
import java.net.URLEncoder

/**
 * Built-in native Comix source (comix.to).
 * Supports SSR initial-data JSON extraction and HTML DOM parsing.
 */
@Suppress("DEPRECATION")
class ComixSource(
    override val id: Long = 80010012L,
    override val lang: String = "en",
) : HttpSource() {

    override val name: String = "Comix"
    override val baseUrl: String = "https://comix.to"
    override val supportsLatest: Boolean = true

    override val client: OkHttpClient
        get() = network.client

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("Referer", "$baseUrl/")

    override fun imageRequest(page: Page): Request {
        val newHeaders = headersBuilder().set("Referer", "$baseUrl/").build()
        return GET(page.imageUrl!!, newHeaders)
    }

    // =========================== Popular ===========================

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/browse?order[score]=desc&page=$page", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val html = response.body.string()
        val doc = response.asJsoup(html)
        val mangas = mutableListOf<SManga>()
        val seen = mutableSetOf<String>()

        // 1. Try DOM links
        doc.select("a[href*='/title/']").forEach { link ->
            val href = link.attr("abs:href")
            if (href.contains("/chapter") || href.contains("-chapter-")) return@forEach
            val slugMatch = Regex("/title/([a-zA-Z0-9]+-[^/?#]+)").find(href) ?: return@forEach
            val slug = slugMatch.groupValues[1]
            if (!seen.add(slug)) return@forEach

            val title = link.select("h3, h4, .title, span").first()?.text()
                ?: link.attr("title")
                ?: link.select("img").attr("alt")
            if (title.isBlank() || title.contains("chapter", ignoreCase = true)) return@forEach

            val img = link.select("img").first()
            val cover = img?.attr("abs:src")?.ifEmpty { img.attr("abs:data-src") }

            val manga = SManga.create().apply {
                this.title = title.trim()
                this.url = "/title/$slug"
                this.thumbnail_url = cover
            }
            mangas.add(manga)
        }

        // 2. Try JSON initial-data if DOM yielded few items
        if (mangas.isEmpty()) {
            val initialData = extractInitialData(html)
            if (initialData != null) {
                val queries = initialData.optJSONObject("queries")
                if (queries != null) {
                    val keys = queries.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        if (key.startsWith("[\"manga\"")) {
                            val obj = queries.opt(key)
                            val items = when (obj) {
                                is org.json.JSONArray -> obj
                                is JSONObject -> obj.optJSONArray("items")
                                else -> null
                            }
                            if (items != null) {
                                for (i in 0 until items.length()) {
                                    val itm = items.optJSONObject(i) ?: continue
                                    val title = itm.optString("title", "")
                                    val itemUrl = itm.optString("url", itm.optString("hid", ""))
                                    val slug = itemUrl.removePrefix("/title/").removePrefix("/")
                                    if (slug.isNotBlank() && seen.add(slug)) {
                                        val manga = SManga.create().apply {
                                            this.title = title.trim()
                                            this.url = "/title/$slug"
                                            val poster = itm.optJSONObject("poster")
                                            this.thumbnail_url = poster?.optString("large")
                                                ?: poster?.optString("medium")
                                        }
                                        mangas.add(manga)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        return MangasPage(mangas, mangas.isNotEmpty())
    }

    // =========================== Latest ===========================

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/browse?order[chapter_updated_at]=desc&page=$page", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // =========================== Search ===========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        return GET("$baseUrl/browse?keyword=$encoded&page=$page", headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // =========================== Details ===========================

    override fun mangaDetailsRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url, headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val html = response.body.string()
        val doc = response.asJsoup(html)
        val manga = SManga.create()

        manga.title = doc.select("h1.mpage__title, .mpage__title, h1").first()?.text()?.trim() ?: "Unknown"
        manga.description = doc.select(".mpage__desc, .mpage__synopsis, [class*='desc']").first()?.text()?.trim()

        val img = doc.select("img.mpage__poster, .mpage__poster img, .cover img").first()
        manga.thumbnail_url = img?.attr("abs:src")?.ifEmpty { img.attr("abs:data-src") }

        val genres = doc.select("a[href*='/genre/'], a[href*='/tag/'], .badge, .genre").eachText()
        manga.genre = genres.distinct().joinToString(", ").ifEmpty { null }

        manga.status = SManga.ONGOING

        return manga
    }

    // =========================== Chapters ===========================

    override fun chapterListRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url, headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val html = response.body.string()
        val doc = response.asJsoup(html)
        val chapters = mutableListOf<SChapter>()
        val seen = mutableSetOf<String>()

        doc.select("a[href*='-chapter-'], a[href*='/chapter/'], .chapter-item a").forEach { link ->
            val href = link.attr("abs:href")
            val match = Regex("chapter-([0-9.]+)").find(href)
            val chNumStr = match?.groupValues?.get(1) ?: ""

            if (seen.add(href)) {
                val chapter = SChapter.create().apply {
                    val txt = link.text().trim()
                    this.name = if (txt.isNotBlank()) txt else "Chapter $chNumStr"
                    this.setUrlWithoutDomain(href)
                    this.chapter_number = chNumStr.toFloatOrNull() ?: -1f
                }
                chapters.add(chapter)
            }
        }

        return chapters.sortedByDescending { it.chapter_number }
    }

    // =========================== Pages ===========================

    override fun pageListParse(response: Response): List<Page> {
        val doc = response.asJsoup()
        val pages = mutableListOf<Page>()
        val imgElements = doc.select("img.chapter-img, .reader img, img.page-img, .reading-content img")

        var index = 0
        imgElements.forEach { img ->
            val src = img.attr("abs:src").ifEmpty { img.attr("abs:data-src") }
            if (src.isNotBlank() && !src.contains("logo") && !src.contains("banner")) {
                pages.add(Page(index++, "", src))
            }
        }
        return pages.distinctBy { it.imageUrl }
    }

    override fun imageUrlParse(response: Response): String =
        throw UnsupportedOperationException("Not used with direct Page image URLs")

    private fun extractInitialData(rawHtml: String): JSONObject? {
        val startTag = "id=\"initial-data\">"
        val idx = rawHtml.indexOf(startTag)
        if (idx == -1) return null
        val end = rawHtml.indexOf("</script>", idx)
        if (end == -1) return null
        return try {
            JSONObject(rawHtml.substring(idx + startTag.length, end))
        } catch (_: Exception) {
            null
        }
    }
}
