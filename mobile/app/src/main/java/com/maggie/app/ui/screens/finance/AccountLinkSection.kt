package com.maggie.app.ui.screens.finance

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.maggie.app.data.model.LinkCandidate
import com.maggie.app.data.model.formatCents
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.ConfirmDeleteDialog
import com.maggie.app.ui.components.MaggieCard
import com.maggie.app.ui.uiTagRoot

/**
 * « Rattacher au compte de la banque » of a hand-kept account (MAG-823): what its page needs to offer it,
 * and what to do with each step. Null when there is nothing to offer.
 */
data class AccountLinkOffer(
    val accountName: String,
    val candidates: List<LinkCandidate>,
    val link: LinkState,
    val onPreview: (LinkCandidate) -> Unit,
    val onConfirm: () -> Unit,
    val onCancel: () -> Unit,
    val onFeedbackShown: () -> Unit,
)

/** The card, then the choice (skipped with one candidate), then the preview and its confirmation in one box. */
@Composable
fun AccountLinkSection(offer: AccountLinkOffer, modifier: Modifier = Modifier) {
    var choosing by remember { mutableStateOf(false) }
    val first = offer.candidates.firstOrNull()
    if (first != null) {
        MaggieCard(
            modifier = modifier.fillMaxWidth().testTag(UiTags.ACCOUNT_LINK_CARD).clickable {
                if (offer.candidates.size == 1) offer.onPreview(first) else choosing = true
            },
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Rattacher au compte de la banque", style = MaterialTheme.typography.titleSmall)
                Text(
                    "${first.bank?.takeIf { it.isNotBlank() } ?: "La banque"} a créé « ${first.name} » · ${formatCents(first.balanceCents, first.currency)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (choosing) {
        Dialog(onDismissRequest = { choosing = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(modifier = Modifier.uiTagRoot()) {
                LinkAccountPickerScreen(
                    candidates = offer.candidates,
                    onPick = {
                        choosing = false
                        offer.onPreview(it)
                    },
                    onBack = { choosing = false },
                )
            }
        }
    }

    val candidate = offer.link.candidate
    val preview = offer.link.preview
    if (candidate != null && preview != null) {
        ConfirmDeleteDialog(
            title = "Rattacher « ${offer.accountName} » à « ${candidate.name} » ?",
            message = linkPreviewText(preview.moved, preview.dropped, offer.accountName),
            confirmLabel = "Rattacher",
            destructive = false,
            onConfirm = offer.onConfirm,
            onDismiss = offer.onCancel,
            confirmTag = UiTags.ACCOUNT_LINK_CONFIRM,
        )
    }
}

internal fun linkPreviewText(moved: Int, dropped: Int, keptName: String): String = buildString {
    append(if (moved == 1) "1 mouvement rejoint « $keptName »" else "$moved mouvements rejoignent « $keptName »")
    if (dropped > 0) append(if (dropped == 1) ", 1 doublon est écarté." else ", $dropped doublons sont écartés.") else append(".")
}

/** « Compte rattaché. » or the API's refusal, as a snackbar of the host's. */
@Composable
fun AccountLinkFeedback(offer: AccountLinkOffer?, snackbarHostState: SnackbarHostState) {
    val message = offer?.link?.notice ?: offer?.link?.error
    LaunchedEffect(message) {
        if (message == null) return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        offer?.onFeedbackShown?.invoke()
    }
}

/** The offer of the account on screen; the link dialog of another account is not shown on this one. */
fun accountLinkOffer(ui: AccountUiState, accountId: String, accountName: String, viewModel: AccountViewModel): AccountLinkOffer =
    AccountLinkOffer(
        accountName = accountName,
        candidates = ui.timelines[accountId]?.linkCandidates.orEmpty(),
        link = ui.link.takeIf { it.accountId == null || it.accountId == accountId } ?: LinkState(),
        onPreview = { viewModel.previewLink(accountId, it) },
        onConfirm = viewModel::confirmLink,
        onCancel = viewModel::cancelLink,
        onFeedbackShown = viewModel::clearLinkFeedback,
    )
