package eu.kanade.tachiyomi.data.news

import android.util.Log
import eu.kanade.tachiyomi.data.news.model.NewsArticle
import eu.kanade.tachiyomi.data.news.model.NewsFeedResponse
import eu.kanade.tachiyomi.data.news.model.NewsItem
import eu.kanade.tachiyomi.data.news.model.extractYouTubeVideoId
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class NewsRepository(
    private val client: OkHttpClient = Injekt.get<NetworkHelper>().client,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // In-memory cache for feed (15 min freshness)
    private var cachedNewsFeed: List<NewsItem>? = null
    private var feedLastFetched: Long = 0L
    private val feedTtlMillis = TimeUnit.MINUTES.toMillis(15)

    // In-memory cache for full articles (60 min freshness)
    private val articleCache = mutableMapOf<String, Pair<NewsArticle, Long>>()
    private val articleTtlMillis = TimeUnit.MINUTES.toMillis(60)

    companion object {
        private const val TAG = "NewsRepository"
        private const val AGGREGATOR_BASE = "https://yomite-parsers.onrender.com"
        private const val ANIME_CORNER_FEED = "https://animecorner.me/feed/"
        private const val ANN_FEED = "https://www.animenewsnetwork.com/news/rss.xml?ann-edition=us"
    }

    suspend fun getNews(forceRefresh: Boolean = false): Result<List<NewsItem>> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedNewsFeed != null && (now - feedLastFetched) < feedTtlMillis) {
            return@withContext Result.success(cachedNewsFeed!!)
        }

        // 1. Try remote aggregator API
        try {
            val request = Request.Builder()
                .url("$AGGREGATOR_BASE/api/news")
                .header("User-Agent", "Wammy/1.0")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyString = response.body.string()
                    val parsed = json.decodeFromString<NewsFeedResponse>(bodyString)
                    if (parsed.items.isNotEmpty()) {
                        cachedNewsFeed = parsed.items
                        feedLastFetched = now
                        return@withContext Result.success(parsed.items)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Aggregator news API unreachable, falling back to direct RSS: ${e.message}")
        }

        // 2. Direct RSS Fallback (Anime Corner & Anime News Network)
        try {
            val directItems = fetchDirectRssFeeds()
            if (directItems.isNotEmpty()) {
                cachedNewsFeed = directItems
                feedLastFetched = now
                return@withContext Result.success(directItems)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch direct RSS news feeds: ${e.message}", e)
        }

        // 3. Fallback to stale cache if present
        cachedNewsFeed?.let {
            return@withContext Result.success(it)
        }

        Result.failure(Exception("Unable to load anime news. Please check your internet connection."))
    }

    suspend fun getArticle(url: String): Result<NewsArticle> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val cached = articleCache[url]
        if (cached != null && (now - cached.second) < articleTtlMillis) {
            return@withContext Result.success(cached.first)
        }

        // 1. Try remote aggregator article parser
        try {
            val encodedUrl = java.net.URLEncoder.encode(url, "UTF-8")
            val request = Request.Builder()
                .url("$AGGREGATOR_BASE/api/news/article?url=$encodedUrl")
                .header("User-Agent", "Wammy/1.0")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyString = response.body.string()
                    val article = json.decodeFromString<NewsArticle>(bodyString)
                    if (article.title.isNotBlank()) {
                        val finalArticle = if (article.trailerUrl.isNullOrBlank() && article.trailerUrls.isEmpty()) {
                            val candidateImages = listOfNotNull(article.imageUrl) + article.images
                            var fallbackTrailer: String? = null
                            for (img in candidateImages) {
                                val ytId = extractYouTubeVideoId(img)
                                if (ytId != null) {
                                    fallbackTrailer = "https://www.youtube.com/embed/$ytId"
                                    break
                                }
                            }
                            if (fallbackTrailer != null) {
                                article.copy(
                                    trailerUrl = fallbackTrailer,
                                    trailerUrls = listOf(fallbackTrailer),
                                )
                            } else {
                                article
                            }
                        } else {
                            article
                        }
                        articleCache[url] = Pair(finalArticle, now)
                        return@withContext Result.success(finalArticle)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Aggregator article endpoint failed, scraping directly: ${e.message}")
        }

        // 2. Direct Jsoup scraper fallback
        try {
            val article = scrapeArticleDirect(url)
            if (article != null) {
                articleCache[url] = Pair(article, now)
                return@withContext Result.success(article)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Direct article scrape failed: ${e.message}", e)
        }

        Result.failure(Exception("Unable to load full article content."))
    }

    private fun fetchDirectRssFeeds(): List<NewsItem> {
        val results = mutableListOf<NewsItem>()

        // Fetch Anime Corner RSS
        try {
            val acRequest = Request.Builder().url(ANIME_CORNER_FEED).header("User-Agent", "Wammy/1.0").build()
            client.newCall(acRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val xml = response.body.string()
                    val doc = Jsoup.parse(xml, "", Parser.xmlParser())
                    val items = doc.select("item")
                    for ((index, item) in items.withIndex()) {
                        val title = item.selectFirst("title")?.text()?.trim().orEmpty()
                        val link = item.selectFirst("link")?.text()?.trim()
                            ?: item.selectFirst("guid")?.text()?.trim().orEmpty()
                        val pubDate = item.selectFirst("pubDate")?.text()?.trim().orEmpty()
                        val desc = item.selectFirst("description")?.text()
                            ?.replace(Regex("<!\\[CDATA\\[|\\]\\]>"), "")
                            ?.trim().orEmpty()

                        if (link.isNotBlank()) {
                            results.add(
                                NewsItem(
                                    id = link.ifBlank { "ac-$index" },
                                    title = title,
                                    summary = desc,
                                    publishedAt = parseRssDateToIso(pubDate),
                                    url = link,
                                    source = "Anime Corner",
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error fetching Anime Corner RSS: ${e.message}")
        }

        // Fetch ANN RSS
        try {
            val annRequest = Request.Builder().url(ANN_FEED).header("User-Agent", "Wammy/1.0").build()
            client.newCall(annRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val xml = response.body.string()
                    val doc = Jsoup.parse(xml, "", Parser.xmlParser())
                    val items = doc.select("item")
                    for ((index, item) in items.withIndex()) {
                        val title = item.selectFirst("title")?.text()?.trim().orEmpty()
                        val link = item.selectFirst("link")?.text()?.trim()
                            ?: item.selectFirst("guid")?.text()?.trim().orEmpty()
                        val pubDate = item.selectFirst("pubDate")?.text()?.trim()
                            ?: item.selectFirst("dc|date")?.text()?.trim().orEmpty()
                        val desc = item.selectFirst("description")?.text()?.trim().orEmpty()

                        if (link.isNotBlank()) {
                            results.add(
                                NewsItem(
                                    id = link.ifBlank { "ann-$index" },
                                    title = title,
                                    summary = desc,
                                    publishedAt = parseRssDateToIso(pubDate),
                                    url = link,
                                    source = "Anime News Network",
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error fetching ANN RSS: ${e.message}")
        }

        return results.sortedByDescending { it.publishedAt }
    }

    private fun scrapeArticleDirect(url: String): NewsArticle? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val html = response.body.string()
            val doc = Jsoup.parse(html, url)
            val isAc = url.contains("animecorner.me")

            val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
                ?: doc.title().trim()
            val summary = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
                ?: doc.selectFirst(".intro")?.text()?.trim().orEmpty()
            val ogImage = doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst("meta[name=twitter:image]")?.attr("content").orEmpty()
            val publishedAt = doc.selectFirst("meta[property=article:published_time]")?.attr("content")
                ?: doc.selectFirst("time")?.attr("datetime")
                ?: Date().toString()

            val targetScope = if (isAc) {
                doc.selectFirst(".entry-content, .ac-single-entry, article")
            } else {
                doc.selectFirst("#content-zone .meat, .meat, article")
            }

            val bodyImages = mutableListOf<String>()
            val trailers = mutableListOf<String>()
            val contentList = mutableListOf<String>()

            targetScope?.let { scope ->
                // Visuals & images
                scope.select("img").forEach { img ->
                    val src = img.attr("src").ifBlank { img.attr("data-src") }
                    val cls = img.attr("class")
                    if (!cls.contains("avatar") && !cls.contains("wp-smiley") && !src.contains("150x150") && src.startsWith("http")) {
                        val cleanUrl = cleanWordPressResizes(src)
                        if (!bodyImages.contains(cleanUrl)) {
                            bodyImages.add(cleanUrl)
                        }
                    }
                }

                // Paragraphs, lists, and quotes
                scope.select("p, ul, blockquote").forEach { el ->
                    val tag = el.tagName().uppercase(Locale.ROOT)
                    when (tag) {
                        "UL" -> {
                            el.select("li").forEach { li ->
                                val text = li.text().trim()
                                if (text.length > 3) contentList.add("• $text")
                            }
                        }
                        "BLOCKQUOTE" -> {
                            val quote = el.text().trim()
                            if (quote.length > 15) contentList.add("“$quote”")
                        }
                        else -> {
                            val text = el.text().trim()
                            if (text.length > 20 && !text.startsWith("Disclosure:") && !text.contains("discuss this in the forum")) {
                                contentList.add(text)
                            }
                        }
                    }
                }

                // Trailers
                scope.select("iframe, div[data-youtube-id]").forEach { el ->
                    val src = el.attr("src").ifBlank { el.attr("data-src") }
                    val ytId = extractYouTubeVideoId(src)
                    if (ytId != null) {
                        val embed = "https://www.youtube.com/embed/$ytId"
                        if (!trailers.contains(embed)) trailers.add(embed)
                    }
                }
            }

            // Document-wide trailer detection (e.g. ANN / Anime Corner PV links or iframes)
            doc.select("iframe, a, div[data-video-id], div[data-youtube-id]").forEach { el ->
                val candidate = el.attr("src")
                    .ifBlank { el.attr("data-src") }
                    .ifBlank { el.attr("data-lazy-src") }
                    .ifBlank { el.attr("href") }
                    .ifBlank { el.attr("data-video-id") }
                    .ifBlank { el.attr("data-youtube-id") }

                val ytId = extractYouTubeVideoId(candidate)
                if (ytId != null) {
                    val embedUrl = "https://www.youtube.com/embed/$ytId"
                    if (!trailers.contains(embedUrl)) {
                        trailers.add(embedUrl)
                    }
                }
            }

            // Fallback: Check ogImage and bodyImages for YouTube thumbnails
            if (trailers.isEmpty()) {
                val allImageCandidates = listOf(ogImage) + bodyImages
                for (img in allImageCandidates) {
                    val ytId = extractYouTubeVideoId(img)
                    if (ytId != null) {
                        val embedUrl = "https://www.youtube.com/embed/$ytId"
                        trailers.add(embedUrl)
                        break
                    }
                }
            }

            val primaryCover = cleanWordPressResizes(ogImage).ifBlank { bodyImages.firstOrNull().orEmpty() }

            return NewsArticle(
                id = url,
                title = title,
                summary = summary,
                publishedAt = publishedAt,
                url = url,
                imageUrl = primaryCover,
                images = bodyImages.filter { it != primaryCover },
                trailerUrl = trailers.firstOrNull(),
                trailerUrls = trailers,
                source = if (isAc) "Anime Corner" else "Anime News Network",
                content = contentList,
            )
        }
    }

    private fun cleanWordPressResizes(url: String): String {
        return url.replace(Regex("-\\d+x\\d+(\\.[a-zA-Z0-9]+)(\\?.*)?$"), "$1")
    }

    private fun parseRssDateToIso(dateStr: String): String {
        if (dateStr.isBlank()) return Date().toString()
        try {
            // E.g. "Wed, 14 Oct 2026 11:47:28 +0000"
            val rfc822 = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US)
            val parsed = rfc822.parse(dateStr)
            if (parsed != null) {
                val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                return isoFormat.format(parsed)
            }
        } catch (_: Exception) {}
        return dateStr
    }
}
