package com.maggie.app.ui.screens.cookbook.recipes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.CiqualFood
import com.maggie.app.data.model.Utensil
import com.maggie.app.ui.UiTags

/** What the full-screen utensil search is choosing for: the recipe's equipment, or one step's. */
private sealed interface UtensilTarget {
    data object Recipe : UtensilTarget
    data class Step(val key: Int) : UtensilTarget
}

/**
 * The form shared by the new-recipe and the edit sheet. [banner] sits above the fields; the utensil search
 * takes the whole screen, as every search of the app does, and gives it back on a pick or on back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecipeFormScreen(
    title: String,
    saveLabel: String,
    form: RecipeForm,
    isSaving: Boolean,
    error: String?,
    stepErrors: Map<Int, String>,
    edit: ((RecipeForm) -> RecipeForm) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    onSearchCiqual: suspend (String) -> List<CiqualFood>,
    onLoadUtensils: suspend () -> List<Utensil>,
    banner: @Composable () -> Unit = {},
) {
    var target by remember { mutableStateOf<UtensilTarget?>(null) }
    // Above the search's early return, so the form comes back where it was left.
    val scroll = rememberScrollState()

    target?.let { searching ->
        UtensilSearchScreen(
            onLoadUtensils = onLoadUtensils,
            chosen = when (searching) {
                UtensilTarget.Recipe -> form.utensils
                is UtensilTarget.Step -> form.steps.firstOrNull { it.key == searching.key }?.utensils.orEmpty()
            },
            onPick = { picked ->
                edit {
                    when (searching) {
                        UtensilTarget.Recipe -> it.addUtensil(picked)
                        is UtensilTarget.Step -> it.addStepUtensil(searching.key, picked)
                    }
                }
                target = null
            },
            onBack = { target = null },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues)
                .verticalScroll(scroll)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            banner()

            OutlinedTextField(
                value = form.name,
                onValueChange = { v -> edit { it.copy(name = v) } },
                label = { Text("Nom") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            OutlinedTextField(
                value = form.servings,
                onValueChange = { v -> edit { it.copy(servings = v) } },
                label = { Text("Portions") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )

            OutlinedTextField(
                value = form.tagsText,
                onValueChange = { v -> edit { it.copy(tagsText = v) } },
                label = { Text("Tags (séparés par des virgules)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            OutlinedTextField(
                value = form.notes,
                onValueChange = { v -> edit { it.copy(notes = v) } },
                label = { Text("Notes") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Ingrédients")
                TextButton(onClick = { edit { it.withIngredients(it.ingredients + IngredientRow()) } }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text("Ajouter")
                }
            }

            form.ingredients.forEachIndexed { index, row ->
                IngredientRowInput(
                    row = row,
                    onUpdate = { updated -> edit { f -> f.withIngredients(f.ingredients.mapIndexed { i, r -> if (i == index) updated else r }) } },
                    onRemove = { edit { f -> f.withIngredients(f.ingredients.filterIndexed { i, _ -> i != index }) } },
                    onSearchCiqual = onSearchCiqual,
                )
            }

            HorizontalDivider()
            UtensilsEditor(
                utensils = form.utensils,
                onAdd = { target = UtensilTarget.Recipe },
                onRemove = { removed -> edit { it.removeUtensil(removed) } },
            )

            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Étapes")
                TextButton(onClick = { edit { it.addStep() } }, modifier = Modifier.testTag(UiTags.RECIPE_STEP_ADD)) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text("Ajouter")
                }
            }

            form.steps.forEachIndexed { index, step ->
                StepEditor(
                    index = index,
                    step = step,
                    form = form,
                    error = stepErrors[step.key],
                    edit = edit,
                    onAddUtensil = { target = UtensilTarget.Step(step.key) },
                )
            }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            Button(
                onClick = onSave,
                modifier = Modifier.fillMaxWidth().testTag(UiTags.RECIPE_SAVE),
                enabled = form.name.isNotBlank() && !isSaving,
            ) {
                Text(saveLabel)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UtensilsEditor(
    utensils: List<UtensilRef>,
    onAdd: () -> Unit,
    onRemove: (UtensilRef) -> Unit,
) {
    Text("Matériel")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        utensils.forEach { utensil ->
            InputChip(
                selected = true,
                onClick = { onRemove(utensil) },
                label = { Text(utensil.name) },
                trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Retirer ${utensil.name}") },
                modifier = Modifier.testTag(UiTags.RECIPE_UTENSIL),
            )
        }
        AssistChip(
            onClick = onAdd,
            label = { Text("Ustensile") },
            leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
            modifier = Modifier.testTag(UiTags.RECIPE_UTENSIL_ADD),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StepEditor(
    index: Int,
    step: StepDraft,
    form: RecipeForm,
    error: String?,
    edit: ((RecipeForm) -> RecipeForm) -> Unit,
    onAddUtensil: () -> Unit,
) {
    val key = step.key
    val choices = form.ingredientChoices
    val earlier = form.steps.take(index)

    Card(modifier = Modifier.fillMaxWidth().testTag(UiTags.RECIPE_STEP)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Étape ${index + 1}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = { edit { it.moveStep(key, -1) } }, enabled = index > 0) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Monter l'étape ${index + 1}")
                }
                IconButton(onClick = { edit { it.moveStep(key, 1) } }, enabled = index < form.steps.lastIndex) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Descendre l'étape ${index + 1}")
                }
                IconButton(onClick = { edit { it.removeStep(key) } }) {
                    Icon(Icons.Default.Delete, contentDescription = "Supprimer l'étape ${index + 1}")
                }
            }

            OutlinedTextField(
                value = step.text,
                onValueChange = { v -> edit { it.setStepText(key, v) } },
                label = { Text("Étape") },
                modifier = Modifier.fillMaxWidth().testTag(UiTags.RECIPE_STEP_TEXT),
                minLines = 2,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = step.duration,
                    onValueChange = { v -> edit { it.setStepDuration(key, v) } },
                    label = { Text("Durée (min)") },
                    modifier = Modifier.weight(1f).testTag(UiTags.RECIPE_STEP_DURATION),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Text("En fond")
                Switch(
                    checked = step.background,
                    onCheckedChange = { v -> edit { it.setStepBackground(key, v) } },
                    enabled = step.duration.toIntOrNull() != null,
                )
            }

            if (choices.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    choices.forEach { ref ->
                        FilterChip(
                            selected = step.ingredients.any { it.isSame(ref) },
                            onClick = { edit { it.toggleStepIngredient(key, ref) } },
                            label = { Text(ref.name) },
                        )
                    }
                }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                step.utensils.forEach { utensil ->
                    InputChip(
                        selected = true,
                        onClick = { edit { it.removeStepUtensil(key, utensil) } },
                        label = { Text(utensil.name) },
                        trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Retirer ${utensil.name}") },
                    )
                }
                AssistChip(
                    onClick = onAddUtensil,
                    label = { Text("Ustensile") },
                    leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                )
            }

            if (earlier.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
                    earlier.forEachIndexed { i, other ->
                        FilterChip(
                            selected = other.key in step.waitsFor,
                            onClick = { edit { it.toggleStepDependency(key, other.key) } },
                            label = { Text("Attend ${i + 1}") },
                        )
                    }
                }
            }

            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(UiTags.RECIPE_STEP_ERROR),
                )
            }
        }
    }
}

/** Candidates of the search, the catalogue first; a name the catalogue lacks is offered to be created on the spot. */
internal fun utensilSearchResults(catalogue: List<Utensil>, chosen: List<UtensilRef>, query: String): List<UtensilRef> {
    val needle = query.trim()
    return catalogue
        .map { UtensilRef(it.id, it.name) }
        .filter { candidate -> chosen.none { it.isSame(candidate) } }
        .filter { needle.isBlank() || it.name.contains(needle, ignoreCase = true) }
        .sortedBy { it.name.lowercase() }
}

/** The name typed when no utensil of the catalogue or of the recipe already bears it. */
internal fun utensilToCreate(catalogue: List<Utensil>, chosen: List<UtensilRef>, query: String): UtensilRef? {
    val name = query.trim()
    if (name.isBlank()) return null
    val taken = catalogue.any { it.name.trim().equals(name, ignoreCase = true) } || chosen.any { it.name.trim().equals(name, ignoreCase = true) }
    return if (taken) null else UtensilRef(name = name)
}

/** Full-screen search among the user's utensils, with creation on the fly: never an inline dropdown. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UtensilSearchScreen(
    onLoadUtensils: suspend () -> List<Utensil>,
    chosen: List<UtensilRef>,
    onPick: (UtensilRef) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var query by rememberSaveable { mutableStateOf("") }
    var catalogue by remember { mutableStateOf<List<Utensil>>(emptyList()) }

    LaunchedEffect(Unit) {
        catalogue = runCatching { onLoadUtensils() }.getOrDefault(emptyList())
    }

    val results = remember(catalogue, chosen, query) { utensilSearchResults(catalogue, chosen, query) }
    val creatable = remember(catalogue, chosen, query) { utensilToCreate(catalogue, chosen, query) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ustensile") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Rechercher un ustensile") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(16.dp).testTag(UiTags.RECIPE_UTENSIL_SEARCH),
            )
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                creatable?.let { new ->
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable { onPick(new) }
                                .padding(16.dp)
                                .testTag(UiTags.RECIPE_UTENSIL_CREATE),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Text("Créer « ${new.name} »", style = MaterialTheme.typography.bodyLarge)
                        }
                        HorizontalDivider()
                    }
                }
                items(results, key = { it.id }) { utensil ->
                    Text(
                        text = utensil.name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth()
                            .clickable { onPick(utensil) }
                            .padding(16.dp)
                            .testTag(UiTags.RECIPE_UTENSIL_RESULT),
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
