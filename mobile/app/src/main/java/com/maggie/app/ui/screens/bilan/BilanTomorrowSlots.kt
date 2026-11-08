package com.maggie.app.ui.screens.bilan

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maggie.app.ui.UiTags
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
    val asks = if (question == null || state.sending) {
        emptyList()
    } else {
        question.choices.map { choice ->
            BilanAsk(choice.label, choice.selected) {
                if (choice.message == null) onAnswerByVoice() else tomorrow.answer(question, choice, bilan::recordTripAnswer)
            }
        }
    }
    return remember(plan, question, asks.size, state.sending) {
        BilanSlots(
            content = mapOf(BilanStep.DEMAIN to { _ -> TomorrowCard(plan) }),
            say = plan?.let { mapOf(BilanStep.DEMAIN to it.say(question)) }.orEmpty(),
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
