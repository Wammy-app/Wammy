package eu.kanade.tachiyomi.source.builtin.webtoon

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.parseHumanCount
import eu.kanade.tachiyomi.source.model.rating
import eu.kanade.tachiyomi.source.model.views
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.URLEncoder
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Built-in native autonomous Webtoons source for LINE Webtoon (webtoons.com).
 * Features:
 * - High-speed trending & daily schedule browsing
 * - Mobile API acceleration for instant chapter list parsing
 * - Automatic Referer spoofing to prevent 403 blocks from webtoon-phinf CDN
 */
@Suppress("DEPRECATION")
class WebtoonsSource(
    override val id: Long = 80010015L,
    override val baseUrl: String = "https://www.webtoons.com",
    override val lang: String = "en",
) : ParsedHttpSource() {

    override val name: String = "Webtoons"
    override val supportsLatest: Boolean = true

    override val client: OkHttpClient
        get() = network.client

    private val mobileUrl = "https://m.webtoons.com"

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

    override fun imageRequest(page: Page): Request {
        val newHeaders = headersBuilder()
            .set("Referer", "$baseUrl/")
            .build()
        return GET(page.imageUrl!!, newHeaders)
    }

    // =========================== Popular ===========================

    override fun popularMangaRequest(page: Int): Request {
        val ranking = when (page) {
            1 -> "trending"
            2 -> "popular"
            3 -> "originals"
            4 -> "canvas"
            else -> "trending"
        }
        return GET("$baseUrl/en/ranking/$ranking", headers)
    }

    override fun popularMangaSelector(): String =
        ".webtoon_list li a, div.ranking_lst ul li, ul.webtoon_lst li, ul.card_lst li"

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        val link = if (element.tagName() == "a") element else element.selectFirst("a")
        manga.setUrlWithoutDomain(link?.attr("abs:href") ?: "")

        val title = element.selectFirst(".title, .subj, .tit, p.subj")?.text()
            ?: link?.attr("title")
            ?: "Unknown"
        manga.title = title.trim()

        val img = element.selectFirst("img")
        manga.thumbnail_url = img?.attr("abs:src")?.ifEmpty { img.attr("abs:data-url") }

        val viewsEl = element.selectFirst(".view_count, .count, .cnt, .type_like")
        if (viewsEl != null) {
            parseHumanCount(viewsEl.text().trim())?.let { manga.views = it }
        }
        val scoreEl = element.selectFirst(".grade_num, .rating, .score")
        val rawScore = scoreEl?.text()?.toDoubleOrNull()
        if (rawScore != null && rawScore > 0.0) {
            manga.rating = if (rawScore <= 5.0) rawScore * 2.0 else rawScore
        }
        return manga
    }

    override fun popularMangaNextPageSelector(): String? = null

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val elements = document.select(popularMangaSelector())
        val mangas = elements.mapNotNull {
            try {
                popularMangaFromElement(it)
            } catch (_: Exception) {
                null
            }
        }.distinctBy { it.url }
        return MangasPage(mangas, mangas.isNotEmpty())
    }

    // =========================== Latest ===========================

    override fun latestUpdatesRequest(page: Int): Request {
        val day = when (Calendar.getInstance().get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> "monday"
            Calendar.TUESDAY -> "tuesday"
            Calendar.WEDNESDAY -> "wednesday"
            Calendar.THURSDAY -> "thursday"
            Calendar.FRIDAY -> "friday"
            Calendar.SATURDAY -> "saturday"
            Calendar.SUNDAY -> "sunday"
            else -> "monday"
        }
        return GET("$baseUrl/en/originals/$day?sortOrder=UPDATE", headers)
    }

    override fun latestUpdatesSelector(): String = popularMangaSelector()

    override fun latestUpdatesFromElement(element: Element): SManga = popularMangaFromElement(element)

    override fun latestUpdatesNextPageSelector(): String? = null

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // =========================== Search ===========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        return GET("$baseUrl/en/search?keyword=$encoded", headers)
    }

    override fun searchMangaSelector(): String =
        ".webtoon_list li a, ul.card_lst li, div.card_wrap ul li, div.search_result ul li"

    override fun searchMangaFromElement(element: Element): SManga = popularMangaFromElement(element)

    override fun searchMangaNextPageSelector(): String? = "a.pagination[aria-current=true] + a"

    // =========================== Details ===========================

    override fun mangaDetailsParse(document: Document): SManga {
        val manga = SManga.create()

        val title = document.selectFirst("h1.subj, h3.subj, .subj, div.info h1")?.text()?.trim()
        if (!title.isNullOrEmpty()) {
            manga.title = title
        }

        val detailElement = document.selectFirst(".detail_header .info")
        manga.author = detailElement?.selectFirst(".author:nth-of-type(1)")?.ownText()
            ?: detailElement?.selectFirst(".author_area")?.ownText()
            ?: document.selectFirst("div.author_area, div.author, a.author")?.text()?.trim()

        manga.artist = detailElement?.selectFirst(".author:nth-of-type(2)")?.ownText()
            ?: detailElement?.selectFirst(".author_area")?.ownText()
            ?: manga.author

        val infoElement = document.selectFirst("#_asideDetail")
        manga.description = infoElement?.selectFirst("p.summary")?.text()?.trim()
            ?: document.selectFirst("p.summary, div.summary, .desc")?.text()?.trim()

        manga.genre = detailElement?.select(".genre")?.joinToString { it.text() }
            ?: document.selectFirst("h2.genre, .genre, a.genre")?.text()?.trim()

        manga.status = with(infoElement?.selectFirst("p.day_info")?.text().orEmpty()) {
            when {
                contains("UP", ignoreCase = true) || contains("EVERY", ignoreCase = true) -> SManga.ONGOING
                contains("COMPLETED", ignoreCase = true) || contains("END", ignoreCase = true) -> SManga.COMPLETED
                else -> SManga.ONGOING
            }
        }

        val banner = document.selectFirst(".detail_header .thmb img, div.detail_header img, .pic img, div.poster img")
        val ogImage = document.selectFirst("head meta[property='og:image']")?.attr("content")
        manga.thumbnail_url = ogImage?.ifEmpty { null }
            ?: banner?.attr("abs:src")?.ifEmpty { banner.attr("abs:data-url") }

        val viewsEl = document.selectFirst("ul.grade_area li:has(.ico_view) .cnt, ul.grade_area li:has(.ico_subscribe) .cnt, .grade_area .cnt")
        if (viewsEl != null) {
            parseHumanCount(viewsEl.text().trim())?.let { manga.views = it }
        }
        val gradeEl = document.selectFirst(".grade_area .grade_num, em.grade_num, #_starScoreAverage")
        val rawGrade = gradeEl?.text()?.toDoubleOrNull()
        if (rawGrade != null && rawGrade > 0.0) {
            manga.rating = if (rawGrade <= 5.0) rawGrade * 2.0 else rawGrade
        }

        return manga
    }

    // =========================== Chapters ===========================

    override fun chapterListRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url, headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val url = response.request.url
        val titleId = url.queryParameter("title_no") ?: url.queryParameter("titleNo")

        if (!titleId.isNullOrBlank()) {
            try {
                val isCanvas = url.encodedPath.contains("canvas", ignoreCase = true) ||
                    url.encodedPath.contains("challenge", ignoreCase = true)
                val type = if (isCanvas) "canvas" else "webtoon"

                val mobileHeaders = Headers.Builder()
                    .add("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                    .add("Referer", "$mobileUrl/")
                    .build()

                val apiReqUrl = mobileUrl.toHttpUrl().newBuilder()
                    .addPathSegments("api/v1")
                    .addPathSegment(type)
                    .addPathSegment(titleId)
                    .addPathSegment("episodes")
                    .addQueryParameter("pageSize", "99999")
                    .apply {
                        if (isCanvas) addQueryParameter("readingLanguageCode", lang)
                    }
                    .build()

                val apiResp = client.newCall(GET(apiReqUrl.toString(), mobileHeaders)).execute()
                if (apiResp.isSuccessful) {
                    val body = apiResp.body.string()
                    val json = JSONObject(body)
                    if (json.optBoolean("success", false) && json.has("result")) {
                        val result = json.getJSONObject("result")
                        val episodeList = result.optJSONArray("episodeList")
                        if (episodeList != null && episodeList.length() > 0) {
                            val chapters = mutableListOf<SChapter>()
                            val numberFormatter = DecimalFormat("#.##")

                            for (i in 0 until episodeList.length()) {
                                val ep = episodeList.getJSONObject(i)
                                val chapter = SChapter.create()
                                chapter.setUrlWithoutDomain(ep.optString("viewerLink"))

                                val epTitle = Parser.unescapeEntities(ep.optString("episodeTitle"), false)
                                val epNo = ep.optInt("episodeNo", i + 1)
                                chapter.name = "$epTitle (ch. ${numberFormatter.format(epNo)})"
                                chapter.date_upload = ep.optLong("exposureDateMillis", 0L)
                                chapter.chapter_number = epNo.toFloat()
                                chapters.add(chapter)
                            }
                            return chapters.reversed()
                        }
                    }
                }
            } catch (_: Exception) {
                // Fallback to HTML parsing if API fails
            }
        }

        val document = response.asJsoup()
        val elements = document.select("div.detail_body ul li[id^=episode_], ul._episode_list li")
        val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)

        return elements.mapNotNull { element ->
            val link = element.selectFirst("a") ?: return@mapNotNull null
            val chapter = SChapter.create()
            chapter.name = element.selectFirst(".sub_title .subj, span.subj, .name")?.text()?.trim()
                ?: link.text().trim()
            chapter.setUrlWithoutDomain(link.attr("abs:href"))

            val dateStr = element.selectFirst(".date, span.date")?.text()?.trim()
            if (!dateStr.isNullOrBlank()) {
                try {
                    chapter.date_upload = dateFormat.parse(dateStr)?.time ?: 0L
                } catch (_: Exception) {
                    chapter.date_upload = 0L
                }
            }
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
        val imgElements = document.select("div#_imageList > img, div._imageList img, div.viewer_img img, div#_viewerBox img")

        var index = 0
        imgElements.forEach { img ->
            val src = img.attr("abs:data-url").ifEmpty { img.attr("abs:src") }
            if (src.isNotBlank() && !src.contains("bg_trans", ignoreCase = true)) {
                pages.add(Page(index++, "", src))
            }
        }
        return pages
    }

    override fun imageUrlParse(document: Document): String =
        throw UnsupportedOperationException("Not used with direct Page image URLs")
}
