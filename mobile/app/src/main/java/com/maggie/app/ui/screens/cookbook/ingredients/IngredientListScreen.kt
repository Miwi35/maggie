package com.maggie.app.ui.screens.cookbook.ingredients

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maggie.app.data.model.Ingredient
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.SubviewRow
import com.maggie.app.ui.components.filledFieldColors
import com.maggie.app.ui.theme.maggieSurface

private val WarningOrange = Color(0xFFF0A35E)

/** Cuisine › Ingrédients as the mock-up draws it (MAG-1057): the state pills, a search field, a card per ingredient. */
@Composable
fun IngredientListScreen(
    viewModel: IngredientListViewModel,
    onIngredientClick: (String) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(viewModel) { viewModel.refresh() }

    when {
        uiState.isLoading && uiState.ingredients.isEmpty() -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        uiState.ingredients.isEmpty() -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Aucun ingrédient", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        else -> {
            Column(modifier = Modifier.fillMaxSize()) {
                SubviewRow(
                    items = ingredientPills(uiState.ingredients),
                    selectedKey = uiState.pill.key,
                    onSelect = { item -> viewModel.filterBy(IngredientPill.entries.first { it.key == item.key }) },
                )
                TextField(
                    value = uiState.query,
                    onValueChange = viewModel::search,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag(UiTags.INGREDIENT_SEARCH_FIELD),
                    placeholder = { Text("Rechercher un ingrédient") },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = filledFieldColors(),
                )
                val visible = uiState.visible
                if (visible.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Aucun ingrédient ne correspond.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(visible, key = { it.id }) { ingredient ->
                            IngredientRow(ingredient = ingredient, onClick = { onIngredientClick(ingredient.id) })
                        }
                    }
                }
            }
        }
    }
}

internal fun ingredientInitial(name: String): String =
    name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"

@Composable
private fun IngredientRow(ingredient: Ingredient, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val facts = ingredientFacts(ingredient)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .background(maggieSurface().paper, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .testTag(UiTags.INGREDIENT_ROW),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(44.dp).background(scheme.primary.copy(alpha = 0.12f), RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = ingredientInitial(ingredient.name),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = scheme.primary,
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = ingredient.name,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            facts?.let { Text(text = it, fontSize = 13.sp, color = scheme.onSurfaceVariant) }
        }
        when (ingredientValuesState(ingredient)) {
            IngredientValuesState.CIQUAL -> StatePill("Ciqual", scheme.onSurfaceVariant, scheme.onSurface.copy(alpha = 0.06f))
            IngredientValuesState.NONE -> StatePill("Sans valeurs", WarningOrange, WarningOrange.copy(alpha = 0.14f))
            null -> Unit
        }
    }
}

@Composable
private fun StatePill(label: String, color: Color, background: Color) {
    Text(
        text = label,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = color,
        maxLines = 1,
        modifier = Modifier
            .testTag(UiTags.INGREDIENT_ROW_STATE)
            .heightIn(min = 24.dp)
            .background(background, RoundedCornerShape(999.dp))
            .padding(horizontal = 9.dp, vertical = 4.dp),
    )
}
