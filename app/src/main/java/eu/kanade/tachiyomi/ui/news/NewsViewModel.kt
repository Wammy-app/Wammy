package eu.kanade.tachiyomi.ui.news

import android.app.Application
import androidx.lifecycle.viewModelScope
import eu.kanade.tachiyomi.data.news.NewsRepository
import eu.kanade.tachiyomi.data.news.model.NewsItem
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mihon.core.viewmodel.StateViewModel
import tachiyomi.core.common.util.lang.withUIContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class NewsState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val items: List<NewsItem> = emptyList(),
    val error: String? = null,
)

class NewsViewModel(
    private val repository: NewsRepository = NewsRepository(),
    private val context: Application = Injekt.get(),
) : StateViewModel<NewsState>(NewsState()) {

    init {
        loadNews(forceRefresh = false)
    }

    fun loadNews(forceRefresh: Boolean = false) {
        if (forceRefresh && state.value.isRefreshing) return

        viewModelScope.launch {
            if (forceRefresh) {
                mutableState.update { it.copy(isRefreshing = true, error = null) }
            } else if (state.value.items.isEmpty()) {
                mutableState.update { it.copy(isLoading = true, error = null) }
            }

            val result = repository.getNews(forceRefresh = forceRefresh)
            result.fold(
                onSuccess = { newsList ->
                    mutableState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            items = newsList,
                            error = null,
                        )
                    }
                    if (forceRefresh) {
                        withUIContext {
                            context.toast("News updated")
                        }
                    }
                },
                onFailure = { throwable ->
                    mutableState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            error = if (it.items.isEmpty()) throwable.message ?: "Failed to load news" else null,
                        )
                    }
                    if (forceRefresh) {
                        withUIContext {
                            context.toast(throwable.message ?: "Failed to refresh news")
                        }
                    }
                },
            )
        }
    }

    fun formatRelativeTime(dateString: String): String {
        if (dateString.isBlank()) return ""
        try {
            val now = System.currentTimeMillis()
            var timeMillis: Long = 0

            // Try ISO 8601
            try {
                val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                val d = isoFormat.parse(dateString.substringBefore("Z").substringBefore("."))
                if (d != null) timeMillis = d.time
            } catch (_: Exception) {}

            // Try RFC 822
            if (timeMillis == 0L) {
                try {
                    val rfc822 = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US)
                    val d = rfc822.parse(dateString)
                    if (d != null) timeMillis = d.time
                } catch (_: Exception) {}
            }

            if (timeMillis == 0L) return dateString

            val diffMillis = now - timeMillis
            if (diffMillis < 0) return "Just now"

            val minutes = TimeUnit.MILLISECONDS.toMinutes(diffMillis)
            if (minutes < 1) return "Just now"
            if (minutes < 60) return "${minutes}m ago"

            val hours = TimeUnit.MILLISECONDS.toHours(diffMillis)
            if (hours < 24) return "${hours}h ago"

            val days = TimeUnit.MILLISECONDS.toDays(diffMillis)
            if (days == 1L) return "Yesterday"
            if (days < 7) return "${days}d ago"

            val displayFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
            return displayFormat.format(Date(timeMillis))
        } catch (_: Exception) {
            return dateString
        }
    }
}
