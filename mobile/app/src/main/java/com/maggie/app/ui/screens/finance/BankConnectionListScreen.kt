package com.maggie.app.ui.screens.finance

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.maggie.app.ui.components.ErrorSnackbar
import com.maggie.app.ui.components.FinanceTopBar
import com.maggie.app.ui.navigation.Screen
import com.maggie.app.ui.theme.MaggieTokens
import com.maggie.app.ui.theme.maggieSurface

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
                    Column(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            "Aucune banque connectée",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        ConnectCard(onConnect = onConnect)
                    }
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
    val mint = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier = modifier.fillMaxWidth()
            .heightIn(min = 64.dp)
            .background(mint.copy(alpha = 0.06f), shape)
            .border(1.dp, mint.copy(alpha = 0.35f), shape)
            .clickable(onClick = onConnect)
            .padding(horizontal = 14.dp)
            .testTag(UiTags.BANK_CONNECT),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(36.dp).background(mint.copy(alpha = 0.16f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Add, contentDescription = null, tint = mint)
        }
        Text(
            "Connecter une banque",
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = maggieSurface().textMuted,
        )
    }
}

@Composable
private fun ConnectionCard(
    connection: BankConnection,
    busy: Boolean,
    onReconnect: () -> Unit,
    onForget: () -> Unit,
) {
    val notice = if (bankConnectionNeedsAction(connection)) null else bankConnectionNotice(connection)

    Column(
        modifier = Modifier.fillMaxWidth()
            .background(maggieSurface().paper, RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = connection.bankName,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                modifier = Modifier.weight(1f),
            )
            StatusLabel(connection)
        }

        Text(
            text = lastSyncLabel(connection.lastSyncedAt),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
            color = maggieSurface().textMuted,
        )

        if (notice != null) {
            Text(
                text = notice,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                color = maggieSurface().textMuted,
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

/**
 * The status, as a coloured label and not a chip: a disabled chip is drawn
 * faded, and « Connectée » faded read as a bank switched off (MAG-45, retour de
 * recette). Nothing to tap, so no button role either.
 */
@Composable
private fun StatusLabel(connection: BankConnection) {
    val (color, alpha) = when (bankConnectionTone(connection)) {
        BankConnectionTone.CONNECTED -> MaterialTheme.colorScheme.primary to 0.14f
        BankConnectionTone.NEEDS_ACTION -> MaggieTokens.Signal.warning to 0.16f
        BankConnectionTone.NEUTRAL -> MaterialTheme.colorScheme.outline to 0.14f
    }

    Box(
        modifier = Modifier.height(24.dp).background(color.copy(alpha = alpha), RoundedCornerShape(8.dp)).padding(horizontal = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = bankConnectionStatusLabel(connection),
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
            color = color,
        )
    }
}
