package eu.kanade.presentation.library.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import tachiyomi.domain.category.model.Category

@Composable
fun SubCategoryChipRow(
    subcategories: List<Category>,
    selectedSubCategoryId: Long?,
    getItemCountForCategory: (Category) -> Int?,
    parentTotalCount: Int?,
    onSelectSubCategory: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (subcategories.isEmpty()) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // "All" Chip
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
        )

        // Child sub-category chips
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
            )
        }
    }
}
