package eu.kanade.tachiyomi.source.builtin.mangapill

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * Built-in native MangaPill source.
 * Fast, reliable, clean scans and minimal bloat.
 */
@Suppress("DEPRECATION")
class MangaPillSource(
    override val id: Long = 80010003L,
    override val lang: String = "en",
) : ParsedHttpSource() {

    override val name: String = "MangaPill"
    override val baseUrl: String = "https://mangapill.com"
    override val supportsLatest: Boolean = true

    override val client: OkHttpClient
        get() = network.client

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("Referer", "$baseUrl/")

    // =========================== Popular ===========================

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/search?status=publishing&page=$page", headers)
    }

    override fun popularMangaSelector(): String = "a.relative.block"

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        manga.setUrlWithoutDomain(element.attr("abs:href"))

        val parent = element.parent()
        val titleText = parent?.select("div.font-black, .line-clamp-2")?.first()?.text()
            ?: element.select("img").attr("alt")
        manga.title = titleText.trim().ifEmpty { "Unknown" }

        val img = element.select("img").first()
        manga.thumbnail_url = img?.attr("abs:data-src")?.ifEmpty { img.attr("abs:src") }
        return manga
    }

    override fun popularMangaNextPageSelector(): String = "a[rel=next], .pagination a:contains(Next)"

    // =========================== Latest ===========================

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/search?page=$page", headers)
    }

    override fun latestUpdatesSelector(): String = popularMangaSelector()

    override fun latestUpdatesFromElement(element: Element): SManga = popularMangaFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularMangaNextPageSelector()

    // =========================== Search ===========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        return GET("$baseUrl/search?q=$encoded&page=$page", headers)
    }

    override fun searchMangaSelector(): String = popularMangaSelector()

    override fun searchMangaFromElement(element: Element): SManga = popularMangaFromElement(element)

    override fun searchMangaNextPageSelector(): String = popularMangaNextPageSelector()

    // =========================== Details ===========================

    override fun mangaDetailsParse(document: Document): SManga {
        val manga = SManga.create()

        val title = document.select("h1.font-bold, h1").first()?.text()?.trim()
        if (!title.isNullOrEmpty()) {
            manga.title = title
        }

        manga.description = document.select("p.text--secondary, p.text-sm, .description").first()?.text()?.trim()

        val genres = document.select("a[href*='/search?genre=']").eachText()
        manga.genre = genres.joinToString(", ").ifEmpty { null }

        val statusText = document.select("div:contains(Status)").text().lowercase()
        manga.status = when {
            statusText.contains("completed") -> SManga.COMPLETED
            statusText.contains("hiatus") -> SManga.ON_HIATUS
            else -> SManga.ONGOING
        }

        val img = document.select("div.flex img, img.w-full").first()
        manga.thumbnail_url = img?.attr("abs:data-src")?.ifEmpty { img.attr("abs:src") }

        return manga
    }

    // =========================== Chapters ===========================

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val elements = document.select("#chapters a, a[href*='/chapters/']")

        return elements.map { element ->
            val chapter = SChapter.create()
            chapter.name = element.text().trim()
            chapter.setUrlWithoutDomain(element.attr("abs:href"))

            val numRegex = Regex("(\\d+(\\.\\d+)?)").findAll(chapter.name).lastOrNull()
            chapter.chapter_number = numRegex?.value?.toFloatOrNull() ?: -1f
            chapter
        }
    }

    override fun chapterFromElement(element: Element): SChapter =
        throw UnsupportedOperationException("Parsed in chapterListParse")

    override fun chapterListSelector(): String =
        throw UnsupportedOperationException("Parsed in chapterListParse")

    // =========================== Pages ===========================

    override fun pageListParse(document: Document): List<Page> {
        val pages = mutableListOf<Page>()
        val imgElements = document.select("chapter-page picture img, chapter-page img, .chapter-container img")

        var index = 0
        imgElements.forEach { img ->
            val src = img.attr("abs:data-src").ifEmpty { img.attr("abs:src") }
            if (src.isNotBlank()) {
                pages.add(Page(index++, "", src))
            }
        }
        return pages
    }

    override fun imageUrlParse(document: Document): String =
        throw UnsupportedOperationException("Not used with direct Page image URLs")
}
