package com.maggie.app.ui.screens.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.EarlyRepayment
import com.maggie.app.data.model.Loan
import com.maggie.app.data.model.LoanLabels
import com.maggie.app.data.model.conditionRows
import com.maggie.app.data.model.formatCents
import com.maggie.app.data.model.formatRate
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.MaggieCard
import com.maggie.app.ui.layout.WindowWidth
import com.maggie.app.ui.layout.rememberAppLayout
import java.util.Locale

/**
 * The sheet of a loan (MAG-523). The phone reads it; the tablet, wider than 600 dp, also edits its
 * conditions and simulates an early repayment.
 */
@Composable
fun LoanSheetScreen(
    viewModel: LoanDetailViewModel,
    loanId: String,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(loanId) { if (viewModel.uiState.value.loanId != loanId) viewModel.load(loanId) }

    LoanSheetContent(
        state = state,
        editable = rememberAppLayout().width != WindowWidth.COMPACT,
        onEdit = viewModel::editDraft,
        onSave = viewModel::save,
        onSimulate = viewModel::simulate,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LoanSheetContent(
    state: LoanDetailUiState,
    editable: Boolean,
    onEdit: (LoanTermsDraft) -> Unit,
    onSave: () -> Unit,
    onSimulate: (Int?) -> Unit,
    onBack: () -> Unit,
) {
    val loan = state.loan

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(loan?.name ?: "Prêt") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
        modifier = Modifier.testTag(UiTags.LOAN_SHEET),
    ) { paddingValues ->
        Box(
            modifier = Modifier.fillMaxSize().padding(paddingValues).verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter,
        ) {
            when {
                loan != null -> Column(
                    modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    LoanSummary(loan, state)
                    LoanConditionsCard(loan)
                    LoanIraCard(loan, state)
                    if (editable) {
                        LoanEditCard(state, onEdit, onSave)
                        LoanSimulationCard(loan, state, onSimulate)
                    }
                }
                state.error != null -> Text(
                    state.error,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )
                else -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@Composable
private fun SheetCard(title: String, content: @Composable () -> Unit) {
    MaggieCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
private fun LoanSummary(loan: Loan, state: LoanDetailUiState) {
    SheetCard(LoanLabels.type(loan.type)) {
        if (loan.projected) {
            AssistChip(onClick = {}, label = { Text("Projeté") }, modifier = Modifier.testTag(UiTags.LOAN_PROJECTED))
        }
        Text(
            text = "${formatCents(loan.principalRemainingCents, loan.currency)} restants · " +
                "${formatCents(loan.monthlyPaymentCents, loan.currency)}/mois · ${formatRate(loan.annualRateBasisPoints)}",
            style = MaterialTheme.typography.bodyMedium,
        )
        state.curve?.let { curve ->
            LoanCurveChart(curve.months, curve.freedOn, loan.currency)
            Text(
                text = "Intérêts restants : ${formatCents(curve.totalInterestCents, loan.currency)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LoanConditionsCard(loan: Loan) {
    SheetCard("Conditions") {
        loan.conditionRows().forEach { (label, value) ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 16.dp))
            }
        }
    }
}

@Composable
private fun LoanIraCard(loan: Loan, state: LoanDetailUiState) {
    SheetCard("Remboursement anticipé") {
        Column(modifier = Modifier.testTag(UiTags.LOAN_IRA), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(iraModeLabel(loan), style = MaterialTheme.typography.bodyMedium)
            state.total?.let { LoanIraBar(it, loan.currency) }
            state.totalError?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** The mode of the IRA, with the amount the offer states when it is fixed. */
internal fun iraModeLabel(loan: Loan): String {
    val label = LoanLabels.penaltyMode(loan.prepaymentPenaltyMode)
    val fixed = loan.prepaymentPenaltyFixedCents
    return if (loan.prepaymentPenaltyMode == LoanLabels.FIXED && fixed != null) {
        "$label · ${formatCents(fixed, loan.currency)}"
    } else {
        label
    }
}

@Composable
private fun LoanEditCard(state: LoanDetailUiState, onEdit: (LoanTermsDraft) -> Unit, onSave: () -> Unit) {
    val draft = state.draft ?: return

    SheetCard("Modifier les conditions") {
        LoanTermsForm(
            draft = draft,
            onChange = onEdit,
            endLabel = state.curve?.let { loanEndLabel(it.freedOn) },
        )
        state.saveError?.let { refusal ->
            MaggieCard(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth().testTag(UiTags.LOAN_SAVE_ERROR),
            ) {
                Text(refusal, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(16.dp))
            }
        }
        if (state.saved) Text("Enregistré", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        Button(
            onClick = onSave,
            enabled = !state.isSaving && draft.toRequest() != null,
            modifier = Modifier.fillMaxWidth().testTag(UiTags.LOAN_SAVE),
        ) { Text("Enregistrer") }
    }
}

@Composable
private fun LoanSimulationCard(loan: Loan, state: LoanDetailUiState, onSimulate: (Int?) -> Unit) {
    var amount by rememberSaveable(loan.id, loan.principalRemainingCents) {
        mutableStateOf(String.format(Locale.FRANCE, "%.2f", loan.principalRemainingCents / 100.0))
    }

    SheetCard("Simulation") {
        OutlinedTextField(
            value = amount,
            onValueChange = { amount = it },
            label = { Text("Montant remboursé") },
            suffix = { Text("€") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth().testTag(UiTags.LOAN_SIMULATION_AMOUNT),
        )
        OutlinedButton(
            onClick = { onSimulate(parseCents(amount)) },
            enabled = !state.isSimulating && (amount.isBlank() || parseCents(amount) != null),
            modifier = Modifier.fillMaxWidth().testTag(UiTags.LOAN_SIMULATE),
        ) { Text("Simuler") }
        state.simulation?.let { LoanSimulationResult(it, loan.currency) }
        state.simulationError?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(UiTags.LOAN_SIMULATION_ERROR),
            )
        }
    }
}

@Composable
private fun LoanSimulationResult(result: EarlyRepayment, currency: String) {
    Column(modifier = Modifier.testTag(UiTags.LOAN_SIMULATION), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "${formatCents(result.amountCents, currency)} · ${LoanLabels.penaltyMode(result.penaltyMode)}",
            style = MaterialTheme.typography.bodyMedium,
        )
        LoanIraBar(result, currency)
    }
}
