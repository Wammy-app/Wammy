package eu.kanade.presentation.library.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import tachiyomi.domain.category.model.Category
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun SubCategoryChipRow(
    subcategories: List<Category>,
    selectedSubCategoryId: Long?,
    getItemCountForCategory: (Category) -> Int?,
    parentTotalCount: Int?,
    onSelectSubCategory: (Long?) -> Unit,
    onCreateSubCategory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // "All" default chip: always present
        val allSelected = selectedSubCategoryId == null
        FilterChip(
            selected = allSelected,
            onClick = { onSelectSubCategory(null) },
            label = {
                val countText = if (parentTotalCount != null) " ($parentTotalCount)" else ""
                Text(text = "All$countText")
            },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
            modifier = Modifier.height(32.dp),
        )

        // Quick + Add Sub-category button right next to "All"
        Surface(
            onClick = onCreateSubCategory,
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(width = 34.dp, height = 32.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = stringResource(MR.strings.action_add),
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        // Sub-category chips
        subcategories.forEach { child ->
            val isSelected = selectedSubCategoryId == child.id
            val count = getItemCountForCategory(child)
            val countText = if (count != null) " ($count)" else ""
            FilterChip(
                selected = isSelected,
                onClick = {
                    onSelectSubCategory(if (isSelected) null else child.id)
                },
                label = {
                    Text(text = "${child.name}$countText")
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
                modifier = Modifier.height(32.dp),
            )
        }
    }
}
