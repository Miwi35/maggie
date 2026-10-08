package com.maggie.app.ui.screens.finance

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.maggie.app.data.api.CategoryCreateRequest
import com.maggie.app.data.model.Category
import com.maggie.app.data.model.obligationLabel
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.ErrorSnackbar
import com.maggie.app.ui.uiTagRoot
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val OBLIGATIONS = listOf("mandatory", "optional", "saving", "investment", "debt", "income")

/**
 * The six fields of a category, for a new one ([initial] null) or an existing one.
 *
 * The same six, in the same order, as the admin's `CategoryForm`: what is edited here is
 * what the admin shows.
 */
internal class CategoryFormState(private val initial: Category? = null) {
    var name by mutableStateOf(initial?.name.orEmpty())
    var obligation by mutableStateOf(initial?.obligation ?: "optional")
        private set
    var passiveIncome by mutableStateOf(initial?.passiveIncome ?: false)
    var parent by mutableStateOf(initial?.parent)
    var color by mutableStateOf(initial?.color.orEmpty())
    var icon by mutableStateOf(initial?.icon.orEmpty())

    // Shown once a save was tried: an empty name is not an error while the form is still being filled.
    var showErrors by mutableStateOf(false)

    val isNew: Boolean get() = initial == null

    val nameError: String? get() =
        if (showErrors && name.isBlank()) "Indiquez un nom pour la catégorie." else null

    val canSave: Boolean get() = name.isNotBlank()

    fun select(value: String) {
        obligation = value
        // The API refuses the flag on anything but an income (422).
        if (value != "income") passiveIncome = false
    }

    fun toRequest() = CategoryCreateRequest(
        name = name.trim(),
        obligation = obligation,
        passiveIncome = passiveIncome,
        parent = parent,
        color = color.trim().ifEmpty { null },
        icon = icon.trim().ifEmpty { null },
    )

    /**
     * Only what moved, as a merge-patch: an unchanged field is absent, a cleared one is an
     * explicit `null` — the API reads absence as « leave it » and null as « empty it ».
     */
    fun changes(): JsonObject {
        val before = initial ?: return JsonObject(emptyMap())
        val fields = linkedMapOf<String, kotlinx.serialization.json.JsonElement>()
        if (name.trim() != before.name) fields["name"] = JsonPrimitive(name.trim())
        if (parent != before.parent) fields["parent"] = parent?.let(::JsonPrimitive) ?: JsonNull
        if (obligation != before.obligation) fields["obligation"] = JsonPrimitive(obligation)
        if (passiveIncome != before.passiveIncome) fields["passiveIncome"] = JsonPrimitive(passiveIncome)
        if (color.trim() != before.color.orEmpty()) fields["color"] = color.trim().ifEmpty { null }?.let(::JsonPrimitive) ?: JsonNull
        if (icon.trim() != before.icon.orEmpty()) fields["icon"] = icon.trim().ifEmpty { null }?.let(::JsonPrimitive) ?: JsonNull
        return JsonObject(fields)
    }
}

/** The title and the body of the question asked before a deletion, from what could be counted. */
fun categoryDeletionBody(impact: CategoryDeletionImpact?): String {
    if (impact == null) return "Cette action est définitive."

    val lines = buildList {
        when (impact.transactions) {
            null -> add("Les transactions rattachées perdront leur catégorie.")
            0 -> {}
            1 -> add("1 transaction perdra sa catégorie.")
            else -> add("${impact.transactions} transactions perdront leur catégorie.")
        }
        when (impact.rules) {
            null -> add("Les règles de catégorisation rattachées seront supprimées.")
            0 -> {}
            1 -> add("1 règle de catégorisation sera supprimée.")
            else -> add("${impact.rules} règles de catégorisation seront supprimées.")
        }
        when (impact.subCategories) {
            0 -> {}
            1 -> add("1 sous-catégorie sera supprimée.")
            else -> add("${impact.subCategories} sous-catégories seront supprimées.")
        }
    }
    return (lines + "Cette action est définitive.").joinToString("\n")
}

/**
 * A category on a screen of its own, new or existing (MAG-353). Parent is chosen on a
 * full-screen search, and a deletion sits at the bottom, apart, with what it takes along.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CategoryEditScreen(
    state: CategoryUiState,
    editing: CategoryEditing,
    onSave: (CategoryFormState) -> Unit,
    onBack: () -> Unit,
    onAskDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    onClearError: () -> Unit,
) {
    val form = remember(editing.category?.id) { CategoryFormState(editing.category) }
    var searchingParent by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    BackHandler(onBack = if (searchingParent) ({ searchingParent = false }) else onBack)

    if (searchingParent) {
        val self = editing.category
        // Two levels at most: only a main category can be a parent, and one that has
        // sub-categories of its own cannot become a sub-category.
        val candidates = state.categories.filter { it.parent == null && it.id != self?.id }
        CategoryParentSearchScreen(
            candidates = candidates,
            selected = form.parent,
            onPick = { picked ->
                form.parent = picked?.let { categoryIri(it.id) }
                searchingParent = false
            },
            onBack = { searchingParent = false },
        )
        return
    }

    ErrorSnackbar(
        error = state.error,
        snackbarHostState = snackbarHostState,
        onDismiss = onClearError,
        message = "Enregistrement impossible. Vérifiez votre connexion et réessayez.",
    )

    val deletion = state.deletion
    if (deletion != null) {
        AlertDialog(
            onDismissRequest = onCancelDelete,
            title = { Text("Supprimer « ${deletion.category.name} » ?") },
            text = {
                if (deletion.impact == null) {
                    CircularProgressIndicator()
                } else {
                    Text(categoryDeletionBody(deletion.impact))
                }
            },
            confirmButton = {
                TextButton(enabled = deletion.impact != null, onClick = onConfirmDelete) {
                    Text("Supprimer", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = onCancelDelete) { Text("Annuler") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (form.isNew) "Nouvelle catégorie" else "Modifier la catégorie") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Button(
                    onClick = {
                        form.showErrors = true
                        if (form.canSave) onSave(form)
                    },
                    enabled = !state.isSaving,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) {
                    Text(if (form.isNew) "Créer" else "Enregistrer")
                }
            }
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CategoryFormFields(
                form = form,
                parentName = state.categories.firstOrNull { categoryIri(it.id) == form.parent }?.name,
                // A category that has sub-categories of its own stays a main one.
                canHaveParent = editing.category == null ||
                    state.categories.none { it.parent == categoryIri(editing.category.id) },
                onPickParent = { searchingParent = true },
            )

            if (!form.isNew) {
                HorizontalDivider(modifier = Modifier.padding(top = 16.dp))
                OutlinedButton(
                    onClick = onAskDelete,
                    modifier = Modifier.fillMaxWidth().testTag(UiTags.CATEGORY_DELETE),
                ) {
                    Text("Supprimer la catégorie", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun Helper(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SectionTitle(title: String, description: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        description?.let { Helper(it) }
    }
}

/** The fields in the order the admin asks them, a helper line under each. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CategoryFormFields(
    form: CategoryFormState,
    parentName: String? = null,
    canHaveParent: Boolean = true,
    onPickParent: () -> Unit = {},
) {
    Column(
        modifier = Modifier.uiTagRoot(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionTitle(
            "La catégorie",
            "Les catégories classent vos opérations : elles portent les budgets, les règles automatiques et la revue mensuelle.",
        )

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(
                value = form.name,
                onValueChange = { form.name = it },
                label = { Text("Nom") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = form.nameError != null,
            )
            Helper(form.nameError ?: "Alimentation, Loisirs, Transport…")
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // A read-only field with a tap target: the choice opens a full-screen search, never a dropdown.
            Box {
                OutlinedTextField(
                    value = parentName ?: "",
                    onValueChange = {},
                    readOnly = true,
                    enabled = canHaveParent,
                    label = { Text("Rattacher à une catégorie") },
                    placeholder = { Text("Aucune — catégorie principale") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                if (canHaveParent) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .testTag(UiTags.CATEGORY_PARENT)
                            .clickable(role = Role.Button, onClick = onPickParent),
                    )
                }
            }
            Helper(
                if (canHaveParent) {
                    "Laissez vide pour une catégorie principale. Deux niveaux au maximum."
                } else {
                    "Cette catégorie a des sous-catégories : elle reste une catégorie principale."
                },
            )
        }

        SectionTitle(
            "Nature de la dépense",
            "C'est ce qui distingue un dépassement sur l'essentiel d'un dépassement sur le reste, dans le score comme dans la revue mensuelle.",
        )

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OBLIGATIONS.forEach { o ->
                    FilterChip(
                        selected = form.obligation == o,
                        onClick = { form.select(o) },
                        label = { Text(obligationLabel(o)) },
                    )
                }
            }
            Helper(
                "Obligatoire : loyer, courses. Non-obligatoire : loisirs. Épargne et investissement ne sont pas des dépenses. Recette : ce qui rentre.",
            )
        }

        if (form.obligation == "income") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(UiTags.CATEGORY_PASSIVE_INCOME)
                    .toggleable(
                        value = form.passiveIncome,
                        role = Role.Switch,
                        onValueChange = { form.passiveIncome = it },
                    ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Rente")
                    Helper(
                        "Un revenu qui rentre sans être travaillé — loyers perçus, dividendes, intérêts, redevances. C'est ce que le compteur d'indépendance compare à votre train de vie.",
                    )
                }
                Switch(checked = form.passiveIncome, onCheckedChange = null)
            }
        }

        SectionTitle("Repères visuels", "Facultatif, pour repérer la catégorie d'un coup d'œil.")

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(
                value = form.color,
                onValueChange = { form.color = it },
                label = { Text("Couleur") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Helper("Code hexadécimal, par exemple #4CAF50.")
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(
                value = form.icon,
                onValueChange = { form.icon = it },
                label = { Text("Icône") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Helper("Nom d'icône, par exemple shopping-cart.")
        }
    }
}

/** Full-screen search among the main categories, plus « none » to make this one a main category. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CategoryParentSearchScreen(
    candidates: List<Category>,
    selected: String?,
    onPick: (Category?) -> Unit,
    onBack: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(candidates, query) {
        candidates.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Rattacher à une catégorie") },
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
                label = { Text("Rechercher une catégorie") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(16.dp).testTag(UiTags.CATEGORY_PARENT_SEARCH),
            )
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    Column(modifier = Modifier.fillMaxWidth().clickable { onPick(null) }.padding(16.dp)) {
                        Text("Aucune", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Catégorie principale",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()
                }
                if (shown.isEmpty()) {
                    item {
                        Text(
                            "Aucune catégorie principale ne correspond.",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                items(shown, key = { it.id }) { category ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onPick(category) }.padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(category.name, style = MaterialTheme.typography.bodyLarge)
                        if (selected == categoryIri(category.id)) {
                            Text("Choisie", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
