package com.maggie.app.ui.screens.health

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maggie.app.data.model.FastingProtocol
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.MaggieCard
import com.maggie.app.ui.screens.settings.TimePickerDialog
import com.maggie.app.ui.theme.maggieSurface
import com.maggie.app.ui.uiTagRoot
import java.time.LocalDate

/** The ring of the coral « Jeûne » card, 104 dp on a phone and 128 on a tablet as the mockups draw it. */
private val RING_PHONE = 104.dp
private val RING_TABLET = 128.dp

/**
 * « Jeûne » (MAG-770, mockup Alimentation-Tel): the coral ring and what is left in its centre; beside it « Fenêtre
 * ouverte » or « Jeûne », the protocol that opens the sheet, the span of the window and the night's fast.
 */
@Composable
fun FastingCard(view: FastingView, onOpenSheet: () -> Unit, modifier: Modifier = Modifier, large: Boolean = false) {
    val surface = maggieSurface()
    val accent = MaterialTheme.colorScheme.primary
    val remaining = formatFastDuration(view.remainingMinutes)
    val ring = if (large) RING_TABLET else RING_PHONE
    MaggieCard(modifier = modifier.fillMaxWidth().semantics { contentDescription = "Jeûne" }.testTag(UiTags.FASTING_CARD)) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(ring), contentAlignment = Alignment.Center) {
                FastingRing(view.progress, ring, description = "${view.title}, ${view.span}, $remaining restantes")
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(remaining, fontSize = 19.sp, color = surface.text, modifier = Modifier.testTag(UiTags.FASTING_REMAINING))
                    Text("restantes", fontSize = 12.sp, color = surface.caption)
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        view.title,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = surface.text,
                        modifier = Modifier.testTag(UiTags.FASTING_STATE),
                    )
                    TextButton(onClick = onOpenSheet, modifier = Modifier.testTag(UiTags.FASTING_PROTOCOL)) {
                        Text(view.protocol.key, fontSize = 15.sp, color = accent)
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
                    }
                }
                Text(view.span, fontSize = 14.sp, color = surface.textMuted, modifier = Modifier.testTag(UiTags.FASTING_SPAN))
                view.nightFast?.let {
                    Text(it, fontSize = 12.sp, color = surface.caption, modifier = Modifier.testTag(UiTags.FASTING_NIGHT))
                }
            }
        }
    }
}

/** The track, then the coral arc from the top, clockwise, round-capped — 14 of the mockup's 170 units thick. */
@Composable
private fun FastingRing(progress: Float, diameter: Dp, description: String) {
    val track = maggieSurface().track
    val accent = MaterialTheme.colorScheme.primary
    Canvas(modifier = Modifier.fillMaxSize().semantics { contentDescription = description }) {
        val stroke = diameter.toPx() * 14f / 170f
        val radius = diameter.toPx() * 70f / 170f
        val topLeft = Offset(center.x - radius, center.y - radius)
        val arc = Size(radius * 2, radius * 2)
        drawCircle(track, radius = radius, style = Stroke(width = stroke))
        if (progress > 0f) {
            drawArc(accent, -90f, 360f * progress, false, topLeft, arc, style = Stroke(width = stroke, cap = StrokeCap.Round))
        }
    }
}

/**
 * « Jeûnes · 14 jours » (MAG-770, mockup Alimentation-Tablette): one bar per night's fast, the protocol's fast hours as
 * a dashed line, today's bar in full coral and the others paler, the day of the month under each.
 */
@Composable
fun FastingHistoryChart(history: List<Pair<LocalDate, Int?>>, protocol: FastingProtocol, modifier: Modifier = Modifier) {
    val surface = maggieSurface()
    val accent = MaterialTheme.colorScheme.primary
    val line = surface.caption
    val scale = fastingChartScale(history).toFloat()
    MaggieCard(modifier = modifier.fillMaxWidth().semantics { contentDescription = "Jeûnes, 14 jours" }.testTag(UiTags.FASTING_HISTORY)) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Text("Jeûnes · 14 jours", fontSize = 14.sp, color = surface.textMuted)
                Text(protocol.key, fontSize = 12.sp, color = surface.caption)
            }
            Canvas(modifier = Modifier.fillMaxWidth().height(120.dp)) {
                if (history.isEmpty()) return@Canvas
                val gap = 6.dp.toPx()
                val bar = (size.width - gap * (history.size - 1)) / history.size
                history.forEachIndexed { i, (_, minutes) ->
                    val value = minutes ?: return@forEachIndexed
                    val height = (value / scale * size.height).coerceAtLeast(2f)
                    val color = if (i == history.lastIndex) accent else accent.copy(alpha = 0.55f)
                    drawRoundRect(color, Offset(i * (bar + gap), size.height - height), Size(bar, height), CornerRadius(4.dp.toPx()))
                }
                val y = size.height - protocol.fastHours * 60 / scale * size.height
                drawLine(
                    line.copy(alpha = 0.6f),
                    Offset(0f, y),
                    Offset(size.width, y),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                history.forEach { (date, _) ->
                    Text(
                        date.dayOfMonth.toString(),
                        fontSize = 11.sp,
                        color = surface.caption,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** The « Jeûne » sheet, a window of its own; its content is [FastingSheetContent], which the screen test drives alone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FastingSheet(
    protocol: FastingProtocol,
    windowStart: String,
    saving: Boolean,
    onSave: (protocol: String, windowStart: String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Box(modifier = Modifier.uiTagRoot()) {
            FastingSheetContent(protocol, windowStart, saving, onSave)
        }
    }
}

/** « Jeûne »: the four protocols two by two, « Début de la fenêtre » and its time, « Enregistrer ». */
@Composable
internal fun FastingSheetContent(
    protocol: FastingProtocol,
    windowStart: String,
    saving: Boolean,
    onSave: (protocol: String, windowStart: String) -> Unit,
) {
    val surface = maggieSurface()
    var picked by rememberSaveable { mutableStateOf(protocol.key) }
    var start by rememberSaveable { mutableStateOf(windowStart) }
    var pickingTime by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 24.dp).testTag(UiTags.FASTING_SHEET),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Jeûne", fontSize = 20.sp, fontWeight = FontWeight.Medium, color = surface.text)
        Column(modifier = Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FastingProtocol.entries.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { option ->
                        ProtocolPill(option, selected = option.key == picked, onClick = { picked = option.key }, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        HorizontalDivider(color = surface.track)
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Début de la fenêtre", fontSize = 15.sp, color = surface.text)
            Box(
                modifier = Modifier
                    .height(40.dp)
                    .background(surface.raised, RoundedCornerShape(14.dp))
                    .clickable(role = Role.Button) { pickingTime = true }
                    .testTag(UiTags.FASTING_WINDOW_START)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(start, fontSize = 15.sp, color = surface.text)
            }
        }
        Button(
            onClick = { onSave(picked, start) },
            enabled = !saving,
            modifier = Modifier.fillMaxWidth().height(44.dp).testTag(UiTags.FASTING_SAVE),
        ) {
            Text("Enregistrer", fontSize = 15.sp)
        }
    }

    if (pickingTime) {
        TimePickerDialog(
            title = "Début de la fenêtre",
            time = start,
            onPick = { hour, minute -> start = fastingTime(hour, minute) },
            onClose = { pickingTime = false },
        )
    }
}

/** « 16:8 » over « 8 h de fenêtre »; the one picked in coral on its tinted ground. */
@Composable
private fun ProtocolPill(option: FastingProtocol, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val surface = maggieSurface()
    val accent = MaterialTheme.colorScheme.primary
    Column(
        modifier = modifier
            .heightIn(min = 56.dp)
            .background(if (selected) accent.copy(alpha = 0.16f) else surface.raised, RoundedCornerShape(14.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .testTag(UiTags.fastingProtocol(option.key))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        Text(option.key, fontSize = 17.sp, color = if (selected) accent else surface.text)
        Text(option.windowLabel, fontSize = 12.sp, color = surface.caption)
    }
}
