package eu.kanade.tachiyomi.ui.news

import androidx.lifecycle.viewModelScope
import eu.kanade.tachiyomi.data.news.NewsRepository
import eu.kanade.tachiyomi.data.news.model.NewsArticle
import eu.kanade.tachiyomi.data.news.model.NewsItem
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mihon.core.viewmodel.StateViewModel

import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

data class NewsArticleState(
    val isLoading: Boolean = true,
    val article: NewsArticle? = null,
    val error: String? = null,
)

class NewsArticleViewModel(
    val item: NewsItem,
    private val repository: NewsRepository = Injekt.get(),
) : StateViewModel<NewsArticleState>(
    NewsArticleState(
        article = NewsArticle(
            id = item.id,
            title = item.title,
            summary = item.summary,
            publishedAt = item.publishedAt,
            url = item.url,
            imageUrl = item.imageUrl,
            images = item.images,
            trailerUrl = item.trailerUrl,
            trailerUrls = item.trailerUrls,
            source = item.source,
            content = emptyList(),
        ),
    ),
) {

    init {
        loadArticle()
    }

    fun loadArticle() {
        viewModelScope.launch {
            mutableState.update { it.copy(isLoading = true, error = null) }
            val result = repository.getArticle(item.url)
            result.fold(
                onSuccess = { fullArticle ->
                    mutableState.update {
                        it.copy(
                            isLoading = false,
                            article = fullArticle,
                            error = null,
                        )
                    }
                },
                onFailure = { throwable ->
                    mutableState.update {
                        it.copy(
                            isLoading = false,
                            error = throwable.message ?: "Failed to load full article content",
                        )
                    }
                },
            )
        }
    }
}
