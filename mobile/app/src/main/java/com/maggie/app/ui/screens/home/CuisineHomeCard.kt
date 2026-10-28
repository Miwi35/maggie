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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.HomeDay
import com.maggie.app.data.model.CuisineHomeMeal
import com.maggie.app.data.model.CuisineHomeSlot
import com.maggie.app.data.model.CuisineHomeState
import com.maggie.app.data.model.label
import com.maggie.app.data.model.missingLabel
import com.maggie.app.data.model.recipesLabel
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.navigation.Screen
import com.maggie.app.ui.screens.cookbook.cooking.CookingResume
import com.maggie.app.ui.screens.cookbook.cooking.CookingResumeViewModel
import com.maggie.app.ui.screens.cookbook.cooking.resumesMeal
import org.koin.androidx.compose.koinViewModel

/**
 * The Cuisine card of the Accueil (MAG-564): the meals of the selected day, lunch then dinner, each with
 * all its recipes, its guests and what its recipes lack. A day with no meal draws no card (MAG-594). A tap anywhere opens Cuisine, which opens on « Repas ».
 */
@Composable
fun CuisineHomeCard(
    day: HomeDay,
    viewModel: CuisineHomeViewModel = koinViewModel(),
    resumeViewModel: CookingResumeViewModel = koinViewModel(),
) {
    val ui by viewModel.uiState.collectAsState()
    val resume by resumeViewModel.resume.collectAsState()
    val open = LocalHomeNavigation.current
    LaunchedEffect(day) { viewModel.selectDay(day) }
    CuisineHomeCardContent(
        ui = ui,
        onOpen = { open(Screen.Cookbook.route) },
        onRetry = viewModel::refresh,
        day = day,
        resume = resume,
        onLaunch = { mealId -> open(Screen.CookingActivity.ofMeal(mealId)) },
    )
}

@Composable
internal fun CuisineHomeCardContent(
    ui: CuisineHomeUiState,
    onOpen: () -> Unit,
    onRetry: () -> Unit,
    day: HomeDay = HomeDay.TODAY,
    resume: CookingResume? = null,
    onLaunch: (String) -> Unit = {},
) {
    ReportHomeCard(HomeModules.CUISINE, ui.status)
    if (ui.status == HomeCardStatus.EMPTY) return

    HomeCard(title = "Cuisine", module = HomeModules.CUISINE, onOpen = onOpen) {
        val state = ui.state
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(onOpen) { detectTapGestures { onOpen() } },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                state != null && !state.isEmpty -> CuisineHomeBody(state, day, resume, onLaunch)
                ui.failed && !ui.isLoading -> Unit
                else -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        if (ui.failed && !ui.isLoading) CuisineHomeError(onRetry)
    }
}

@Composable
private fun CuisineHomeBody(state: CuisineHomeState, day: HomeDay, resume: CookingResume?, onLaunch: (String) -> Unit) {
    state.slots.forEach { slot -> key(slot.slot) { CuisineHomeSlotRow(slot, day, resume, onLaunch) } }
}

@Composable
private fun CuisineHomeSlotRow(slot: CuisineHomeSlot, day: HomeDay, resume: CookingResume?, onLaunch: (String) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().testTag(UiTags.HOME_CUISINE_SLOT)) {
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
                    modifier = Modifier.testTag(UiTags.HOME_CUISINE_EMPTY),
                )
            }
            slot.meals.forEach { meal -> key(meal.id) { CuisineHomeMealRow(meal, day, resume, onLaunch) } }
        }
    }
}

@Composable
private fun CuisineHomeMealRow(meal: CuisineHomeMeal, day: HomeDay, resume: CookingResume?, onLaunch: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().testTag(UiTags.HOME_CUISINE_MEAL)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            // The guests sit under the name: beside it they squeezed a long name until it broke inside a word.
            Column(modifier = Modifier.weight(1f)) {
                Text(text = meal.recipesLabel(), style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "${meal.guests} pers.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            // « ▶ Lancer » opens the Cuisine activity on this meal (MAG-580), « ▶ Reprendre » when it is the one in progress
            // (MAG-615); nothing for tomorrow, nor for a meal with no recipe.
            if (meal.recipes.isNotEmpty()) {
                ActivityLaunchButton(
                    module = HomeModules.CUISINE,
                    activity = "la cuisine de ${meal.recipesLabel()}",
                    day = day,
                    onLaunch = { onLaunch(meal.id) },
                    resume = resume.resumesMeal(meal.id),
                )
            }
        }
        meal.missingLabel()?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.testTag(UiTags.HOME_CUISINE_MISSING),
            )
        }
    }
}

@Composable
private fun CuisineHomeError(onRetry: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(UiTags.HOME_CUISINE_ERROR),
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
        TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.HOME_CUISINE_RETRY)) {
            Text("Réessayer")
        }
    }
}
