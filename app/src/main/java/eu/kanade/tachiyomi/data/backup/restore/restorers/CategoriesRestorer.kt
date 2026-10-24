package eu.kanade.tachiyomi.data.backup.restore.restorers

import app.cash.sqldelight.async.coroutines.awaitAsOne
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import tachiyomi.data.Database
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.service.LibraryPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class CategoriesRestorer(
    private val database: Database = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
) {

    suspend operator fun invoke(backupCategories: List<BackupCategory>) {
        if (backupCategories.isNotEmpty()) {
            val dbCategories = getCategories.await()
            val dbCategoriesByName = dbCategories.groupBy { it.name }
            var nextOrder = dbCategories.maxOfOrNull { it.order }?.plus(1) ?: 0

            // Pass 1: Insert or match categories (without parent initially to avoid foreign key ordering constraints)
            val categories = backupCategories
                .sortedBy { it.order }
                .map {
                    val dbCategory = if (it.contentType != Category.CONTENT_TYPE_ALL) {
                        dbCategoriesByName[it.name]?.firstOrNull { db -> db.contentType == it.contentType }
                    } else {
                        dbCategoriesByName[it.name]?.firstOrNull()
                    }
                    if (dbCategory != null) return@map dbCategory
                    val order = nextOrder++
                    database.categoriesQueries.insert(
                        name = it.name,
                        order = order,
                        flags = it.flags,
                        contentType = it.contentType.toLong(),
                        parentId = null,
                    )
                    database.categoriesQueries.selectLastInsertedRowId()
                        .awaitAsOne()
                        .let { id -> it.toCategory(id).copy(order = order) }
                }

            // Pass 2: Re-link parent-child relationships using parentName
            val allCurrentCategories = getCategories.await()
            backupCategories.forEach { backupCat ->
                val parentName = backupCat.parentName
                if (parentName != null) {
                    val parent = allCurrentCategories.firstOrNull { it.name == parentName }
                    val child = allCurrentCategories.firstOrNull { it.name == backupCat.name && it.contentType == backupCat.contentType }
                    if (parent != null && child != null && parent.id != child.id) {
                        database.categoriesQueries.updateParent(parentId = parent.id, categoryId = child.id)
                    }
                }
            }

            libraryPreferences.categorizedDisplaySettings.set(
                (dbCategories + categories)
                    .distinctBy { it.flags }
                    .size > 1,
            )
        }
    }
}
