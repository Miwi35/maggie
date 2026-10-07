package com.maggie.app.ui.screens.cookbook.recipes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.maggie.app.data.api.PlannedMealRef
import com.maggie.app.data.api.RecipeDeletionImpact
import com.maggie.app.data.repository.RecipeRepository
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecipeDetailScreen(
    recipeId: String,
    recipeRepository: RecipeRepository,
    viewModel: RecipeDetailViewModel,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(recipe?.name ?: "Recette") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    IconButton(onClick = { onEdit(recipeId) }) {
                        Icon(Icons.Default.Edit, contentDescription = "Modifier")
                    }
                    IconButton(onClick = { confirmingDelete = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Supprimer")
                    }
                },
            )
        },
    ) { paddingValues ->
        if (confirmingDelete) {
            AlertDialog(
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
                val r = recipe
                Column(
                    modifier = Modifier.fillMaxSize().padding(paddingValues)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text("${r.servings} portions", style = MaterialTheme.typography.titleMedium)

                    if (r.tags.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            r.tags.forEach { tag ->
                                AssistChip(onClick = {}, label = { Text(tag) })
                            }
                        }
                    }

                    r.notes?.let { notes ->
                        Text(text = notes, style = MaterialTheme.typography.bodyMedium)
                    }

                    if (r.ingredients.isNotEmpty()) {
                        HorizontalDivider()
                        Text("Ingrédients", style = MaterialTheme.typography.titleSmall)
                        r.ingredients.forEach { ing ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(text = ing.ingredientName.orEmpty(), modifier = Modifier.weight(1f))
                                Text(text = "${ing.quantity} ${ing.unit.name.lowercase()}")
                            }
                        }
                    }

                    // Nutrition summary placeholder — will populate when ingredients carry macros
                    if (r.ingredients.isNotEmpty()) {
                        HorizontalDivider()
                        Text("Nutrition (par portion)", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "Les données nutritionnelles seront affichées lorsque les ingrédients seront liés à la base Ciqual.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
