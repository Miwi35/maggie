package com.maggie.app.ui.screens.proactions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.Proaction
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.DateField
import com.maggie.app.ui.components.TimeField
import com.maggie.app.ui.screens.shared.parseColor
import com.maggie.app.ui.screens.fullcalendar.PROACTIONS_COLOR
import com.maggie.app.ui.uiTagRoot
import com.maggie.app.util.DateRanges
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dayFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRENCH)
private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.FRENCH)

/** « Jeudi 15 octobre 2026, 08:30 » in Paris, the zone the agenda draws in; empty without a start. */
internal fun proactionWhen(proaction: Proaction): String {
    val start = proaction.startAt?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() } ?: return ""
    val local = start.toInstant().atZone(DateRanges.zone)
    return "${local.format(dayFormatter).replaceFirstChar { it.uppercase() }}, ${local.format(timeFormatter)}"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProactionDetailSheet(
    proaction: Proaction,
    onDismiss: () -> Unit,
    onMove: (Instant) -> Unit,
    onDelete: () -> Unit,
) {
    ProactionDetailContent(proaction = proaction, onMove = onMove, onDelete = onDelete) { body ->
        ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.uiTagRoot()) { body() }
    }
}

/**
 * The detail of one of Maggie's runs: what she was asked, how it went, what she answered or why it
 * failed. The pencil changes the time only; deleting cancels the run. [frame] is the sheet's
 * modal, or nothing in a detail pane.
 */
@Composable
fun ProactionDetailContent(
    proaction: Proaction,
    onMove: (Instant) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    frame: @Composable (body: @Composable () -> Unit) -> Unit = { body -> body() },
) {
    var editing by remember(proaction.id) { mutableStateOf(false) }

    frame {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(parseColor(PROACTIONS_COLOR) ?: MaterialTheme.colorScheme.primary, CircleShape),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = proaction.summary.ifBlank { proaction.prompt },
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f).testTag(UiTags.PROACTION_DETAIL_TITLE),
                )
            }

            if (editing) {
                ProactionTimeEditor(
                    proaction = proaction,
                    onSave = {
                        editing = false
                        onMove(it)
                    },
                    onCancel = { editing = false },
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CalendarToday, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(text = proactionWhen(proaction), style = MaterialTheme.typography.bodyMedium)
                }
            }

            Text(
                text = proaction.statusLabel,
                style = MaterialTheme.typography.labelLarge,
                color = if (proaction.isFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag(UiTags.PROACTION_DETAIL_STATUS),
            )

            LabelledText("Instruction", proaction.prompt)
            proaction.response?.takeIf { it.isNotBlank() }?.let { LabelledText("Réponse", it) }
            proaction.error?.takeIf { it.isNotBlank() }?.let {
                LabelledText("Erreur", it, color = MaterialTheme.colorScheme.error)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                IconButton(
                    onClick = { editing = !editing },
                    modifier = Modifier.testTag(UiTags.PROACTION_DETAIL_EDIT),
                ) {
                    Icon(Icons.Default.Edit, contentDescription = "Modifier l'heure")
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.testTag(UiTags.PROACTION_DETAIL_DELETE),
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Supprimer",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun LabelledText(label: String, text: String, color: Color = Color.Unspecified) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

@Composable
private fun ProactionTimeEditor(
    proaction: Proaction,
    onSave: (Instant) -> Unit,
    onCancel: () -> Unit,
) {
    val current = proaction.startAt
        ?.let { runCatching { OffsetDateTime.parse(it).toInstant().atZone(DateRanges.zone) }.getOrNull() }
    var day by remember(proaction.id) { mutableStateOf(current?.toLocalDate() ?: LocalDate.now(DateRanges.zone)) }
    var time by remember(proaction.id) { mutableStateOf(current?.toLocalTime()?.withSecond(0)?.withNano(0) ?: LocalTime.of(9, 0)) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DateField(
            label = "Jour",
            value = day,
            onValueChange = { day = it },
            modifier = Modifier.fillMaxWidth(),
            tag = UiTags.PROACTION_DETAIL_DATE,
        )
        TimeField(
            label = "Heure",
            value = time,
            onValueChange = { time = it },
            modifier = Modifier.fillMaxWidth(),
            tag = UiTags.PROACTION_DETAIL_TIME,
        )
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onCancel) { Text("Annuler") }
            Button(
                onClick = { onSave(day.atTime(time).atZone(DateRanges.zone).toInstant()) },
                modifier = Modifier.testTag(UiTags.PROACTION_DETAIL_SAVE),
            ) { Text("Enregistrer") }
        }
    }
}
