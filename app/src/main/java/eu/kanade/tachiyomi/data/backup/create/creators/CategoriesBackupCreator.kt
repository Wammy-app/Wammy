package eu.kanade.tachiyomi.data.backup.create.creators

import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.backupCategoryMapper
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class CategoriesBackupCreator(
    private val getCategories: GetCategories = Injekt.get(),
) {

    suspend operator fun invoke(): List<BackupCategory> {
        val categories = getCategories.await()
            .filterNot(Category::isSystemCategory)
        return categories.map { backupCategoryMapper(it, categories) }
    }
}
