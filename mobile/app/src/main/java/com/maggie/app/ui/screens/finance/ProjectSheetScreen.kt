package com.maggie.app.ui.screens.finance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.Account
import com.maggie.app.data.model.FundingKind
import com.maggie.app.data.model.Placement
import com.maggie.app.data.model.Project
import com.maggie.app.data.model.ProjectFundingSource
import com.maggie.app.data.model.ProjectInvestment
import com.maggie.app.data.model.ProjectOption
import com.maggie.app.data.model.ProjectOptions
import com.maggie.app.data.model.formatCents
import com.maggie.app.data.model.formatRate
import com.maggie.app.data.model.investmentBalanceLabel
import com.maggie.app.data.model.investmentResaleLabel
import com.maggie.app.data.model.label
import com.maggie.app.data.model.projectDateLabel
import com.maggie.app.data.model.projectFundingSummary
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.layout.WindowWidth
import com.maggie.app.ui.layout.rememberAppLayout
import kotlin.math.abs

/**
 * The sheet of a project (MAG-525): where its money comes from, as a bar and in words, and its three priced
 * ways to pay — cash, loan, a down payment and a loan — side by side, facts only. The phone reads it; the
 * tablet, wider than 600 dp, also edits the sources and the parameters the options are priced with.
 */
@Composable
fun ProjectSheetScreen(
    viewModel: ProjectViewModel,
    projectId: String,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val editable = rememberAppLayout().width != WindowWidth.COMPACT

    // Once the list is there: a link may open the sheet before the projects are read.
    LaunchedEffect(projectId, state.hasLoaded) {
        if (state.hasLoaded && viewModel.uiState.value.sheet?.projectId != projectId) viewModel.openSheet(projectId)
    }
    LaunchedEffect(editable) {
        if (editable) {
            viewModel.loadAccounts()
            viewModel.loadPlacements()
        }
    }

    ProjectSheetContent(
        project = state.projects.firstOrNull { it.id == projectId },
        projects = state.projects,
        sheet = state.sheet?.takeIf { it.projectId == projectId },
        accounts = state.accounts,
        placements = state.placements,
        hasLoaded = state.hasLoaded,
        editable = editable,
        actions = ProjectSheetActions(
            onBack = onBack,
            onEdit = { onEdit(projectId) },
            onAddSource = viewModel::addSource,
            onRemoveSource = viewModel::removeSource,
            onEditSource = viewModel::editSource,
            onEditLoanTerms = viewModel::editLoanTerms,
            onSave = { sources -> viewModel.saveFunding(projectId, sources) },
            onEditOptions = viewModel::editOptions,
            onApplyOptions = viewModel::applyOptions,
        ),
    )
}

class ProjectSheetActions(
    val onBack: () -> Unit = {},
    val onEdit: () -> Unit = {},
    val onAddSource: (FundingKind) -> Unit = {},
    val onRemoveSource: (Int) -> Unit = {},
    val onEditSource: (Int, (FundingSourceDraft) -> FundingSourceDraft) -> Unit = { _, _ -> },
    val onEditLoanTerms: (Int) -> Unit = {},
    val onSave: (List<FundingSourceDraft>) -> Unit = {},
    val onEditOptions: ((ProjectOptionsDraft) -> ProjectOptionsDraft) -> Unit = {},
    val onApplyOptions: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProjectSheetContent(
    project: Project?,
    sheet: ProjectSheet?,
    accounts: List<Account>,
    hasLoaded: Boolean,
    editable: Boolean,
    actions: ProjectSheetActions,
    /** All the projects, to name the one a resale funds or comes from (MAG-557). */
    projects: List<Project> = emptyList(),
    placements: List<Placement> = emptyList(),
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(project?.name ?: "Projet") },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    if (project != null) {
                        IconButton(onClick = actions.onEdit, modifier = Modifier.testTag(UiTags.PROJECT_SHEET_EDIT)) {
                            Icon(Icons.Default.Edit, contentDescription = "Modifier le projet")
                        }
                    }
                },
            )
        },
        modifier = Modifier.testTag(UiTags.PROJECT_SHEET),
    ) { paddingValues ->
        Box(
            modifier = Modifier.fillMaxSize().padding(paddingValues).verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter,
        ) {
            when {
                project != null -> Column(
                    modifier = Modifier.widthIn(max = if (editable) 840.dp else 640.dp).fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "${formatCents(project.amountCents, project.currency)} · ${projectDateLabel(project)}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    projectFundingSummary(project)?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(UiTags.PROJECT_FUNDING_SUMMARY))
                    }
                    project.investment?.let { InvestmentCard(project, it, projects) }
                    FundingCard(project, projects)
                    OptionsCard(sheet)
                    if (editable && sheet != null) {
                        FundingEditorCard(project, sheet, accounts, placements, actions)
                        OptionsParametersCard(sheet, actions)
                    }
                }
                hasLoaded -> Text(
                    ProjectViewModel.MESSAGE_NOT_FOUND,
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
private fun SheetCard(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

private class FundingSegment(val label: String, val cents: Int, val color: Color)

@Composable
private fun kindColor(kind: FundingKind): Color = when (kind) {
    FundingKind.SAVINGS -> MaterialTheme.colorScheme.primary
    FundingKind.CAPITAL -> MaterialTheme.colorScheme.secondary
    FundingKind.LOAN -> MaterialTheme.colorScheme.tertiary
    FundingKind.RESALE -> MaterialTheme.colorScheme.inversePrimary
}

/** The sources over the amount of the project, then what is left on the salary: a stacked bar and its legend. */
@Composable
private fun FundingCard(project: Project, projects: List<Project>) {
    val sources = project.fundingSources.mapNotNull { source ->
        FundingKind.of(source.kind)?.let { FundingSegment(sourceLabel(it, source, projects), source.amountCents, kindColor(it)) }
    }
    val salary = FundingSegment("Salaire", project.salaryCents, MaterialTheme.colorScheme.outlineVariant)
    val segments = (sources + salary).filter { it.cents > 0 }

    SheetCard("Financement") {
        if (segments.isEmpty()) return@SheetCard
        val description = segments.joinToString(", ") { "${it.label} ${formatCents(it.cents, project.currency)}" }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(16.dp)
                .clip(RoundedCornerShape(8.dp))
                .semantics { contentDescription = "Financement : $description" }
                .testTag(UiTags.PROJECT_FUNDING_BAR),
        ) {
            segments.forEach { segment ->
                Box(Modifier.weight(segment.cents.toFloat()).fillMaxHeight().background(segment.color))
            }
        }
        segments.forEach { segment ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().testTag(UiTags.PROJECT_FUNDING_SOURCE),
            ) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(segment.color))
                Text(segment.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(formatCents(segment.cents, project.currency), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** « Revente de Box à louer » for a resale, the kind's label for the others. */
internal fun sourceLabel(kind: FundingKind, source: ProjectFundingSource, projects: List<Project>): String {
    if (kind != FundingKind.RESALE) return kind.label
    val from = source.sourceProjectId?.let { id -> projects.firstOrNull { it.id == id } }
    return if (from != null) "${kind.label} de ${from.name}" else kind.label
}

/**
 * The investment of a project (MAG-557), read-only: the price and the down payment, the rent, the loan's
 * payment and what is left each month, then the planned resale in a sentence. It is written from the admin
 * and by Maggie, which the card says.
 */
@Composable
private fun InvestmentCard(project: Project, investment: ProjectInvestment, projects: List<Project>) {
    val currency = project.currency
    SheetCard("Investissement locatif", modifier = Modifier.testTag(UiTags.PROJECT_INVESTMENT)) {
        InvestmentRow("Prix", formatCents(investment.priceCents, currency), UiTags.PROJECT_INVESTMENT_PRICE)
        InvestmentRow("Apport", formatCents(investment.downPaymentCents, currency), UiTags.PROJECT_INVESTMENT_DOWN_PAYMENT)
        InvestmentRow("Loyer perçu", "${formatCents(investment.monthlyRentCents, currency)} par mois", UiTags.PROJECT_INVESTMENT_RENT)
        InvestmentRow(
            "Mensualité du prêt",
            if (investment.loanPaymentCents > 0) "${formatCents(investment.loanPaymentCents, currency)} par mois" else "—",
            UiTags.PROJECT_INVESTMENT_LOAN,
        )
        InvestmentRow(
            "Solde mensuel",
            investmentBalanceLabel(investment, currency).removePrefix("Solde : "),
            UiTags.PROJECT_INVESTMENT_BALANCE,
            bold = true,
        )
        Text(
            investmentResaleLabel(investment, projects, currency) ?: "Aucune revente planifiée.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag(UiTags.PROJECT_INVESTMENT_RESALE),
        )
        Text(
            "Se modifie depuis l'administration, ou en le demandant à Maggie.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InvestmentRow(label: String, value: String, tag: String, bold: Boolean = false) {
    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold)
    }
}

private class OptionColumn(val key: String, val title: String, val option: ProjectOption)

/** Cash, loan and down payment + loan, three equal columns: no one is drawn as better than the others. */
@Composable
private fun OptionsCard(sheet: ProjectSheet?) {
    SheetCard("Options", modifier = Modifier.testTag(UiTags.PROJECT_OPTIONS)) {
        val options = sheet?.options
        when {
            options != null -> OptionColumns(options)
            sheet?.optionsError != null -> Text(
                sheet.optionsError,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(UiTags.PROJECT_OPTIONS_ERROR),
            )
            else -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun OptionColumns(options: ProjectOptions) {
    val columns = listOf(
        OptionColumn("cash", "Comptant", options.cash),
        OptionColumn("loan", "Emprunt", options.loan),
        OptionColumn("partial", "Apport + emprunt", options.partial),
    )
    // One scale for every cost, one for every delay: the bars compare across the columns.
    val costScale = columns.maxOf { maxOf(it.option.totalCostCents, abs(it.option.netCostCents)) }.coerceAtLeast(1)
    val delayScale = columns.maxOf { it.option.availableInMonths ?: 0 }.coerceAtLeast(1)

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        columns.forEach { column ->
            OptionColumnCard(column, options.currency, costScale, delayScale, Modifier.weight(1f))
        }
    }
}

@Composable
private fun OptionColumnCard(column: OptionColumn, currency: String, costScale: Int, delayScale: Int, modifier: Modifier) {
    val option = column.option
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = modifier.testTag(UiTags.projectOption(column.key)),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(column.title, style = MaterialTheme.typography.titleSmall)
            Metric(
                "Coût total", formatCents(option.totalCostCents, currency), option.totalCostCents.toFloat() / costScale,
                UiTags.projectOptionTotal(column.key),
            )
            Metric(
                "Coût net", formatCents(option.netCostCents, currency), abs(option.netCostCents).toFloat() / costScale,
                UiTags.projectOptionNet(column.key),
            )
            Metric(
                "Mensualité",
                if (option.monthlyPaymentCents > 0) formatCents(option.monthlyPaymentCents, currency) else "—",
                null,
                UiTags.projectOptionMonthly(column.key),
            )
            Metric(
                "Délai avant jouissance", delayLabel(option.availableInMonths),
                option.availableInMonths?.let { it.toFloat() / delayScale },
                UiTags.projectOptionDelay(column.key),
            )
            option.nImpactMonths?.let { Metric("Impact sur N", "$it mois", null, null) }
        }
    }
}

/** « Immédiat », « 8 mois », « — » when the money is never there. */
internal fun delayLabel(months: Int?): String = when {
    months == null -> "—"
    months == 0 -> "Immédiat"
    else -> "$months mois"
}

@Composable
private fun Metric(label: String, value: String, fraction: Float?, tag: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = if (tag != null) Modifier.testTag(tag) else Modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        if (fraction != null) {
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.outlineVariant)) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Tablet: the sources and the parameters of the options.
// ---------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FundingEditorCard(
    project: Project,
    sheet: ProjectSheet,
    accounts: List<Account>,
    placements: List<Placement>,
    actions: ProjectSheetActions,
) {
    SheetCard("Sources de financement") {
        sheet.sources.forEachIndexed { index, source ->
            FundingSourceEditor(index, source, accounts, placements, actions)
        }
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth().testTag(UiTags.PROJECT_FUNDING_SALARY),
        ) {
            Text("Le reste, sur salaire", style = MaterialTheme.typography.bodyLarge)
            Text(
                formatCents(FundingDraft.salaryCents(FundingDraft.editableAmountCents(project), sheet.sources), project.currency),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FundingKind.entries.filter { it.editable }.forEach { kind ->
                if (kind == FundingKind.LOAN && sheet.sources.any { it.kind == FundingKind.LOAN }) return@forEach
                AssistChip(
                    onClick = { actions.onAddSource(kind) },
                    label = { Text(addLabel(kind)) },
                    leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                    modifier = Modifier.testTag(UiTags.projectFundingAdd(kind.key)),
                )
            }
        }
        sheet.fundingError?.let { refusal ->
            Text(
                refusal,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(UiTags.PROJECT_FUNDING_ERROR),
            )
        }
        if (sheet.fundingSaved) {
            Text("Sources enregistrées.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Button(
            onClick = { actions.onSave(sheet.sources) },
            enabled = !sheet.isSavingFunding,
            modifier = Modifier.testTag(UiTags.PROJECT_FUNDING_SAVE),
        ) { Text("Enregistrer les sources") }
    }
}

private fun addLabel(kind: FundingKind): String = when (kind) {
    FundingKind.SAVINGS -> "Ajouter des économies"
    FundingKind.CAPITAL -> "Ajouter du capital"
    FundingKind.LOAN -> "Ajouter un emprunt"
    FundingKind.RESALE -> "Ajouter une revente"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FundingSourceEditor(
    index: Int,
    source: FundingSourceDraft,
    accounts: List<Account>,
    placements: List<Placement>,
    actions: ProjectSheetActions,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(source.kind.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = { actions.onRemoveSource(index) }, modifier = Modifier.testTag(UiTags.PROJECT_FUNDING_REMOVE)) {
                    Icon(Icons.Default.Delete, contentDescription = "Retirer la source", tint = MaterialTheme.colorScheme.error)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(
                    onClick = { actions.onEditSource(index) { it.stepped(-FUNDING_STEP_CENTS) } },
                    modifier = Modifier.testTag(UiTags.PROJECT_FUNDING_MINUS),
                ) { Icon(Icons.Default.Remove, contentDescription = "Moins 100 €") }
                OutlinedTextField(
                    value = source.amount,
                    onValueChange = { value -> actions.onEditSource(index) { it.copy(amount = value) } },
                    label = { Text("Montant") },
                    suffix = { Text("€") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f).testTag(UiTags.PROJECT_FUNDING_AMOUNT),
                )
                IconButton(
                    onClick = { actions.onEditSource(index) { it.stepped(FUNDING_STEP_CENTS) } },
                    modifier = Modifier.testTag(UiTags.PROJECT_FUNDING_PLUS),
                ) { Icon(Icons.Default.Add, contentDescription = "Plus 100 €") }
            }
            if (source.kind == FundingKind.LOAN) {
                val terms = source.loanTerms
                if (terms != null) {
                    LoanTermsForm(
                        draft = terms,
                        onChange = { changed -> actions.onEditSource(index) { it.copy(loanTerms = changed) } },
                        showPayment = false,
                    )
                } else {
                    OutlinedButton(onClick = { actions.onEditLoanTerms(index) }) { Text("Modifier les conditions de l'emprunt") }
                }
            } else {
                val eligible = FundingDraft.eligibleAccounts(source.kind, accounts)
                if (eligible.isEmpty()) {
                    Text("Aucun compte éligible.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        eligible.forEach { account ->
                            FilterChip(
                                selected = source.accountId == account.id,
                                onClick = { actions.onEditSource(index) { it.copy(accountId = account.id) } },
                                label = { Text(account.label()) },
                                modifier = Modifier.testTag(UiTags.projectFundingAccount(account.id)),
                            )
                        }
                    }
                }
                // Optional (MAG-556): the simulated placement this capital leaves, taken out the month the project is bought.
                if (source.kind == FundingKind.CAPITAL && placements.isNotEmpty()) {
                    Text(
                        "Placement quitté (facultatif)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = source.placementId == null,
                            onClick = { actions.onEditSource(index) { it.copy(placementId = null) } },
                            label = { Text("Aucun") },
                            modifier = Modifier.testTag(UiTags.PROJECT_FUNDING_PLACEMENT_NONE),
                        )
                        placements.forEach { placement ->
                            FilterChip(
                                selected = source.placementId == placement.id,
                                onClick = { actions.onEditSource(index) { it.copy(placementId = placement.id) } },
                                label = { Text(placement.name) },
                                modifier = Modifier.testTag(UiTags.projectFundingPlacement(placement.id)),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** What the options are priced with; blank, the API takes the loan source's conditions. */
@Composable
private fun OptionsParametersCard(sheet: ProjectSheet, actions: ProjectSheetActions) {
    val draft = sheet.optionsDraft
    val options = sheet.options
    SheetCard("Hypothèses des options") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            ParameterField(
                "Taux de l'emprunt", draft.rate, "%", options?.let { formatRate(it.loanRateBasisPoints).removeSuffix(" %") },
                UiTags.PROJECT_OPTIONS_RATE, Modifier.weight(1f),
            ) { value -> actions.onEditOptions { it.copy(rate = value) } }
            ParameterField(
                "Durée", draft.months, "mois", options?.loanMonths?.toString(),
                UiTags.PROJECT_OPTIONS_MONTHS, Modifier.weight(1f),
            ) { value -> actions.onEditOptions { it.copy(months = value) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            ParameterField(
                "Apport", draft.downPayment, "€", options?.let { cents(it.downPaymentCents) },
                UiTags.PROJECT_OPTIONS_DOWN_PAYMENT, Modifier.weight(1f),
            ) { value -> actions.onEditOptions { it.copy(downPayment = value) } }
            ParameterField(
                "Rendement du capital gardé", draft.yieldRate, "%", null,
                UiTags.PROJECT_OPTIONS_YIELD, Modifier.weight(1f),
            ) { value -> actions.onEditOptions { it.copy(yieldRate = value) } }
        }
        TextButton(onClick = actions.onApplyOptions, modifier = Modifier.testTag(UiTags.PROJECT_OPTIONS_APPLY)) {
            Text("Chiffrer les options")
        }
    }
}

private fun cents(value: Int): String = String.format(java.util.Locale.FRANCE, "%.2f", value / 100.0)

@Composable
private fun ParameterField(
    label: String,
    value: String,
    suffix: String,
    placeholder: String?,
    tag: String,
    modifier: Modifier,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        suffix = { Text(suffix) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.testTag(tag),
    )
}
