package com.maggie.app.ui.screens.home

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.HomeDay
import com.maggie.app.data.model.NutritionHomeMeal
import com.maggie.app.data.model.NutritionHomeSlot
import com.maggie.app.data.model.NutritionHomeState
import com.maggie.app.data.model.label
import com.maggie.app.data.model.missingLabel
import com.maggie.app.data.model.recipesLabel
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.navigation.Screen
import org.koin.androidx.compose.koinViewModel

/**
 * The Nutrition card of the Accueil (MAG-564): the meals of the selected day, lunch then dinner, each with
 * all its recipes, its guests and what its recipes lack. A day with no meal draws no card (MAG-594). A tap anywhere opens Cuisine on « Repas ».
 */
@Composable
fun NutritionHomeCard(day: HomeDay, viewModel: NutritionHomeViewModel = koinViewModel()) {
    val ui by viewModel.uiState.collectAsState()
    val open = LocalHomeNavigation.current
    LaunchedEffect(day) { viewModel.selectDay(day) }
    NutritionHomeCardContent(
        ui = ui,
        onOpen = { open(Screen.CookbookMeals.route) },
        onRetry = viewModel::refresh,
        day = day,
        onLaunch = { mealId -> open(Screen.CookingActivity.ofMeal(mealId)) },
    )
}

@Composable
internal fun NutritionHomeCardContent(
    ui: NutritionHomeUiState,
    onOpen: () -> Unit,
    onRetry: () -> Unit,
    day: HomeDay = HomeDay.TODAY,
    onLaunch: (String) -> Unit = {},
) {
    ReportHomeCard(HomeModules.NUTRITION, ui.status)
    if (ui.status == HomeCardStatus.EMPTY) return

    HomeCard(title = "Nutrition", module = HomeModules.NUTRITION, onOpen = onOpen) {
        val state = ui.state
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(onOpen) { detectTapGestures { onOpen() } },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                state != null && !state.isEmpty -> NutritionHomeBody(state, day, onLaunch)
                ui.failed && !ui.isLoading -> Unit
                else -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        if (ui.failed && !ui.isLoading) NutritionHomeError(onRetry)
    }
}

@Composable
private fun NutritionHomeBody(state: NutritionHomeState, day: HomeDay, onLaunch: (String) -> Unit) {
    state.slots.forEach { slot -> key(slot.slot) { NutritionHomeSlotRow(slot, day, onLaunch) } }
}

@Composable
private fun NutritionHomeSlotRow(slot: NutritionHomeSlot, day: HomeDay, onLaunch: (String) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().testTag(UiTags.HOME_NUTRITION_SLOT)) {
        Text(
            text = slot.label(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (slot.meals.isEmpty()) {
                Text(
                    text = "—",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(UiTags.HOME_NUTRITION_EMPTY),
                )
            }
            slot.meals.forEach { meal -> key(meal.id) { NutritionHomeMealRow(meal, day, onLaunch) } }
        }
    }
}

@Composable
private fun NutritionHomeMealRow(meal: NutritionHomeMeal, day: HomeDay, onLaunch: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().testTag(UiTags.HOME_NUTRITION_MEAL)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = meal.recipesLabel(),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            meal.guests?.let {
                Text(
                    text = "$it pers.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            // « ▶ Lancer » opens the Cuisine activity on this meal (MAG-580); nothing for tomorrow, nor for a meal with no recipe.
            if (meal.recipes.isNotEmpty()) {
                ActivityLaunchButton(
                    module = HomeModules.NUTRITION,
                    activity = "la cuisine de ${meal.recipesLabel()}",
                    day = day,
                    onLaunch = { onLaunch(meal.id) },
                )
            }
        }
        meal.missingLabel()?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = homeModuleColor(HomeModules.GROCERY),
                modifier = Modifier.testTag(UiTags.HOME_NUTRITION_MISSING),
            )
        }
    }
}

@Composable
private fun NutritionHomeError(onRetry: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(UiTags.HOME_NUTRITION_ERROR),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "Impossible de lire les repas.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .weight(1f)
                .padding(top = 12.dp),
        )
        TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.HOME_NUTRITION_RETRY)) {
            Text("Réessayer")
        }
    }
}
