package com.maggie.app.ui.screens.cookbook.recipes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.maggie.app.data.api.PlannedMealRef
import com.maggie.app.data.api.RecipeDeletionImpact
import com.maggie.app.data.model.Recipe
import com.maggie.app.data.model.quantityLabel
import com.maggie.app.data.repository.RecipeRepository
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.screens.cookbook.cooking.CookingOrigin
import com.maggie.app.ui.screens.cookbook.cooking.CookingResume
import com.maggie.app.ui.screens.cookbook.cooking.detailFor
import com.maggie.app.ui.screens.cookbook.cooking.resumesRecipe
import com.maggie.app.ui.screens.grocery.SeasonBadge
import com.maggie.app.ui.components.GuestsStepper
import com.maggie.app.ui.uiTagRoot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The question asked before a recipe goes. Deleting it also deletes the meals it
 * was the only recipe of (MAG-289), so the count is part of the question; `null`
 * is a count that could not be read, and asks the plain question.
 */
fun recipeDeletionTitle(name: String, mealCount: Int?): String = when {
    mealCount == null || mealCount <= 0 -> "Supprimer « $name » ?"
    mealCount == 1 -> "Supprimer « $name » et son repas planifié ?"
    else -> "Supprimer « $name » et ses $mealCount repas planifiés ?"
}

private const val LISTED_MEALS = 5

private val dayFormat = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRANCE)

private fun plannedMealLabel(meal: PlannedMealRef): String {
    val day = runCatching { LocalDate.parse(meal.date).format(dayFormat) }.getOrDefault(meal.date)
    return "$day, ${if (meal.slot == "lunch") "midi" else "soir"}"
}

/**
 * What the owner reads under the question: the days he had planned this recipe
 * and that the meals leave with it. `null` is an impact that could not be read.
 */
fun recipeDeletionBody(impact: RecipeDeletionImpact?): String {
    if (impact == null) return "Les repas planifiés qui n’ont que cette recette seront supprimés avec elle."
    if (impact.mealCount <= 0) {
        return "Les repas qui n’ont que cette recette disparaissent de l’agenda et de la liste de courses. Cette action est définitive."
    }

    val listed = impact.meals.take(LISTED_MEALS)
    val rest = impact.mealCount - listed.size
    val lines = listed.map { "• ${plannedMealLabel(it)}" } +
        listOfNotNull(if (rest > 0) (if (rest == 1) "• et 1 autre" else "• et $rest autres") else null)

    return "Vous aviez prévu de cuisiner cette recette :\n" + lines.joinToString("\n") +
        "\n\nCes repas seront supprimés avec elle, de l’agenda comme de la liste de courses. Cette action est définitive."
}

/**
 * « Pour N personnes » (MAG-483): a simulation of the quantities for another number of people. The
 * API does the scaling; nothing is saved, and « Parts de la recette » shows the original again.
 */
@Composable
internal fun GuestsSimulation(
    servings: Int,
    simulation: RecipeSimulation?,
    onStep: (Int) -> Unit,
    onReset: () -> Unit,
) {
    val guests = simulation?.guests ?: servings
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Pour", style = MaterialTheme.typography.bodyLarge)
            GuestsStepper(value = guests, onStep = onStep, tag = UiTags.RECIPE_GUESTS)
            Text(if (guests > 1) "personnes" else "personne", style = MaterialTheme.typography.bodyLarge)
        }
        if (simulation != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Simulation : rien n'est enregistré.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).testTag(UiTags.RECIPE_GUESTS_SIMULATION),
                )
                TextButton(onClick = onReset, modifier = Modifier.testTag(UiTags.RECIPE_GUESTS_RESET)) {
                    Text("Parts de la recette")
                }
            }
            simulation.error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(UiTags.RECIPE_GUESTS_ERROR),
                )
            }
        }
    }
}

/**
 * A `null` [onBack] is the recipe drawn in the detail pane beside the list (MAG-263):
 * no back arrow, and no status-bar inset, the shell's own top bar being right above.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecipeDetailScreen(
    recipeId: String,
    recipeRepository: RecipeRepository,
    viewModel: RecipeDetailViewModel,
    onBack: (() -> Unit)?,
    onEdit: (String) -> Unit,
    onCook: (String) -> Unit,
    onDelete: (String) -> Unit,
    /** The Cuisine in progress (MAG-615): when it cooks this recipe, « Cuisiner » becomes « Reprendre ». */
    resume: CookingResume? = null,
    onResume: (CookingOrigin) -> Unit = {},
) {
    val state by viewModel.uiState.collectAsState()
    val recipe = state.recipe
    val isLoading = state.isLoading
    val error = state.error
    var confirmingDelete by remember { mutableStateOf(false) }
    var impact by remember { mutableStateOf<RecipeDeletionImpact?>(null) }
    var impactLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(confirmingDelete) {
        if (confirmingDelete) {
            impactLoaded = false
            impact = null
            impact = withContext(Dispatchers.IO) {
                recipeRepository.getDeletionImpact(recipeId).getOrNull()
            }
            impactLoaded = true
        }
    }

    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    if (onBack != null) {
                        TextButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Recettes")
                        }
                    }
                },
                windowInsets = if (onBack != null) TopAppBarDefaults.windowInsets else WindowInsets(0),
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag(UiTags.RECIPE_MENU)) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Plus d’actions")
                        }
                        RecipeActionsMenu(
                            expanded = menuOpen,
                            onDismiss = { menuOpen = false },
                            onEdit = {
                                menuOpen = false
                                onEdit(recipeId)
                            },
                            onDelete = {
                                menuOpen = false
                                confirmingDelete = true
                            },
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        if (confirmingDelete) {
            AlertDialog(
                modifier = Modifier.uiTagRoot(),
                onDismissRequest = { confirmingDelete = false },
                title = { Text(recipeDeletionTitle(recipe?.name.orEmpty(), impact?.mealCount)) },
                text = {
                    if (!impactLoaded) {
                        CircularProgressIndicator()
                    } else {
                        Text(recipeDeletionBody(impact))
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = impactLoaded,
                        onClick = {
                            confirmingDelete = false
                            onDelete(recipeId)
                        },
                    ) { Text("Supprimer") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmingDelete = false }) { Text("Annuler") }
                },
            )
        }

        when {
            isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            error != null -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(error, color = MaterialTheme.colorScheme.error)
                }
            }
            recipe != null -> {
                RecipeSheetContent(
                    state = state,
                    recipe = recipe,
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    onCook = { onCook(recipeId) },
                    resume = resume,
                    onResume = onResume,
                    onStepGuests = viewModel::stepGuests,
                    onResetGuests = viewModel::resetSimulation,
                )
            }
        }
    }
}

@Composable
internal fun RecipeActionsMenu(expanded: Boolean, onDismiss: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, modifier = Modifier.uiTagRoot()) {
        DropdownMenuItem(
            text = { Text("Modifier") },
            onClick = onEdit,
            modifier = Modifier.testTag(UiTags.RECIPE_EDIT),
        )
        DropdownMenuItem(
            text = { Text("Supprimer") },
            onClick = onDelete,
            modifier = Modifier.testTag(UiTags.RECIPE_DELETE),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RecipeSheetContent(
    state: RecipeDetailUiState,
    recipe: Recipe,
    modifier: Modifier,
    onCook: () -> Unit,
    onStepGuests: (Int) -> Unit,
    onResetGuests: () -> Unit,
    resume: CookingResume? = null,
    onResume: (CookingOrigin) -> Unit = {},
) {
    val r = recipe
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(r.name, style = MaterialTheme.typography.headlineSmall)
            if (r.tags.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    r.tags.forEach { tag ->
                        AssistChip(onClick = {}, label = { Text(tag) })
                    }
                }
            }
            Text(
                text = recipeSummary(r.steps, state.nutrition, r.servings),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (resume != null && resume.resumesRecipe(r.id)) {
                Button(onClick = { onResume(resume.session.origin) }, modifier = Modifier.testTag(UiTags.RECIPE_COOK_RESUME)) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("Reprendre")
                        resume.detailFor(r.id, r.steps.size)?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag(UiTags.RECIPE_COOK_RESUME_DETAIL))
                        }
                    }
                }
            } else {
                Button(onClick = onCook, modifier = Modifier.testTag(UiTags.RECIPE_COOK)) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Cuisiner")
                }
            }
        }

        GuestsSimulation(
            servings = r.servings,
            simulation = state.simulation,
            onStep = onStepGuests,
            onReset = onResetGuests,
        )

        r.notes?.let { notes ->
            Text(text = notes, style = MaterialTheme.typography.bodyMedium)
        }

        if (r.ingredients.isNotEmpty()) {
            HorizontalDivider()
            Text("Ingrédients", style = MaterialTheme.typography.titleSmall)
            state.ingredients.forEach { ing ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text = ing.ingredientName.orEmpty())
                        SeasonBadge(ing.inSeason)
                    }
                    Text(
                        text = ing.quantityLabel(),
                        modifier = Modifier.testTag(UiTags.RECIPE_INGREDIENT_QUANTITY),
                    )
                }
            }
        }

        RecipeMethod(r)

        state.nutrition?.let {
            HorizontalDivider()
            RecipeNutritionBlock(it, dailyKcal = null)
        }
    }
}
