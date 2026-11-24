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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.maggie.app.ui.theme.MaggieTokens
import com.maggie.app.ui.theme.isMaggieDark
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
    val bubble = MaggieTokens.Maggie.bubble.let { if (isMaggieDark()) it.second else it.first }
    Popup(
        popupPositionProvider = WindowOrigin,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, clippingEnabled = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .uiTagRoot()
                .clickable(onClick = onDismiss)
                .testTag(UiTags.INTERRUPTIONS_SCRIM),
        ) {
            Box(modifier = Modifier.fillMaxSize().padding(top = topOffset).background(ScrimColor))
            Box(
                modifier = Modifier
                    .padding(top = topOffset - 4.dp, start = 12.dp, end = 12.dp)
                    .fillMaxWidth(),
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = bubble,
                    shadowElevation = 12.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 700.dp)
                        .pointerInput(Unit) { detectTapGestures { } }
                        .testTag(UiTags.INTERRUPTIONS_PANEL),
                ) {
                    PanelContent(state, actions)
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = (-26).dp, y = (-6).dp)
                        .size(14.dp)
                        .rotate(45f)
                        .background(bubble, RoundedCornerShape(3.dp)),
                )
            }
        }
    }
}

private val ScrimColor = Color(0xFF08070E).copy(alpha = 0.62f)

@Composable
private fun PanelContent(state: InterruptionsUiState, actions: InterruptionsActions) {
    Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(6.dp)) {
        val nothingPending = state.pendingCount == 0
        SectionTitle("En attente de votre réponse", UiTags.INTERRUPTIONS_PENDING)
        if (nothingPending) {
            Text(
                text = "Maggie n'attend rien de vous.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).testTag(UiTags.INTERRUPTIONS_EMPTY),
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
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 4.dp).testTag(tag),
    )
}

@Composable
private fun Entry(
    title: String,
    subtitle: String?,
    tag: String,
    onClick: () -> Unit,
    leading: @Composable () -> Unit,
    background: Color,
    trailing: @Composable () -> Unit,
    indent: Dp = 0.dp,
    faded: Boolean = false,
    answer: String? = null,
    titleMaxLines: Int = 1,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .padding(start = indent)
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(background, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(12.dp)
            .testTag(tag),
    ) {
        Box(modifier = Modifier.alpha(if (faded) 0.55f else 1f)) { leading() }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                fontSize = if (faded) 14.sp else 15.sp,
                fontWeight = if (faded) FontWeight.Normal else FontWeight.Medium,
                color = if (faded) scheme.onSurfaceVariant else scheme.onSurface,
                maxLines = titleMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let {
                Text(it, fontSize = 13.sp, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            answer?.let {
                Text(it, fontSize = 13.sp, color = scheme.primary)
            }
        }
        trailing()
    }
}

@Composable
private fun MaggieFace() = MaggieAvatar(36.dp)

@Composable
private fun CarTile() {
    val agenda = MaggieTokens.Module.agenda.let { if (isMaggieDark()) it.second else it.first }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(36.dp).background(agenda.copy(alpha = 0.14f), RoundedCornerShape(12.dp)),
    ) {
        Icon(Icons.Default.DirectionsCar, contentDescription = null, tint = agenda, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun Arrow(icon: ImageVector) {
    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
}

@Composable
private fun pendingTint() = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)

@Composable
private fun TripRow(trip: TripEntry, actions: InterruptionsActions) {
    Entry(
        title = trip.title,
        subtitle = trip.minutes?.let { "$it min de trajet" },
        tag = UiTags.interruptionsTrip(trip.notificationId),
        onClick = { actions.onTrip(trip) },
        leading = { CarTile() },
        background = pendingTint(),
        trailing = { Arrow(Icons.AutoMirrored.Filled.OpenInNew) },
    )
}

@Composable
private fun QuestionsRow(questions: List<PushPayload>, actions: InterruptionsActions) {
    if (questions.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    Entry(
        title = if (questions.size == 1) "Maggie a une question" else "Maggie a ${questions.size} questions",
        subtitle = questions.first().let { it.text ?: it.title },
        tag = UiTags.INTERRUPTIONS_QUESTIONS,
        onClick = { expanded = !expanded },
        leading = { MaggieFace() },
        background = pendingTint(),
        trailing = { Arrow(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore) },
    )
    if (expanded) {
        questions.forEach { question ->
            Entry(
                title = question.text ?: question.title,
                subtitle = null,
                tag = UiTags.interruptionsQuestion(question.key),
                onClick = { actions.onQuestion(question) },
                leading = {},
                background = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
                trailing = { Arrow(Icons.AutoMirrored.Filled.KeyboardArrowRight) },
                indent = 22.dp,
                titleMaxLines = 2,
            )
        }
    }
}

@Composable
private fun ConfirmationRow(confirmation: ConfirmationEntry, actions: InterruptionsActions) {
    Entry(
        title = "Nouveau message de Maggie",
        subtitle = confirmation.preview,
        tag = UiTags.INTERRUPTIONS_CONFIRMATION,
        onClick = { actions.onConfirmation(confirmation) },
        leading = { MaggieFace() },
        background = pendingTint(),
        trailing = { Arrow(Icons.AutoMirrored.Filled.KeyboardArrowRight) },
    )
}

@Composable
private fun SettledRow(entry: SettledEntry, actions: InterruptionsActions) {
    val trip = entry.kind == SettledKind.TRIP
    Entry(
        title = entry.title,
        subtitle = null,
        answer = entry.answer,
        faded = true,
        tag = UiTags.interruptionsSettled(entry.id),
        onClick = { actions.onSettled(entry) },
        leading = { if (trip) CarTile() else MaggieFace() },
        background = Color.Transparent,
        trailing = { Arrow(if (trip) Icons.AutoMirrored.Filled.OpenInNew else Icons.AutoMirrored.Filled.KeyboardArrowRight) },
    )
}
