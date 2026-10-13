package com.maggie.app.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.maggie.app.data.model.Category
import com.maggie.app.ui.uiTagRoot

/** The row that stands for « no category », above the results. */
data class CategorySearchNone(val title: String, val subtitle: String)

/**
 * Full-screen search among categories, each shown by its path (« Loisirs › Bars »): the one
 * place a category is chosen, never an inline dropdown. The parent of a category and the
 * category of a transaction both go through it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategorySearchScreen(
    title: String,
    candidates: List<Category>,
    pathOf: (Category) -> String,
    selectedId: String?,
    onPick: (Category) -> Unit,
    onBack: () -> Unit,
    searchTag: String,
    none: CategorySearchNone? = null,
    onPickNone: () -> Unit = {},
    emptyMessage: String = "Aucune catégorie ne correspond.",
) {
    BackHandler(onBack = onBack)
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(candidates, query) {
        candidates
            .map { it to pathOf(it) }
            .filter { (_, path) -> query.isBlank() || path.contains(query.trim(), ignoreCase = true) }
            .sortedBy { (_, path) -> path.lowercase() }
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
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Rechercher une catégorie") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(16.dp).testTag(searchTag),
            )
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (none != null) {
                    item {
                        Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onPickNone).padding(16.dp)) {
                            Text(none.title, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                none.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        HorizontalDivider()
                    }
                }
                if (shown.isEmpty()) {
                    item {
                        Text(
                            emptyMessage,
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                items(shown, key = { (category, _) -> category.id }) { (category, path) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onPick(category) }.padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(path, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        if (selectedId == category.id) {
                            Text("Choisie", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

/** The same search in a window of its own, for a choice made from inside a dialog. */
@Composable
fun CategorySearchDialog(
    title: String,
    candidates: List<Category>,
    pathOf: (Category) -> String,
    selectedId: String?,
    onPick: (Category) -> Unit,
    onDismiss: () -> Unit,
    searchTag: String,
    none: CategorySearchNone? = null,
    onPickNone: () -> Unit = {},
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(modifier = Modifier.uiTagRoot()) {
            CategorySearchScreen(
                title = title,
                candidates = candidates,
                pathOf = pathOf,
                selectedId = selectedId,
                onPick = onPick,
                onBack = onDismiss,
                searchTag = searchTag,
                none = none,
                onPickNone = onPickNone,
            )
        }
    }
}
