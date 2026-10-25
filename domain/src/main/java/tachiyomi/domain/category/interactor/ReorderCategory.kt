package tachiyomi.domain.category.interactor

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository

class ReorderCategory(
    private val categoryRepository: CategoryRepository,
) {
    private val mutex = Mutex()

    suspend fun await(category: Category, newIndex: Int) = withNonCancellableContext {
        if (category.isSystemCategory) {
            return@withNonCancellableContext Result.Unchanged
        }
        mutex.withLock {
            val categories = categoryRepository.getAll()
                .filterNot(Category::isSystemCategory)
                .toMutableList()

            val currentIndex = categories.indexOfFirst { it.id == category.id }
            if (currentIndex == -1) {
                return@withNonCancellableContext Result.Unchanged
            }

            try {
                if (category.parentId == null) {
                    // Moving a top-level category: collect parent and all its children to move as a block
                    val children = categories.filter { it.parentId == category.id }
                    val block = listOf(category) + children
                    categories.removeAll(block)
                    val targetIndex = newIndex.coerceIn(0, categories.size)
                    categories.addAll(targetIndex, block)
                } else {
                    // Moving a child category
                    val item = categories.removeAt(currentIndex)
                    val targetIndex = newIndex.coerceIn(0, categories.size)
                    categories.add(targetIndex, item)
                }

                categoryRepository.updateAllOrders(orderedIds = categories.map { it.id })
                Result.Success
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e)
                Result.InternalError(e)
            }
        }
    }

    sealed interface Result {
        data object Success : Result
        data object Unchanged : Result
        data class InternalError(val error: Throwable) : Result
    }
}
