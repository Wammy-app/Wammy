package eu.kanade.tachiyomi.ui.category

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.util.fastMap
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.category.CategoryScreen
import eu.kanade.presentation.category.components.CategoryCreateDialog
import eu.kanade.presentation.category.components.CategoryDeleteDialog
import eu.kanade.presentation.category.components.CategoryRenameDialog
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.flow.collectLatest
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.model.topLevel
import tachiyomi.presentation.core.screens.LoadingScreen

class CategoryScreen : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = viewModel<CategoryViewModel>()

        val state by viewModel.state.collectAsState()

        if (state is CategoryScreenState.Loading) {
            LoadingScreen()
            return
        }

        val successState = state as CategoryScreenState.Success

        CategoryScreen(
            state = successState,
            onClickCreate = { viewModel.showDialog(CategoryDialog.Create()) },
            onClickRename = { viewModel.showDialog(CategoryDialog.Rename(it)) },
            onClickDelete = { viewModel.showDialog(CategoryDialog.Delete(it)) },
            onChangeOrder = viewModel::changeOrder,
            navigateUp = navigator::pop,
            onClickAddSubCategory = { viewModel.showDialog(CategoryDialog.Create(defaultParentId = it.id)) },
        )

        when (val dialog = successState.dialog) {
            null -> {}
            is CategoryDialog.Create -> {
                val defaultCat = Category(
                    id = Category.UNCATEGORIZED_ID,
                    name = "",
                    order = -1,
                    flags = 0,
                )
                val topLevelCats = successState.categories.topLevel().filterNot { it.isSystemCategory }
                val parentCandidates = listOf(defaultCat) + topLevelCats
                CategoryCreateDialog(
                    onDismissRequest = viewModel::dismissDialog,
                    onCreate = { name, contentType, parentId -> viewModel.createCategory(name, contentType, parentId) },
                    categories = successState.categories.fastMap { it.name },
                    parentCategories = parentCandidates,
                    defaultParentId = dialog.defaultParentId,
                )
            }
            is CategoryDialog.Rename -> {
                val defaultCat = Category(
                    id = Category.UNCATEGORIZED_ID,
                    name = "",
                    order = -1,
                    flags = 0,
                )
                val topLevelCats = successState.categories.filter { it.isTopLevel && it.id != dialog.category.id && !it.isSystemCategory }
                val parentCandidates = listOf(defaultCat) + topLevelCats
                CategoryRenameDialog(
                    onDismissRequest = viewModel::dismissDialog,
                    onRename = { newName, newParentId -> viewModel.renameCategory(dialog.category, newName, newParentId) },
                    categories = successState.categories.fastMap { it.name },
                    category = dialog.category.name,
                    currentParentId = dialog.category.parentId,
                    parentCategories = parentCandidates,
                )
            }
            is CategoryDialog.Delete -> {
                val hasSubcategories = successState.categories.any { it.parentId == dialog.category.id }
                CategoryDeleteDialog(
                    onDismissRequest = viewModel::dismissDialog,
                    onDelete = { viewModel.deleteCategory(dialog.category.id) },
                    category = dialog.category.name,
                    hasSubcategories = hasSubcategories,
                )
            }
        }

        LaunchedEffect(Unit) {
            viewModel.events.collectLatest { event ->
                if (event is CategoryEvent.LocalizedMessage) {
                    context.toast(event.stringRes)
                }
            }
        }
    }
}
