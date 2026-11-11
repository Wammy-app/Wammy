package eu.kanade.tachiyomi.ui.manga

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.category.model.Category

class FavoriteCategoryDecisionTest {

    private fun category(id: Long, contentType: Int) = Category(
        id = id,
        name = "Cat $id",
        order = id,
        flags = 0,
        contentType = contentType,
    )

    @Test
    fun `always ask with compatible categories prompts category picker`() {
        val mangaCats = listOf(category(1, Category.CONTENT_TYPE_MANGA))
        assertEquals(
            FavoriteCategoryAction.AskCategory,
            resolveFavoriteCategoryAction(mangaCats, defaultCategoryId = -1L, defaultCategory = null),
        )
    }

    @Test
    fun `explicit default category or empty list skips picker`() {
        val cat = category(1, Category.CONTENT_TYPE_ALL)
        assertEquals(
            FavoriteCategoryAction.AddToDefaultCategory,
            resolveFavoriteCategoryAction(listOf(cat), defaultCategoryId = 1L, defaultCategory = cat),
        )
        assertEquals(
            FavoriteCategoryAction.AddUncategorized,
            resolveFavoriteCategoryAction(emptyList(), defaultCategoryId = -1L, defaultCategory = null),
        )
        assertEquals(
            FavoriteCategoryAction.AddUncategorized,
            resolveFavoriteCategoryAction(listOf(cat), defaultCategoryId = 0L, defaultCategory = null),
        )
    }

    @Test
    fun `space separation filters categories to matching content type plus all`() {
        val cats = listOf(
            category(1, Category.CONTENT_TYPE_ALL),
            category(2, Category.CONTENT_TYPE_MANGA),
            category(3, Category.CONTENT_TYPE_NOVEL),
        )
        assertEquals(listOf(1L, 2L), filterCategoriesForContent(cats, isNovel = false, isJoinedLibrary = false).map { it.id })
        assertEquals(listOf(1L, 3L), filterCategoriesForContent(cats, isNovel = true, isJoinedLibrary = false).map { it.id })
        assertEquals(listOf(1L, 2L, 3L), filterCategoriesForContent(cats, isNovel = true, isJoinedLibrary = true).map { it.id })
    }
}
