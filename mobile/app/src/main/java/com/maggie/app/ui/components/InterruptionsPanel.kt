package com.maggie.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.maggie.app.data.fcm.PushPayload
import com.maggie.app.data.interruption.key
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.interruption.ConfirmationEntry
import com.maggie.app.ui.interruption.InterruptionsUiState
import com.maggie.app.ui.interruption.SettledEntry
import com.maggie.app.ui.interruption.SettledKind
import com.maggie.app.ui.interruption.TripEntry
import com.maggie.app.ui.uiTagRoot

private object WindowOrigin : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset.Zero
}

/**
 * What Maggie waits on and what was settled in the last 24 hours (MAG-712): a panel under the bell, on a
 * dimmed scrim whose tap closes it. [topOffset] is the bell's bottom edge in the window.
 */
@Composable
fun InterruptionsPanel(
    state: InterruptionsUiState,
    topOffset: Dp,
    actions: InterruptionsActions,
    onDismiss: () -> Unit,
) {
    Popup(
        popupPositionProvider = WindowOrigin,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, clippingEnabled = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .uiTagRoot()
                .background(Color.Black.copy(alpha = 0.4f))
                .clickable(onClick = onDismiss)
                .testTag(UiTags.INTERRUPTIONS_SCRIM),
        ) {
            Surface(
                shape = RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                modifier = Modifier
                    .padding(top = topOffset)
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .testTag(UiTags.INTERRUPTIONS_PANEL),
            ) {
                PanelContent(state, actions)
            }
        }
    }
}

@Composable
private fun PanelContent(state: InterruptionsUiState, actions: InterruptionsActions) {
    Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        val nothingPending = state.pendingCount == 0
        SectionTitle("En attente de votre réponse", UiTags.INTERRUPTIONS_PENDING)
        if (nothingPending) {
            Text(
                text = "Maggie n'attend rien de vous.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp).testTag(UiTags.INTERRUPTIONS_EMPTY),
            )
        }
        state.trips.forEach { TripRow(it, actions) }
        QuestionsRow(state.questions, actions)
        state.confirmations.firstOrNull()?.let { ConfirmationRow(it, actions) }
        if (state.settled.isNotEmpty()) {
            SectionTitle("Réglées", UiTags.INTERRUPTIONS_SETTLED)
            state.settled.forEach { SettledRow(it, actions) }
        }
    }
}

@Composable
private fun SectionTitle(text: String, tag: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp).testTag(tag),
    )
}

@Composable
private fun Entry(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    tag: String,
    onClick: () -> Unit,
    discreet: Boolean = false,
    answer: String? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val tone = if (discreet) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .testTag(tag),
    ) {
        Icon(icon, contentDescription = null, tint = if (discreet) tone else MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = tone, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            answer?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        trailing?.invoke()
    }
}

@Composable
private fun TripRow(trip: TripEntry, actions: InterruptionsActions) {
    Entry(
        icon = Icons.Default.DirectionsCar,
        title = trip.title,
        subtitle = trip.minutes?.let { "$it min de trajet" },
        tag = UiTags.interruptionsTrip(trip.notificationId),
        onClick = { actions.onTrip(trip) },
    )
}

@Composable
private fun QuestionsRow(questions: List<PushPayload>, actions: InterruptionsActions) {
    if (questions.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    Entry(
        icon = Icons.AutoMirrored.Filled.HelpOutline,
        title = if (questions.size == 1) "Maggie a une question" else "Maggie a ${questions.size} questions",
        subtitle = questions.first().let { it.text ?: it.title },
        tag = UiTags.INTERRUPTIONS_QUESTIONS,
        onClick = { expanded = !expanded },
        trailing = { Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null) },
    )
    if (expanded) {
        questions.forEach { question ->
            Text(
                text = question.text ?: question.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable { actions.onQuestion(question) }
                    .padding(start = 58.dp, end = 20.dp, top = 12.dp, bottom = 12.dp)
                    .testTag(UiTags.interruptionsQuestion(question.key)),
            )
        }
    }
}

@Composable
private fun ConfirmationRow(confirmation: ConfirmationEntry, actions: InterruptionsActions) {
    Entry(
        icon = Icons.Default.ChatBubbleOutline,
        title = "Nouveau message de Maggie",
        subtitle = confirmation.preview,
        tag = UiTags.INTERRUPTIONS_CONFIRMATION,
        onClick = { actions.onConfirmation(confirmation) },
    )
}

@Composable
private fun SettledRow(entry: SettledEntry, actions: InterruptionsActions) {
    Entry(
        icon = if (entry.kind == SettledKind.TRIP) Icons.Default.DirectionsCar else Icons.Default.ChatBubbleOutline,
        title = entry.title,
        subtitle = null,
        answer = entry.answer,
        discreet = true,
        tag = UiTags.interruptionsSettled(entry.id),
        onClick = { actions.onSettled(entry) },
    )
}
