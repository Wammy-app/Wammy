package eu.kanade.tachiyomi.data.news.model

import androidx.annotation.Keep
import kotlinx.serialization.Serializable

@Keep
@Serializable
data class NewsItem(
    val id: String = "",
    val title: String = "",
    val summary: String = "",
    val publishedAt: String = "",
    val url: String = "",
    val imageUrl: String? = null,
    val images: List<String> = emptyList(),
    val trailerUrl: String? = null,
    val trailerUrls: List<String> = emptyList(),
    val source: String = "Anime News Network",
) : java.io.Serializable

@Keep
@Serializable
data class NewsArticle(
    val id: String = "",
    val title: String = "",
    val summary: String = "",
    val publishedAt: String = "",
    val url: String = "",
    val imageUrl: String? = null,
    val images: List<String> = emptyList(),
    val trailerUrl: String? = null,
    val trailerUrls: List<String> = emptyList(),
    val source: String = "Anime News Network",
    val content: List<String> = emptyList(),
) : java.io.Serializable

@Keep
@Serializable
data class NewsFeedResponse(
    val items: List<NewsItem> = emptyList(),
)

fun extractYouTubeVideoId(url: String?): String? {
    if (url.isNullOrBlank()) return null
    val raw = url.trim()
    if (raw.matches(Regex("^[a-zA-Z0-9_-]{11}$"))) {
        return raw
    }
    val patterns = listOf(
        Regex("""(?:youtube\.com\/(?:[^\/\n\s]+\/\S+\/|(?:v|e(?:mbed)?)\/|.*[?&]v=)|youtu\.be\/|youtube\/)([\w-]{11})"""),
        Regex("""[?&]v=([\w-]{11})"""),
        Regex("""[?&]yt_embed=([\w-]{11})"""),
        Regex("""(?:embed\/|v\/|shorts\/)([\w-]{11})"""),
    )
    for (pattern in patterns) {
        val match = pattern.find(raw)
        if (match != null && match.groupValues.size > 1) {
            return match.groupValues[1]
        }
    }
    return null
}
