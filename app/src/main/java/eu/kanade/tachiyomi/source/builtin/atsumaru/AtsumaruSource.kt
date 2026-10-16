package eu.kanade.tachiyomi.source.builtin.atsumaru

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
import java.time.Instant

/**
 * Built-in native Atsumaru source (atsu.moe).
 * Uses Atsumaru's Typesense and REST APIs for blazing fast loading.
 */
@Suppress("DEPRECATION")
class AtsumaruSource(
    override val id: Long = 80010009L,
    override val lang: String = "en",
) : HttpSource() {

    override val name: String = "Atsumaru"
    override val baseUrl: String = "https://atsu.moe"
    override val supportsLatest: Boolean = true

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .build()
    }

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("Referer", "$baseUrl/")
        .add("Accept", "application/json, text/plain, */*")

    override fun imageRequest(page: Page): Request {
        val imgHeaders = headersBuilder()
            .set("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            .build()
        return GET(page.imageUrl!!, imgHeaders)
    }

    // =========================== Popular ===========================

    override fun popularMangaRequest(page: Int): Request {
        val offset = (page - 1) * BROWSE_LIMIT
        return GET(
            "$baseUrl/api/home2/popular?offset=$offset&limit=$BROWSE_LIMIT&types=Manga,Manwha,Manhua,OEL&mediums=Comic&timeframe=daily",
            headers,
        )
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val json = JSONObject(response.body.string())
        val items = json.optJSONArray("items") ?: JSONArray()
        val mangas = mutableListOf<SManga>()

        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            mangas.add(parseMangaItem(item))
        }

        return MangasPage(mangas, hasNextPage = mangas.size >= BROWSE_LIMIT)
    }

    // =========================== Latest ===========================

    override fun latestUpdatesRequest(page: Int): Request {
        val offset = (page - 1) * BROWSE_LIMIT
        return GET(
            "$baseUrl/api/home2/recentlyUpdated?offset=$offset&limit=$BROWSE_LIMIT&types=Manga,Manwha,Manhua,OEL&mediums=Comic",
            headers,
        )
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // =========================== Search ===========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val cleanQuery = if (query.isBlank()) "*" else query.trim()
        val encodedQuery = URLEncoder.encode(cleanQuery, "UTF-8")
        val url = "$baseUrl/collections/manga/documents/search?q=$encodedQuery&page=$page&per_page=$BROWSE_LIMIT" +
            "&filter_by=hidden:!=true%20%26%26%20medium:!=[%60Novel%60]%20%26%26%20views:>0" +
            "&query_by=title,englishTitle,otherNames,authors&query_by_weights=4,3,2,1&num_typos=4,3,2,1"
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val bodyStr = response.body.string()
        val json = JSONObject(bodyStr)
        val mangas = mutableListOf<SManga>()

        if (json.has("hits")) {
            val hits = json.getJSONArray("hits")
            val found = json.optInt("found", 0)
            val page = json.optInt("page", 1)

            for (i in 0 until hits.length()) {
                val hit = hits.getJSONObject(i)
                val doc = hit.optJSONObject("document") ?: continue
                mangas.add(parseMangaItem(doc))
            }
            val hasNext = page * BROWSE_LIMIT < found
            return MangasPage(mangas, hasNext)
        } else if (json.has("items")) {
            val items = json.getJSONArray("items")
            for (i in 0 until items.length()) {
                mangas.add(parseMangaItem(items.getJSONObject(i)))
            }
            return MangasPage(mangas, mangas.size >= BROWSE_LIMIT)
        }

        return MangasPage(emptyList(), false)
    }

    // =========================== Details ===========================

    override fun mangaDetailsRequest(manga: SManga): Request {
        val mangaId = manga.url.removePrefix("/manga/").removePrefix("/")
        return GET("$baseUrl/api/manga/page?id=$mangaId", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val json = JSONObject(response.body.string())
        val mangaObj = json.optJSONObject("mangaPage") ?: json
        return parseMangaItem(mangaObj).apply {
            initialized = true
        }
    }

    // =========================== Chapters ===========================

    override fun chapterListRequest(manga: SManga): Request {
        val mangaId = manga.url.removePrefix("/manga/").removePrefix("/")
        return GET("$baseUrl/api/manga/allChapters?mangaId=$mangaId", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val json = JSONObject(response.body.string())
        val chaptersArray = json.optJSONArray("chapters") ?: JSONArray()
        val chapters = mutableListOf<SChapter>()

        for (i in 0 until chaptersArray.length()) {
            val c = chaptersArray.getJSONObject(i)
            val chapter = SChapter.create()
            val id = c.optString("id")
            val slug = c.optString("slug").ifBlank { c.optString("mangaId") }
            val finalSlug = if (slug.isNotBlank()) slug else c.optString("manga_id")

            chapter.url = if (finalSlug.isNotBlank()) "$finalSlug/$id" else id
            val num = c.optDouble("number", 0.0)
            chapter.chapter_number = num.toFloat()

            val title = c.optString("title", "")
            chapter.name = if (title.isNotBlank()) title else "Chapter $num"

            val dateStr = c.optString("createdAt", "")
            if (dateStr.isNotBlank()) {
                chapter.date_upload = runCatching { Instant.parse(dateStr).toEpochMilli() }.getOrDefault(0L)
            } else {
                chapter.date_upload = c.optLong("createdAt", 0L)
            }
            chapters.add(chapter)
        }

        return chapters.sortedWith(
            compareByDescending<SChapter> { it.chapter_number }
                .thenByDescending { it.date_upload },
        )
    }

    // =========================== Pages ===========================

    override fun pageListRequest(chapter: SChapter): Request {
        val parts = chapter.url.split("/")
        val mangaId = parts.firstOrNull() ?: ""
        val chapterId = if (parts.size > 1) parts[1] else parts[0]
        return GET("$baseUrl/api/read/chapter?mangaId=$mangaId&chapterId=$chapterId", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val json = JSONObject(response.body.string())
        val readChapter = json.optJSONObject("readChapter") ?: json
        val pagesArray = readChapter.optJSONArray("pages") ?: JSONArray()
        val pages = mutableListOf<Page>()

        for (i in 0 until pagesArray.length()) {
            val pageObj = pagesArray.getJSONObject(i)
            val rawImage = pageObj.optString("image", "")
            if (rawImage.isBlank()) continue

            val imageUrl = when {
                rawImage.startsWith("http") -> rawImage
                rawImage.startsWith("//") -> "https:$rawImage"
                else -> "$baseUrl/static/${rawImage.removePrefix("/").removePrefix("static/")}"
            }.replaceFirst(Regex("^https?:?//"), "https://cdn.")

            pages.add(Page(i, imageUrl = imageUrl))
        }

        return pages
    }

    override fun imageUrlParse(response: Response): String = ""

    // =========================== Utilities ===========================

    private fun parseMangaItem(item: JSONObject): SManga {
        val manga = SManga.create()
        val id = item.optString("id", "")
        manga.url = id

        val rawTitle = item.optString("title").ifBlank {
            item.optString("englishTitle")
        }
        manga.title = rawTitle.trim()

        val largeImage = item.optString("largeImage", "")
        val poster = if (largeImage.isNotBlank()) {
            largeImage
        } else {
            val posterObj = item.optJSONObject("poster")
            posterObj?.optString("largeImage") ?: posterObj?.optString("image") ?: item.optString("poster", "")
        }

        if (poster.isNotBlank()) {
            val cleanPoster = poster.removePrefix("/").removePrefix("static/")
            manga.thumbnail_url = when {
                cleanPoster.startsWith("http") -> cleanPoster
                cleanPoster.startsWith("//") -> "https:$cleanPoster"
                else -> "$baseUrl/static/$cleanPoster"
            }.replaceFirst(Regex("^https?:?//"), "https://")
        }

        manga.description = item.optString("synopsis", "")

        val authorsArray = item.optJSONArray("authors")
        if (authorsArray != null && authorsArray.length() > 0) {
            val authorsList = mutableListOf<String>()
            for (i in 0 until authorsArray.length()) {
                val a = authorsArray.opt(i)
                if (a is JSONObject) {
                    authorsList.add(a.optString("name", ""))
                } else if (a is String) {
                    authorsList.add(a)
                }
            }
            manga.author = authorsList.filter { it.isNotBlank() }.joinToString(", ")
        }

        val genresArray = item.optJSONArray("genres") ?: item.optJSONArray("tags")
        if (genresArray != null && genresArray.length() > 0) {
            val genresList = mutableListOf<String>()
            for (i in 0 until genresArray.length()) {
                val g = genresArray.opt(i)
                if (g is JSONObject) {
                    genresList.add(g.optString("name", ""))
                } else if (g is String) {
                    genresList.add(g)
                }
            }
            manga.genre = genresList.filter { it.isNotBlank() }.joinToString(", ")
        }

        val statusStr = item.optString("status", "").lowercase().trim()
        manga.status = when (statusStr) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            "hiatus" -> SManga.ON_HIATUS
            "canceled", "cancelled" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }

        return manga
    }

    companion object {
        private const val BROWSE_LIMIT = 40
    }
}
