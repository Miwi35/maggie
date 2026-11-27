package com.maggie.app.ui.screens.cookbook.grocery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maggie.app.data.model.Need
import com.maggie.app.data.model.RecipeNeedMeal
import com.maggie.app.data.model.RecipeNeedResponse
import com.maggie.app.data.model.label
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.theme.maggieSurface
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val MEAL_DAY = DateTimeFormatter.ofPattern("EEE", Locale.FRENCH)

/** « Riz cantonais mer. 500 g »: one meal asking for the product, its short day when the date is one. */
fun RecipeNeedMeal.shortLine(): String {
    val day = runCatching { LocalDate.parse(date.take(10)).format(MEAL_DAY) }.getOrNull()
    return listOfNotNull(name, day, Need(quantity, unit).label()).joinToString(" ")
}

/**
 * What the week's planned meals need of the product being added (MAG-573), with the meals behind
 * the figure. Nothing is drawn when no meal needs it.
 */
@Composable
fun RecipeNeedBox(need: RecipeNeedResponse?, modifier: Modifier = Modifier) {
    val headline = need?.recipeNeed ?: return
    val surface = maggieSurface()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(surface.background, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag(UiTags.GROCERY_RECIPE_NEED_BOX),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(text = "Pour les recettes de la semaine", fontSize = 14.sp, color = surface.textMuted)
            Text(text = headline.label(), fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
        if (need.meals.isNotEmpty()) {
            Text(
                text = need.meals.joinToString(" · ") { it.shortLine() },
                fontSize = 13.sp,
                color = surface.textMuted,
                modifier = Modifier.testTag(UiTags.GROCERY_RECIPE_NEED_MEAL),
            )
        }
    }
}
