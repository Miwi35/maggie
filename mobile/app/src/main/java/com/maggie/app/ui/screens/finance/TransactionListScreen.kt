package com.maggie.app.ui.screens.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.maggie.app.data.api.TransactionCreateRequest
import com.maggie.app.data.model.Account
import com.maggie.app.data.model.formatCents
import com.maggie.app.data.model.transactionStatusLabel
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionListScreen(
    viewModel: TransactionViewModel,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transactions") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Nouvelle transaction")
            }
        },
    ) { paddingValues ->
        when {
            uiState.isLoading && uiState.transactions.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            uiState.transactions.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Aucune transaction", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(uiState.transactions, key = { it.id }) { transaction ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = transaction.label,
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                    val subtitle = buildString {
                                        transaction.bookedAt?.let { append(it).append(" · ") }
                                        append(transactionStatusLabel(transaction.status))
                                    }
                                    Text(
                                        text = subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    text = formatCents(transaction.amountCents, transaction.currency),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (transaction.amountCents < 0) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    },
                                )
                                IconButton(onClick = { viewModel.deleteTransaction(transaction.id) }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Supprimer",
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        TransactionCreateDialog(
            accounts = uiState.accounts,
            onConfirm = { request ->
                viewModel.createTransaction(request)
                showCreateDialog = false
            },
            onDismiss = { showCreateDialog = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransactionCreateDialog(
    accounts: List<Account>,
    onConfirm: (TransactionCreateRequest) -> Unit,
    onDismiss: () -> Unit,
) {
    var label by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }
    var isExpense by remember { mutableStateOf(true) }
    var selectedAccountId by remember { mutableStateOf(accounts.firstOrNull()?.id) }

    val canSubmit = label.isNotBlank() && selectedAccountId != null && amountText.toDoubleOrNull() != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nouvelle transaction") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Libellé") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("Montant (€)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = isExpense,
                        onClick = { isExpense = true },
                        label = { Text("Dépense") },
                    )
                    FilterChip(
                        selected = !isExpense,
                        onClick = { isExpense = false },
                        label = { Text("Revenu") },
                    )
                }
                if (accounts.isEmpty()) {
                    Text(
                        "Crée d'abord un compte",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text("Compte", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        accounts.forEach { account ->
                            FilterChip(
                                selected = selectedAccountId == account.id,
                                onClick = { selectedAccountId = account.id },
                                label = { Text(account.name) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val accountId = selectedAccountId ?: return@TextButton
                    val euros = amountText.toDoubleOrNull() ?: return@TextButton
                    val cents = (euros * 100).roundToInt().let { if (isExpense) -it else it }
                    onConfirm(
                        TransactionCreateRequest(
                            account = "/api/accounts/$accountId",
                            amountCents = cents,
                            label = label.trim(),
                        ),
                    )
                },
                enabled = canSubmit,
            ) {
                Text("Créer")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annuler")
            }
        },
    )
}
