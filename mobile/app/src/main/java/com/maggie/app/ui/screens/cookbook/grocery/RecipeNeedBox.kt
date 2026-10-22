package com.maggie.app.ui.screens.cookbook.grocery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.Need
import com.maggie.app.data.model.RecipeNeedMeal
import com.maggie.app.data.model.RecipeNeedResponse
import com.maggie.app.data.model.label
import com.maggie.app.ui.UiTags
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val MEAL_DAY = DateTimeFormatter.ofPattern("dd/MM")

/** « 24/10 » for a meal's `yyyy-MM-dd` day; the text itself when it is not a date. */
fun mealDayLabel(date: String): String =
    runCatching { LocalDate.parse(date.take(10)).format(MEAL_DAY) }.getOrDefault(date)

/** « Riz au curry · 24/10 · 300 g »: one meal asking for the product. */
fun RecipeNeedMeal.line(): String =
    listOf(name, mealDayLabel(date).takeIf { it.isNotBlank() }, Need(quantity, unit).label()).filterNotNull().joinToString(" · ")

/** « Pour les recettes de la semaine · 800 g »: the box's title; null when no meal needs the product. */
fun RecipeNeedResponse.boxTitle(): String? = recipeNeed?.let { "Pour les recettes de la semaine · ${it.label()}" }

/**
 * What the week's planned meals need of the product being added (MAG-573), with the meals behind
 * the figure. Nothing is drawn when no meal needs it.
 */
@Composable
fun RecipeNeedBox(need: RecipeNeedResponse?, modifier: Modifier = Modifier) {
    val title = need?.boxTitle() ?: return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(12.dp)
            .testTag(UiTags.GROCERY_RECIPE_NEED_BOX),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.titleSmall)
        need.meals.forEach { meal ->
            Row(modifier = Modifier.fillMaxWidth().testTag(UiTags.GROCERY_RECIPE_NEED_MEAL)) {
                Text(
                    text = meal.line(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
