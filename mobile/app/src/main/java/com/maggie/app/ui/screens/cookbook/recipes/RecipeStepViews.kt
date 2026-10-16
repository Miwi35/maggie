package com.maggie.app.ui.screens.cookbook.recipes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.Recipe
import com.maggie.app.ui.UiTags

/** The equipment of a recipe, then its numbered steps; nothing at all for a recipe that has neither. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RecipeMethod(recipe: Recipe) {
    if (recipe.utensils.isNotEmpty()) {
        HorizontalDivider()
        Text("Matériel", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            recipe.utensils.forEach { utensil ->
                AssistChip(onClick = {}, label = { Text(utensil.name) }, modifier = Modifier.testTag(UiTags.RECIPE_UTENSIL))
            }
        }
    }

    if (recipe.steps.isNotEmpty()) {
        HorizontalDivider()
        Text("Étapes", style = MaterialTheme.typography.titleSmall)
        recipe.steps.sortedBy { it.position }.forEach { step ->
            Row(
                modifier = Modifier.fillMaxWidth().testTag(UiTags.RECIPE_STEP),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = step.position.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(24.dp),
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(step.text, style = MaterialTheme.typography.bodyLarge)
                    stepDurationLabel(step)?.let {
                        Text(it, style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag(UiTags.RECIPE_STEP_DURATION))
                    }
                    stepWaitsLabel(step)?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag(UiTags.RECIPE_STEP_WAITS),
                        )
                    }
                    if (step.ingredients.isNotEmpty() || step.utensils.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            step.ingredients.forEach { AssistChip(onClick = {}, label = { Text(it.name) }) }
                            step.utensils.forEach { AssistChip(onClick = {}, label = { Text(it.name) }) }
                        }
                    }
                }
            }
        }
    }
}
