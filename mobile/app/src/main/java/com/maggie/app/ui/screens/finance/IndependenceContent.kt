package com.maggie.app.ui.screens.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.IndependenceCounter
import com.maggie.app.data.model.IndependenceRente
import com.maggie.app.data.model.formatCents
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.EmptyState

/**
 * How far the rentes cover the way the owner lives (MAG-46), as the body of the Planification tab
 * (MAG-518): the same wording as the admin's summary, with both terms of the ratio shown.
 *
 * No target date and no curve (Premium), and no percentage when there is nothing to measure:
 * 0 % would be a verdict on somebody who has simply not started.
 */
@Composable
fun IndependenceContent(
    viewModel: IndependenceViewModel,
    onDeclareRente: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()

    IndependenceBody(uiState, onDeclareRente, onRetry = viewModel::refresh, modifier = modifier)
}

@Composable
internal fun IndependenceBody(
    uiState: IndependenceUiState,
    onDeclareRente: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val counter = uiState.counter

    Box(modifier = modifier.fillMaxSize().testTag(UiTags.INDEPENDENCE)) {
        when {
            counter == null && uiState.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            counter == null -> Column(
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    uiState.error ?: "Indépendance financière indisponible",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onRetry, modifier = Modifier.testTag(UiTags.INDEPENDENCE_RETRY)) {
                    Text("Réessayer")
                }
            }
            !counter.hasPassiveIncomeCategories -> EmptyState(
                title = "Aucune rente déclarée",
                description = "Cochez « Rente » sur une catégorie de recette — loyers perçus, dividendes, " +
                    "intérêts — pour suivre ce qu'elles couvrent de votre train de vie.",
                actionLabel = "Déclarer une rente",
                onAction = onDeclareRente,
            )
            !counter.isMeasurable -> EmptyState(
                title = "Rien à mesurer pour l'instant",
                description = "Pas encore de train de vie mesuré : il se calcule sur les " +
                    "${counter.sampleMonths} mois complets précédents.",
            )
            else -> MeasuredIndependence(counter)
        }
    }
}

@Composable
private fun MeasuredIndependence(counter: IndependenceCounter) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Indépendance financière", style = MaterialTheme.typography.titleMedium)

                Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 8.dp)) {
                    Text(
                        "${counter.coveragePercent} %",
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.testTag(UiTags.INDEPENDENCE_PERCENT),
                    )
                    Text(
                        " du train de vie",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                LinearProgressIndicator(
                    progress = { counter.coveragePercent.coerceIn(0, 100) / 100f },
                    color = if (counter.isReached) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.fillMaxWidth().height(8.dp).testTag(UiTags.INDEPENDENCE_PROGRESS),
                )

                Text(
                    "${formatCents(counter.passiveIncomeCents)} de rentes sur " +
                        "${formatCents(counter.lifestyleCents)} de train de vie, mesurés sur ${counter.sampleMonths} mois",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )

                Text(
                    text = gapLine(counter),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (counter.isReached) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 4.dp).testTag(UiTags.INDEPENDENCE_GAP),
                )
            }
        }

        counter.byCategory.forEach { RenteRow(it) }
    }
}

internal fun gapLine(counter: IndependenceCounter): String {
    if (counter.isReached) return "Les rentes couvrent le train de vie."

    val milestone = counter.nextMilestonePercent?.let {
        " — prochain palier $it %, à ${formatCents(counter.nextMilestoneGapCents ?: 0)} près"
    }.orEmpty()

    return "Il manque ${formatCents(counter.gapCents)} par mois$milestone"
}

@Composable
private fun RenteRow(rente: IndependenceRente) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp).testTag(UiTags.independenceRente(rente.categoryId)),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(rente.categoryName, style = MaterialTheme.typography.bodyMedium)
        Row {
            Text("${formatCents(rente.monthlyCents)} / mois", style = MaterialTheme.typography.bodyMedium)
            Text(
                "  ${rente.sharePercent} %",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
