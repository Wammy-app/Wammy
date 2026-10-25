package eu.kanade.tachiyomi.ui.search

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import eu.kanade.presentation.browse.GlobalSearchContent
import eu.kanade.tachiyomi.data.sync.CloudUser
import eu.kanade.tachiyomi.data.sync.FirestoreUserRepository
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchViewModel
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.NovelGlobalSearchViewModel
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.profile.PublicProfileScreen
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class UserSearchScreen : Screen {

    private fun loadRecentUsers(prefs: SharedPreferences): List<Pair<String, CloudUser>> {
        val str = prefs.getString("recent_users_list", null) ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(str)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                val uid = obj.optString("uid", "") 
                val user = CloudUser(
                    username = obj.optString("username", ""),
                    displayName = obj.optString("displayName", ""),
                    avatarUrl = obj.optString("avatarUrl", ""),
                )
                if (uid.isNotEmpty()) uid to user else null
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun saveRecentUser(prefs: SharedPreferences, uid: String, user: CloudUser): List<Pair<String, CloudUser>> {
        val current = loadRecentUsers(prefs).toMutableList()
        current.removeAll { it.first == uid }
        current.add(0, uid to user)
        if (current.size > 15) current.removeLast()

        val arr = org.json.JSONArray()
        current.forEach { (id, u) ->
            arr.put(org.json.JSONObject().apply {
                put("uid", id)
                put("username", u.username)
                put("displayName", u.displayName)
                put("avatarUrl", u.avatarUrl)
            })
        }
        prefs.edit().putString("recent_users_list", arr.toString()).apply()
        return current
    }
    
    private fun clearRecentUsers(prefs: SharedPreferences): List<Pair<String, CloudUser>> {
        prefs.edit().remove("recent_users_list").apply()
        return emptyList()
    }

    private fun loadRecentQueries(prefs: SharedPreferences, key: String): List<String> {
        val str = prefs.getString(key, null) ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(str)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) { emptyList() }
    }

    private fun saveRecentQuery(prefs: SharedPreferences, key: String, query: String): List<String> {
        val current = loadRecentQueries(prefs, key).toMutableList()
        current.removeAll { it.equals(query, ignoreCase = true) }
        current.add(0, query)
        if (current.size > 15) current.removeLast()
        
        prefs.edit().putString(key, org.json.JSONArray(current).toString()).apply()
        return current
    }

    private fun clearRecentQueries(prefs: SharedPreferences, key: String): List<String> {
        prefs.edit().remove(key).apply()
        return emptyList()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val prefs = remember { context.getSharedPreferences("search_prefs", Context.MODE_PRIVATE) }
        
        val scope = rememberCoroutineScope()
        val repo = remember { FirestoreUserRepository() }

        var searchQuery by remember { mutableStateOf("") }
        var searchResults by remember { mutableStateOf<List<Pair<String, CloudUser>>>(emptyList()) }
        var isSearching by remember { mutableStateOf(false) }
        var searchJob by remember { mutableStateOf<Job?>(null) }
        
        var selectedTab by remember { mutableStateOf(0) }
        val tabs = listOf("Users", "Manga", "Novel")
        
        var recentUsers by remember { mutableStateOf(loadRecentUsers(prefs)) }
        var recentManga by remember { mutableStateOf(loadRecentQueries(prefs, "recent_manga_list")) }
        var recentNovel by remember { mutableStateOf(loadRecentQueries(prefs, "recent_novel_list")) }

        val mangaListState = rememberLazyListState()
        val novelListState = rememberLazyListState()

        val mangaViewModel = viewModel<GlobalSearchViewModel>(
            factory = GlobalSearchViewModel.Factory,
            extras = remember {
                MutableCreationExtras().apply {
                    set(GlobalSearchViewModel.INITIAL_QUERY_KEY, "")
                    set(GlobalSearchViewModel.INITIAL_EXTENSION_FILTER_KEY, null)
                }
            }
        )
        val mangaState by mangaViewModel.state.collectAsState()

        val novelViewModel = viewModel<NovelGlobalSearchViewModel>(
            factory = NovelGlobalSearchViewModel.Factory,
            extras = remember {
                MutableCreationExtras().apply {
                    set(NovelGlobalSearchViewModel.INITIAL_QUERY_KEY, "")
                    set(NovelGlobalSearchViewModel.INITIAL_EXTENSION_FILTER_KEY, null)
                }
            }
        )
        val novelState by novelViewModel.state.collectAsState()

        fun performSearch(query: String) {
            searchJob?.cancel()
            if (query.isBlank()) {
                searchResults = emptyList()
                isSearching = false
                return
            }

            if (selectedTab == 0) { // Only search users live
                isSearching = true
                searchJob = scope.launch {
                    delay(300) // Debounce 300ms
                    searchResults = repo.searchByPrefix(query)
                    isSearching = false
                }
            } else {
                searchResults = emptyList()
            }
        }

        fun executeGlobalSearch(query: String) {
            if (query.isNotBlank()) {
                if (selectedTab == 1) {
                    recentManga = saveRecentQuery(prefs, "recent_manga_list", query)
                    mangaViewModel.updateSearchQuery(query)
                    mangaViewModel.setSourceFilter(eu.kanade.tachiyomi.ui.browse.source.globalsearch.SourceFilter.All)
                    scope.launch { mangaListState.scrollToItem(0, 0) }
                } else if (selectedTab == 2) {
                    recentNovel = saveRecentQuery(prefs, "recent_novel_list", query)
                    novelViewModel.updateSearchQuery(query)
                    novelViewModel.setSourceFilter(eu.kanade.tachiyomi.ui.browse.source.globalsearch.SourceFilter.All)
                    scope.launch { novelListState.scrollToItem(0, 0) }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Search Bar Row (Instagram Style)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .windowInsetsPadding(WindowInsets.statusBars),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Search Input Box
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Search, 
                        contentDescription = "Search", 
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { 
                            searchQuery = it
                            performSearch(it)
                        },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = TextStyle(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 16.sp
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(
                            onSearch = { executeGlobalSearch(searchQuery) }
                        ),
                        decorationBox = { innerTextField ->
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = "Search",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 16.sp
                                )
                            }
                            innerTextField()
                        }
                    )
                    if (searchQuery.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Default.Close, 
                            contentDescription = "Clear",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(18.dp)
                                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f), CircleShape)
                                .padding(2.dp)
                                .clickable { 
                                    searchQuery = "" 
                                    performSearch("")
                                }
                        )
                    }
                }
                
                Spacer(modifier = Modifier.width(12.dp))
                
                // Cancel Button
                Text(
                    text = "Cancel",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { navigator.pop() }
                )
            }
            
            // Tabs
            PrimaryTabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.background,
                divider = { HorizontalDivider() }
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { 
                            selectedTab = index 
                            performSearch(searchQuery)
                        },
                        text = { 
                            Text(
                                text = title,
                                fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal
                            ) 
                        }
                    )
                }
            }
            
            // Content Below Tabs
            Box(modifier = Modifier.fillMaxSize()) {
                if (isSearching && selectedTab == 0) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                } else if (searchQuery.isNotEmpty()) {
                    if (selectedTab == 0) {
                        if (searchResults.isEmpty()) {
                            Text(
                                "No users found.",
                                modifier = Modifier.align(Alignment.Center),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                items(searchResults) { (uid, user) ->
                                    UserListItem(user = user) {
                                        recentUsers = saveRecentUser(prefs, uid, user)
                                        navigator.push(PublicProfileScreen(uid = uid, user = user))
                                    }
                                }
                            }
                        }
                    } else if (selectedTab == 1) {
                        if (mangaState.searchQuery == searchQuery) {
                            if (mangaState.items.isEmpty()) {
                                Column(
                                    modifier = Modifier.fillMaxSize().padding(32.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = "No manga extensions installed.",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 18.sp
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Go to the Browse tab and install some extensions to search manga.",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 14.sp
                                    )
                                }
                            } else {
                                GlobalSearchContent(
                                    items = mangaState.items,
                                    contentPadding = PaddingValues(bottom = 16.dp),
                                    getManga = { mangaViewModel.getManga(it) },
                                    onClickSource = { navigator.push(BrowseSourceScreen(it.id, searchQuery)) },
                                    onClickItem = { navigator.push(MangaScreen(it.id, true)) },
                                    onLongClickItem = { navigator.push(MangaScreen(it.id, true)) },
                                    lazyListState = mangaListState,
                                )
                            }
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                item {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { executeGlobalSearch(searchQuery) }
                                            .padding(horizontal = 16.dp, vertical = 16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                        Spacer(modifier = Modifier.width(16.dp))
                                        Text(
                                            text = "Search Manga for \"$searchQuery\"",
                                            color = MaterialTheme.colorScheme.primary,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }
                    } else if (selectedTab == 2) {
                        if (novelState.searchQuery == searchQuery) {
                            if (novelState.items.isEmpty()) {
                                Column(
                                    modifier = Modifier.fillMaxSize().padding(32.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = "No novel extensions installed.",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 18.sp
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Go to the Browse tab and install some extensions to search novels.",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 14.sp
                                    )
                                }
                            } else {
                                GlobalSearchContent(
                                    items = novelState.items,
                                    contentPadding = PaddingValues(bottom = 16.dp),
                                    getManga = { novelViewModel.getManga(it) },
                                    onClickSource = { navigator.push(BrowseSourceScreen(it.id, searchQuery)) },
                                    onClickItem = { navigator.push(MangaScreen(it.id, true)) },
                                    onLongClickItem = { navigator.push(MangaScreen(it.id, true)) },
                                    lazyListState = novelListState,
                                )
                            }
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                item {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { executeGlobalSearch(searchQuery) }
                                            .padding(horizontal = 16.dp, vertical = 16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                        Spacer(modifier = Modifier.width(16.dp))
                                        Text(
                                            text = "Search Novel for \"$searchQuery\"",
                                            color = MaterialTheme.colorScheme.primary,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // Empty Search Bar - Show Recents
                    val hasRecents = when (selectedTab) {
                        0 -> recentUsers.isNotEmpty()
                        1 -> recentManga.isNotEmpty()
                        else -> recentNovel.isNotEmpty()
                    }
                    
                    if (hasRecents) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Recent",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Text(
                                    text = "Clear all",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 14.sp,
                                    modifier = Modifier.clickable {
                                        when (selectedTab) {
                                            0 -> recentUsers = clearRecentUsers(prefs)
                                            1 -> recentManga = clearRecentQueries(prefs, "recent_manga_list")
                                            2 -> recentNovel = clearRecentQueries(prefs, "recent_novel_list")
                                        }
                                    }
                                )
                            }
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                if (selectedTab == 0) {
                                    items(recentUsers) { (uid, user) ->
                                        UserListItem(user = user) {
                                            recentUsers = saveRecentUser(prefs, uid, user)
                                            navigator.push(PublicProfileScreen(uid = uid, user = user))
                                        }
                                    }
                                } else {
                                    val stringRecents = if (selectedTab == 1) recentManga else recentNovel
                                    items(stringRecents) { query ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { 
                                                    searchQuery = query 
                                                    executeGlobalSearch(query) 
                                                }
                                                .padding(horizontal = 16.dp, vertical = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Spacer(modifier = Modifier.width(16.dp))
                                            Text(
                                                text = query,
                                                color = MaterialTheme.colorScheme.onBackground,
                                                fontSize = 15.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Text(
                            "No recent searches.",
                            modifier = Modifier.align(Alignment.Center),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun UserListItem(user: CloudUser, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (user.avatarUrl.isNotEmpty()) {
            AsyncImage(
                model = user.avatarUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
            )
        } else {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        user.displayName.take(1).uppercase(),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.width(12.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = user.username,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 14.sp
            )
            Text(
                text = user.displayName.ifEmpty { user.username },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
        }
    }
}
