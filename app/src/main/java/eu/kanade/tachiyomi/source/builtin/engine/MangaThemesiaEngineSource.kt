package eu.kanade.tachiyomi.source.builtin.engine

import android.util.Base64
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Generic MangaThemesia Engine implementation (formerly WPMangaStream/WPMangaReader).
 * Powers Arena Scans, Aster Scans, Comic Asura, and many other scanlation sites.
 */
@Suppress("DEPRECATION")
open class MangaThemesiaEngineSource(
    override val id: Long,
    override val name: String,
    override val baseUrl: String,
    override val lang: String = "en",
    val mangaUrlDirectory: String = "/manga",
) : ParsedHttpSource() {

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
        return if (baseUrl.contains("comicasura", ignoreCase = true)) {
            GET("$baseUrl/advanced-search?sort=rating&page=$page", headers)
        } else {
            GET("$baseUrl$mangaUrlDirectory?page=$page&order=popular", headers)
        }
    }

    override fun popularMangaSelector(): String =
        ".grid > a[href*=manga], .flex-wrap.flex a[href*=manga], .utao .uta .imgu, .listupd .bs .bsx, .listo .bs .bsx, .animposx, .bsx"

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        val link = if (element.tagName() == "a") element else element.select("a").first()
        val img = element.select("img").first()

        val rawTitle = link?.attr("title")?.ifBlank { null }
            ?: img?.attr("title")?.ifBlank { null }
            ?: img?.attr("alt")?.ifBlank { null }
            ?: link?.text()?.trim()
            ?: "Unknown"

        manga.title = rawTitle.trim()
        manga.setUrlWithoutDomain(link?.attr("abs:href") ?: "")
        manga.thumbnail_url = extractImageUrl(img)
        return manga
    }

    override fun popularMangaNextPageSelector(): String? =
        "div.pagination .next, div.hpage .r, a:has(img[alt=Next]), a.r"

    // =========================== Latest ===========================

    override fun latestUpdatesRequest(page: Int): Request {
        return if (baseUrl.contains("comicasura", ignoreCase = true)) {
            GET("$baseUrl/advanced-search?sort=latest&page=$page", headers)
        } else {
            GET("$baseUrl$mangaUrlDirectory?page=$page&order=update", headers)
        }
    }

    override fun latestUpdatesSelector(): String = popularMangaSelector()

    override fun latestUpdatesFromElement(element: Element): SManga = popularMangaFromElement(element)

    override fun latestUpdatesNextPageSelector(): String? = popularMangaNextPageSelector()

    // =========================== Search ===========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        return if (baseUrl.contains("comicasura", ignoreCase = true)) {
            GET("$baseUrl/advanced-search?name=$encodedQuery&page=$page", headers)
        } else {
            GET("$baseUrl$mangaUrlDirectory?page=$page&title=$encodedQuery", headers)
        }
    }

    override fun searchMangaSelector(): String = popularMangaSelector()

    override fun searchMangaFromElement(element: Element): SManga = popularMangaFromElement(element)

    override fun searchMangaNextPageSelector(): String? = popularMangaNextPageSelector()

    // =========================== Details ===========================

    override fun mangaDetailsParse(document: Document): SManga {
        val manga = SManga.create()
        manga.title = document.select(".comic-title-content, .entry-title, h1.entry-title, .ts-breadcrumb li:last-child span").first()?.text()?.trim()
            ?: document.select("h1").first()?.text()?.trim() ?: ""

        val authorElement = document.select(".infotable tr:contains(Author) td:last-child, .tsinfo .imptdt:contains(Author) i, .fmed b:contains(Author)+span, span:contains(Author)").first()
        manga.author = authorElement?.ownText()?.trim() ?: authorElement?.text()?.trim()

        val artistElement = document.select(".infotable tr:contains(Artist) td:last-child, .tsinfo .imptdt:contains(Artist) i, .fmed b:contains(Artist)+span, span:contains(Artist)").first()
        manga.artist = artistElement?.ownText()?.trim() ?: artistElement?.text()?.trim()

        val desc = document.select(".comic-content.mobile, .desc, .entry-content[itemprop=description]").first()
        manga.description = desc?.text()?.trim()

        val genres = document.select("div.hidden div:contains(Genres) + div > a, div.gnr a, .mgen a, .seriestugenre a").map { it.text().trim() }
        if (genres.isNotEmpty()) {
            manga.genre = genres.joinToString(", ")
        }

        val statusText = document.select("div:contains(Status) + div, .infotable tr:contains(Status) td:last-child, .tsinfo .imptdt:contains(Status) i").text()
        manga.status = when {
            statusText.contains("Ongoing", ignoreCase = true) || statusText.contains("En curso", ignoreCase = true) -> SManga.ONGOING
            statusText.contains("Completed", ignoreCase = true) || statusText.contains("Completo", ignoreCase = true) -> SManga.COMPLETED
            statusText.contains("Hiatus", ignoreCase = true) -> SManga.ON_HIATUS
            statusText.contains("Cancelled", ignoreCase = true) || statusText.contains("Dropped", ignoreCase = true) -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }

        val img = document.select("img[alt=poster], .infomanga > div[itemprop=image] img, .thumb img, .animefull .bigcontent img").first()
        manga.thumbnail_url = extractImageUrl(img)
        manga.initialized = true
        return manga
    }

    // =========================== Chapters ===========================

    override fun chapterListSelector(): String =
        ".chapter-items, div.bxcl li, div.cl li, #chapterlist li, ul li:has(div.chbox):has(div.eph-num), .eplister li"

    override fun chapterFromElement(element: Element): SChapter {
        val chapter = SChapter.create()
        val link = element.select("a").first()
        chapter.setUrlWithoutDomain(link?.attr("abs:href") ?: "")

        val name = element.select(".text-sm.text-white, .lch a, .chapternum").first()?.text()
            ?: link?.text()
            ?: "Chapter"
        chapter.name = name.trim()

        val dateStr = element.selectFirst(".text-xs.text-\\[\\#A2A2A2\\], .chapterdate")?.text()
            ?: element.select(".chapterdate").text()
        chapter.date_upload = parseChapterDate(dateStr)
        return chapter
    }

    private fun parseChapterDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0L
        val cleanDate = dateStr.replace(Regex("(?<=\\d)(st|nd|rd|th)"), "").trim()
        val formats = listOf(
            SimpleDateFormat("MMMM d yyyy", Locale.US),
            SimpleDateFormat("MMMM d, yyyy", Locale.US),
            SimpleDateFormat("yyyy-MM-dd", Locale.US),
            SimpleDateFormat("dd/MM/yyyy", Locale.US),
        )
        for (format in formats) {
            try {
                val parsed = format.parse(cleanDate)
                if (parsed != null) return parsed.time
            } catch (_: Exception) { }
        }
        return 0L
    }

    // =========================== Pages ===========================

    override fun pageListParse(document: Document): List<Page> {
        val pages = mutableListOf<Page>()

        // 1. Try DOM images first
        val imgElements = document.select("div#readerarea img, div > img.object-cover.mx-auto, .reader-area img")
            .filterNot { extractImageUrl(it).isBlank() }

        if (imgElements.isNotEmpty()) {
            imgElements.forEachIndexed { index, img ->
                pages.add(Page(index, imageUrl = extractImageUrl(img)))
            }
            return pages
        }

        // 2. Try ts_reader base64 script or direct JSON
        val script = document.selectFirst("script[src^=data:text/javascript;base64,dHNfcmVhZGVyLnJ1bih7]")
        val scriptData = script?.attr("src")
            ?.substringAfter("base64,")
            ?.let { Base64.decode(it, Base64.DEFAULT).decodeToString() }
            ?: document.select("script:containsData(ts_reader.run)").firstOrNull()?.data()
            ?: document.toString()

        val match = JSON_IMAGE_LIST_REGEX.find(scriptData)
        if (match != null) {
            try {
                val jsonArray = JSONArray(match.groupValues[1])
                for (i in 0 until jsonArray.length()) {
                    val url = jsonArray.getString(i)
                    val absoluteUrl = if (url.startsWith("/")) baseUrl + url else url
                    pages.add(Page(i, imageUrl = absoluteUrl))
                }
                if (pages.isNotEmpty()) return pages
            } catch (_: Exception) { }
        }

        return pages
    }

    override fun imageUrlParse(document: Document): String = ""

    private fun extractImageUrl(element: Element?): String {
        if (element == null) return ""
        return element.attr("abs:data-lazy-src").ifBlank {
            element.attr("abs:data-src").ifBlank {
                element.attr("abs:data-cfsrc").ifBlank {
                    element.attr("abs:src")
                }
            }
        }.trim()
    }

    companion object {
        private val JSON_IMAGE_LIST_REGEX = """["']?(?:images|imageUrls)["']?\s*[:=]\s*(\[.*?])""".toRegex()
    }
}
