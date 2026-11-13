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
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
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
import com.maggie.app.ui.components.ConfirmDeleteDialog
import com.maggie.app.ui.components.EmptyState
import com.maggie.app.ui.components.ErrorSnackbar
import com.maggie.app.ui.components.FinanceTopBar
import com.maggie.app.ui.components.MaggieCard
import com.maggie.app.ui.navigation.Screen
import com.maggie.app.ui.theme.MaggieTokens

/**
 * The banks Maggie reads, and the one button that reads them.
 *
 * A first link starts here (the search is its own screen), the consent is given
 * in the browser, and the list is read again when the user comes back. The part
 * that comes back every week is where each link stands, when it last brought
 * something, and the fetch itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BankConnectionListScreen(
    viewModel: BankConnectionViewModel,
    onBack: () -> Unit,
    onConnect: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    var forgetting by remember { mutableStateOf<BankConnection?>(null) }

    // Back from the bank's page: a consent just given shows without a pull to refresh.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

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
            FinanceTopBar(Screen.BankConnectionList.route, actions = {
                if (uiState.connections.isNotEmpty()) {
                    IconButton(
                        onClick = { viewModel.sync() },
                        enabled = !uiState.isSyncing,
                        modifier = Modifier.testTag(UiTags.BANK_SYNC),
                    ) {
                        Icon(
                            Icons.Default.Sync,
                            contentDescription = if (uiState.isSyncing) "Récupération…" else "Récupérer les transactions",
                        )
                    }
                }
            })
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
                        description = "Connectez une banque : vos transactions arriveront ensuite toutes seules, et cet écran les récupérera.",
                        actionLabel = "Connecter une banque",
                        onAction = onConnect,
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
                                onForget = { forgetting = connection },
                            )
                        }
                        item(key = "connect") { ConnectCard(onConnect = onConnect) }
                    }
                }
            }
        }
    }

    forgetting?.let { connection ->
        ConfirmDeleteDialog(
            title = "Oublier la banque « ${connection.bankName} » ?",
            message = "Ses comptes et leurs transactions restent dans Maggie, mais la synchronisation avec cette banque s'arrête.",
            confirmLabel = "Oublier",
            onConfirm = {
                viewModel.forget(connection.id)
                forgetting = null
            },
            onDismiss = { forgetting = null },
        )
    }
}

@Composable
private fun ConnectCard(onConnect: () -> Unit, modifier: Modifier = Modifier) {
    MaggieCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            Button(onClick = onConnect, modifier = Modifier.testTag(UiTags.BANK_CONNECT)) {
                Text("Connecter une banque")
            }
        }
    }
}

@Composable
private fun ConnectionCard(
    connection: BankConnection,
    busy: Boolean,
    onReconnect: () -> Unit,
    onForget: () -> Unit,
) {
    val notice = bankConnectionNotice(connection)

    MaggieCard(modifier = Modifier.fillMaxWidth()) {
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

            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onForget, enabled = !busy) {
                    Text("Oublier la banque", color = MaterialTheme.colorScheme.error)
                }
                if (bankConnectionOffersReconnect(connection)) {
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
