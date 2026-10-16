package eu.kanade.tachiyomi.ui.discover.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.google.firebase.auth.FirebaseUser
import eu.kanade.tachiyomi.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeTopBar(
    isNovel: Boolean,
    updatesCount: Int,
    user: FirebaseUser?,
    onUpdatesClick: () -> Unit,
    onSearchClick: () -> Unit,
    onProfileClick: () -> Unit,
    onToggleMode: () -> Unit = {},
    modifier: Modifier = Modifier,
    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    TopAppBar(
        modifier = modifier,
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.background,
        ),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // App Logo
                Image(
                    painter = painterResource(R.drawable.wammy_logo),
                    contentDescription = null,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )

                // Kanji Title "和美"
                Text(
                    text = "和美",
                    style = MaterialTheme.typography.titleLarge,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )

                // Subtitle tag: MANGA / NOVELS (clickable to toggle mode)
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(onClick = onToggleMode),
                ) {
                    Text(
                        text = if (isNovel) "NOVELS" else "MANGA",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        letterSpacing = 1.sp,
                    )
                }
            }
        },
        actions = {
            // Updates / History Icon with Badge
            IconButton(onClick = onUpdatesClick) {
                BadgedBox(
                    badge = {
                        if (updatesCount > 0) {
                            Badge(containerColor = MaterialTheme.colorScheme.primary)
                        }
                    },
                ) {
                    Icon(
                        imageVector = Icons.Outlined.History,
                        contentDescription = "Updates",
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
            }

            // Search Icon
            IconButton(onClick = onSearchClick) {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = "Search",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }

            // Profile Avatar
            IconButton(onClick = onProfileClick) {
                if (user?.photoUrl != null) {
                    AsyncImage(
                        model = user.photoUrl,
                        contentDescription = "Profile",
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.AccountCircle,
                        contentDescription = "Profile",
                        modifier = Modifier.size(30.dp),
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
            }
        },
    )
}
