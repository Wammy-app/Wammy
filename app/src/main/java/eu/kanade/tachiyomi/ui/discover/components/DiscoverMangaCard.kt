package eu.kanade.tachiyomi.ui.discover.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import eu.kanade.presentation.library.components.RatingBadge
import eu.kanade.presentation.library.components.formatViewsCount
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.domain.manga.model.effectiveRating
import tachiyomi.domain.manga.model.effectiveViews
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * 3-Column Discover Manga Card:
 * - Cover art with bottom-left star rating pill
 * - Manga title with 2-line height alignment
 * - Subtitle row: author on the left, and amber eye icon with numeric views on the right
 */
@Composable
fun DiscoverMangaCard(
    manga: Manga,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sourceManager = remember { Injekt.get<SourceManager>() }
    val sourceName = remember(manga.source) {
        try {
            sourceManager.getOrStub(manga.source).name
        } catch (_: Exception) {
            ""
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
    ) {
        // Cover Poster with Rating Pill
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(10.dp)),
        ) {
            AsyncImage(
                model = manga.asMangaCover(),
                contentDescription = manga.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )

            val rating = manga.effectiveRating
            if (rating != null && rating > 0.0) {
                RatingBadge(
                    rating = rating,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(5.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Manga Title: uniform 2 lines across all 3 columns
        Text(
            text = manga.title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 16.sp,
            fontSize = 13.sp,
        )

        Spacer(modifier = Modifier.height(2.dp))

        // Bottom metadata row: author / source on left, eye icon + views on right
        val authorText = manga.author?.trim()?.takeIf { it.isNotBlank() }
        val views = manga.effectiveViews
        val leftText = authorText ?: sourceName.takeIf { it.isNotBlank() }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leftText != null) {
                Text(
                    text = leftText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f, fill = false),
                )
            } else {
                Spacer(modifier = Modifier.weight(1f, fill = false))
            }

            if (views != null && views > 0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Visibility,
                        contentDescription = null,
                        tint = Color(0xFFFFA000),
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(modifier = Modifier.width(2.5.dp))
                    Text(
                        text = formatViewsCount(views),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                }
            } else if (authorText != null && sourceName.isNotBlank()) {
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = sourceName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}
