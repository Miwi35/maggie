package com.maggie.app.ui.screens.cookbook.meals

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maggie.app.data.model.Ingredient
import com.maggie.app.data.model.Meal
import com.maggie.app.data.model.MealSlot
import com.maggie.app.data.model.Recipe
import com.maggie.app.data.model.quantityLabel
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.GuestsStepper
import com.maggie.app.ui.screens.cookbook.recipes.formatMinutes
import com.maggie.app.ui.screens.cookbook.recipes.nutritionPerServing
import com.maggie.app.ui.theme.MaggieTokens
import com.maggie.app.ui.theme.isMaggieDark
import com.maggie.app.ui.theme.maggieSurface
import com.maggie.app.ui.uiTagRoot
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** « 1 convive », « 4 convives ». */
internal fun guestsLabel(guests: Int): String = if (guests > 1) "$guests convives" else "$guests convive"

/** « 20 → 26 octobre », or « 28 octobre → 3 novembre » when the week crosses a month. */
internal fun weekRangeLabel(weekStart: LocalDate): String {
    val end = weekStart.plusDays(6)
    fun month(day: LocalDate) = day.month.getDisplayName(TextStyle.FULL, Locale.FRANCE)
    return if (weekStart.month == end.month) {
        "${weekStart.dayOfMonth} → ${end.dayOfMonth} ${month(end)}"
    } else {
        "${weekStart.dayOfMonth} ${month(weekStart)} → ${end.dayOfMonth} ${month(end)}"
    }
}

/** « Lun. 20 », or « Mar. 21 · aujourd’hui »: the title of a day's block. */
internal fun dayLabel(day: LocalDate, today: LocalDate = LocalDate.now()): String {
    val weekday = day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.FRANCE).replaceFirstChar { it.uppercase() }
    return "$weekday ${day.dayOfMonth}" + if (day == today) " · aujourd’hui" else ""
}

internal fun MealSlot.label(): String = if (this == MealSlot.LUNCH) "Midi" else "Soir"

/**
 * « ≈ 515 kcal par personne · 35 min »: what one of the [guests] brings from the quantities scaled to them,
 * and the time of the steps. Each part only when it is known; null when neither is.
 */
internal fun portionFacts(recipe: Recipe, guests: Int, products: Map<String, Ingredient>): String? {
    val kcal = nutritionPerServing(recipe.ingredients, guests, products)?.kcal
    val minutes = recipe.steps.sumOf { it.durationMinutes ?: 0 }
    return listOfNotNull(
        kcal?.let { "≈ $it kcal par personne" },
        if (minutes > 0) formatMinutes(minutes) else null,
    ).joinToString(" · ").ifEmpty { null }
}

/**
 * Cuisine › Repas as Repas-Tel's list view draws it (MAG-628, MAG-1004): the week, one block per day, one card per meal. A tap
 * on the meal opens its sheet, never plans a second one (MAG-378); the add button of a day does, while a slot is free.
 */
@Composable
fun MealsWeekScreen(
    viewModel: MealsWeekViewModel,
    onCreateMeal: (day: String, slot: String) -> Unit,
    onOpenMeal: (Meal) -> Unit,
    onChooseIngredients: (mealId: String) -> Unit,
    onLaunchRecipe: (recipeId: String) -> Unit,
    today: LocalDate = LocalDate.now(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var mealToDelete by remember { mutableStateOf<Meal?>(null) }

    mealToDelete?.let { meal ->
        AlertDialog(
            onDismissRequest = { mealToDelete = null },
            modifier = Modifier.uiTagRoot(),
            title = { Text("Supprimer ce repas ?") },
            text = { Text(meal.summary) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteMeal(meal)
                        mealToDelete = null
                    },
                    modifier = Modifier.testTag(UiTags.MEAL_DELETE_CONFIRM),
                ) { Text("Supprimer") }
            },
            dismissButton = { TextButton(onClick = { mealToDelete = null }) { Text("Annuler") } },
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = viewModel::previousWeek, modifier = Modifier.testTag(UiTags.MEALS_WEEK_PREVIOUS)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Semaine précédente")
            }
            Text(
                text = weekRangeLabel(uiState.weekStart),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag(UiTags.MEALS_WEEK_LABEL),
            )
            IconButton(onClick = viewModel::nextWeek, modifier = Modifier.testTag(UiTags.MEALS_WEEK_NEXT)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Semaine suivante")
            }
        }

        listOfNotNull(
            uiState.deleteError?.let { it to viewModel::dismissDeleteError },
            uiState.guestsError?.let { it to viewModel::dismissGuestsError },
        ).forEach { (message, dismiss) ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = dismiss) { Text("Fermer") }
            }
        }

        if (uiState.isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            uiState.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            for (dayOffset in 0L..6L) {
                val day = uiState.weekStart.plusDays(dayOffset)
                val slots = uiState.mealsByDaySlot[day].orEmpty()
                val meals = MealSlot.entries.flatMap { slots[it].orEmpty() }
                // The first meal of a day is its lunch; once that is planned, the next one is the dinner.
                val freeSlots = MealSlot.entries.filter { slots[it].isNullOrEmpty() }
                val nextSlot = if (MealSlot.LUNCH in freeSlots) "lunch" else "dinner"

                Text(
                    text = dayLabel(day, today),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (day == today) cuisineHue() else maggieSurface().textMuted,
                    modifier = Modifier.padding(top = 8.dp),
                )
                meals.forEach { meal ->
                    MealCard(
                        meal = meal,
                        uiState = uiState,
                        viewModel = viewModel,
                        onOpen = { onOpenMeal(meal) },
                        onLongPress = { mealToDelete = meal },
                        onChooseIngredients = { onChooseIngredients(meal.id) },
                        onLaunchRecipe = onLaunchRecipe,
                    )
                }
                if (freeSlots.isNotEmpty()) {
                    AddMealButton(
                        label = when (freeSlots.singleOrNull()) {
                            MealSlot.LUNCH -> "Ajouter le midi"
                            MealSlot.DINNER -> "Ajouter le soir"
                            else -> "Ajouter un repas"
                        },
                        onClick = { onCreateMeal(day.toString(), nextSlot) },
                        modifier = Modifier.testTag(UiTags.mealAddDay(dayOffset.toInt())),
                    )
                }
            }
            Spacer(Modifier.size(16.dp))
        }
    }
}

@Composable
private fun cuisineHue() = MaggieTokens.Module.cuisine.let { if (isMaggieDark()) it.second else it.first }

@Composable
private fun AddMealButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val surface = maggieSurface()
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = modifier.fillMaxWidth()
            .heightIn(min = 48.dp)
            .border(1.dp, surface.text.copy(alpha = 0.10f), shape)
            .clip(shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp), tint = cuisineHue())
        Text(label, fontSize = 14.sp, color = surface.caption)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MealCard(
    meal: Meal,
    uiState: MealsWeekUiState,
    viewModel: MealsWeekViewModel,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onChooseIngredients: () -> Unit,
    onLaunchRecipe: (String) -> Unit,
) {
    val recipeIds = meal.recipeIds()
    val surface = maggieSurface()
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(surface.paper, RoundedCornerShape(16.dp))
            .testTag(UiTags.MEAL_CARD)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier.weight(1f)
                    .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
                    .testTag(UiTags.MEAL_ROW),
            ) {
                Text(meal.slot.label(), fontSize = 12.sp, color = surface.caption)
                // The menu names itself after its recipes, which the card lists below: a title is only worth a line when it says more.
                if (recipeIds.isEmpty() || !meal.customTitle.isNullOrBlank()) {
                    Text(
                        text = meal.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text("Convives", fontSize = 12.sp, color = cuisineHue())
            GuestsStepper(
                value = meal.guests,
                onStep = { viewModel.stepGuests(meal.id, it) },
                tag = UiTags.MEAL_CARD_GUESTS,
            )
        }

        recipeIds.forEach { recipeId ->
            RecipePortionsBlock(
                portions = uiState.portions[MealRecipeKey(meal.id, recipeId)],
                products = uiState.products,
                onStep = { viewModel.stepPortions(meal.id, recipeId, it) },
                onLaunch = { onLaunchRecipe(recipeId) },
            )
        }

        if (recipeIds.isNotEmpty()) {
            TextButton(onClick = onChooseIngredients, modifier = Modifier.testTag(UiTags.MEAL_CARD_INGREDIENTS)) {
                Text("Ingrédients sur la liste")
            }
        }
    }
}

/** One recipe of a meal: « Lancer », its portions (a simulation, nothing saved) and what that many people bring. */
@Composable
private fun RecipePortionsBlock(
    portions: RecipePortions?,
    products: Map<String, Ingredient>,
    onStep: (Int) -> Unit,
    onLaunch: () -> Unit,
) {
    val recipe = portions?.recipe
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = recipe?.name ?: "Recette",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onLaunch, modifier = Modifier.testTag(UiTags.MEAL_RECIPE_LAUNCH)) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Lancer")
            }
        }
        if (portions != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Portions", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                GuestsStepper(value = portions.guests, onStep = onStep, tag = UiTags.MEAL_RECIPE_PORTIONS)
                if (portions.isLoading) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
            recipe?.let { scaled ->
                portionFacts(scaled, portions.guests, products)?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag(UiTags.MEAL_RECIPE_FACTS),
                    )
                }
                if (scaled.ingredients.isNotEmpty()) {
                    Text(
                        text = scaled.ingredients.joinToString(" · ") { "${it.ingredientName.orEmpty()} ${it.quantityLabel()}".trim() },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            portions.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
