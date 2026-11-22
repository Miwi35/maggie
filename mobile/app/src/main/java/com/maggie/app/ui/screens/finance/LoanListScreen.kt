package com.maggie.app.ui.screens.finance

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.maggie.app.data.api.LoanCreateRequest
import com.maggie.app.data.model.DebtTimeline
import com.maggie.app.data.model.Loan
import com.maggie.app.data.model.LoanLabels
import com.maggie.app.data.model.LoanSchedule
import com.maggie.app.data.model.SavingCapacity
import com.maggie.app.data.model.formatCentsRounded
import com.maggie.app.data.model.formatLoanMonth
import com.maggie.app.data.model.formatRate
import com.maggie.app.ui.components.ConfirmDeleteDialog
import com.maggie.app.ui.components.EmptyState
import com.maggie.app.ui.components.ErrorSnackbar
import com.maggie.app.ui.components.FinanceTopBar
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.FabModule
import com.maggie.app.ui.components.ModuleFab
import com.maggie.app.ui.components.MaggieCard
import com.maggie.app.ui.uiTagRoot
import com.maggie.app.ui.navigation.Screen
import com.maggie.app.ui.theme.MaggieTokens
import java.time.YearMonth
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoanListScreen(
    viewModel: LoanViewModel,
    onOpenLoan: (String) -> Unit,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showCreateDialog by remember { mutableStateOf(false) }
    var deletingLoan by remember { mutableStateOf<Loan?>(null) }

    ErrorSnackbar(
        error = uiState.error,
        snackbarHostState = snackbarHostState,
        onDismiss = viewModel::clearError,
        onRetry = viewModel::refresh,
    )

    Scaffold(
        topBar = {
            FinanceTopBar(Screen.LoanList.route)
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ModuleFab(FabModule.FINANCE, "Nouveau prêt", onClick = { showCreateDialog = true })
        },
    ) { paddingValues ->
        when {
            uiState.isLoading && uiState.loans.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            uiState.loans.isEmpty() -> {
                EmptyState(
                    modifier = Modifier.padding(paddingValues),
                    title = "Aucun prêt enregistré",
                    description = "Renseignez vos crédits en cours pour savoir quand chaque mensualité se libère.",
                    actionLabel = "Ajouter un prêt",
                    onAction = { showCreateDialog = true },
                )
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    uiState.timeline?.let { timeline ->
                        item { LoansHeader(timeline) }
                        if (timeline.reliefByMonth.isNotEmpty()) {
                            item { ReliefCard(timeline) }
                        }
                    }

                    item {
                        Text(
                            "Par priorité de remboursement".uppercase(Locale.FRANCE),
                            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.14.em),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }

                    items(uiState.loans, key = { it.id }) { loan ->
                        val schedule = uiState.timeline?.loans?.firstOrNull { it.id == loan.id }

                        MaggieCard(
                            onClick = { onOpenLoan(loan.id) },
                            modifier = Modifier.fillMaxWidth().testTag(UiTags.LOAN_ROW),
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(loan.name, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                            if (loan.projected) {
                                                Text(
                                                    "Projeté",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                    modifier = Modifier
                                                        .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(8.dp))
                                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                                                )
                                            }
                                        }
                                        Text(
                                            loanSubtitle(loan),
                                            fontSize = 13.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Text(
                                        formatCentsRounded(loan.principalRemainingCents, loan.currency),
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        loanEndLine(schedule).orEmpty(),
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f),
                                    )
                                    IconButton(onClick = { deletingLoan = loan }) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "Supprimer",
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    uiState.timeline?.savingCapacity?.takeIf { it.isIncomeKnown }?.let { capacity ->
                        item { CapacityBand(capacity) }
                    }
                }
            }
        }
    }

    deletingLoan?.let { item ->
        ConfirmDeleteDialog(
            title = "Supprimer le prêt « ${item.name} » ?",
            onConfirm = {
                viewModel.deleteLoan(item.id)
                deletingLoan = null
            },
            onDismiss = { deletingLoan = null },
        )
    }

    if (showCreateDialog) {
        LoanCreateDialog(
            onConfirm = { request ->
                viewModel.createLoan(request)
                showCreateDialog = false
            },
            onDismiss = { showCreateDialog = false },
        )
    }
}

@Composable
private fun LoansHeader(timeline: DebtTimeline) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Prêts", fontSize = 30.sp, fontWeight = FontWeight.Medium)
        Text(
            "${formatCentsRounded(timeline.totalMonthlyPaymentCents)} / mois",
            fontSize = 46.sp,
            fontWeight = FontWeight.Light,
        )
        Text(
            "de mensualités · ${formatCentsRounded(timeline.totalPrincipalRemainingCents)} restants",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ReliefCard(timeline: DebtTimeline) {
    val first = timeline.reliefByMonth.first()
    val (months, _) = reliefSteps(timeline, YearMonth.now())

    MaggieCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Libération des charges", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text("$months mois", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                text = buildAnnotatedString {
                    append("Prochaine libération : ")
                    withStyle(SpanStyle(color = MaggieTokens.Signal.success, fontWeight = FontWeight.Medium)) {
                        append(
                            "+${formatCentsRounded(first.freedCents)} par mois en " +
                                formatLoanMonth(first.month).lowercase(Locale.FRANCE),
                        )
                    }
                },
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ReliefStepChart(timeline, YearMonth.now())
        }
    }
}

@Composable
private fun CapacityBand(capacity: SavingCapacity) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f), RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("Capacité d’épargne nette", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("${formatCentsRounded(capacity.netCapacityCents)} / mois", fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun LoanCreateDialog(
    onConfirm: (LoanCreateRequest) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var principal by remember { mutableStateOf("") }
    var payment by remember { mutableStateOf("") }
    var rate by remember { mutableStateOf("0") }

    val canSubmit = name.isNotBlank() &&
        principal.toDoubleOrNull() != null &&
        (payment.toDoubleOrNull() ?: 0.0) > 0.0

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.uiTagRoot(),
        title = { Text("Nouveau prêt") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nom") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = principal,
                    onValueChange = { principal = it },
                    label = { Text("Capital restant dû (€)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(
                    value = payment,
                    onValueChange = { payment = it },
                    label = { Text("Mensualité (€)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(
                    value = rate,
                    onValueChange = { rate = it },
                    label = { Text("Taux annuel (%)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val principalCents = principal.toDoubleOrNull()?.let { (it * 100).roundToInt() }
                        ?: return@TextButton
                    val paymentCents = payment.toDoubleOrNull()?.let { (it * 100).roundToInt() }
                        ?: return@TextButton

                    onConfirm(
                        LoanCreateRequest(
                            name = name.trim(),
                            principalRemainingCents = principalCents,
                            monthlyPaymentCents = paymentCents,
                            annualRateBasisPoints = rate.toDoubleOrNull()
                                ?.let { (it * 100).roundToInt() } ?: 0,
                        ),
                    )
                },
                enabled = canSubmit,
            ) {
                Text("Créer")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annuler")
            }
        },
    )
}

internal fun loanSubtitle(loan: Loan): String =
    "${loan.lender?.takeIf { it.isNotBlank() } ?: LoanLabels.type(loan.type)} · ${formatRate(loan.annualRateBasisPoints)} · " +
        "${formatCentsRounded(loan.monthlyPaymentCents, loan.currency)} / mois"

internal fun loanEndLine(schedule: LoanSchedule?): String? {
    schedule ?: return null
    val interest = "${formatCentsRounded(schedule.totalInterestCents, schedule.currency)} d’intérêts restants"

    return if (schedule.endsBeyondHorizon) {
        "Se termine au-delà de l'horizon · $interest"
    } else {
        "Fini en ${formatLoanMonth(schedule.freedOn).lowercase(Locale.FRANCE)} · $interest"
    }
}
