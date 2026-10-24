package tachiyomi.domain.category.interactor

import logcat.LogPriority
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.repository.CategoryRepository

class SetCategoryParent(
    private val categoryRepository: CategoryRepository,
) {
    suspend fun await(categoryId: Long, parentId: Long?) = withNonCancellableContext {
        try {
            categoryRepository.updateParent(categoryId, parentId)
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
