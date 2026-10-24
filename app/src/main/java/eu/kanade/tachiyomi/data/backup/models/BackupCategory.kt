package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import tachiyomi.domain.category.model.Category

@Serializable
class BackupCategory(
    @ProtoNumber(1) var name: String,
    @ProtoNumber(2) var order: Long = 0,
    @ProtoNumber(3) var id: Long = 0,
    // @ProtoNumber(3) val updateInterval: Int = 0, 1.x value not used in 0.x
    @ProtoNumber(100) var flags: Long = 0,
    // Content type (0=all, 1=manga, 2=novel). Fork-reserved 8000+ ProtoNumber block.
    @ProtoNumber(8001) var contentType: Int = Category.CONTENT_TYPE_ALL,
    @ProtoNumber(8002) var parentId: Long? = null,
    @ProtoNumber(8003) var parentName: String? = null,
) {
    fun toCategory(id: Long, parentId: Long? = this@BackupCategory.parentId) = Category(
        id = id,
        name = this@BackupCategory.name,
        flags = this@BackupCategory.flags,
        order = this@BackupCategory.order,
        contentType = this@BackupCategory.contentType,
        parentId = parentId,
    )
}

val backupCategoryMapper: (Category, List<Category>) -> BackupCategory = { category, allCategories ->
    BackupCategory(
        id = category.id,
        name = category.name,
        order = category.order,
        flags = category.flags,
        contentType = category.contentType,
        parentId = category.parentId,
        parentName = when (category.parentId) {
            null -> null
            Category.UNCATEGORIZED_ID -> ""
            else -> allCategories.find { it.id == category.parentId }?.name
        },
    )
}
