package com.maggie.app.ui.screens.cookbook.recipes

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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maggie.app.data.model.Recipe
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.SubviewItem
import com.maggie.app.ui.components.SubviewRow
import com.maggie.app.ui.components.filledFieldColors
import com.maggie.app.ui.theme.maggieSurface
import java.util.Locale

/** Cuisine › Recettes as the mock-up draws it (MAG-628): the tag pills, a search field, then a row per recipe. */
@Composable
fun RecipeListScreen(
    viewModel: RecipeListViewModel,
    onRecipeClick: (String) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    // The view model may have loaded before the session existed; opening the list asks again.
    LaunchedEffect(viewModel) { viewModel.refresh() }

    when {
        uiState.isLoading && uiState.recipes.isEmpty() -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        uiState.recipes.isEmpty() -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Aucune recette", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        else -> {
            Column(modifier = Modifier.fillMaxSize()) {
                SubviewRow(
                    items = recipePills(uiState.tags),
                    selectedKey = uiState.tag?.let { TAG_KEY_PREFIX + it } ?: ALL_KEY,
                    onSelect = { item ->
                        viewModel.filterBy(if (item.key == ALL_KEY) null else item.key.removePrefix(TAG_KEY_PREFIX))
                    },
                )
                TextField(
                    value = uiState.query,
                    onValueChange = viewModel::search,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag(UiTags.RECIPE_SEARCH_FIELD),
                    placeholder = { Text("Rechercher une recette") },
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
                        Text("Aucune recette ne correspond.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(visible, key = { it.id }) { recipe ->
                            RecipeRow(recipe = recipe, facts = uiState.facts(recipe), onClick = { onRecipeClick(recipe.id) })
                        }
                    }
                }
            }
        }
    }
}

/** The first letter of the name, on a disc: the list has no pictures to show. */
internal fun recipeInitial(name: String): String =
    name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"

/** « Toutes », then one pill per tag, written with a capital; the key keeps the tag as stored. */
internal fun recipePills(tags: List<String>): List<SubviewItem> =
    listOf(SubviewItem(ALL_KEY, "Toutes")) +
        tags.map { SubviewItem(TAG_KEY_PREFIX + it, it.replaceFirstChar { c -> c.titlecase(Locale.FRANCE) }) }

/** The pill at the right of a row: the first non-blank tag, in lower case. */
internal fun recipeChip(recipe: Recipe): String? =
    recipe.tags.firstOrNull { it.isNotBlank() }?.trim()?.lowercase(Locale.FRANCE)

@Composable
private fun RecipeRow(recipe: Recipe, facts: String?, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .background(maggieSurface().paper, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(48.dp)
                .background(scheme.primary.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
                .testTag(UiTags.RECIPE_ROW_AVATAR),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = recipeInitial(recipe.name),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = scheme.primary,
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = recipe.name,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            facts?.let {
                Text(
                    text = it,
                    fontSize = 13.sp,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.testTag(UiTags.RECIPE_ROW_FACTS),
                )
            }
        }
        recipeChip(recipe)?.let {
            Text(
                text = it,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier
                    .heightIn(min = 24.dp)
                    .background(scheme.onSurface.copy(alpha = 0.06f), RoundedCornerShape(999.dp))
                    .padding(horizontal = 9.dp, vertical = 4.dp),
            )
        }
    }
}

private const val ALL_KEY = "all"
private const val TAG_KEY_PREFIX = "tag:"
