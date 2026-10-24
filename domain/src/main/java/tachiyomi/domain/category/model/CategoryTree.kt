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

/**
 * Sort a list of categories hierarchically:
 * Top-level categories in order, each followed immediately by all its child categories in order.
 */
fun List<Category>.toHierarchicalCategoryList(): List<Category> {
    val topLevel = filter { it.parentId == null }.sortedBy { it.order }
    val childrenByParent = filter { it.parentId != null }.groupBy { it.parentId }
    val result = ArrayList<Category>(size)
    for (parent in topLevel) {
        result.add(parent)
        val children = childrenByParent[parent.id]?.sortedBy { it.order }
        if (children != null) {
            result.addAll(children)
        }
    }
    // Append any orphan sub-categories whose parent does not exist in topLevel
    val remaining = filter { it.parentId != null && topLevel.none { p -> p.id == it.parentId } }
    if (remaining.isNotEmpty()) {
        result.addAll(remaining.sortedBy { it.order })
    }
    return result
}
