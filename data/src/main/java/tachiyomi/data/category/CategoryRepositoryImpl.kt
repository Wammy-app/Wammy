package tachiyomi.data.category

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.model.toHierarchicalCategoryList
import tachiyomi.domain.category.repository.CategoryRepository

class CategoryRepositoryImpl(
    private val database: Database,
) : CategoryRepository {

    override suspend fun get(id: Long): Category? {
        return database.categoriesQueries.getCategory(id, ::mapCategory).awaitAsOneOrNull()
    }

    override suspend fun getAll(): List<Category> {
        return database.categoriesQueries.getCategories(::mapCategory).awaitAsList().toHierarchicalCategoryList()
    }

    override fun getAllAsFlow(): Flow<List<Category>> {
        return database.categoriesQueries.getCategories(::mapCategory).subscribeToList()
            .map { it.toHierarchicalCategoryList() }
    }

    override suspend fun getSubcategories(parentId: Long): List<Category> {
        return database.categoriesQueries.getSubcategories(parentId, ::mapCategory).awaitAsList()
    }

    override fun getSubcategoriesAsFlow(parentId: Long): Flow<List<Category>> {
        return database.categoriesQueries.getSubcategories(parentId, ::mapCategory).subscribeToList()
    }

    override suspend fun getTopLevelCategories(): List<Category> {
        return database.categoriesQueries.getTopLevelCategories(::mapCategory).awaitAsList()
    }

    override fun getTopLevelCategoriesAsFlow(): Flow<List<Category>> {
        return database.categoriesQueries.getTopLevelCategories(::mapCategory).subscribeToList()
    }

    override suspend fun getCategoriesByMangaId(mangaId: Long): List<Category> {
        return database.categoriesQueries.getCategoriesByMangaId(mangaId, ::mapCategory).awaitAsList()
    }

    override fun getCategoriesByMangaIdAsFlow(mangaId: Long): Flow<List<Category>> {
        return database.categoriesQueries.getCategoriesByMangaId(mangaId, ::mapCategory).subscribeToList()
    }

    override suspend fun getCategoriesByMangaIds(mangaIds: List<Long>): Map<Long, List<Category>> {
        if (mangaIds.isEmpty()) return emptyMap()

        val resultMap = mutableMapOf<Long, MutableList<Category>>()
        mangaIds.chunked(500).forEach { chunk ->
            database.categoriesQueries.getCategoriesByMangaIds(chunk) { mangaId, id, name, order, flags, contentType, parentId ->
                resultMap.getOrPut(mangaId) { mutableListOf() }
                    .add(mapCategory(id, name, order, flags, contentType, parentId))
            }.awaitAsList()
        }
        return resultMap
    }

    override suspend fun getAllMangaCategoryPairs(): List<Pair<Long, Long>> {
        return database.mangas_categoriesQueries.getAllMangaCategoryPairs { mangaId, categoryId ->
            mangaId to categoryId
        }.awaitAsList()
    }

    override suspend fun getCategoriesByContentType(contentType: Int): List<Category> {
        return database.categoriesQueries.getCategoriesByContentType(contentType.toLong(), ::mapCategory).awaitAsList()
    }

    override fun getCategoriesByContentTypeAsFlow(contentType: Int): Flow<List<Category>> {
        return database.categoriesQueries.getCategoriesByContentType(
            contentType.toLong(),
            ::mapCategory,
        ).subscribeToList()
    }

    override suspend fun insert(category: Category) {
        database.categoriesQueries.insert(
            name = category.name,
            order = category.order,
            flags = category.flags,
            contentType = category.contentType.toLong(),
            parentId = category.parentId,
        )
    }

    override suspend fun updateName(categoryId: Long, name: String) {
        database.categoriesQueries.updateName(name = name, categoryId = categoryId)
    }

    override suspend fun updateParent(categoryId: Long, parentId: Long?) {
        database.categoriesQueries.updateParent(parentId = parentId, categoryId = categoryId)
    }

    override suspend fun updateFlags(categoryId: Long, flags: Long) {
        database.categoriesQueries.updateFlags(flags = flags, categoryId = categoryId)
    }

    override suspend fun updateAllFlags(flags: Long?) {
        database.categoriesQueries.updateAllFlags(flags = flags)
    }

    override suspend fun updateAllOrders(orderedIds: List<Long>) {
        database.transaction {
            orderedIds.forEachIndexed { index, categoryId ->
                database.categoriesQueries.updateOrder(order = index.toLong(), categoryId = categoryId)
            }
        }
    }

    override suspend fun delete(categoryId: Long) {
        database.categoriesQueries.delete(
            categoryId = categoryId,
        )
    }

    override suspend fun deleteOrphanSubcategories() {
        database.categoriesQueries.deleteOrphanSubcategories()
    }

    private fun mapCategory(
        id: Long,
        name: String,
        order: Long,
        flags: Long,
        contentType: Long,
        parentId: Long?,
    ): Category {
        return Category(
            id = id,
            name = name,
            order = order,
            flags = flags,
            contentType = contentType.toInt(),
            parentId = parentId,
        )
    }
}
