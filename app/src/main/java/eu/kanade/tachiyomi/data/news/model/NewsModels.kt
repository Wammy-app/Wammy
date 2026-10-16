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
)

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
)

@Keep
@Serializable
data class NewsFeedResponse(
    val items: List<NewsItem> = emptyList(),
)
