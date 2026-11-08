package com.maggie.app.ui.screens.bilan

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maggie.app.data.model.HealthDay
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.screens.health.balanceLabel
import com.maggie.app.ui.screens.health.balanceZone
import com.maggie.app.ui.screens.health.BalanceZone
import com.maggie.app.ui.screens.health.formatCount
import com.maggie.app.ui.screens.health.journalSport
import com.maggie.app.ui.screens.health.journalWeight
import com.maggie.app.ui.theme.MaggieTokens
import java.time.ZoneId

private const val NONE = "—"

internal const val MOOD_KEY = "mood"
internal const val FATIGUE_KEY = "fatigue"

/** One figure card of the step: « Bilan calorique », « Pas », « Sport », « Poids ». No verdict, only what exists. */
data class SanteFigure(val key: String, val label: String, val value: String, val detail: String)

/** The four figures of the day, a dash where the day has no source (no steps, no weighing). */
fun santeFigures(entry: HealthDay?, zone: ZoneId): List<SanteFigure> {
    val eatenSpent = listOfNotNull(entry?.eatenKcal?.let(::formatCount), entry?.expendedKcal?.let(::formatCount)).joinToString(" · ")
    return listOf(
        SanteFigure("balance", "Bilan calorique", balanceLabel(entry?.balanceKcal) ?: NONE, eatenSpent),
        SanteFigure("steps", "Pas", entry?.steps?.let(::formatCount) ?: NONE, ""),
        SanteFigure("sport", "Sport", journalSport(entry?.sportMinutes), ""),
        SanteFigure("weight", "Poids", journalWeight(entry, zone), ""),
    )
}

/** Maggie's line for the step: the day's figures in plain words, never a verdict, then the two questions. */
fun santeSay(entry: HealthDay?): String {
    val facts = listOfNotNull(
        entry?.sportMinutes?.takeIf { it > 0 }?.let { "Séance faite" },
        entry?.steps?.let { "${formatCount(it)} pas" },
        entry?.balanceKcal?.let { b ->
            when (balanceZone(b)) {
                BalanceZone.DEFICIT -> "un déficit de ${formatCount(-b)} kcal"
                BalanceZone.SURPLUS -> "un excédent de ${formatCount(b)} kcal"
                else -> "une journée équilibrée en calories"
            }
        },
    )
    val questions = "Et votre moral, sur 10 ? Votre fatigue ?"
    if (facts.isEmpty()) return questions
    val head = if (facts.size == 1) facts[0] else facts.dropLast(1).joinToString(", ") + " et " + facts.last()
    return "$head. $questions"
}

/** The Santé step's slot (MAG-754): Maggie's line from the figures and the card, wired to [viewModel] and the bilan's answers. */
@Composable
fun rememberSanteSlots(
    viewModel: BilanSanteViewModel,
    session: BilanSession?,
    zone: ZoneId,
    onAnswer: (key: String, value: Int) -> Unit,
): BilanSlots {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(session?.day) { session?.day?.let(viewModel::open) }
    val mood = state.entry?.mood ?: session?.answerInt(MOOD_KEY)
    val fatigue = state.entry?.fatigue ?: session?.answerInt(FATIGUE_KEY)
    return BilanSlots(
        content = mapOf(
            BilanStep.SANTE to {
                SanteContent(
                    figures = santeFigures(state.entry, zone),
                    mood = mood,
                    fatigue = fatigue,
                    error = state.error,
                    onMood = { viewModel.pickMood(it) { v -> onAnswer(MOOD_KEY, v) } },
                    onFatigue = { viewModel.pickFatigue(it) { v -> onAnswer(FATIGUE_KEY, v) } },
                )
            },
        ),
        say = mapOf(BilanStep.SANTE to santeSay(state.entry)),
    )
}

@Composable
internal fun SanteContent(
    figures: List<SanteFigure>,
    mood: Int?,
    fatigue: Int?,
    error: String?,
    onMood: (Int) -> Unit,
    onFatigue: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        figures.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { FigureCard(it, Modifier.weight(1f)) }
            }
        }
        Scale("Moral", mood, onMood, UiTags::bilanSanteMood)
        Scale("Fatigue", fatigue, onFatigue, UiTags::bilanSanteFatigue)
        if (error != null) {
            Text(error, fontSize = 13.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(UiTags.BILAN_SANTE_ERROR))
        }
    }
}

@Composable
private fun FigureCard(figure: SanteFigure, modifier: Modifier) {
    val surface = MaggieTokens.surfaceDark
    Column(
        modifier = modifier
            .background(surface.raised, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) {}
            .testTag(UiTags.BILAN_SANTE_CARD_PREFIX + figure.key),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(figure.label, fontSize = 12.sp, color = surface.textMuted)
        Text(figure.value, fontSize = 18.sp, fontWeight = FontWeight.Light, color = surface.text)
        if (figure.detail.isNotEmpty()) Text(figure.detail, fontSize = 12.sp, color = surface.caption)
    }
}

@Composable
private fun Scale(label: String, value: Int?, onPick: (Int) -> Unit, tag: (Int) -> String) {
    val surface = MaggieTokens.surfaceDark
    val accent = MaterialTheme.colorScheme.primary
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Text(label, fontSize = 14.sp, color = surface.textMuted)
            Text(value?.let { "$it / 10" } ?: "— / 10", fontSize = 15.sp, color = surface.text)
        }
        Row(
            modifier = Modifier.fillMaxWidth().selectableGroup().semantics { contentDescription = "$label sur 10" },
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            HealthDay.SCALE.forEach { n ->
                val chosen = n == value
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .background(if (chosen) accent else surface.raised, RoundedCornerShape(10.dp))
                        .border(1.dp, if (chosen) accent else Color.Transparent, RoundedCornerShape(10.dp))
                        .selectable(selected = chosen, role = Role.RadioButton, onClick = { onPick(n) })
                        .semantics { contentDescription = "$label $n sur 10" }
                        .testTag(tag(n)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        n.toString(),
                        fontSize = 15.sp,
                        color = if (chosen) MaterialTheme.colorScheme.onPrimary else surface.textMuted,
                    )
                }
            }
        }
    }
}
