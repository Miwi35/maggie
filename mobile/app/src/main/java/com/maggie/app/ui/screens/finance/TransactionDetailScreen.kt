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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.TransferLeg
import com.maggie.app.data.model.formatCents
import com.maggie.app.data.model.transactionStatusLabel
import com.maggie.app.data.model.transferLegSummary
import com.maggie.app.ui.UiTags

/** The « Virement interne » chip, shared by the list and the detail screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferBadge(modifier: Modifier = Modifier) {
    AssistChip(
        onClick = {},
        label = { Text("Virement interne") },
        modifier = modifier.testTag(UiTags.TRANSFER_BADGE),
    )
}

/**
 * One line and what makes it an internal transfer: the badge, the other leg, and the
 * toggle. Marking opens a full-screen search for the counterpart, never an inline dropdown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionDetailScreen(
    state: TransferDetailState,
    onBack: () -> Unit,
    onSearchCounterpart: () -> Unit,
    onRelease: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val transaction = state.transaction

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transaction") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(transaction.label, style = MaterialTheme.typography.titleLarge)
            Text(
                text = formatCents(transaction.amountCents, transaction.currency),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = listOfNotNull(
                    transaction.bookedAt?.take(10),
                    transactionStatusLabel(transaction.status, transaction.amountCents),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            if (state.isLoading && state.info == null) {
                CircularProgressIndicator()
            }

            if (transaction.isInternalTransfer) {
                TransferBadge()
                Text(
                    text = if (transaction.transferSource == "manual") "Marqué à la main" else "Détecté automatiquement",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val counterpart = state.info?.counterpart
                if (state.info != null) {
                    Text(
                        text = counterpart?.let { "Contrepartie : ${transferLegSummary(it)}" }
                            ?: "Sans contrepartie : l'autre compte n'est pas suivi",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                OutlinedButton(
                    onClick = onRelease,
                    enabled = !state.isSaving,
                    modifier = Modifier.fillMaxWidth().testTag(UiTags.TRANSFER_TOGGLE),
                ) { Text("Ce n'est pas un virement interne") }
            } else {
                Button(
                    onClick = onSearchCounterpart,
                    enabled = !state.isSaving,
                    modifier = Modifier.fillMaxWidth().testTag(UiTags.TRANSFER_TOGGLE),
                ) { Text("C'est un virement interne") }
            }

            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Full-screen search among the lines a transfer can be paired with, plus « no counterpart ». */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CounterpartSearchScreen(
    state: TransferDetailState,
    onLoad: () -> Unit,
    onPick: (TransferLeg?) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { onLoad() }

    val shown = remember(state.candidates, query) {
        state.candidates.filter {
            query.isBlank() || transferLegSummary(it).contains(query.trim(), ignoreCase = true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Choisir la contrepartie") },
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
                label = { Text("Rechercher un libellé, un compte, une date") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(16.dp).testTag(UiTags.TRANSFER_SEARCH),
            )
            state.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            if (state.isLoading) {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    Column(modifier = Modifier.fillMaxWidth().clickable { onPick(null) }.padding(16.dp)) {
                        Text("Aucune contrepartie", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "L'autre compte n'est pas suivi dans Maggie",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()
                }
                if (!state.isLoading && shown.isEmpty()) {
                    item {
                        Text(
                            "Aucune ligne proche à apparier.",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                items(shown, key = { it.id }) { leg ->
                    Column(modifier = Modifier.fillMaxWidth().clickable { onPick(leg) }.padding(16.dp)) {
                        Text(leg.label, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = listOfNotNull(
                                leg.accountName,
                                leg.bookedAt?.take(10),
                                formatCents(leg.amountCents, leg.currency),
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
