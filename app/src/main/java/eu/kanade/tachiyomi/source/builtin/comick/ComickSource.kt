package eu.kanade.tachiyomi.source.builtin.comick

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
 * Built-in native ComicK source using official ComicK REST API.
 * High quality manhwa and webtoon images with fast direct CDN loading.
 */
@Suppress("DEPRECATION")
class ComickSource(
    override val id: Long = 80010002L,
    override val lang: String = "en",
) : HttpSource() {

    override val name: String = "ComicK"
    override val baseUrl: String = "https://comick.dev"
    override val supportsLatest: Boolean = true

    private val apiBase = "https://api.comick.dev"
    private val cdnBase = "https://meo.comick.pictures"

    override val client: OkHttpClient
        get() = network.client

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
        .add("Referer", "$baseUrl/")

    // =========================== Popular ===========================

    override fun popularMangaRequest(page: Int): Request {
        val url = "$apiBase/v1.0/search?type=comic&tachiyomi=true&limit=24&page=$page&sort=view"
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val jsonArray = JSONArray(response.body.string())
        val mangas = mutableListOf<SManga>()

        for (i in 0 until jsonArray.length()) {
            val item = jsonArray.getJSONObject(i)
            val manga = parseMangaFromJson(item)
            mangas.add(manga)
        }

        val hasNextPage = jsonArray.length() >= 24
        return MangasPage(mangas, hasNextPage)
    }

    // =========================== Latest ===========================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$apiBase/v1.0/search?type=comic&tachiyomi=true&limit=24&page=$page&sort=uploaded"
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // =========================== Search ===========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "$apiBase/v1.0/search?type=comic&tachiyomi=true&limit=24&page=$page&q=$encoded"
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // =========================== Details ===========================

    override fun mangaDetailsRequest(manga: SManga): Request {
        val slug = manga.url.removePrefix("/comic/").substringBefore("/")
        val url = "$apiBase/comic/$slug?tachiyomi=true"
        return GET(url, headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val json = JSONObject(response.body.string())
        val comic = json.optJSONObject("comic") ?: json
        val manga = SManga.create()

        val slug = comic.optString("slug", "")
        manga.url = "/comic/$slug"
        manga.title = comic.optString("title", "Unknown").trim()
        manga.description = comic.optString("desc", "").trim()

        val statusVal = comic.optInt("status", 1)
        manga.status = when (statusVal) {
            2 -> SManga.COMPLETED
            3 -> SManga.CANCELLED
            4 -> SManga.ON_HIATUS
            else -> SManga.ONGOING
        }

        val mdCovers = comic.optJSONArray("md_covers")
        val b2key = mdCovers?.optJSONObject(0)?.optString("b2key")
        if (!b2key.isNullOrBlank()) {
            manga.thumbnail_url = "$cdnBase/$b2key"
        }

        // Genres
        val mdGenres = comic.optJSONArray("md_comic_md_genres")
        val genres = mutableListOf<String>()
        if (mdGenres != null) {
            for (i in 0 until mdGenres.length()) {
                val g = mdGenres.getJSONObject(i).optJSONObject("md_genres")
                val name = g?.optString("name")
                if (!name.isNullOrBlank()) genres.add(name)
            }
        }
        manga.genre = genres.joinToString(", ").ifEmpty { null }

        // Authors
        val authors = comic.optJSONArray("authors")
        val authorList = mutableListOf<String>()
        if (authors != null) {
            for (i in 0 until authors.length()) {
                val name = authors.getJSONObject(i).optString("name")
                if (name.isNotBlank()) authorList.add(name)
            }
        }
        manga.author = authorList.joinToString(", ").ifEmpty { null }

        return manga
    }

    // =========================== Chapters ===========================

    override fun chapterListRequest(manga: SManga): Request {
        val slug = manga.url.removePrefix("/comic/").substringBefore("/")
        val langParam = if (lang == "all") "" else "&lang=$lang"
        val url = "$apiBase/comic/$slug/chapters?limit=1000$langParam"
        return GET(url, headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val json = JSONObject(response.body.string())
        val chaptersArray = json.optJSONArray("chapters") ?: JSONArray()
        val chapters = mutableListOf<SChapter>()

        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        for (i in 0 until chaptersArray.length()) {
            val item = chaptersArray.getJSONObject(i)
            val hid = item.optString("hid")
            val chap = item.optString("chap", "")
            val title = item.optString("title", "")

            val name = buildString {
                if (chap.isNotBlank()) append("Ch. $chap")
                if (title.isNotBlank()) {
                    if (isNotEmpty()) append(" - ")
                    append(title)
                }
                if (isEmpty()) append("Chapter")
            }

            val chapter = SChapter.create().apply {
                this.name = name
                this.url = "/chapter/$hid"
                this.chapter_number = chap.toFloatOrNull() ?: -1f

                val createdAt = item.optString("created_at", "")
                if (createdAt.isNotBlank()) {
                    try {
                        this.date_upload = isoFormat.parse(createdAt.substringBefore("."))?.time ?: 0L
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
        val hid = chapter.url.removePrefix("/chapter/").substringBefore("/")
        val url = "$apiBase/chapter/$hid?tachiyomi=true"
        return GET(url, headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val json = JSONObject(response.body.string())
        val chapterObj = json.optJSONObject("chapter") ?: JSONObject()
        val images = chapterObj.optJSONArray("images") ?: JSONArray()

        val pages = mutableListOf<Page>()
        for (i in 0 until images.length()) {
            val img = images.getJSONObject(i)
            val b2key = img.optString("b2key")
            val url = if (b2key.isNotBlank()) "$cdnBase/$b2key" else img.optString("url")
            if (url.isNotBlank()) {
                pages.add(Page(i, "", url))
            }
        }
        return pages
    }

    override fun imageUrlParse(response: Response): String =
        throw UnsupportedOperationException("ComicK provides direct image URLs")

    // =========================== Helpers ===========================

    private fun parseMangaFromJson(item: JSONObject): SManga {
        val manga = SManga.create()
        val slug = item.optString("slug", item.optString("hid", ""))
        manga.url = "/comic/$slug"
        manga.title = item.optString("title", "Untitled").trim()

        val coverUrl = item.optString("cover_url", "")
        if (coverUrl.isNotBlank()) {
            manga.thumbnail_url = coverUrl
        } else {
            val mdCovers = item.optJSONArray("md_covers")
            val b2key = mdCovers?.optJSONObject(0)?.optString("b2key")
            if (!b2key.isNullOrBlank()) {
                manga.thumbnail_url = "$cdnBase/$b2key"
            }
        }

        return manga
    }
}
