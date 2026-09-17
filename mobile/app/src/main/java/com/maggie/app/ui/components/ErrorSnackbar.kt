package com.maggie.app.ui.components

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * Surfaces a failure instead of leaving the screen silently empty, and offers
 * the way out of it. The technical message stays out of the way: what the user
 * needs is what happened and what to do about it.
 */
@Composable
fun ErrorSnackbar(
    error: String?,
    snackbarHostState: SnackbarHostState,
    onDismiss: () -> Unit,
    onRetry: (() -> Unit)? = null,
    message: String = "Chargement impossible. Vérifiez votre connexion.",
) {
    LaunchedEffect(error) {
        if (error == null) return@LaunchedEffect

        val result = snackbarHostState.showSnackbar(
            message = message,
            actionLabel = if (onRetry != null) "Réessayer" else null,
            duration = SnackbarDuration.Long,
        )

        if (result == SnackbarResult.ActionPerformed) {
            onRetry?.invoke()
        }

        onDismiss()
    }
}
