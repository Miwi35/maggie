package com.maggie.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.testTag
import com.maggie.app.ui.UiTags
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.Context
import com.maggie.app.ui.screens.contexts.ContextUiState
import com.maggie.app.ui.uiTagRoot
import com.maggie.app.ui.theme.contextStateColor

/**
 * The threads the one conversation is filed in (MAG-342): to consult, and to clean.
 *
 * Deleting is asked for per row, confirmed once — a thread takes its messages with it, and
 * the dialog says how many — then undone from a snackbar for a few seconds, the server
 * hearing nothing until the window closes. The snackbar lives here and not on the screen's
 * scaffold: the sheet is a window of its own, drawn over the scaffold's host.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextListSheet(
    sheetState: SheetState,
    uiState: ContextUiState,
    onDismiss: () -> Unit,
    onRequestDelete: (Context) -> Unit = {},
    onConfirmDelete: () -> Unit = {},
    onCancelDelete: () -> Unit = {},
    onUndoDelete: () -> Unit = {},
    onDeleteFailedShown: () -> Unit = {},
    onOpened: () -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }

    // The counts a deletion's confirmation quotes: a thread opened since the list was loaded has none yet.
    LaunchedEffect(Unit) { onOpened() }

    // The ViewModel owns the undo window and clears `undoableDeletion` when it closes, which
    // ends this effect and dismisses the snackbar with it.
    LaunchedEffect(uiState.undoableDeletion?.id) {
        if (uiState.undoableDeletion == null) return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "Fil supprimé",
            actionLabel = "Annuler",
            duration = SnackbarDuration.Indefinite,
        )
        if (result == SnackbarResult.ActionPerformed) onUndoDelete()
    }

    ErrorSnackbar(
        error = "delete".takeIf { uiState.deleteFailed },
        snackbarHostState = snackbarHostState,
        onDismiss = onDeleteFailedShown,
        message = "La suppression a échoué. Réessayez.",
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        modifier = Modifier.fillMaxHeight(0.85f),
    ) {
        // Its own window, so its own tag root (MAG-98) — see ui/UiTagRoot.kt.
        Column(modifier = Modifier.fillMaxWidth().uiTagRoot()) {
            // Header
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp, top = 4.dp),
            ) {
                Text(
                    text = "Fils de discussion",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.CenterEnd),
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Fermer")
                }
            }

            when {
                uiState.isLoading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                uiState.contexts.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Aucun fil de discussion",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(uiState.contexts, key = { it.id }) { context ->
                            ContextRow(context, onDelete = { onRequestDelete(context) })
                        }
                    }
                }
            }

            SnackbarHost(snackbarHostState)
        }
    }

    // After the sheet, so that its window is created over it.
    uiState.deletionToConfirm?.let { context ->
        DeleteThreadDialog(context = context, onConfirm = onConfirmDelete, onCancel = onCancelDelete)
    }
}

@Composable
private fun ContextRow(context: Context, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = statusIndicator(context.status),
            style = MaterialTheme.typography.bodyLarge,
            color = contextStateColor(context.status),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = context.label,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = messageCountLabel(context.messageCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Apart from the label and reachable without a swipe; an IconButton is 48 dp.
        IconButton(onClick = onDelete) {
            Icon(Icons.Outlined.Delete, contentDescription = "Supprimer le fil ${context.label}")
        }
    }
}

/** What deleting the thread takes with it, said before the owner has to decide. */
@Composable
private fun DeleteThreadDialog(context: Context, onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        // A dialog is a window of its own, so it carries its own tag root (MAG-98).
        modifier = Modifier.uiTagRoot(),
        onDismissRequest = onCancel,
        title = { Text("Supprimer ce fil ?") },
        text = { Text(deletionWarning(context)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag(UiTags.THREAD_DELETE_CONFIRM),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text("Supprimer")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Annuler") }
        },
    )
}

/** « Ce fil et ses 3 messages seront supprimés » — and the two shorter forms. */
internal fun deletionWarning(context: Context): String = when (context.messageCount) {
    0 -> "Ce fil sera supprimé"
    1 -> "Ce fil et son message seront supprimés"
    else -> "Ce fil et ses ${context.messageCount} messages seront supprimés"
}

private fun messageCountLabel(count: Int): String = when (count) {
    0 -> "Aucun message"
    1 -> "1 message"
    else -> "$count messages"
}

private fun statusIndicator(status: String): String = when (status) {
    "active" -> "●"
    "dormant" -> "◐"
    else -> "○"
}
