package eu.kanade.tachiyomi.ui.discover.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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

@Composable
fun HomeMediaSection(
    title: String,
    items: List<Manga>,
    onSeeAll: () -> Unit,
    onClick: (Manga) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    if (items.isEmpty()) return

    val columns = items.chunked(2)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
    ) {
        // Section Header: Title (+ Subtitle) + Arrow Button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onSeeAll)
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.9f),
                    )
                }
            }

            IconButton(onClick = onSeeAll) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                    contentDescription = "See All",
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            val itemSpacing = 12.dp
            val cardWidth = (maxWidth - itemSpacing * 2) / 3

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(itemSpacing),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(
                    items = columns,
                    key = { col -> "col_${col.first().id}_${col.first().url}" },
                ) { col ->
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        HomeMediaCard(
                            manga = col[0],
                            cardWidth = cardWidth,
                            onClick = { onClick(col[0]) },
                        )
                        if (col.size > 1) {
                            HomeMediaCard(
                                manga = col[1],
                                cardWidth = cardWidth,
                                onClick = { onClick(col[1]) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeMediaCard(
    manga: Manga,
    cardWidth: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(cardWidth)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
    ) {
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

        Text(
            text = manga.title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        val views = manga.effectiveViews
        if (views != null && views > 0) {
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Visibility,
                    contentDescription = "Views",
                    tint = Color(0xFFFFA000),
                    modifier = Modifier.size(11.dp),
                )
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    text = formatViewsCount(views),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}
