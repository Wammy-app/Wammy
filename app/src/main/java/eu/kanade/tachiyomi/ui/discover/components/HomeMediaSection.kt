package eu.kanade.tachiyomi.ui.discover.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.asMangaCover

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
        AsyncImage(
            model = manga.asMangaCover(),
            contentDescription = manga.title,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(10.dp)),
            contentScale = ContentScale.Crop,
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = manga.title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
