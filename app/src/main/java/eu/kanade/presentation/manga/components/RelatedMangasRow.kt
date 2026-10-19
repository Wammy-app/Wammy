package eu.kanade.presentation.manga.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
import eu.kanade.presentation.library.components.MangaComfortableGridItem
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.domain.manga.model.effectiveRating
import tachiyomi.domain.manga.model.effectiveViews
import tachiyomi.presentation.core.components.material.padding

@Composable
fun RelatedMangasRow(
    relatedMangas: List<Manga>?,
    isLoading: Boolean,
    getManga: @Composable (Manga) -> State<Manga>,
    onMangaClick: (Manga) -> Unit,
    onMangaLongClick: (Manga) -> Unit,
    onSeeRecommendationsClick: () -> Unit,
    onSuggestionArrowClick: () -> Unit,
) {
    Column {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.padding.medium),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Suggestions",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            IconButton(onClick = onSuggestionArrowClick) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                    contentDescription = null,
                )
            }
        }

        // Carousel
        if (isLoading && relatedMangas == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = MaterialTheme.padding.medium),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
        } else if (relatedMangas != null && relatedMangas.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(MaterialTheme.padding.small),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
            ) {
                items(relatedMangas, key = { "suggestion-${it.id}" }) { manga ->
                    val title by getManga(manga)
                    Box(modifier = Modifier.width(96.dp)) {
                        MangaComfortableGridItem(
                            title = title.title,
                            titleMaxLines = 3,
                            coverData = title.asMangaCover(),
                            coverBadgeStart = {},
                            coverAlpha = if (title.favorite) CommonMangaItemDefaults.BrowseFavoriteCoverAlpha else 1f,
                            rating = title.effectiveRating,
                            views = title.effectiveViews,
                            subtitle = title.author,
                            onClick = { onMangaClick(title) },
                            onLongClick = { onMangaLongClick(title) },
                        )
                    }
                }
            }
        } else if (relatedMangas != null && relatedMangas.isEmpty()) {
            // Empty state - don't show anything
        }

        // See Recommendations button
        Button(
            onClick = onSeeRecommendationsClick,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
        ) {
            Text(text = "See Recommendations")
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                contentDescription = null,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

