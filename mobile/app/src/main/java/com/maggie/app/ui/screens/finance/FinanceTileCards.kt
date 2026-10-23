package com.maggie.app.ui.screens.finance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.AccountsTile
import com.maggie.app.data.model.BudgetTile
import com.maggie.app.data.model.FinanceTodo
import com.maggie.app.data.model.accountWithRejections
import com.maggie.app.data.model.advancePillText
import com.maggie.app.data.model.rejectionsPillText
import com.maggie.app.data.model.toSortPillText
import com.maggie.app.data.model.PlanningTile
import com.maggie.app.data.model.ProjectPlanning
import com.maggie.app.data.model.TileAccount
import com.maggie.app.data.model.TileEnvelope
import com.maggie.app.data.model.accountLabel
import com.maggie.app.data.model.consumedFraction
import com.maggie.app.data.model.cushionStateLabel
import com.maggie.app.data.model.formatCents
import com.maggie.app.data.model.owedPillText
import com.maggie.app.data.model.independenceSummary
import com.maggie.app.data.model.referenceSalarySourceLabel
import com.maggie.app.data.model.treasuryDate
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.navigation.DeepLinks
import com.maggie.app.ui.navigation.Screen

/** The envelopes drawn as bars; the tile leads to the budget for the rest. */
internal const val MAX_TILE_ENVELOPES = 6

/** A tile: a card with its title and a chevron, leading to the menu it summarises. */
@Composable
private fun FinanceTile(
    title: String,
    tag: String,
    onClick: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Icon(Icons.Default.ChevronRight, contentDescription = null)
            }
            content()
        }
    }
}

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = color, modifier = modifier)
}

/** A pill of a tile: its own tap target. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TodoPill(label: String, tag: String, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        modifier = Modifier.testTag(tag),
    )
}

/**
 * Comptes: the total, how much of it is the cushion, and each account on its line, then what is to do.
 * A pill that has nothing to say is not drawn, and the row not either.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AccountsTileCard(
    tile: AccountsTile,
    todo: FinanceTodo,
    onOpen: (String) -> Unit,
    onOpenIncidents: (TileAccount) -> Unit,
) {
    FinanceTile("Comptes", UiTags.FINANCE_TILE_ACCOUNTS, onClick = { onOpen(Screen.AccountList.route) }) {
        Text(
            text = formatCents(tile.totalCents),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        Hint("dont ${formatCents(tile.cushionCents)} de matelas — ${formatCents(tile.availableCents)} disponibles")
        tile.accounts.forEach { account ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = accountLabel(account.name, account.bank, account.currency).let { if (account.isCushion) "$it · matelas" else it },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatCents(account.balanceCents, account.currency),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        val toSort = toSortPillText(todo.toSortCount)
        val rejections = rejectionsPillText(todo.rejectionsToSettleCount)
        val rejectionsAccount = tile.accountWithRejections()
        val owed = owedPillText(todo.owedToMeCents, todo.iOweCents)
        if (toSort != null || rejections != null || owed != null) {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (toSort != null) {
                    TodoPill(toSort, UiTags.FINANCE_PILL_TO_SORT) { onOpen(DeepLinks.REVIEW_TO_SORT_ROUTE) }
                }
                if (rejections != null) {
                    TodoPill(rejections, UiTags.FINANCE_PILL_REJECTIONS) {
                        rejectionsAccount?.let(onOpenIncidents) ?: onOpen(Screen.AccountList.route)
                    }
                }
                if (owed != null) {
                    TodoPill(owed, UiTags.FINANCE_PILL_OWED) { onOpen(Screen.PeerDebts.route) }
                }
            }
        }
    }
}

/** Budget: one bar per envelope, the cushion, the salary expected and the balance expected before it. */
@Composable
internal fun BudgetTileCard(tile: BudgetTile, todo: FinanceTodo, onOpen: (String) -> Unit) {
    FinanceTile("Budget", UiTags.FINANCE_TILE_BUDGET, onClick = { onOpen(Screen.BudgetList.route) }) {
        advancePillText(todo.advanceToRepayCents)?.let { label ->
            TodoPill(label, UiTags.FINANCE_PILL_ADVANCE) { onOpen(Screen.SavingsAdvances.route) }
        }
        EnvelopeBars(tile.envelopes)
        CushionLine(tile)

        val salary = tile.expectedSalary
        Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag(UiTags.FINANCE_TILE_EXPECTED_SALARY)) {
            Text("Salaire attendu", style = MaterialTheme.typography.labelMedium)
            if (salary != null) {
                Text(
                    text = formatCents(salary.monthlyCents) +
                        (salary.expectedOn?.let { " · le ${treasuryDate(it)}" } ?: ""),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Hint(referenceSalarySourceLabel(salary.source))
            } else {
                // Never a 0 € salary: unknown is said, with the way to fill it in.
                Text("salaire non déclaré", style = MaterialTheme.typography.bodyLarge)
                TextButton(
                    onClick = { onOpen(Screen.Cushion.route) },
                    modifier = Modifier.testTag(UiTags.REFERENCE_SALARY_LINK),
                ) {
                    Text("Déclarer mon salaire net dans le matelas")
                }
            }
        }

        val before = tile.balanceBeforeSalary
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag(UiTags.FINANCE_TILE_BALANCE_BEFORE_SALARY),
        ) {
            Text("Solde attendu avant le salaire", style = MaterialTheme.typography.labelMedium)
            if (before != null) {
                Text(
                    text = "${formatCents(before.balanceCents)} · le ${treasuryDate(before.onDate)}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (before.balanceCents < 0) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            } else {
                Text("solde attendu inconnu", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun EnvelopeBars(envelopes: List<TileEnvelope>) {
    if (envelopes.isEmpty()) {
        Hint("Aucune enveloppe sur ce mois.", modifier = Modifier.padding(top = 4.dp))
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        envelopes.take(MAX_TILE_ENVELOPES).forEach { envelope ->
            val color = when {
                envelope.isOverspent -> MaterialTheme.colorScheme.error
                envelope.isOvercommitted -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.primary
            }
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .testTag(UiTags.financeTileEnvelope(envelope.envelopeId))
                        .semantics {
                            contentDescription = "${envelope.name} : ${formatCents(envelope.consumedCents)} " +
                                "sur ${formatCents(envelope.budgetCents)}"
                        },
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(consumedFraction(envelope.consumedCents, envelope.budgetCents).coerceIn(0f, 1f))
                            .background(color),
                    )
                }
                Text(
                    text = envelope.name,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
    if (envelopes.size > MAX_TILE_ENVELOPES) {
        Hint("+ ${envelopes.size - MAX_TILE_ENVELOPES} autres enveloppes", modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun CushionLine(tile: BudgetTile) {
    val cushion = tile.cushion
    if (!cushion.isConfigured) {
        Hint("Matelas non configuré", modifier = Modifier.padding(top = 12.dp))
        return
    }

    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(
            text = "Matelas : ${cushionStateLabel(cushion.state).lowercase()} · ${cushion.coveragePercent} %",
            style = MaterialTheme.typography.bodyMedium,
        )
        LinearProgressIndicator(
            progress = { (cushion.coveragePercent / 100f).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp).padding(top = 4.dp),
        )
        Hint(
            "${formatCents(cushion.currentCents)} sur ${formatCents(cushion.targetCents)}",
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * Planification: how far the rentes cover the way the owner lives (MAG-46), then the saving capacity.
 *
 * The independence line says what it compares, never a bare percentage: a ratio whose terms are
 * hidden cannot be checked. The date N, the curve and the dated milestones (MAG-549) sit under it,
 * stated plainly: « non atteinte d'ici 20 ans » is a fact of the projection, not a verdict. The same
 * card fills the right pane on a tablet.
 */
@Composable
internal fun PlanningTileCard(tile: PlanningTile, planning: ProjectPlanning?, onOpen: (String) -> Unit) {
    FinanceTile("Planification", UiTags.FINANCE_TILE_PLANNING, onClick = { onOpen(Screen.Treasury.route) }) {
        IndependenceBody(tile, planning)
    }
}

/**
 * The independence counter and the net saving capacity: the planning tile's content, and the « Indépendance »
 * sub-view's. [planning] draws the first three projects (MAG-529) when it is read.
 */
@Composable
internal fun ColumnScope.IndependenceBody(tile: PlanningTile, planning: ProjectPlanning? = null) {
    val counter = tile.independence
    val capacity = tile.savingCapacity

    Text("Indépendance financière", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
    Text(
        text = independenceSummary(counter),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(vertical = 4.dp),
    )

    // The first three projects, one line each (MAG-529); nothing while the planning is not read or draws nothing.
    planning?.let { ProjectGantt(it, modifier = Modifier.padding(vertical = 8.dp), compact = true) }

    if (!counter.isMeasurable || !counter.hasPassiveIncomeCategories) {
        // Two different states, two different things to say.
        Hint(
            if (counter.hasPassiveIncomeCategories) {
                "Le compteur se calcule sur les ${counter.sampleMonths} mois complets précédents."
            } else {
                "Une rente se déclare sur une catégorie de recette — loyers perçus, " +
                    "dividendes — en créant la catégorie dans Finance › Catégories."
            },
        )
        // Placements and investments exist whether or not a train de vie is measured.
        IndependenceRenteSources(counter.renteSources, modifier = Modifier.padding(top = 10.dp))
    } else {
        LinearProgressIndicator(
            progress = { minOf(1f, counter.coveragePercent / 100f) },
            color = if (counter.isReached) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.fillMaxWidth().height(6.dp).padding(top = 4.dp),
        )
        Hint(
            "${formatCents(counter.passiveIncomeCents)} de rentes sur " +
                "${formatCents(counter.lifestyleCents)} de train de vie, mesurés sur " +
                "${counter.sampleMonths} mois",
            modifier = Modifier.padding(top = 6.dp),
        )
        IndependenceProjectionBlock(counter, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
        counter.byCategory.forEach { rente ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(rente.categoryName, style = MaterialTheme.typography.bodySmall)
                Text(
                    text = "${formatCents(rente.monthlyCents)} / mois · ${rente.sharePercent} %",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    Text(
        "Capacité d'épargne nette",
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(top = 12.dp),
    )
    if (capacity.isIncomeKnown) {
        Text(
            text = "${formatCents(capacity.netCapacityCents)} / mois",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        Hint(
            "${formatCents(capacity.monthlyNetIncomeCents)} de revenu − " +
                "${formatCents(capacity.loanPaymentsCents)} de mensualités − " +
                "${formatCents(capacity.estimatedLifestyleCents)} de train de vie",
        )
    } else {
        Hint("Renseignez votre revenu dans le matelas pour l'obtenir.", modifier = Modifier.padding(top = 4.dp))
    }
}
