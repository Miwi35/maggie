package com.maggie.app.ui.screens.bilan

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.screens.tasks.UrgencyPill
import com.maggie.app.ui.screens.tasks.WhenPill
import com.maggie.app.util.DateRanges
import java.time.LocalDate
import com.maggie.app.ui.theme.MaggieTokens

/**
 * The Demain step's slots (MAG-755): the card of tomorrow's programme, Maggie's line (the first appointments, then the
 * next trip question) and the replies as buttons — « Je précise » hands over to the microphone.
 */
@Composable
fun rememberTomorrowSlots(
    bilan: BilanActivityViewModel,
    tomorrow: BilanTomorrowViewModel,
    onAnswerByVoice: () -> Unit,
): BilanSlots {
    val bilanState by bilan.uiState.collectAsState()
    val state by tomorrow.uiState.collectAsState()
    val answered = bilanState.session?.answeredTrips().orEmpty()
    val plan = state.plan
    val question = plan?.nextQuestion(answered)
    val session = bilanState.session
    val ids = session?.let { (it.leftForTomorrow() + it.tomorrowTasks().map { t -> t.taskId }).distinct() }.orEmpty()
    val rows = plannedRows(
        ids,
        session?.tomorrowTasks().orEmpty(),
        state.tasks,
        plan?.day ?: LocalDate.now(DateRanges.zone).plusDays(1),
    )
    val rowIds = rows.map { it.taskId }
    LaunchedEffect(ids) { tomorrow.tasksOf(ids) }
    LaunchedEffect(rowIds) { bilan.shown(rowIds) }
    val canSwap = session?.tomorrowSpares()?.isNotEmpty() == true
    val failure = bilanState.failure
    val asks = if (question == null || state.sending) {
        emptyList()
    } else {
        question.choices.map { choice ->
            BilanAsk(choice.label, choice.selected) {
                if (choice.message == null) onAnswerByVoice() else tomorrow.answer(question, choice, bilan::recordTripAnswer)
            }
        }
    }
    return remember(plan, question, asks.size, state.sending, rows, canSwap, failure) {
        BilanSlots(
            content = mapOf(
                BilanStep.DEMAIN to { _ ->
                    TomorrowCard(plan)
                    TasksCard(rows, canSwap) { row -> bilan.swap(row.taskId, row.title) }
                    if (failure != null) {
                        Text(failure, color = MaggieTokens.surfaceDark.text, fontSize = 14.sp, modifier = Modifier.testTag(UiTags.BILAN_TOMORROW_FAILURE))
                    }
                },
            ),
            say = plan?.let { mapOf(BilanStep.DEMAIN to it.say(question, rows.isNotEmpty())) }.orEmpty(),
            asks = mapOf(BilanStep.DEMAIN to asks),
        )
    }
}

@Composable
private fun TomorrowCard(plan: TomorrowPlan?) {
    val surface = MaggieTokens.surfaceDark
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(surface.paper, RoundedCornerShape(20.dp))
            .padding(18.dp)
            .testTag(UiTags.BILAN_TOMORROW_CARD),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(plan?.label ?: "Demain", color = surface.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        when {
            plan == null -> Text("Je regarde demain…", color = surface.caption, fontSize = 14.sp)
            plan.rows.isEmpty() -> Text("Rien de prévu.", color = surface.caption, fontSize = 14.sp)
            else -> plan.rows.forEach { row ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(
                        row.time.ifEmpty { "Journée" },
                        color = surface.caption,
                        fontSize = 13.sp,
                        modifier = Modifier.width(64.dp),
                    )
                    Text(row.title, color = surface.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    if (row.detail.isNotEmpty()) Text(row.detail, color = surface.caption, fontSize = 12.sp)
                }
            }
        }
    }
}

/** « Tâches prévues » (MAG-967): what Maggie chose for tomorrow, each with ⇄ to swap it for the next in her reserve. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TasksCard(rows: List<PlannedRow>, canSwap: Boolean, onSwap: (PlannedRow) -> Unit) {
    if (rows.isEmpty()) return
    val surface = MaggieTokens.surfaceDark
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(surface.paper, RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag(UiTags.BILAN_TOMORROW_TASKS),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Tâches prévues", color = surface.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(if (rows.size == 1) "1 tâche" else "${rows.size} tâches", color = surface.caption, fontSize = 13.sp)
        }
        rows.forEach { row ->
            HorizontalDivider(color = surface.text.copy(alpha = 0.08f))
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(vertical = 6.dp).testTag(UiTags.BILAN_TOMORROW_TASK_PREFIX + row.taskId),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (row.time.isNotEmpty()) Text(row.time, color = surface.caption, fontSize = 14.sp, modifier = Modifier.width(48.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(row.title, color = surface.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (row.whenChip != null || row.urgency != null || row.meta != null) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            row.whenChip?.let { WhenPill(it) }
                            row.urgency?.let { UrgencyPill(it) }
                            row.meta?.let { Text(it, color = surface.caption, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterVertically)) }
                        }
                    }
                }
                if (canSwap) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(surface.text.copy(alpha = 0.06f))
                            .clickable(role = Role.Button) { onSwap(row) }
                            .semantics { contentDescription = "Échanger « ${row.title} » contre une autre tâche" }
                            .testTag(UiTags.BILAN_TOMORROW_SWAP_PREFIX + row.taskId),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.SwapHoriz, contentDescription = null, tint = surface.text, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}
