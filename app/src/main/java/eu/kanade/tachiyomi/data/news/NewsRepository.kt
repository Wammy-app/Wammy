package eu.kanade.tachiyomi.data.news

import android.app.Application
import android.content.Context
import android.util.Log
import eu.kanade.tachiyomi.data.news.model.NewsArticle
import eu.kanade.tachiyomi.data.news.model.NewsFeedResponse
import eu.kanade.tachiyomi.data.news.model.NewsItem
import eu.kanade.tachiyomi.data.news.model.extractYouTubeVideoId
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.interceptor.rateLimitExempt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class NewsRepository(
    private val context: Context = Injekt.get<Application>(),
    private val client: OkHttpClient = Injekt.get<NetworkHelper>().client.rateLimitExempt(),
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

    // Aggregator client with 8s timeout to handle Render cold-starts gracefully
    private val aggregatorClient: OkHttpClient = client.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    // Dedicated client for RSS fallback and quick metadata scraping
    private val rssClient: OkHttpClient = client.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    private val diskCacheFile by lazy {
        File(context.cacheDir, CACHE_FILE_NAME)
    }

    companion object {
        private const val TAG = "NewsRepository"
        private const val AGGREGATOR_BASE = "https://yomite-parsers.onrender.com"
        private const val ANIME_CORNER_FEED = "https://animecorner.me/feed/"
        private const val ANN_FEED = "https://www.animenewsnetwork.com/news/rss.xml?ann-edition=us"
        private const val CACHE_FILE_NAME = "news_feed_cache_v2.json"

        // In-memory cache for feed (15 min freshness), shared across all NewsRepository instances
        @Volatile
        private var cachedNewsFeed: List<NewsItem>? = null
        @Volatile
        private var feedLastFetched: Long = 0L
        private val feedTtlMillis = TimeUnit.MINUTES.toMillis(15)

        // In-memory cache for full articles (60 min freshness)
        private val articleCache = ConcurrentHashMap<String, Pair<NewsArticle, Long>>()
        private val articleTtlMillis = TimeUnit.MINUTES.toMillis(60)
    }

    fun getCachedNews(): List<NewsItem> {
        val mem = cachedNewsFeed
        if (!mem.isNullOrEmpty()) {
            return mem
        }
        val disk = readDiskCache()
        if (disk.isNotEmpty()) {
            cachedNewsFeed = disk
            return disk
        }
        return emptyList()
    }

    private fun readDiskCache(): List<NewsItem> {
        return try {
            if (diskCacheFile.exists()) {
                val content = diskCacheFile.readText()
                if (content.isNotBlank()) {
                    val parsed = json.decodeFromString<List<NewsItem>>(content)
                    if (parsed.isNotEmpty()) {
                        feedLastFetched = diskCacheFile.lastModified()
                        return parsed
                    }
                }
            }
            emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Error reading news disk cache: ${e.message}")
            emptyList()
        }
    }

    private fun saveToDiskCache(items: List<NewsItem>) {
        if (items.isEmpty()) return
        try {
            val jsonString = json.encodeToString(items)
            val tempFile = File(context.cacheDir, "$CACHE_FILE_NAME.tmp")
            tempFile.writeText(jsonString)
            if (tempFile.exists()) {
                tempFile.renameTo(diskCacheFile)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed writing news disk cache: ${e.message}")
        }
    }

    suspend fun getNews(forceRefresh: Boolean = false): Result<List<NewsItem>> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val cached = getCachedNews()

        if (!forceRefresh && cached.isNotEmpty() && (now - feedLastFetched) < feedTtlMillis) {
            return@withContext Result.success(cached)
        }

        // Cache map to preserve existing imageUrls so photos are NEVER lost
        val knownImages = (cached + readDiskCache())
            .associate { it.url to it.imageUrl }
            .filterValues { !it.isNullOrBlank() }

        // 1. Try remote aggregator API with 8s timeout (gives waking Render servers time to respond)
        try {
            val request = Request.Builder()
                .url("$AGGREGATOR_BASE/api/news")
                .header("User-Agent", "Wammy/1.0")
                .build()

            aggregatorClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyString = response.body.string()
                    val parsed = json.decodeFromString<NewsFeedResponse>(bodyString)
                    if (parsed.items.isNotEmpty()) {
                        // Merge with known images if any item in parsed is missing imageUrl
                        val finalItems = parsed.items.map { item ->
                            if (item.imageUrl.isNullOrBlank() && knownImages.containsKey(item.url)) {
                                item.copy(imageUrl = knownImages[item.url])
                            } else {
                                item
                            }
                        }
                        cachedNewsFeed = finalItems
                        feedLastFetched = now
                        saveToDiskCache(finalItems)
                        return@withContext Result.success(finalItems)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Aggregator news API unreachable/timed out, falling back to direct RSS: ${e.message}")
        }

        // 2. Direct RSS Fallback with concurrent image extraction
        try {
            val directItems = fetchDirectRssFeeds(knownImages)
            if (directItems.isNotEmpty()) {
                cachedNewsFeed = directItems
                feedLastFetched = now
                saveToDiskCache(directItems)
                return@withContext Result.success(directItems)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch direct RSS news feeds: ${e.message}", e)
        }

        // 3. Fallback to stale cache if present
        if (cached.isNotEmpty()) {
            return@withContext Result.success(cached)
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

    private suspend fun fetchDirectRssFeeds(knownImages: Map<String, String?>): List<NewsItem> = coroutineScope {
        val acDeferred = async(Dispatchers.IO) { fetchAnimeCornerRss() }
        val annDeferred = async(Dispatchers.IO) { fetchAnnRss() }
        val rawItems = (acDeferred.await() + annDeferred.await()).sortedByDescending { it.publishedAt }

        // Enrich items with imageUrl so fallback NEVER shows blank missing photos
        val enriched = rawItems.mapIndexed { index, item ->
            async(Dispatchers.IO) {
                val cachedImg = knownImages[item.url]
                if (!cachedImg.isNullOrBlank()) {
                    item.copy(imageUrl = cachedImg)
                } else if (!item.imageUrl.isNullOrBlank()) {
                    item
                } else if (index < 12) {
                    val og = extractOgImageFast(item.url)
                    if (!og.isNullOrBlank()) {
                        item.copy(imageUrl = og)
                    } else {
                        item
                    }
                } else {
                    item
                }
            }
        }.awaitAll()

        enriched
    }

    private fun extractOgImageFast(url: String): String? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .build()
            rssClient.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return null
                val inputStream = response.body.byteStream()
                val buffer = ByteArray(49152) // 48 KB is plenty for <head>
                var totalRead = 0
                while (totalRead < buffer.size) {
                    val read = inputStream.read(buffer, totalRead, buffer.size - totalRead)
                    if (read == -1) break
                    totalRead += read
                }
                val headHtml = String(buffer, 0, totalRead, Charsets.UTF_8)
                val doc = Jsoup.parse(headHtml, url)
                val og = doc.selectFirst("meta[property=og:image]")?.attr("content")
                    ?: doc.selectFirst("meta[name=twitter:image]")?.attr("content")
                    ?: doc.selectFirst("link[rel=image_src]")?.attr("href")
                og?.takeIf { it.isNotBlank() && it.startsWith("http") }?.let { cleanWordPressResizes(it) }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchAnimeCornerRss(): List<NewsItem> {
        val results = mutableListOf<NewsItem>()
        try {
            val acRequest = Request.Builder().url(ANIME_CORNER_FEED).header("User-Agent", "Wammy/1.0").build()
            rssClient.newCall(acRequest).execute().use { response ->
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
                        val mediaThumb = item.selectFirst("media|thumbnail, media|content, enclosure[type^=image]")?.attr("url")
                            ?.takeIf { it.isNotBlank() }

                        if (link.isNotBlank()) {
                            results.add(
                                NewsItem(
                                    id = link.ifBlank { "ac-$index" },
                                    title = title,
                                    summary = desc,
                                    publishedAt = parseRssDateToIso(pubDate),
                                    url = link,
                                    imageUrl = mediaThumb,
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
        return results
    }

    private fun fetchAnnRss(): List<NewsItem> {
        val results = mutableListOf<NewsItem>()
        try {
            val annRequest = Request.Builder().url(ANN_FEED).header("User-Agent", "Wammy/1.0").build()
            rssClient.newCall(annRequest).execute().use { response ->
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
                        val mediaThumb = item.selectFirst("media|thumbnail, media|content, enclosure[type^=image]")?.attr("url")
                            ?.takeIf { it.isNotBlank() }

                        if (link.isNotBlank()) {
                            results.add(
                                NewsItem(
                                    id = link.ifBlank { "ann-$index" },
                                    title = title,
                                    summary = desc,
                                    publishedAt = parseRssDateToIso(pubDate),
                                    url = link,
                                    imageUrl = mediaThumb,
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
        return results
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
