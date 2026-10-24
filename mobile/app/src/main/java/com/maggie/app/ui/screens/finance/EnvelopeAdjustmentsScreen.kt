package com.maggie.app.ui.screens.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.AdjustmentSuggestion
import com.maggie.app.data.model.EnvelopeAdjustment
import com.maggie.app.data.model.formatCents
import com.maggie.app.data.model.label
import com.maggie.app.data.model.monthLabel
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.ErrorSnackbar
import com.maggie.app.ui.components.MaggieCard
import java.time.YearMonth
import kotlin.math.max
import kotlin.math.roundToInt

/** « Ajuster » as a screen of its own: the Budgets top bar's entry opens it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnvelopeAdjustmentsScreen(
    viewModel: EnvelopeAdjustmentsViewModel,
    onBack: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ajuster les enveloppes") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        EnvelopeAdjustmentsContent(
            viewModel = viewModel,
            snackbarHostState = snackbarHostState,
            modifier = Modifier.padding(paddingValues),
        )
    }
}

/**
 * The adjustment itself, with no bar of its own so that it can sit in a screen or be one step of
 * the monthly review (« Enveloppes »). The caller owns [snackbarHostState]: errors and the
 * confirmation of a write go there.
 */
@Composable
fun EnvelopeAdjustmentsContent(
    viewModel: EnvelopeAdjustmentsViewModel,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val adjustments = uiState.adjustments

    ErrorSnackbar(
        error = uiState.error,
        snackbarHostState = snackbarHostState,
        onDismiss = viewModel::clearError,
        onRetry = viewModel::refresh,
        message = "Impossible d'ajuster les enveloppes. Rien n'a été modifié.",
    )
    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Column(modifier = modifier.fillMaxSize().testTag(UiTags.BUDGET_ADJUST)) {
        PeriodSelector(
            year = uiState.month.year,
            month = uiState.month.monthValue,
            onPrevious = { viewModel.shiftMonth(-1) },
            onNext = { viewModel.shiftMonth(1) },
        )
        adjustments?.let {
            Text(
                text = "Le réel de ce mois, les montants de ${monthName(it.targetMonth)}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        when {
            adjustments == null && uiState.isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            adjustments == null -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    TextButton(onClick = viewModel::refresh) { Text("Réessayer") }
                }
            }
            adjustments.envelopes.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Aucune enveloppe mensuelle sur ce mois.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> {
                AdjustmentActions(
                    suggestionCount = uiState.pendingSuggestions.size,
                    draftCount = uiState.draft.size,
                    isSaving = uiState.isSaving,
                    onApplyAll = viewModel::applyAll,
                    onSave = viewModel::save,
                )
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(adjustments.envelopes, key = { it.categoryId }) { line ->
                        AdjustmentCard(
                            line = line,
                            monthName = monthName(uiState.month.toString()).lowercase(),
                            targetName = monthName(adjustments.targetMonth),
                            amountCents = uiState.amountFor(line),
                            edited = line.categoryId in uiState.draft,
                            enabled = !uiState.isSaving,
                            onStep = { viewModel.step(line.categoryId, it) },
                            onSlide = { viewModel.setAmount(line.categoryId, it) },
                            onReset = { viewModel.resetDraft(line.categoryId) },
                            onApply = { viewModel.apply(line.categoryId) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AdjustmentActions(
    suggestionCount: Int,
    draftCount: Int,
    isSaving: Boolean,
    onApplyAll: () -> Unit,
    onSave: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = onApplyAll,
            enabled = suggestionCount > 0 && !isSaving,
            modifier = Modifier.testTag(UiTags.BUDGET_ADJUST_APPLY_ALL),
        ) {
            Text(if (suggestionCount > 0) "Tout appliquer ($suggestionCount)" else "Tout appliquer")
        }
        Button(
            onClick = onSave,
            enabled = draftCount > 0 && !isSaving,
            modifier = Modifier.testTag(UiTags.BUDGET_ADJUST_SAVE),
        ) {
            Text(if (draftCount > 0) "Enregistrer ($draftCount)" else "Enregistrer")
        }
    }
}

@Composable
private fun AdjustmentCard(
    line: EnvelopeAdjustment,
    monthName: String,
    targetName: String,
    amountCents: Int,
    edited: Boolean,
    enabled: Boolean,
    onStep: (Int) -> Unit,
    onSlide: (Int) -> Unit,
    onReset: () -> Unit,
    onApply: () -> Unit,
) {
    val name = line.categoryName.ifEmpty { line.categoryPath }
    MaggieCard(modifier = Modifier.fillMaxWidth().testTag(UiTags.budgetAdjustRow(name))) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(line.categoryPath.ifEmpty { name }, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "Réel de $monthName : ${formatCents(line.actualCents, line.currency)} " +
                    "sur ${formatCents(line.amountCents, line.currency)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { onStep(-ADJUSTMENT_STEP_CENTS) },
                    enabled = enabled && amountCents > 0,
                    modifier = Modifier.testTag(UiTags.BUDGET_ADJUST_MINUS_PREFIX + name),
                ) {
                    Icon(Icons.Default.Remove, contentDescription = "Moins, $name")
                }
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "$targetName : ${formatCents(amountCents, line.currency)}",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (edited) {
                        Text(
                            text = "Modifié, pas enregistré",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                IconButton(
                    onClick = { onStep(ADJUSTMENT_STEP_CENTS) },
                    enabled = enabled,
                    modifier = Modifier.testTag(UiTags.BUDGET_ADJUST_PLUS_PREFIX + name),
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Plus, $name")
                }
            }

            // Wide enough for the amount, the actual and the proposal to sit on it.
            val ceiling = max(max(line.actualCents, line.targetAmountCents), line.suggestion?.proposedCents ?: 0)
                .let { max(it * 2, 10 * ADJUSTMENT_STEP_CENTS) }
                .toFloat()
            Slider(
                value = amountCents.coerceAtMost(ceiling.toInt()).toFloat(),
                onValueChange = { onSlide((it / ADJUSTMENT_STEP_CENTS).roundToInt() * ADJUSTMENT_STEP_CENTS) },
                valueRange = 0f..ceiling,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().testTag(UiTags.BUDGET_ADJUST_SLIDER_PREFIX + name),
            )
            if (edited) {
                TextButton(onClick = onReset, enabled = enabled) {
                    Text("Revenir à ${formatCents(line.targetAmountCents, line.currency)}")
                }
            }

            line.suggestion?.let { suggestion ->
                SuggestionBlock(
                    name = name,
                    suggestion = suggestion,
                    currency = line.currency,
                    enabled = enabled,
                    onApply = onApply,
                )
            }
        }
    }
}

/** Only an envelope that does not fit has one: what is proposed, why, and the season if it is one. */
@Composable
private fun SuggestionBlock(
    name: String,
    suggestion: AdjustmentSuggestion,
    currency: String,
    enabled: Boolean,
    onApply: () -> Unit,
) {
    MaggieCard(
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag(UiTags.BUDGET_ADJUST_SUGGESTION_PREFIX + name),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = (if (suggestion.direction == AdjustmentSuggestion.LOWER) "Baisser à " else "Passer à ") +
                    formatCents(suggestion.proposedCents, currency),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(suggestion.reason, style = MaterialTheme.typography.bodySmall)
            suggestion.seasonal?.let { season ->
                Text(
                    text = "Saison : ${season.label()}",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            TextButton(
                onClick = onApply,
                enabled = enabled,
                modifier = Modifier.testTag(UiTags.budgetAdjustApply(name)),
            ) {
                Text("Appliquer")
            }
        }
    }
}

/** « 2026-07 » → « Juillet 2026 »; the text as it came when it is not a month. */
internal fun monthName(yearMonth: String): String =
    runCatching { YearMonth.parse(yearMonth) }.getOrNull()
        ?.let { "${monthLabel(it.monthValue)} ${it.year}" }
        ?: yearMonth
