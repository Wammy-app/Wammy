package eu.kanade.tachiyomi.ui.category

import androidx.compose.runtime.Immutable
import androidx.lifecycle.viewModelScope
import dev.icerock.moko.resources.StringResource
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mihon.core.viewmodel.StateViewModel
import tachiyomi.domain.category.interactor.CreateCategoryWithName
import tachiyomi.domain.category.interactor.DeleteCategory
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.RenameCategory
import tachiyomi.domain.category.interactor.ReorderCategory
import tachiyomi.domain.category.interactor.SetCategoryParent
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class CategoryViewModel(
    private val getCategories: GetCategories = Injekt.get(),
    private val createCategoryWithName: CreateCategoryWithName = Injekt.get(),
    private val deleteCategory: DeleteCategory = Injekt.get(),
    private val reorderCategory: ReorderCategory = Injekt.get(),
    private val renameCategory: RenameCategory = Injekt.get(),
    private val setCategoryParent: SetCategoryParent = Injekt.get(),
    private val getLibraryManga: GetLibraryManga = Injekt.get(),
    private val categoryRepository: CategoryRepository = Injekt.get(),
) : StateViewModel<CategoryScreenState>(CategoryScreenState.Loading) {

    private val _events: Channel<CategoryEvent> = Channel()
    val events = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            try {
                categoryRepository.deleteOrphanSubcategories()
            } catch (_: Exception) {}
            getCategories.subscribe()
                .collectLatest { categories ->
                    mutableState.update {
                        CategoryScreenState.Success(
                            categories = categories
                                .filterNot(Category::isSystemCategory),
                        )
                    }
                }
        }
    }

    private fun scheduleDriveSync() {
        try {
            val context = uy.kohesive.injekt.Injekt.get<android.app.Application>()
            eu.kanade.tachiyomi.data.sync.DriveSyncWorker.scheduleBackup(context)
        } catch (_: Exception) {}
    }

    fun createCategory(name: String, contentType: Int = Category.CONTENT_TYPE_ALL, parentId: Long? = null) {
        viewModelScope.launch {
            when (createCategoryWithName.await(name, contentType, parentId)) {
                is CreateCategoryWithName.Result.InternalError -> _events.send(CategoryEvent.InternalError)
                else -> scheduleDriveSync()
            }
        }
    }

    fun deleteCategory(categoryId: Long) {
        viewModelScope.launch {
            val childCategories = try {
                categoryRepository.getSubcategories(categoryId)
            } catch (_: Exception) {
                emptyList()
            }
            val allAffectedIds = listOf(categoryId) + childCategories.map { it.id }

            // Get all manga-category pairs to find manga IDs for this category and subcategories
            val allPairs = categoryRepository.getAllMangaCategoryPairs()
            val mangaIds = allPairs
                .filter { it.second in allAffectedIds }
                .map { it.first }
                .distinct()

            when (deleteCategory.await(categoryId = categoryId)) {
                is DeleteCategory.Result.InternalError -> _events.send(CategoryEvent.InternalError)
                else -> {
                    // Refresh the manga that were in this category to update their category lists
                    if (mangaIds.isNotEmpty()) {
                        getLibraryManga.applyCategoryUpdates(
                            mangaIds = mangaIds,
                            addCategories = emptyList(),
                            removeCategories = allAffectedIds,
                        )
                    }
                    scheduleDriveSync()
                }
            }
        }
    }

    fun changeOrder(category: Category, newIndex: Int) {
        viewModelScope.launch {
            when (reorderCategory.await(category, newIndex)) {
                is ReorderCategory.Result.InternalError -> _events.send(CategoryEvent.InternalError)
                else -> scheduleDriveSync()
            }
        }
    }

    fun renameCategory(category: Category, name: String, parentId: Long? = category.parentId) {
        viewModelScope.launch {
            var failed = false
            if (category.name != name) {
                when (renameCategory.await(category, name)) {
                    is RenameCategory.Result.InternalError -> {
                        failed = true
                        _events.send(CategoryEvent.InternalError)
                    }
                    else -> {}
                }
            }
            if (!failed && category.parentId != parentId) {
                when (setCategoryParent.await(category.id, parentId)) {
                    is SetCategoryParent.Result.InternalError -> {
                        _events.send(CategoryEvent.InternalError)
                    }
                    else -> {}
                }
            }
            scheduleDriveSync()
        }
    }

    fun showDialog(dialog: CategoryDialog) {
        mutableState.update {
            when (it) {
                CategoryScreenState.Loading -> it
                is CategoryScreenState.Success -> it.copy(dialog = dialog)
            }
        }
    }

    fun dismissDialog() {
        mutableState.update {
            when (it) {
                CategoryScreenState.Loading -> it
                is CategoryScreenState.Success -> it.copy(dialog = null)
            }
        }
    }
}

sealed interface CategoryDialog {
    data class Create(val defaultParentId: Long? = null) : CategoryDialog
    data class Rename(val category: Category) : CategoryDialog
    data class Delete(val category: Category) : CategoryDialog
}

sealed interface CategoryEvent {
    sealed class LocalizedMessage(val stringRes: StringResource) : CategoryEvent
    data object InternalError : LocalizedMessage(MR.strings.internal_error)
}

sealed interface CategoryScreenState {

    @Immutable
    data object Loading : CategoryScreenState

    @Immutable
    data class Success(
        val categories: List<Category>,
        val dialog: CategoryDialog? = null,
    ) : CategoryScreenState {
        val isEmpty: Boolean = categories.isEmpty()
    }
}
