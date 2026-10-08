package com.maggie.app.ui.screens.finance

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.BankConnection
import com.maggie.app.data.model.BankConnectionTone
import com.maggie.app.data.model.bankConnectionNeedsAction
import com.maggie.app.data.model.bankConnectionNotice
import com.maggie.app.data.model.bankConnectionOffersReconnect
import com.maggie.app.data.model.bankConnectionStatusLabel
import com.maggie.app.data.model.bankConnectionTone
import com.maggie.app.data.model.bankReconnectLabel
import com.maggie.app.data.model.lastSyncLabel
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.EmptyState
import com.maggie.app.ui.components.ErrorSnackbar
import com.maggie.app.ui.theme.MaggieTokens

/**
 * The banks Maggie reads, and the one button that reads them.
 *
 * Connecting a bank for the first time is not here: the provider sends the user
 * back to the web admin once the consent is given, so the first link is made
 * there. What the phone owes is the part that comes back every week — where each
 * link stands, when it last brought something, and the fetch itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BankConnectionListScreen(
    viewModel: BankConnectionViewModel,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    // The consent is the bank's own screen: the app hands the browser over.
    LaunchedEffect(uiState.authorizationUrl) {
        val url = uiState.authorizationUrl ?: return@LaunchedEffect
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            snackbarHostState.showSnackbar("Aucun navigateur pour ouvrir la page de votre banque.")
        }
        viewModel.consumeAuthorizationUrl()
    }

    ErrorSnackbar(
        error = uiState.error,
        snackbarHostState = snackbarHostState,
        onDismiss = viewModel::clearError,
        onRetry = viewModel::refresh,
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Banques") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    if (uiState.connections.isNotEmpty()) {
                        TextButton(
                            onClick = { viewModel.sync() },
                            enabled = !uiState.isSyncing,
                            modifier = Modifier.testTag(UiTags.BANK_SYNC),
                        ) {
                            Text(if (uiState.isSyncing) "Récupération…" else "Récupérer les opérations")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        PullToRefreshBox(
            isRefreshing = uiState.isLoading,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize().padding(paddingValues),
        ) {
            when {
                uiState.isLoading && uiState.connections.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                uiState.connections.isEmpty() -> {
                    EmptyState(
                        title = "Aucune banque connectée",
                        description = "Connectez une banque depuis Maggie sur le web : vos opérations arriveront ensuite toutes seules, et cet écran les récupérera.",
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(uiState.connections, key = { it.id }) { connection ->
                            ConnectionCard(
                                connection = connection,
                                busy = uiState.reconnectingId == connection.id,
                                onReconnect = { viewModel.reconnect(connection.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionCard(
    connection: BankConnection,
    busy: Boolean,
    onReconnect: () -> Unit,
) {
    val notice = bankConnectionNotice(connection)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = connection.bankName,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                StatusLabel(connection)
            }

            Text(
                text = lastSyncLabel(connection.lastSyncedAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (notice != null) {
                Text(
                    text = notice,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (connection.needsReconnecting) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            if (bankConnectionOffersReconnect(connection)) {
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    if (bankConnectionNeedsAction(connection)) {
                        Button(onClick = onReconnect, enabled = !busy) {
                            Text(bankReconnectLabel(connection))
                        }
                    } else {
                        TextButton(onClick = onReconnect, enabled = !busy) {
                            Text(bankReconnectLabel(connection))
                        }
                    }
                }
            }
        }
    }
}

/**
 * The status, as a coloured label and not a chip: a disabled chip is drawn
 * faded, and « Connectée » faded read as a bank switched off (MAG-45, retour de
 * recette). Nothing to tap, so no button role either.
 */
@Composable
private fun StatusLabel(connection: BankConnection) {
    val color = when (bankConnectionTone(connection)) {
        BankConnectionTone.CONNECTED -> MaggieTokens.Signal.success
        BankConnectionTone.NEEDS_ACTION -> MaggieTokens.Signal.warning
        BankConnectionTone.NEUTRAL -> MaterialTheme.colorScheme.outline
    }

    Surface(
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.12f),
        contentColor = color,
    ) {
        Text(
            text = bankConnectionStatusLabel(connection),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
