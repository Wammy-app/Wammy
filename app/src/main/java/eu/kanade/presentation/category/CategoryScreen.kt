package eu.kanade.presentation.category

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import eu.kanade.presentation.category.components.CategoryFloatingActionButton
import eu.kanade.presentation.category.components.CategoryListItem
import eu.kanade.presentation.components.AppBar
import eu.kanade.tachiyomi.ui.category.CategoryScreenState
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import tachiyomi.domain.category.model.Category
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.components.material.topSmallPaddingValues
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.util.plus

@Composable
fun CategoryScreen(
    state: CategoryScreenState.Success,
    onClickCreate: () -> Unit,
    onClickRename: (Category) -> Unit,
    onClickDelete: (Category) -> Unit,
    onChangeOrder: (Category, Int) -> Unit,
    navigateUp: () -> Unit,
    onClickAddSubCategory: (Category) -> Unit = {},
) {
    val lazyListState = rememberLazyListState()
    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = stringResource(MR.strings.action_edit_categories),
                navigateUp = navigateUp,
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            CategoryFloatingActionButton(
                lazyListState = lazyListState,
                onCreate = onClickCreate,
            )
        },
    ) { paddingValues ->
        if (state.isEmpty) {
            EmptyScreen(
                stringRes = MR.strings.information_empty_category,
                modifier = Modifier.padding(paddingValues),
            )
            return@Scaffold
        }

        CategoryContent(
            categories = state.categories,
            lazyListState = lazyListState,
            paddingValues = paddingValues,
            onClickRename = onClickRename,
            onClickDelete = onClickDelete,
            onChangeOrder = onChangeOrder,
            onClickAddSubCategory = onClickAddSubCategory,
        )
    }
}

@Composable
private fun CategoryContent(
    categories: List<Category>,
    lazyListState: LazyListState,
    paddingValues: PaddingValues,
    onClickRename: (Category) -> Unit,
    onClickDelete: (Category) -> Unit,
    onChangeOrder: (Category, Int) -> Unit,
    onClickAddSubCategory: (Category) -> Unit,
) {
    val categoriesState = remember { categories.toMutableStateList() }
    var expandedCategoryIds by rememberSaveable { mutableStateOf(emptySet<Long>()) }

    val visibleCategories = remember(categoriesState.toList(), expandedCategoryIds) {
        categoriesState.filter { category ->
            val parentId = category.parentId
            parentId == null || expandedCategoryIds.contains(parentId)
        }
    }

    val reorderableState = rememberReorderableLazyListState(lazyListState, paddingValues) { from, to ->
        val fromCategory = visibleCategories.getOrNull(from.index) ?: return@rememberReorderableLazyListState
        val toCategory = visibleCategories.getOrNull(to.index) ?: return@rememberReorderableLazyListState

        val fromIndex = categoriesState.indexOfFirst { it.id == fromCategory.id }
        val toIndex = categoriesState.indexOfFirst { it.id == toCategory.id }
        if (fromIndex != -1 && toIndex != -1) {
            if (fromCategory.parentId == null) {
                val children = categoriesState.filter { it.parentId == fromCategory.id }
                val block = listOf(fromCategory) + children
                categoriesState.removeAll(block)
                val targetIndex = categoriesState.indexOfFirst { it.id == toCategory.id }
                val insertIndex = if (targetIndex != -1) {
                    if (from.index < to.index) {
                        val toChildren = categoriesState.filter { it.parentId == toCategory.id }
                        val lastIdx = if (toChildren.isNotEmpty()) {
                            categoriesState.indexOfFirst { it.id == toChildren.last().id }
                        } else {
                            targetIndex
                        }
                        (lastIdx + 1).coerceIn(0, categoriesState.size)
                    } else {
                        targetIndex.coerceIn(0, categoriesState.size)
                    }
                } else {
                    categoriesState.size
                }
                categoriesState.addAll(insertIndex, block)
                onChangeOrder(fromCategory, insertIndex)
            } else {
                val item = categoriesState.removeAt(fromIndex)
                val targetIndex = categoriesState.indexOfFirst { it.id == toCategory.id }
                val insertIndex = if (targetIndex != -1) targetIndex else categoriesState.size
                categoriesState.add(insertIndex, item)
                onChangeOrder(fromCategory, insertIndex)
            }
        }
    }

    LaunchedEffect(categories) {
        if (!reorderableState.isAnyItemDragging) {
            categoriesState.clear()
            categoriesState.addAll(categories)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = lazyListState,
        contentPadding = paddingValues +
            topSmallPaddingValues +
            PaddingValues(horizontal = MaterialTheme.padding.medium),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    ) {
        items(
            items = visibleCategories,
            key = { category -> category.key },
        ) { category ->
            ReorderableItem(reorderableState, category.key) {
                val isChild = category.parentId != null
                val siblings = if (isChild) categoriesState.filter { it.parentId == category.parentId } else emptyList()
                val isFirstChild = isChild && siblings.firstOrNull()?.id == category.id
                val isLastChild = isChild && siblings.lastOrNull()?.id == category.id
                val parentName = category.parentId?.let { pid -> categoriesState.find { it.id == pid }?.visualName }
                val subcategories = remember(categoriesState.toList(), category.id) {
                    categoriesState.filter { it.parentId == category.id }
                }
                val hasSubcategories = subcategories.isNotEmpty()
                val isExpanded = expandedCategoryIds.contains(category.id)

                CategoryListItem(
                    modifier = Modifier.animateItem(),
                    category = category,
                    parentName = parentName,
                    isChild = isChild,
                    isFirstChild = isFirstChild,
                    isLastChild = isLastChild,
                    onRename = { onClickRename(category) },
                    onDelete = { onClickDelete(category) },
                    onAddSubCategory = if (!isChild) {
                        {
                            expandedCategoryIds = expandedCategoryIds + category.id
                            onClickAddSubCategory(category)
                        }
                    } else {
                        null
                    },
                    hasSubcategories = hasSubcategories,
                    subcategoriesCount = subcategories.size,
                    isExpanded = isExpanded,
                    onToggleExpand = if (hasSubcategories) {
                        {
                            expandedCategoryIds = if (isExpanded) {
                                expandedCategoryIds - category.id
                            } else {
                                expandedCategoryIds + category.id
                            }
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

private val Category.key inline get() = "category-$id"
