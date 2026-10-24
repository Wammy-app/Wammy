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
            onClickCreate = { viewModel.showDialog(CategoryDialog.Create) },
            onClickRename = { viewModel.showDialog(CategoryDialog.Rename(it)) },
            onClickDelete = { viewModel.showDialog(CategoryDialog.Delete(it)) },
            onChangeOrder = viewModel::changeOrder,
            navigateUp = navigator::pop,
        )

        when (val dialog = successState.dialog) {
            null -> {}
            CategoryDialog.Create -> {
                val parentCandidates = successState.categories.topLevel()
                CategoryCreateDialog(
                    onDismissRequest = viewModel::dismissDialog,
                    onCreate = { name, contentType, parentId -> viewModel.createCategory(name, contentType, parentId) },
                    categories = successState.categories.fastMap { it.name },
                    parentCategories = parentCandidates,
                )
            }
            is CategoryDialog.Rename -> {
                val parentCandidates = successState.categories.filter { it.isTopLevel && it.id != dialog.category.id }
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
                CategoryDeleteDialog(
                    onDismissRequest = viewModel::dismissDialog,
                    onDelete = { viewModel.deleteCategory(dialog.category.id) },
                    category = dialog.category.name,
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
