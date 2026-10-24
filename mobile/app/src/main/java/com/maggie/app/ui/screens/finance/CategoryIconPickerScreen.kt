package com.maggie.app.ui.screens.finance

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.MaggieCard

/**
 * Full-screen choice of a category's icon (MAG-474): a search field on top, then a grid of
 * icons with their French label, and « Aucune icône ». Never an inline dropdown.
 *
 * [current] is the stored name; one outside the catalogue is listed first, selected.
 * [color] only dresses the previews, so the tile looks like the category will.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CategoryIconPickerScreen(
    current: String?,
    color: String?,
    onPick: (String?) -> Unit,
    onBack: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val tiles = remember(query, current) { CategoryIconCatalog.tiles(query, current) }
    val selectedName = current?.trim()?.lowercase()?.ifEmpty { null }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Choisir une icône") },
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
                label = { Text("Rechercher une icône") },
                placeholder = { Text("courses, voiture, restaurant…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(16.dp).testTag(UiTags.CATEGORY_ICON_SEARCH),
            )
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 96.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "__none", span = { GridItemSpan(maxLineSpan) }) {
                    IconTile(
                        label = "Aucune icône",
                        selected = selectedName == null,
                        modifier = Modifier.fillMaxWidth().testTag(UiTags.CATEGORY_ICON_TILE_PREFIX + "none"),
                        onClick = { onPick(null) },
                    ) {
                        CategoryGlyph(color = color, icon = null)
                    }
                }
                if (tiles.isEmpty()) {
                    item(key = "__empty", span = { GridItemSpan(maxLineSpan) }) {
                        Text("Aucune icône ne correspond.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                items(tiles, key = { "icon:${it.name}" }) { tile ->
                    IconTile(
                        label = tile.label,
                        selected = tile.name.equals(selectedName, ignoreCase = true),
                        modifier = Modifier.testTag(UiTags.CATEGORY_ICON_TILE_PREFIX + tile.name),
                        onClick = { onPick(tile.name) },
                    ) {
                        CategoryGlyph(color = color, icon = tile.name)
                    }
                }
            }
        }
    }
}

@Composable
private fun IconTile(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    glyph: @Composable () -> Unit,
) {
    MaggieCard(modifier = modifier, selected = selected, listRow = true) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { this.selected = selected }
                .padding(vertical = 12.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(modifier = Modifier.clearAndSetSemantics {}) { glyph() }
            Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
        }
    }
}
