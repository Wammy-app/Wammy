package tachiyomi.domain.category.model

/** Group a flat list into a parent-to-children map. */
fun List<Category>.groupByParent(): Map<Long?, List<Category>> =
    groupBy { it.parentId }

/** Get only top-level categories from a list. */
fun List<Category>.topLevel(): List<Category> =
    filter { it.parentId == null }

/** Get children of a specific parent from a list. */
fun List<Category>.childrenOf(parentId: Long): List<Category> =
    filter { it.parentId == parentId }
