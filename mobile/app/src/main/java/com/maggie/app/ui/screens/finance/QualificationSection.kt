package com.maggie.app.ui.screens.finance

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.Category
import com.maggie.app.data.model.CategoryTree
import com.maggie.app.data.model.Qualification
import com.maggie.app.data.model.QualificationKind
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.CategorySearchDialog
import com.maggie.app.ui.components.CategorySearchNone

/**
 * The one place a qualification is edited — on a transaction, a series, a rule: its category, whether
 * it is mandatory, and its type. Any category fits any line: the direction comes from the amount's sign.
 * The category is chosen in a full-screen search, never an inline dropdown.
 */
@Composable
fun QualificationSection(
    categories: List<Category>,
    categoryId: String?,
    qualification: Qualification,
    onCategoryChange: (String?) -> Unit,
    onQualificationChange: (Qualification) -> Unit,
    categoryTag: String,
    searchTag: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    // Offers « Aucune catégorie » in the search, for a line that can lose its category.
    canClearCategory: Boolean = false,
) {
    var searching by rememberSaveable { mutableStateOf(false) }
    val tree = remember(categories) { CategoryTree(categories) }
    val path = tree.pathOf(categoryId)

    if (searching) {
        CategorySearchDialog(
            title = "Choisir la catégorie",
            candidates = categories,
            pathOf = tree::path,
            selectedId = categoryId,
            onPick = {
                searching = false
                onCategoryChange(it.id)
            },
            onDismiss = { searching = false },
            searchTag = searchTag,
            none = CategorySearchNone("Aucune catégorie", "Retirer la catégorie").takeIf { canClearCategory && categoryId != null },
            onPickNone = {
                searching = false
                onCategoryChange(null)
            },
        )
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                    .testTag(categoryTag)
                    .semantics { contentDescription = "Catégorie, ${path ?: "aucune"}" }
                    .clickable(enabled = enabled, role = Role.Button) { searching = true },
            )
        }

        QualificationFlags(qualification, onQualificationChange, enabled)
    }
}

/** « Obligatoire » and the type of a qualification: the part of [QualificationSection] that does not depend on a category. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QualificationFlags(
    qualification: Qualification,
    onQualificationChange: (Qualification) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(UiTags.QUALIFICATION_MANDATORY)
                .toggleable(
                    value = qualification.mandatory,
                    enabled = enabled,
                    role = Role.Switch,
                    onValueChange = { onQualificationChange(qualification.copy(mandatory = it)) },
                ),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Obligatoire")
            Switch(checked = qualification.mandatory, onCheckedChange = null, enabled = enabled)
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Type", style = MaterialTheme.typography.bodyLarge)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QualificationKind.entries.forEach { kind ->
                    FilterChip(
                        selected = qualification.kind == kind,
                        onClick = { onQualificationChange(qualification.copy(kind = kind)) },
                        enabled = enabled,
                        label = { Text(kind.label) },
                        modifier = Modifier.testTag(UiTags.qualificationKind(kind.name)),
                    )
                }
            }
        }
    }
}
