package eu.kanade.tachiyomi.source.builtin.comick

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.rating
import eu.kanade.tachiyomi.source.model.views
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.time.Instant

/**
 * Built-in native autonomous ComicK source (comick.art).
 * Uses ComicK's REST API and direct JSON extraction for high performance.
 */
@Suppress("DEPRECATION")
class ComickSource(
    override val id: Long = 80010014L,
    override val baseUrl: String = "https://comick.art",
    override val lang: String = "en",
) : HttpSource() {

    override val name: String = "ComicK"
    override val supportsLatest: Boolean = true

    override val client: OkHttpClient
        get() = network.client

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

    override fun imageRequest(page: Page): Request {
        val imgHeaders = headersBuilder()
            .set("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            .build()
        return GET(page.imageUrl!!, imgHeaders)
    }

    // =========================== Popular ===========================

    override fun popularMangaRequest(page: Int): Request {
        val days = when (page) {
            1 -> 7
            2 -> 30
            3 -> 90
            else -> 7
        }
        val type = if (page <= 3) "follow" else "most_follow_new"
        return GET("$baseUrl/api/comics/top?days=$days&type=$type", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val bodyStr = response.body.string()
        val json = JSONObject(bodyStr)
        val dataArray = json.optJSONArray("data") ?: JSONArray()
        val mangas = mutableListOf<SManga>()

        for (i in 0 until dataArray.length()) {
            val item = dataArray.getJSONObject(i)
            val slug = item.optString("slug")
            val title = item.optString("title")
            val thumb = item.optString("default_thumbnail")

            if (slug.isNotBlank() && title.isNotBlank()) {
                val manga = SManga.create()
                manga.url = "/comic/$slug"
                manga.title = title
                manga.thumbnail_url = thumb

                val bStr = item.optString("bayesian_rating", "")
                val bayesian = bStr.toDoubleOrNull() ?: item.optDouble("bayesian_rating", 0.0)
                val rStr = item.optString("rating", "")
                val rVal = rStr.toDoubleOrNull() ?: item.optDouble("rating", 0.0)
                val rating = if (bayesian > 0.0) bayesian else rVal
                if (rating > 0.0) {
                    manga.rating = rating
                }

                val followCount = item.optLong("user_follow_count", 0L).takeIf { it > 0 }
                    ?: item.optLong("follow_count", 0L).takeIf { it > 0 }
                    ?: item.optLong("view_count", 0L).takeIf { it > 0 }
                    ?: item.optLong("rating_count", 0L).takeIf { it > 0 }
                    ?: item.optString("user_follow_count", "").replace(",", "").toLongOrNull()?.takeIf { it > 0 }
                    ?: item.optString("view_count", "").replace(",", "").toLongOrNull()?.takeIf { it > 0 }
                if (followCount != null && followCount > 0L) {
                    manga.views = followCount
                }

                mangas.add(manga)
            }
        }

        return MangasPage(mangas, hasNextPage = mangas.isNotEmpty())
    }

    // =========================== Latest ===========================

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/api/chapters/latest?order=new&page=$page", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // =========================== Search ===========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) {
            return popularMangaRequest(page)
        }
        val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
        return GET("$baseUrl/api/search?q=$encoded&type=comic&page=$page", headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // =========================== Details ===========================

    override fun mangaDetailsRequest(manga: SManga): Request {
        val path = if (manga.url.startsWith("http")) manga.url else "$baseUrl${manga.url}"
        return GET(path, headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        var document = response.asJsoup()

        // Handle meta-refresh redirects if landing on an outdated slug
        if (document.selectFirst("#comic-data") == null) {
            val metaRefresh = document.selectFirst("meta[http-equiv=refresh]")?.attr("content")
            val refreshUrl = metaRefresh?.substringAfter("url='", "")?.substringBefore("'", "")
            if (!refreshUrl.isNullOrBlank()) {
                val target = if (refreshUrl.startsWith("http")) refreshUrl else "$baseUrl$refreshUrl"
                val redirected = client.newCall(GET(target, headers)).execute()
                document = redirected.asJsoup()
            }
        }

        val manga = SManga.create()
        val comicDataEl = document.selectFirst("#comic-data, script#__NEXT_DATA__")
        val comicDataScript = comicDataEl?.let { it.data().ifBlank { it.html() }.ifBlank { it.wholeText() } }?.trim()

        if (!comicDataScript.isNullOrBlank()) {
            val rawObj = JSONObject(comicDataScript)
            val json = if (comicDataEl?.id() == "comic-data" || !rawObj.has("props")) {
                rawObj
            } else {
                rawObj.optJSONObject("props")?.optJSONObject("pageProps")?.optJSONObject("comic") ?: rawObj
            }
            val slug = json.optString("slug")
            if (slug.isNotBlank()) {
                manga.url = "/comic/$slug"
            }
            manga.title = json.optString("title", "Unknown")
            manga.thumbnail_url = json.optString("default_thumbnail")

            val statusInt = json.optInt("status", 1)
            val translationCompleted = json.optBoolean("translation_completed", false)
            manga.status = when (statusInt) {
                1 -> SManga.ONGOING
                2 -> if (translationCompleted) SManga.COMPLETED else SManga.PUBLISHING_FINISHED
                3 -> SManga.CANCELLED
                4 -> SManga.ON_HIATUS
                else -> SManga.ONGOING
            }

            val authors = json.optJSONArray("authors")
            if (authors != null) {
                val list = mutableListOf<String>()
                for (i in 0 until authors.length()) {
                    list.add(authors.getJSONObject(i).optString("name"))
                }
                manga.author = list.joinToString(", ")
            }

            val artists = json.optJSONArray("artists")
            if (artists != null) {
                val list = mutableListOf<String>()
                for (i in 0 until artists.length()) {
                    list.add(artists.getJSONObject(i).optString("name"))
                }
                manga.artist = list.joinToString(", ")
            }

            val descRaw = json.optString("desc", "")
            if (descRaw.isNotBlank()) {
                manga.description = Jsoup.parseBodyFragment(descRaw).wholeText().trim()
            }

            val genres = json.optJSONArray("md_comic_md_genres")
            if (genres != null) {
                val genreList = mutableListOf<String>()
                when (json.optString("country")) {
                    "jp" -> genreList.add("Manga")
                    "cn" -> genreList.add("Manhua")
                    "ko" -> genreList.add("Manhwa")
                }
                for (i in 0 until genres.length()) {
                    val g = genres.getJSONObject(i).optJSONObject("md_genres")?.optString("name")
                    if (!g.isNullOrBlank()) genreList.add(g)
                }
                manga.genre = genreList.joinToString(", ")
            }

            val bStr = json.optString("bayesian_rating", "")
            val bayesian = bStr.toDoubleOrNull() ?: json.optDouble("bayesian_rating", 0.0)
            val rStr = json.optString("rating", "")
            val rVal = rStr.toDoubleOrNull() ?: json.optDouble("rating", 0.0)
            val rating = if (bayesian > 0.0) bayesian else rVal
            if (rating > 0.0) {
                manga.rating = rating
            }

            val followCount = json.optLong("user_follow_count", 0L).takeIf { it > 0 }
                ?: json.optLong("follow_count", 0L).takeIf { it > 0 }
                ?: json.optLong("view_count", 0L).takeIf { it > 0 }
                ?: json.optLong("rating_count", 0L).takeIf { it > 0 }
                ?: json.optString("user_follow_count", "").replace(",", "").toLongOrNull()?.takeIf { it > 0 }
                ?: json.optString("view_count", "").replace(",", "").toLongOrNull()?.takeIf { it > 0 }
            if (followCount != null && followCount > 0L) {
                manga.views = followCount
            }
        } else {
            // Fallback to meta tags
            manga.title = document.selectFirst("h1, meta[property='og:title']")?.let {
                it.attr("content").ifEmpty { it.text() }
            } ?: "Unknown"
            manga.description = document.selectFirst("meta[property='og:description']")?.attr("content")
            manga.thumbnail_url = document.selectFirst("meta[property='og:image']")?.attr("content")
            manga.status = SManga.ONGOING
        }

        return manga
    }

    // =========================== Chapters ===========================

    override fun chapterListRequest(manga: SManga): Request {
        val cleanUrl = manga.url.removePrefix("/comic/").removeSuffix("/")
        val slug = cleanUrl.substringBefore("/")
        return GET("$baseUrl/api/comics/$slug/chapter-list?lang=en", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val requestUrl = response.request.url.toString()
        val slug = requestUrl.substringAfter("/api/comics/").substringBefore("/chapter-list")

        val bodyStr = response.body.string()
        val json = JSONObject(bodyStr)
        val dataArray = json.optJSONArray("data") ?: JSONArray()
        val chapters = mutableListOf<SChapter>()

        fun parseArray(arr: JSONArray) {
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val lang = item.optString("lang", "en")
                if (lang != "en") continue

                val hid = item.optString("hid")
                val chap = item.optString("chap")
                val vol = item.optString("vol").takeUnless { it == "null" || it.isBlank() }
                val title = item.optString("title").takeUnless { it == "null" || it.isBlank() }
                val createdAt = item.optString("created_at")
                val groupNames = item.optJSONArray("group_name")

                val chapter = SChapter.create()
                chapter.url = "/comic/$slug/$hid-chapter-$chap-$lang"
                chapter.name = buildString {
                    if (!vol.isNullOrBlank()) append("Vol. $vol ")
                    append("Ch. $chap")
                    if (!title.isNullOrBlank()) append(": $title")
                }
                if (createdAt.isNotBlank()) {
                    try {
                        chapter.date_upload = Instant.parse(createdAt).toEpochMilli()
                    } catch (_: Exception) {
                        chapter.date_upload = 0L
                    }
                }
                if (groupNames != null && groupNames.length() > 0) {
                    val groups = mutableListOf<String>()
                    for (g in 0 until groupNames.length()) {
                        groups.add(groupNames.getString(g))
                    }
                    chapter.scanlator = groups.joinToString(", ")
                }
                chapters.add(chapter)
            }
        }

        parseArray(dataArray)

        // Handle multi-page pagination for chapter lists
        val pagination = json.optJSONObject("pagination")
        val lastPage = pagination?.optInt("last_page", 1) ?: 1
        if (lastPage > 1) {
            val maxPages = minOf(lastPage, 10)
            for (p in 2..maxPages) {
                try {
                    val pageResp = client.newCall(GET("$baseUrl/api/comics/$slug/chapter-list?lang=en&page=$p", headers)).execute()
                    val pageJson = JSONObject(pageResp.body.string())
                    val pageData = pageJson.optJSONArray("data") ?: continue
                    parseArray(pageData)
                } catch (_: Exception) {
                    break
                }
            }
        }

        return chapters
    }

    // =========================== Pages ===========================

    override fun pageListRequest(chapter: SChapter): Request {
        val path = if (chapter.url.startsWith("http")) chapter.url else "$baseUrl${chapter.url}"
        return GET(path, headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val svDataScript = document.selectFirst("#sv-data")?.data()
            ?: throw Exception("sv-data script tag not found on ComicK chapter")

        val json = JSONObject(svDataScript)
        val chapterObj = json.getJSONObject("chapter")
        val images = chapterObj.getJSONArray("images")
        val pages = mutableListOf<Page>()

        for (i in 0 until images.length()) {
            val img = images.getJSONObject(i)
            val imgUrl = img.getString("url")
            pages.add(Page(i, "", imgUrl))
        }

        return pages
    }

    override fun imageUrlParse(response: Response): String =
        throw UnsupportedOperationException("ComicK pageListParse directly supplies image URLs")
}
