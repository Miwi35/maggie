package com.maggie.app.ui.screens.finance

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import com.maggie.app.data.model.LinkCandidate
import com.maggie.app.data.model.formatCents
import com.maggie.app.ui.UiTags

/** Full-screen search among the bank's accounts a hand-kept account can be linked to (MAG-823). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkAccountPickerScreen(
    candidates: List<LinkCandidate>,
    onPick: (LinkCandidate) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(candidates, query) {
        candidates
            .filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) || it.bank.orEmpty().contains(query.trim(), ignoreCase = true) }
            .sortedBy { it.name.lowercase() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Rattacher au compte de la banque") },
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
                label = { Text("Rechercher un compte") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(16.dp).testTag(UiTags.ACCOUNT_LINK_SEARCH),
            )
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (shown.isEmpty()) {
                    item {
                        Text("Aucun compte ne correspond.", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                items(shown, key = { it.id }) { candidate ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(candidate) }
                            .padding(16.dp)
                            .testTag(UiTags.ACCOUNT_LINK_ITEM),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(candidate.name, style = MaterialTheme.typography.bodyLarge)
                            candidate.bank?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Text(formatCents(candidate.balanceCents, candidate.currency), style = MaterialTheme.typography.bodyMedium)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
