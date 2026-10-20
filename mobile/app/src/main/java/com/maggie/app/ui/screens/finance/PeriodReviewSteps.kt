package com.maggie.app.ui.screens.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.PeriodBalance
import com.maggie.app.data.model.PeriodEnvelope
import com.maggie.app.data.model.PeriodMonth
import com.maggie.app.data.model.PeriodProject
import com.maggie.app.data.model.PlanCategory
import com.maggie.app.data.model.PlanningPeriodRef
import com.maggie.app.data.model.REVIEW_LEVEL_YEAR
import com.maggie.app.data.model.formatCents
import com.maggie.app.data.model.periodLabel
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.statusColor
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/** « mars », from `2027-03`. */
internal fun periodMonthName(month: String): String =
    runCatching { YearMonth.parse(month).month.getDisplayName(TextStyle.FULL, Locale.FRENCH) }.getOrDefault(month)

/**
 * Bilan de la période (MAG-462): the quarter, semester or year the review closes — month by month, the
 * envelopes, the satisfaction, what was put aside, and the planned expenses done or postponed.
 */
@Composable
fun PeriodBalanceStep(viewModel: PeriodReviewViewModel, month: String) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(month) { viewModel.loadBalance(month) }

    val balance = state.balance
    if (balance == null) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            val error = state.balanceError
            if (error == null) {
                CircularProgressIndicator()
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(error, modifier = Modifier.testTag(UiTags.PERIOD_BALANCE_ERROR))
                    Button(onClick = { viewModel.loadBalance(month) }, modifier = Modifier.heightIn(min = 56.dp)) { Text("Réessayer") }
                }
            }
        }
        return
    }
    PeriodBalanceContent(balance)
}

@Composable
internal fun PeriodBalanceContent(balance: PeriodBalance) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag(UiTags.PERIOD_BALANCE),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BalanceCard("Mois par mois · ${periodLabel(balance.period)}", UiTags.PERIOD_BALANCE_MONTHS) {
            balance.months.forEach { PeriodMonthBar(it) }
            Text(
                "${formatCents(balance.totals.spentCents)} dépensés sur ${formatCents(balance.totals.budgetCents)} budgétés" +
                    " · ${formatCents(balance.totals.savedCents)} épargnés",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        BalanceCard("Enveloppes", UiTags.PERIOD_BALANCE_ENVELOPES) {
            if (balance.envelopes.isEmpty()) {
                Text(
                    "Aucune enveloppe sur la période.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            balance.envelopes.forEach { PeriodEnvelopeBar(it) }
        }
        SatisfactionCard(balance.satisfaction)
        SavingsCard(balance.savings)
        BalanceCard("Projets", UiTags.PERIOD_BALANCE_PROJECTS) {
            ProjectList("Réalisés", balance.projects.done)
            ProjectList("Reportés", balance.projects.postponed)
            if (balance.projects.done.isEmpty() && balance.projects.postponed.isEmpty()) {
                Text(
                    "Aucune dépense prévue sur la période.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PeriodMonthBar(month: PeriodMonth) {
    val fill = when {
        month.budgetCents > 0 -> (month.spentCents.toFloat() / month.budgetCents).coerceIn(0f, 1f)
        month.spentCents > 0 -> 1f
        else -> 0f
    }
    val status = if (month.budgetCents in 1 until month.spentCents) "red" else "green"
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(periodMonthName(month.month), style = MaterialTheme.typography.bodyMedium)
            Text(
                "${formatCents(month.spentCents)} sur ${formatCents(month.budgetCents)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LinearProgressIndicator(
            progress = { fill },
            color = statusColor(status),
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).height(8.dp),
        )
    }
}

@Composable
private fun PeriodEnvelopeBar(envelope: PeriodEnvelope) {
    val fill = when {
        envelope.budgetCents > 0 -> (envelope.consumedCents.toFloat() / envelope.budgetCents).coerceIn(0f, 1f)
        envelope.consumedCents > 0 -> 1f
        else -> 0f
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(envelope.name, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${formatCents(envelope.consumedCents)} sur ${formatCents(envelope.budgetCents)}" +
                    (envelope.percent?.let { " · $it %" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LinearProgressIndicator(
            progress = { fill },
            color = statusColor(envelopeScoreStatus(envelope.status)),
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).height(8.dp),
        )
    }
}

@Composable
private fun ProjectList(title: String, projects: List<PeriodProject>) {
    if (projects.isEmpty()) return
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    projects.forEach { project ->
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "${project.label} · ${periodMonthName(project.month)}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(formatCents(project.amountCents, project.currency), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * Planification de la période suivante (MAG-462): a row per category with the amount proposed — a monthly
 * envelope, or the year's for a year — editable, the one-off events to add, and « Appliquer le plan », which
 * writes every line or, one of them being refused, none.
 */
@Composable
fun PeriodPlanStep(viewModel: PeriodReviewViewModel, nextPeriod: PlanningPeriodRef) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(nextPeriod.key) { viewModel.loadPlan(nextPeriod.key) }

    val plan = state.plan
    if (plan == null) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            val error = state.planError
            if (error == null) {
                CircularProgressIndicator()
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(error, modifier = Modifier.testTag(UiTags.PERIOD_PLAN_ERROR))
                    Button(onClick = { viewModel.loadPlan(nextPeriod.key) }, modifier = Modifier.heightIn(min = 56.dp)) { Text("Réessayer") }
                }
            }
        }
        return
    }

    val yearly = plan.period.level == REVIEW_LEVEL_YEAR
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag(UiTags.PERIOD_PLAN),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Plan ${periodLabel(plan.period)} · " + if (yearly) "montant de l'année" else "montant par mois",
            style = MaterialTheme.typography.titleSmall,
        )
        if (state.rows.isEmpty()) {
            Text(
                "Aucune catégorie à planifier : rien n'a été dépensé sur la période écoulée.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.rows.forEach { category ->
            PlanRow(
                category = category,
                value = state.amountOf(category),
                set = category.envelopeCents != null && state.edits[category.categoryId] == null,
                refused = state.refusal?.line == category.categoryName,
                onChange = { viewModel.edit(category.categoryId, it) },
            )
        }
        state.oneOffs.forEachIndexed { index, oneOff ->
            Card(modifier = Modifier.fillMaxWidth().testTag(UiTags.PERIOD_PLAN_ONE_OFF_ROW)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${oneOff.name} · ${formatCents(amountCents(oneOff.amount))}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { viewModel.removeOneOff(index) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Retirer") }
                }
            }
        }
        Text("Une dépense ponctuelle", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        OutlinedTextField(
            value = state.draftName,
            onValueChange = viewModel::changeDraftName,
            label = { Text("Nom (Escapade, Cadeaux…)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(UiTags.PERIOD_PLAN_ONE_OFF_NAME),
        )
        OutlinedTextField(
            value = state.draftAmount,
            onValueChange = viewModel::changeDraftAmount,
            label = { Text("Montant (€)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth().testTag(UiTags.PERIOD_PLAN_ONE_OFF_AMOUNT),
        )
        OutlinedButton(
            onClick = viewModel::addOneOff,
            enabled = state.canAddOneOff,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(UiTags.PERIOD_PLAN_ONE_OFF_ADD),
        ) { Text("Ajouter") }

        state.refusal?.let { refusal ->
            Text(
                listOfNotNull(refusal.line?.let { "« $it » : " }, refusal.message).joinToString("") + " Rien n'a été écrit.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(UiTags.PERIOD_PLAN_REFUSED),
            )
        }
        if (state.applied) {
            Text(
                "Le plan est appliqué.",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.testTag(UiTags.PERIOD_PLAN_APPLIED),
            )
        }
        Button(
            onClick = viewModel::apply,
            enabled = !state.applying && state.lines.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().widthIn(min = 240.dp).heightIn(min = 56.dp).testTag(UiTags.PERIOD_PLAN_APPLY),
        ) { Text(if (state.applying) "Application…" else "Appliquer le plan") }
    }
}

@Composable
private fun PlanRow(category: PlanCategory, value: String, set: Boolean, refused: Boolean, onChange: (String) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().testTag(UiTags.periodPlanRow(category.categoryId))) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(category.categoryName, style = MaterialTheme.typography.titleSmall)
            Text(
                "Période écoulée : ${formatCents(category.lastYear.consumedCents, category.currency)}" +
                    if (set) " · déjà fixé" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = value,
                onValueChange = onChange,
                label = { Text("Montant (€)") },
                singleLine = true,
                isError = refused,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().testTag(UiTags.periodPlanAmount(category.categoryId)),
            )
        }
    }
}
