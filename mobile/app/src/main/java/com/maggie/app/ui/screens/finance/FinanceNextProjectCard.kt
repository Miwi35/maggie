package com.maggie.app.ui.screens.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.NextProject
import com.maggie.app.data.model.ProjectBeyondHorizon
import com.maggie.app.data.model.durationLabel
import com.maggie.app.data.model.formatCents
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.MaggieCard
import java.time.LocalDate

/** Whole months from [today]'s month to [purchaseMonth] (`YYYY-MM`), never below 0. */
internal fun monthsUntil(purchaseMonth: String, today: LocalDate = LocalDate.now()): Int =
    (monthNumber(purchaseMonth) - (today.year * 12 + today.monthValue - 1)).coerceAtLeast(0)

/** « dans 4 mois · oct. 2026 », « ce mois-ci · oct. 2026 ». */
internal fun purchaseLabel(purchaseMonth: String, today: LocalDate = LocalDate.now()): String {
    val months = monthsUntil(purchaseMonth, today)
    val delay = if (months == 0) "ce mois-ci" else "dans ${durationLabel(months)}"
    return "$delay · ${ganttMonthLabel(purchaseMonth)}"
}

/** « Restaurants et Obsydian », « A, B et C ». */
internal fun joinNames(names: List<String>): String = when (names.size) {
    0 -> ""
    1 -> names[0]
    else -> "${names.dropLast(1).joinToString(", ")} et ${names.last()}"
}

/** « d'un mois », « de 3 mois », « d'un an », « de 1 an et 2 mois ». */
internal fun delayLabel(months: Int): String = when (months) {
    1 -> "d'un mois"
    12 -> "d'un an"
    else -> "de ${durationLabel(months)}"
}

/** « Les dépassements de ce mois (Restaurants et Obsydian, +58,00 €) repoussent Voyage au Japon d'un mois. » */
internal fun negativeReturnSentence(project: NextProject): String? = project.delay?.let { delay ->
    "Les dépassements de ce mois (${joinNames(delay.envelopes)}, +${formatCents(delay.overspentCents)}) " +
        "repoussent ${delay.name} ${delayLabel(delay.months)}."
}

/**
 * « Prochain projet » (MAG-529): the first project of the planning, what is gathered, when it falls, the two after it
 * and — a fact, not a reproach — what the month's overspending pushes back. Not clickable.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NextProjectCard(project: NextProject, modifier: Modifier = Modifier, today: LocalDate = LocalDate.now()) {
    NextProjectCardLayout(
        name = project.name,
        dateText = purchaseLabel(project.purchaseMonth, today),
        gatheredCents = project.gatheredCents,
        amountCents = project.amountCents,
        modifier = modifier,
    ) {
        if (project.following.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                project.following.forEach { next ->
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.testTag(UiTags.FINANCE_NEXT_PROJECT_FOLLOWING),
                        label = {
                            Text(
                                "${next.name} · ${formatCents(next.amountCents)} · ${ganttMonthLabel(next.purchaseMonth)}",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                    )
                }
            }
        }
        negativeReturnSentence(project)?.let { sentence ->
            Text(
                text = sentence,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp).testTag(UiTags.FINANCE_NEXT_PROJECT_NEGATIVE),
            )
        }
    }
}

/** The same card when nothing is planned inside the horizon (MAG-667): the first project, « au-delà de 3 ans », and nothing else. */
@Composable
internal fun NextProjectBeyondHorizonCard(project: ProjectBeyondHorizon, modifier: Modifier = Modifier) {
    NextProjectCardLayout(
        name = project.name,
        dateText = "au-delà de ${durationLabel(project.horizonMonths)}",
        gatheredCents = project.gatheredCents,
        amountCents = project.amountCents,
        modifier = modifier,
    )
}

@Composable
private fun NextProjectCardLayout(
    name: String,
    dateText: String,
    gatheredCents: Int,
    amountCents: Int,
    modifier: Modifier = Modifier,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    MaggieCard(modifier = modifier.fillMaxWidth().testTag(UiTags.FINANCE_NEXT_PROJECT)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Prochain projet", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = dateText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(UiTags.FINANCE_NEXT_PROJECT_DATE),
                )
            }
            LinearProgressIndicator(
                progress = { (gatheredCents.toFloat() / amountCents.coerceAtLeast(1)).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp).padding(top = 4.dp),
            )
            Text(
                text = "${formatCents(gatheredCents)} sur ${formatCents(amountCents)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            extra()
        }
    }
}
