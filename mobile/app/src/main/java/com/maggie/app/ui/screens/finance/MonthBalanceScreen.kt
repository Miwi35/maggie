package com.maggie.app.ui.screens.finance

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.BalanceEnvelope
import com.maggie.app.data.model.BalanceSatisfaction
import com.maggie.app.data.model.BalanceSavings
import com.maggie.app.data.model.MonthBalance
import com.maggie.app.data.model.TileCushion
import com.maggie.app.data.model.envelopeStatusWord
import com.maggie.app.data.model.formatCents
import com.maggie.app.data.model.monthLabel
import com.maggie.app.data.model.toQualifyLabel
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.ErrorSnackbar
import com.maggie.app.ui.components.MaggieCard
import com.maggie.app.ui.components.MonthScoreRing
import com.maggie.app.ui.components.RhythmCurve
import com.maggie.app.ui.components.statusColor
import com.maggie.app.ui.navigation.DeepLinks
import com.maggie.app.ui.theme.maggieSurface

private const val CLOSE = "close"
private const val OVER = "over"

/** The bar of an envelope follows the score palette: held is green, close orange, over red. */
internal fun envelopeScoreStatus(status: String): String = when (status) {
    OVER -> "red"
    CLOSE -> "orange"
    else -> "green"
}

/**
 * Dashboard › État courant (MAG-457), a tab of the finance dashboard: the balance of a month in a
 * hero figure — the month ring and its curve — then one card each for the envelopes, the
 * satisfaction, what was put aside and the cushion. The lines nobody qualified are reported apart,
 * above, as a pill.
 */
@Composable
fun MonthBalanceTab(
    viewModel: MonthBalanceViewModel,
    onOpen: (String) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val balance = uiState.balance

    ErrorSnackbar(
        error = uiState.error,
        snackbarHostState = snackbarHostState,
        onDismiss = viewModel::clearError,
        onRetry = viewModel::refresh,
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { viewModel.shiftPeriod(-1) }) {
                    Icon(Icons.Default.ChevronLeft, contentDescription = "Mois précédent")
                }
                Text(
                    text = "${monthLabel(uiState.month)} ${uiState.year}",
                    style = MaterialTheme.typography.titleMedium,
                )
                IconButton(onClick = { viewModel.shiftPeriod(1) }) {
                    Icon(Icons.Default.ChevronRight, contentDescription = "Mois suivant")
                }
            }

            if (balance == null) {
                Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    if (uiState.isLoading) {
                        CircularProgressIndicator()
                    } else {
                        Text(
                            uiState.error ?: "Bilan indisponible",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag(UiTags.MONTH_BALANCE_EMPTY),
                        )
                    }
                }
            } else {
                MonthBalanceContent(balance, onOpenToQualify = { onOpen(DeepLinks.REVIEW_TO_SORT_ROUTE) })
            }
        }
        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
internal fun MonthBalanceContent(balance: MonthBalance, onOpenToQualify: () -> Unit) {
    // A handful of cards: a plain scrolling column, every one of them composed.
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MonthBalanceCards(balance, onOpenToQualify)
    }
}

@Composable
private fun ColumnScope.MonthBalanceCards(balance: MonthBalance, onOpenToQualify: () -> Unit) {
    toQualifyLabel(balance.toQualify)?.let { label ->
        ToQualifyPill(label, onClick = onOpenToQualify)
    }
    MaggieCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            MonthScoreRing(balance.spending, onClassify = onOpenToQualify)
            RhythmCurve(rhythm = balance.spending.rhythm, title = "Mois")
        }
    }
    EnvelopesCard(balance.envelopes)
    SatisfactionCard(balance.satisfaction)
    SavingsCard(balance.savings)
    CushionCard(balance.cushion)
}

/**
 * The month's balance (MAG-457) as the lower part of « État courant » (MAG-537): the same cards as
 * [MonthBalanceTab], in the dashboard's own scroll instead of a scroll of their own. The dashboard's month header drives the
 * month; errors go to the dashboard's snackbar.
 */
@Composable
fun MonthBalanceSection(
    viewModel: MonthBalanceViewModel,
    onOpen: (String) -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    val uiState by viewModel.uiState.collectAsState()
    val balance = uiState.balance

    ErrorSnackbar(
        error = uiState.error,
        snackbarHostState = snackbarHostState,
        onDismiss = viewModel::clearError,
        onRetry = viewModel::refresh,
    )

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Bilan de ${monthLabel(uiState.month).lowercase()} ${uiState.year}",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (balance == null) {
            Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                if (uiState.isLoading) {
                    CircularProgressIndicator()
                } else {
                    Text(
                        uiState.error ?: "Bilan indisponible",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag(UiTags.MONTH_BALANCE_EMPTY),
                    )
                }
            }
        } else {
            MonthBalanceCards(balance, onOpenToQualify = { onOpen(DeepLinks.REVIEW_TO_SORT_ROUTE) })
        }
    }
}

@Composable
internal fun BalanceCard(title: String, tag: String, content: @Composable () -> Unit) {
    MaggieCard(modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
private fun EnvelopesCard(envelopes: List<BalanceEnvelope>) {
    BalanceCard("Enveloppes", UiTags.MONTH_BALANCE_ENVELOPES) {
        if (envelopes.isEmpty()) {
            Text(
                "Aucune enveloppe ce mois-ci.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(UiTags.MONTH_BALANCE_ENVELOPES_EMPTY),
            )
        }
        envelopes.forEach { envelope -> EnvelopeBar(envelope) }
    }
}

@Composable
private fun EnvelopeBar(envelope: BalanceEnvelope) {
    val color = statusColor(envelopeScoreStatus(envelope.status))
    val fill = when {
        envelope.budgetCents > 0 -> (envelope.consumedCents.toFloat() / envelope.budgetCents).coerceIn(0f, 1f)
        envelope.consumedCents > 0 -> 1f
        else -> 0f
    }
    val word = envelopeStatusWord(envelope.status)
    val amounts = "${formatCents(envelope.consumedCents)} sur ${formatCents(envelope.budgetCents)}" +
        (envelope.percent?.let { " · $it %" } ?: "")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(UiTags.monthBalanceEnvelope(envelope.envelopeId))
            .semantics(mergeDescendants = true) { contentDescription = "${envelope.name} : $word, $amounts" },
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(envelope.name, style = MaterialTheme.typography.bodyMedium)
            Text(word, style = MaterialTheme.typography.labelLarge, color = color)
        }
        LinearProgressIndicator(
            progress = { fill },
            color = color,
            trackColor = maggieSurface().track,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).height(8.dp),
        )
        Text(amounts, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun SatisfactionCard(satisfaction: BalanceSatisfaction) {
    BalanceCard("Satisfaction", UiTags.MONTH_BALANCE_SATISFACTION) {
        val kept = satisfaction.keptPercent
        val track = maggieSurface().track
        val color = statusColor("green")

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(modifier = Modifier.size(SATISFACTION_RING), contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.size(SATISFACTION_RING)) {
                    val stroke = SATISFACTION_STROKE.toPx()
                    val topLeft = Offset(stroke / 2, stroke / 2)
                    val arc = Size(size.width - stroke, size.height - stroke)
                    drawArc(track, -90f, 360f, false, topLeft, arc, style = Stroke(stroke))
                    if (kept != null && kept > 0) {
                        drawArc(color, -90f, 360f * kept / 100f, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Butt))
                    }
                }
                // Nothing judged yet: an empty ring that says so, never a 0 %.
                if (kept == null) {
                    Text(
                        "Rien de jugé",
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(12.dp).testTag(UiTags.MONTH_BALANCE_SATISFACTION_EMPTY),
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("$kept %", style = MaterialTheme.typography.titleMedium)
                        Text("à conserver", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Column {
                Text("Superflu : ${formatCents(satisfaction.avoidableCents)}", style = MaterialTheme.typography.bodyMedium)
                if (satisfaction.unratedCount > 0) {
                    Text(
                        "${satisfaction.unratedCount} à juger",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
internal fun SavingsCard(savings: BalanceSavings) {
    BalanceCard("Épargne", UiTags.MONTH_BALANCE_SAVINGS) {
        val planned = savings.plannedCents
        val fill = if (planned != null && planned > 0) (savings.savedCents.toFloat() / planned).coerceIn(0f, 1f) else 0f
        LinearProgressIndicator(progress = { fill }, modifier = Modifier.fillMaxWidth().height(8.dp))
        Text(
            text = if (planned == null) {
                "${formatCents(savings.savedCents)} épargnés · capacité inconnue"
            } else {
                "${formatCents(savings.savedCents)} épargnés sur ${formatCents(planned)} prévus"
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun CushionCard(cushion: TileCushion) {
    BalanceCard("Matelas", UiTags.MONTH_BALANCE_CUSHION) {
        if (!cushion.isConfigured) {
            Text("Matelas non configuré.", style = MaterialTheme.typography.bodyMedium)
            return@BalanceCard
        }
        LinearProgressIndicator(
            progress = { (cushion.coveragePercent / 100f).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(8.dp),
        )
        Text(
            "${formatCents(cushion.currentCents)} sur ${formatCents(cushion.targetCents)} · ${cushion.coveragePercent} %",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private val SATISFACTION_RING = 96.dp
private val SATISFACTION_STROKE = 10.dp
