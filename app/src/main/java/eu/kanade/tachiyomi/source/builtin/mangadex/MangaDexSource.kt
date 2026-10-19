package eu.kanade.tachiyomi.source.builtin.mangadex

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Built-in native MangaDex source using official MangaDex API v5.
 * Multi-language, zero Cloudflare blocks, and high quality MangaDex@Home reader network.
 */
@Suppress("DEPRECATION")
class MangaDexSource(
    override val id: Long = 80010001L,
    override val lang: String = "en",
) : HttpSource() {

    override val name: String = "MangaDex"
    override val baseUrl: String = "https://mangadex.org"
    override val supportsLatest: Boolean = true

    private val apiBase = "https://api.mangadex.org"
    private val coversBase = "https://uploads.mangadex.org/covers"

    override val client: OkHttpClient
        get() = network.client

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Wammy/1.0 (Android; contact@wammy.app)")
        .add("Referer", "$baseUrl/")

    private val contentRatings = "&contentRating[]=safe&contentRating[]=suggestive&contentRating[]=erotica"

    // =========================== Popular ===========================

    override fun popularMangaRequest(page: Int): Request {
        val offset = (page - 1) * 20
        val langParam = if (lang == "all") "" else "&availableTranslatedLanguage[]=$lang"
        val url = "$apiBase/manga?limit=20&offset=$offset&order[followedCount]=desc&includes[]=cover_art&includes[]=author$contentRatings$langParam"
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val json = JSONObject(response.body.string())
        val data = json.optJSONArray("data") ?: JSONArray()
        val total = json.optInt("total", 0)
        val offset = json.optInt("offset", 0)
        val limit = json.optInt("limit", 20)

        val mangas = mutableListOf<SManga>()
        for (i in 0 until data.length()) {
            val item = data.getJSONObject(i)
            mangas.add(parseMangaFromJson(item))
        }

        val hasNextPage = (offset + limit) < total
        return MangasPage(mangas, hasNextPage)
    }

    // =========================== Latest ===========================

    override fun latestUpdatesRequest(page: Int): Request {
        val offset = (page - 1) * 20
        val langParam = if (lang == "all") "" else "&availableTranslatedLanguage[]=$lang"
        val url = "$apiBase/manga?limit=20&offset=$offset&order[latestUploadedChapter]=desc&includes[]=cover_art&includes[]=author$contentRatings$langParam"
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // =========================== Search ===========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val offset = (page - 1) * 20
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val langParam = if (lang == "all") "" else "&availableTranslatedLanguage[]=$lang"
        val url = "$apiBase/manga?limit=20&offset=$offset&title=$encoded&includes[]=cover_art&includes[]=author$contentRatings$langParam"
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // =========================== Details ===========================

    override fun mangaDetailsRequest(manga: SManga): Request {
        val mangaId = manga.url.removePrefix("/manga/").substringBefore("/")
        val url = "$apiBase/manga/$mangaId?includes[]=cover_art&includes[]=author&includes[]=artist"
        return GET(url, headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val json = JSONObject(response.body.string())
        val data = json.optJSONObject("data") ?: JSONObject()
        return parseMangaFromJson(data)
    }

    // =========================== Chapters ===========================

    override fun chapterListRequest(manga: SManga): Request {
        val mangaId = manga.url.removePrefix("/manga/").substringBefore("/")
        val langParam = if (lang == "all") "" else "&translatedLanguage[]=$lang"
        val url = "$apiBase/manga/$mangaId/feed?limit=500&order[chapter]=desc&includeExternalUrl=0$langParam"
        return GET(url, headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val json = JSONObject(response.body.string())
        val data = json.optJSONArray("data") ?: JSONArray()
        val chapters = mutableListOf<SChapter>()

        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        for (i in 0 until data.length()) {
            val item = data.getJSONObject(i)
            val chapterId = item.optString("id")
            val attr = item.optJSONObject("attributes") ?: JSONObject()

            val chapterNum = attr.optString("chapter", "")
            val title = attr.optString("title", "")
            val name = buildString {
                if (chapterNum.isNotBlank()) append("Ch. $chapterNum")
                if (title.isNotBlank()) {
                    if (isNotEmpty()) append(" - ")
                    append(title)
                }
                if (isEmpty()) append("Chapter")
            }

            val chapter = SChapter.create().apply {
                this.name = name
                this.url = "/chapter/$chapterId"
                this.chapter_number = chapterNum.toFloatOrNull() ?: -1f

                val publishAt = attr.optString("publishAt", "")
                if (publishAt.isNotBlank()) {
                    try {
                        this.date_upload = isoFormat.parse(publishAt.substringBefore("+").substringBefore("Z"))?.time ?: 0L
                    } catch (_: Exception) {
                        this.date_upload = 0L
                    }
                }
            }
            chapters.add(chapter)
        }

        return chapters
    }

    // =========================== Pages ===========================

    override fun pageListRequest(chapter: SChapter): Request {
        val chapterId = chapter.url.removePrefix("/chapter/").substringBefore("/")
        return GET("$apiBase/at-home/server/$chapterId", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val json = JSONObject(response.body.string())
        val serverUrl = json.optString("baseUrl")
        val chapterObj = json.optJSONObject("chapter") ?: JSONObject()
        val hash = chapterObj.optString("hash")
        val data = chapterObj.optJSONArray("data") ?: JSONArray()

        val pages = mutableListOf<Page>()
        for (i in 0 until data.length()) {
            val fileName = data.getString(i)
            val pageUrl = "$serverUrl/data/$hash/$fileName"
            pages.add(Page(i, "", pageUrl))
        }

        return pages
    }

    override fun imageUrlParse(response: Response): String =
        throw UnsupportedOperationException("MangaDex provides direct page image URLs")

    // =========================== Helpers ===========================

    private fun parseMangaFromJson(item: JSONObject): SManga {
        val manga = SManga.create()
        val mangaId = item.optString("id")
        manga.url = "/manga/$mangaId"

        val attr = item.optJSONObject("attributes") ?: JSONObject()

        // Title: try en, ja-ro, or first key
        val titleObj = attr.optJSONObject("title") ?: JSONObject()
        manga.title = titleObj.optString("en", "").ifEmpty {
            titleObj.optString("ja-ro", "").ifEmpty {
                val keys = titleObj.keys()
                if (keys.hasNext()) titleObj.optString(keys.next(), "Unknown") else "Unknown"
            }
        }.trim()

        // Description
        val descObj = attr.optJSONObject("description") ?: JSONObject()
        manga.description = descObj.optString("en", "").ifEmpty {
            val keys = descObj.keys()
            if (keys.hasNext()) descObj.optString(keys.next(), "") else ""
        }.trim()

        // Status
        manga.status = when (attr.optString("status", "").lowercase()) {
            "completed" -> SManga.COMPLETED
            "hiatus" -> SManga.ON_HIATUS
            "cancelled" -> SManga.CANCELLED
            else -> SManga.ONGOING
        }

        // Tags / Genres
        val tagsArray = attr.optJSONArray("tags") ?: JSONArray()
        val genres = mutableListOf<String>()
        for (j in 0 until tagsArray.length()) {
            val tagItem = tagsArray.getJSONObject(j)
            val tagAttr = tagItem.optJSONObject("attributes") ?: JSONObject()
            val tagNameObj = tagAttr.optJSONObject("name") ?: JSONObject()
            val tagName = tagNameObj.optString("en", "")
            if (tagName.isNotBlank()) genres.add(tagName)
        }
        manga.genre = genres.joinToString(", ").ifEmpty { null }

        // Relationships: author, cover_art
        val rels = item.optJSONArray("relationships") ?: JSONArray()
        var authorName: String? = null
        var coverFileName: String? = null

        for (k in 0 until rels.length()) {
            val rel = rels.getJSONObject(k)
            val type = rel.optString("type")
            val relAttr = rel.optJSONObject("attributes") ?: JSONObject()
            if (type == "author" || type == "artist") {
                val name = relAttr.optString("name", "")
                if (name.isNotBlank()) authorName = name
            } else if (type == "cover_art") {
                val file = relAttr.optString("fileName", "")
                if (file.isNotBlank()) coverFileName = file
            }
        }

        manga.author = authorName
        if (!coverFileName.isNullOrBlank()) {
            manga.thumbnail_url = "$coversBase/$mangaId/$coverFileName.512.jpg"
        }

        return manga
    }
}
