package com.maggie.app.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.uiTagRoot

@Composable
fun ConfirmDeleteDialog(
    title: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    message: String? = null,
    confirmLabel: String = "Confirmer",
    // false for a confirmation that deletes nothing (« Rattacher »): the button is not red.
    destructive: Boolean = true,
    confirmTag: String = UiTags.CONFIRM_DELETE,
    cancelTag: String = UiTags.CONFIRM_DELETE_CANCEL,
) {
    AlertDialog(
        modifier = Modifier.uiTagRoot(),
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = message?.let { { Text(it) } },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag(confirmTag)) {
                Text(
                    confirmLabel,
                    color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(cancelTag)) { Text("Annuler") }
        },
    )
}
