package eu.kanade.presentation.category.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.category.visualName
import sh.calvin.reorderable.ReorderableCollectionItemScope
import tachiyomi.domain.category.model.Category
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun ReorderableCollectionItemScope.CategoryListItem(
    category: Category,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    parentName: String? = null,
    isChild: Boolean = false,
    isFirstChild: Boolean = false,
    isLastChild: Boolean = false,
    onAddSubCategory: (() -> Unit)? = null,
) {
    if (isChild) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val lineColor = MaterialTheme.colorScheme.outlineVariant
            Box(
                modifier = Modifier
                    .width(36.dp)
                    .fillMaxHeight(),
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val strokeWidth = 2.dp.toPx()
                    val trunkX = 18.dp.toPx()
                    val branchY = size.height / 2f
                    val gap = 8.dp.toPx()
                    val topY = -gap
                    val bottomY = if (isLastChild) branchY else size.height + gap

                    // Vertical trunk line
                    drawLine(
                        color = lineColor,
                        start = Offset(trunkX, topY),
                        end = Offset(trunkX, bottomY),
                        strokeWidth = strokeWidth,
                    )

                    // Horizontal branch tick connecting to sub-category card
                    drawLine(
                        color = lineColor,
                        start = Offset(trunkX, branchY),
                        end = Offset(size.width, branchY),
                        strokeWidth = strokeWidth,
                    )
                }
            }

            ElevatedCard(
                modifier = Modifier.weight(1f),
            ) {
                CategoryCardContent(
                    category = category,
                    isChild = true,
                    parentName = parentName,
                    onRename = onRename,
                    onDelete = onDelete,
                    onAddSubCategory = null,
                )
            }
        }
    } else {
        ElevatedCard(
            modifier = modifier.fillMaxWidth(),
        ) {
            CategoryCardContent(
                category = category,
                isChild = false,
                parentName = null,
                onRename = onRename,
                onDelete = onDelete,
                onAddSubCategory = onAddSubCategory,
            )
        }
    }
}

@Composable
private fun ReorderableCollectionItemScope.CategoryCardContent(
    category: Category,
    isChild: Boolean,
    parentName: String?,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onAddSubCategory: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (!category.isSystemCategory) {
                    Modifier.clickable(onClick = onRename)
                } else {
                    Modifier
                },
            )
            .padding(vertical = MaterialTheme.padding.small)
            .padding(
                start = MaterialTheme.padding.small,
                end = MaterialTheme.padding.medium,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!category.isSystemCategory) {
            Icon(
                imageVector = Icons.Outlined.DragHandle,
                contentDescription = null,
                modifier = Modifier
                    .padding(MaterialTheme.padding.medium)
                    .draggableHandle(),
            )
        } else {
            Spacer(modifier = Modifier.width(MaterialTheme.padding.medium * 2 + 24.dp))
        }
        Column(
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = category.visualName,
                style = if (isChild) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
            )
            val contentTypeText = when (category.contentType) {
                Category.CONTENT_TYPE_MANGA -> stringResource(TDMR.strings.category_content_type_manga)
                Category.CONTENT_TYPE_NOVEL -> stringResource(TDMR.strings.category_content_type_novel)
                else -> stringResource(TDMR.strings.category_content_type_all)
            }
            val subtitleText = if (isChild && !parentName.isNullOrBlank()) {
                "Sub-category of $parentName • $contentTypeText"
            } else {
                contentTypeText
            }
            Text(
                text = subtitleText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onAddSubCategory != null) {
            IconButton(onClick = onAddSubCategory) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = "Add sub-category",
                )
            }
        }
        if (!category.isSystemCategory) {
            IconButton(onClick = onRename) {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = stringResource(MR.strings.action_rename_category),
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = stringResource(MR.strings.action_delete),
                )
            }
        }
    }
}
