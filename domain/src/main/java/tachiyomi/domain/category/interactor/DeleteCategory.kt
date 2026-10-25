package tachiyomi.domain.category.interactor

import logcat.LogPriority
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.model.toHierarchicalCategoryList
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences

class DeleteCategory(
    private val categoryRepository: CategoryRepository,
    private val libraryPreferences: LibraryPreferences,
    private val downloadPreferences: DownloadPreferences,
) {

    suspend fun await(categoryId: Long) = withNonCancellableContext {
        if (categoryId == Category.UNCATEGORIZED_ID) {
            return@withNonCancellableContext Result.Success
        }

        // Collect subcategories to cascade deletion and preferences cleanup
        val childCategories = try {
            categoryRepository.getSubcategories(categoryId)
        } catch (_: Exception) {
            emptyList()
        }
        val allAffectedCategoryIds = listOf(categoryId) + childCategories.map { it.id }

        try {
            // Delete child categories explicitly first
            childCategories.forEach { child ->
                categoryRepository.delete(child.id)
            }
            categoryRepository.delete(categoryId)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            return@withNonCancellableContext Result.InternalError(e)
        }

        val orderedIds = categoryRepository.getAll()
            .toHierarchicalCategoryList()
            .filterNot(Category::isSystemCategory)
            .map { it.id }

        val defaultCategory = libraryPreferences.defaultCategory.get()
        if (defaultCategory.toLong() in allAffectedCategoryIds) {
            libraryPreferences.defaultCategory.delete()
        }

        val categoryPreferences = listOf(
            libraryPreferences.updateCategories,
            libraryPreferences.updateCategoriesExclude,
            downloadPreferences.removeExcludeCategories,
            downloadPreferences.downloadNewChapterCategories,
            downloadPreferences.downloadNewChapterCategoriesExclude,
        )
        val categoryIdStrings = allAffectedCategoryIds.map { it.toString() }.toSet()
        categoryPreferences.forEach { preference ->
            val ids = preference.get()
            val remaining = ids.minus(categoryIdStrings)
            if (remaining.size != ids.size) {
                preference.set(remaining)
            }
        }

        try {
            categoryRepository.updateAllOrders(orderedIds = orderedIds)
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
