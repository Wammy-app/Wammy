package tachiyomi.domain.category.interactor

import logcat.LogPriority
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.model.toHierarchicalCategoryList
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.library.service.LibraryPreferences

class CreateCategoryWithName(
    private val categoryRepository: CategoryRepository,
    private val preferences: LibraryPreferences,
) {

    private val initialFlags: Long
        get() {
            val sort = preferences.sortingMode.get()
            return sort.type.flag or sort.direction.flag
        }

    suspend fun await(
        name: String,
        contentType: Int = Category.CONTENT_TYPE_ALL,
        parentId: Long? = null,
    ): Result = withNonCancellableContext {
        val categories = categoryRepository.getAll()
        val nextOrder = (categories.maxOfOrNull { it.order } ?: -1) + 1
        val newCategory = Category(
            id = 0,
            name = name,
            order = nextOrder,
            flags = initialFlags,
            contentType = contentType,
            parentId = parentId,
        )

        try {
            categoryRepository.insert(newCategory)

            // Re-normalize all categories into hierarchical order sequentially
            val all = categoryRepository.getAll()
                .filterNot(Category::isSystemCategory)
                .toHierarchicalCategoryList()
            categoryRepository.updateAllOrders(orderedIds = all.map { it.id })

            Result.Success
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            Result.InternalError(e)
        }
    }

    sealed interface Result {
        data object Success : Result
        data class InternalError(val error: Throwable) : Result
    }
}
