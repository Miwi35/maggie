package com.maggie.app.ui.screens.finance

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.Category
import com.maggie.app.data.model.CategoryTree
import com.maggie.app.data.model.TransactionNature
import com.maggie.app.data.model.categoryMatchesNature
import com.maggie.app.data.model.directionLabel
import com.maggie.app.data.model.formatCents
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.CategorySearchScreen

/**
 * « Créer un filtre » (MAG-464): a categorisation rule drafted from a line, with the lines it
 * would catch listed live as the pattern changes. The category is picked in the full-screen search.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionRuleDraftScreen(
    draft: RuleDraftState,
    categories: List<Category>,
    onPatternChange: (String) -> Unit,
    onCategoryChange: (String?) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    var pickingCategory by remember { mutableStateOf(false) }
    val tree = remember(categories) { CategoryTree(categories) }
    val path = tree.pathOf(draft.categoryId)

    if (pickingCategory) {
        val nature = if (draft.direction == "debit") TransactionNature.Expense else TransactionNature.Income
        CategorySearchScreen(
            title = "Choisir la catégorie",
            candidates = categories.filter { categoryMatchesNature(it, nature) },
            pathOf = tree::path,
            selectedId = draft.categoryId,
            onPick = {
                onCategoryChange(it.id)
                pickingCategory = false
            },
            onBack = { pickingCategory = false },
            searchTag = UiTags.TRANSACTION_CATEGORY_SEARCH,
        )
        return
    }

    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Créer un filtre") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = draft.labelPattern,
                    onValueChange = onPatternChange,
                    label = { Text("Le libellé contient") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(UiTags.RULE_DRAFT_PATTERN),
                )
                Text(
                    text = "Sens : ${directionLabel(draft.direction)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box {
                    OutlinedTextField(
                        value = path.orEmpty(),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Catégorie") },
                        placeholder = { Text("Choisir une catégorie") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .testTag(UiTags.RULE_DRAFT_CATEGORY)
                            .semantics { contentDescription = "Catégorie, ${path ?: "aucune"}" }
                            .clickable(enabled = !draft.isSaving, role = Role.Button) { pickingCategory = true },
                    )
                }
                draft.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                Button(
                    onClick = onSave,
                    enabled = draft.labelPattern.isNotBlank() && draft.categoryId != null && !draft.isSaving,
                    modifier = Modifier.fillMaxWidth().testTag(UiTags.RULE_DRAFT_SAVE),
                ) { Text("Enregistrer le filtre") }
            }
            HorizontalDivider()
            MatchList(draft)
        }
    }
}

@Composable
private fun MatchList(draft: RuleDraftState) {
    val preview = draft.preview
    MatchCount(draft.isPreviewing, preview?.total)
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(preview?.matches.orEmpty(), key = { it.transactionId }) { match ->
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag(UiTags.RULE_DRAFT_MATCH)) {
                Text(match.label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = listOfNotNull(match.bookedAt.take(10).ifBlank { null }, formatCents(match.amountCents))
                        .joinToString(" · ") + if (match.wouldChange) " · serait classée" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
        }
    }
}

// The count line above the matches: a spinner while a request is in flight, the total once it landed.
@Composable
private fun MatchCount(isPreviewing: Boolean, total: Int?) {
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
        when {
            isPreviewing -> CircularProgressIndicator(modifier = Modifier.padding(4.dp))
            total == null -> Text("Saisissez un motif pour voir les transactions concernées.", style = MaterialTheme.typography.bodyMedium)
            total == 0 -> Text("Aucune transaction ne correspond.", style = MaterialTheme.typography.bodyMedium)
            else -> Text(
                if (total == 1) "1 transaction correspond" else "$total transactions correspondent",
                style = MaterialTheme.typography.titleSmall,
            )
        }
    }
}
