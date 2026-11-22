package com.maggie.app.ui.screens.finance

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.maggie.app.data.model.ProposalClaim
import com.maggie.app.data.model.RecurringOperationRecord
import com.maggie.app.data.model.RecurringProposal
import com.maggie.app.data.model.RecurringSeries
import com.maggie.app.data.model.RecurringSuggestion
import com.maggie.app.data.model.RecurringTotals
import com.maggie.app.data.model.Transaction
import com.maggie.app.data.model.claimsOf
import com.maggie.app.data.model.describeReason
import com.maggie.app.data.model.describeSchedule
import com.maggie.app.data.model.formatCents
import com.maggie.app.data.model.formatCentsRounded
import com.maggie.app.data.model.formatDay
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.ErrorSnackbar
import com.maggie.app.ui.components.FabModule
import com.maggie.app.ui.components.FinanceTopBar
import com.maggie.app.ui.components.MaggieCard
import com.maggie.app.ui.components.ModuleFab
import com.maggie.app.ui.components.formatShortBookedDay
import com.maggie.app.ui.navigation.Screen
import com.maggie.app.ui.theme.MaggieTokens
import com.maggie.app.ui.theme.isMaggieDark
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * The recurring operations: what they cost each month and each year, what asks for a look
 * (a late due date, a price that moved, a line to attach, a series to confirm) and the next due dates.
 *
 * [seed] is the transaction the owner asked to turn into a series from its detail screen: the
 * editor opens on it once, and leaving it goes back to that transaction ([onBackToTransaction]).
 * [openSeriesId] is the series « Reconnues à contrôler » (MAG-460) asked to see: its editor opens once the series are read.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecurringOperationListScreen(
    viewModel: RecurringOperationViewModel,
    onBack: () -> Unit,
    seed: Transaction? = null,
    onSeedConsumed: () -> Unit = {},
    onBackToTransaction: () -> Unit = {},
    openSeriesId: String? = null,
    onSeriesOpened: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    // The suggestion whose heading is being chosen: a screen of its own, never a dropdown.
    var choosingFor by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(seed) {
        if (seed != null) {
            viewModel.startFromTransaction(seed)
            onSeedConsumed()
        }
    }

    // The series are read after the screen opens: wait for the overview, then open the editor on the one asked for.
    LaunchedEffect(openSeriesId, uiState.isLoading, uiState.overview) {
        if (openSeriesId != null && !uiState.isLoading && uiState.overview != null) {
            viewModel.startEdit(openSeriesId)
            onSeriesOpened()
        }
    }

    LaunchedEffect(uiState.returnToTransaction) {
        if (uiState.returnToTransaction) {
            viewModel.consumeReturnToTransaction()
            onBackToTransaction()
        }
    }

    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    uiState.preview?.let { preview ->
        RecurringHistoryPreviewScreen(
            preview = preview,
            isSaving = uiState.isSaving,
            error = uiState.error,
            onConfirm = viewModel::confirmPreview,
            onLater = viewModel::dismissPreview,
            onClearError = viewModel::clearError,
        )
        return
    }

    uiState.editing?.let { editing ->
        RecurringOperationEditScreen(
            state = uiState,
            editing = editing,
            onSave = viewModel::save,
            onDelete = viewModel::delete,
            onBack = viewModel::closeEditor,
            onClearError = viewModel::clearError,
            onDetachLine = viewModel::detachSeriesLine,
            onRetryLines = viewModel::reloadSeriesLines,
            onPreview = viewModel::previewRecognition,
        )
        return
    }

    choosingFor?.let { key ->
        BackHandler { choosingFor = null }
        RecurringCategorySearchScreen(
            categories = uiState.categories,
            selected = uiState.suggestionCategories[key],
            onPick = {
                viewModel.chooseSuggestionCategory(key, it.id)
                uiState.visibleSuggestions.firstOrNull { s -> suggestionKey(s) == key }?.let(viewModel::acceptSuggestion)
                choosingFor = null
            },
            onBack = { choosingFor = null },
        )
        return
    }

    BackHandler(onBack = onBack)

    ErrorSnackbar(
        error = uiState.error,
        snackbarHostState = snackbarHostState,
        onDismiss = viewModel::clearError,
        onRetry = viewModel::refresh,
    )

    Scaffold(
        topBar = {
            FinanceTopBar(Screen.RecurringOperations.route) {
                TopAppBar(
                    title = {},
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                        }
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ModuleFab(FabModule.FINANCE, "Nouvelle opération récurrente", onClick = viewModel::startCreate, tag = UiTags.RECURRING_NEW)
        },
    ) { paddingValues ->
        PullToRefreshBox(
            isRefreshing = uiState.isLoading,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize().padding(paddingValues),
        ) {
            when {
                uiState.overview == null && uiState.error == null -> {
                    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                        RecurringTitle()
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
                uiState.isEmpty -> {
                    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                        RecurringTitle()
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Aucune opération récurrente", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
                else -> RecurringContent(uiState, viewModel, onChooseCategory = { choosingFor = it })
            }
        }
    }
}

@Composable
private fun RecurringTitle() {
    Text("Opérations récurrentes", fontSize = 30.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.6).sp)
}

@Composable
private fun RecurringContent(
    uiState: RecurringOperationUiState,
    viewModel: RecurringOperationViewModel,
    onChooseCategory: (String) -> Unit,
) {
    val checks = checks(uiState.operations)
    val hasChecks = checks.isNotEmpty() || uiState.proposals.isNotEmpty() || uiState.visibleSuggestions.isNotEmpty()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "title") { RecurringTitle() }

        uiState.overview?.totals?.takeIf { uiState.operations.isNotEmpty() }?.let { totals ->
            item(key = "totals") { RecurringTotalsLine(totals) }
        }

        if (hasChecks) {
            item(key = "checks-title") { SectionLabel("À vérifier") }
            items(checks, key = { it.key }) { check ->
                when (check) {
                    is RecurringCheck.Missed -> MissedCard(check.series) { viewModel.startEdit(check.series.id) }
                    is RecurringCheck.PriceChanged -> PriceChangedCard(check.series) { viewModel.startEdit(check.series.id) }
                }
            }
            items(uiState.proposals, key = { "proposal-${it.transactionId}" }) { proposal ->
                ProposalCard(
                    proposal = proposal,
                    busy = uiState.actingOn != null,
                    onAccept = { claim -> viewModel.acceptProposal(proposal, claim) },
                    onDismiss = { viewModel.dismissProposal(proposal.transactionId) },
                )
            }
            items(uiState.visibleSuggestions, key = { "suggestion-${suggestionKey(it)}" }) { suggestion ->
                SuggestionCard(
                    suggestion = suggestion,
                    state = uiState,
                    onChooseCategory = { onChooseCategory(suggestionKey(suggestion)) },
                    onAccept = { viewModel.acceptSuggestion(suggestion) },
                    onDismiss = { viewModel.dismissSuggestion(suggestionKey(suggestion)) },
                )
            }
        }

        if (uiState.operations.isNotEmpty()) {
            item(key = "operations-title") { SectionLabel("Prochaines échéances") }
            items(dueOrder(uiState.operations), key = { "series-${it.id}" }) { series ->
                DueRow(series = series, record = uiState.records[series.id], onClick = { viewModel.startEdit(series.id) })
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(Locale.FRANCE),
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.14.em),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
    )
}

/** « 1 182 € par mois   14 184 € par an »: the amounts in clear, the words in grey. */
@Composable
internal fun RecurringTotalsLine(totals: RecurringTotals) {
    val scheme = MaterialTheme.colorScheme

    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        listOf(totals.monthlyCostCents to " par mois", totals.yearlyCostCents to " par an").forEach { (cents, unit) ->
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = scheme.onSurface, fontWeight = FontWeight.Medium)) { append(formatCentsRounded(-cents)) }
                    append(unit)
                },
                fontSize = 15.sp,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

/** What asks for a look: a due date that did not come, a price that moved. */
internal sealed interface RecurringCheck {
    val series: RecurringSeries
    val key: String

    data class Missed(override val series: RecurringSeries) : RecurringCheck {
        override val key get() = "missed-${series.id}"
    }

    data class PriceChanged(override val series: RecurringSeries) : RecurringCheck {
        override val key get() = "price-${series.id}"
    }
}

/** Every late due date first, then every price change, each in the order of the series. */
internal fun checks(operations: List<RecurringSeries>): List<RecurringCheck> =
    operations.filter { it.missedOccurrence != null }.map { RecurringCheck.Missed(it) } +
        operations.filter { it.priceChange != null }.map { RecurringCheck.PriceChanged(it) }

internal fun dueOrder(operations: List<RecurringSeries>): List<RecurringSeries> =
    operations.sortedWith(compareBy(nullsLast()) { it.nextOccurrenceOn?.take(10) })

private fun signedPercent(percent: Int): String = "${if (percent > 0) "+" else ""}$percent %"

@Composable
internal fun MissedCard(series: RecurringSeries, onClick: () -> Unit = {}) {
    val missed = series.missedOccurrence ?: return
    val days = missed.daysOverdue
    val line = listOfNotNull(
        "Attendue le ${formatShortBookedDay(missed.occurrenceOn)}",
        formatCents(abs(series.referenceAmountCents), series.currency),
        series.accountName.takeIf { it.isNotBlank() },
    ).joinToString(" · ")

    CheckCard(
        title = series.label,
        chip = "en retard de $days jour${if (days > 1) "s" else ""}",
        chipColor = MaggieTokens.Signal.warning,
        chipTag = UiTags.RECURRING_MISSED_BADGE,
        line = line,
        onClick = onClick,
    )
}

@Composable
internal fun PriceChangedCard(series: RecurringSeries, onClick: () -> Unit = {}) {
    val change = series.priceChange ?: return

    CheckCard(
        title = series.label,
        chip = signedPercent(change.percent),
        chipColor = if (change.percent > 0) MaggieTokens.Signal.warning else MaggieTokens.Signal.neutral,
        chipTag = UiTags.RECURRING_PRICE_BADGE,
        line = "${formatCents(abs(change.currentCents), series.currency)} au lieu de ${formatCents(abs(change.previousCents), series.currency)} depuis le ${formatShortBookedDay(change.since)}",
        onClick = onClick,
    )
}

/** A point to check: the name, a chip at the right, one grey line, and the answers when there are some. */
@Composable
internal fun CheckCard(
    title: String,
    chip: String,
    chipColor: Color,
    chipTag: String?,
    line: String,
    onClick: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    MaggieCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f).padding(end = 8.dp))
                Box(
                    modifier = Modifier
                        .let { if (chipTag != null) it.testTag(chipTag) else it }
                        // Its own touch node: inside a clickable card the tag would be merged away.
                        .let { if (onClick != null) it.clickable(onClick = onClick) else it }
                        .height(22.dp)
                        .background(chipColor.copy(alpha = 0.16f), RoundedCornerShape(50))
                        .padding(horizontal = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(chip, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = chipColor)
                }
            }
            Text(line, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
        }
    }
}

@Composable
private fun PillButton(text: String, filled: Boolean, enabled: Boolean = true, tag: String? = null, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme

    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(50),
        colors = if (filled) {
            ButtonDefaults.buttonColors(containerColor = scheme.onSurface, contentColor = scheme.surface)
        } else {
            ButtonDefaults.buttonColors(containerColor = scheme.onSurface.copy(alpha = 0.06f), contentColor = scheme.onSurface)
        },
        modifier = Modifier.heightIn(min = 44.dp).let { if (tag != null) it.testTag(tag) else it },
    ) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

/** « 15 » over « oct. »: null without a date. */
internal fun dueDayAndMonth(iso: String?): Pair<String, String>? {
    val day = iso?.takeIf { it.length >= 10 }?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() } ?: return null

    return day.dayOfMonth.toString() to DateTimeFormatter.ofPattern("MMM", Locale.FRENCH).format(day)
}

/** A series on its next due date: the day and the month at the left, the name and the rhythm, the amount. */
@Composable
internal fun DueRow(series: RecurringSeries, record: RecurringOperationRecord? = null, onClick: () -> Unit = {}) {
    val scheme = MaterialTheme.colorScheme
    val hairline = scheme.onSurface.copy(alpha = if (isMaggieDark()) MaggieTokens.Divider.DARK else MaggieTokens.Divider.LIGHT)
    val date = dueDayAndMonth(series.nextOccurrenceOn)
    val schedule = describeSchedule(series.period, series.dayRule, record?.anchorOn?.takeIf { it.isNotBlank() } ?: series.nextOccurrenceOn) +
        (series.endsOn?.let { " · jusqu'au ${formatShortBookedDay(it)}" } ?: "")

    Column {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.width(44.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                date?.let {
                    Text(it.first, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                    Text(it.second, fontSize = 12.sp, color = scheme.onSurfaceVariant)
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(series.label, fontSize = 15.sp)
                Text(schedule, fontSize = 12.sp, color = scheme.onSurfaceVariant)
            }
            Text(
                text = (if (series.referenceAmountCents > 0) "+" else "") + formatCents(abs(series.referenceAmountCents), series.currency),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        HorizontalDivider(color = hairline)
    }
}

@Composable
internal fun ProposalCard(
    proposal: RecurringProposal,
    busy: Boolean,
    onAccept: (ProposalClaim) -> Unit,
    onDismiss: () -> Unit,
) {
    CheckCard(
        title = proposal.label,
        chip = "à rattacher",
        chipColor = MaggieTokens.Brand.primaryHover,
        chipTag = null,
        line = "Transaction du ${formatDay(proposal.bookedAt)} : ${describeReason(proposal)}",
    ) {
        claimsOf(proposal).forEach { claim ->
            PillButton("Rattacher à « ${claim.recurringOperationLabel ?: "?"} »", filled = true, enabled = !busy, tag = UiTags.RECURRING_PROPOSAL_ACCEPT) {
                onAccept(claim)
            }
        }
        PillButton("Non", filled = false, onClick = onDismiss)
    }
}

@Composable
internal fun SuggestionCard(
    suggestion: RecurringSuggestion,
    state: RecurringOperationUiState,
    onChooseCategory: () -> Unit,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
) {
    CheckCard(
        title = suggestion.label,
        chip = "proposée",
        chipColor = MaggieTokens.Brand.primaryHover,
        chipTag = null,
        line = "${formatCents(abs(suggestion.referenceAmountCents))} vus ${suggestion.occurrences} fois · ${describeSchedule(suggestion.period, suggestion.dayRule, suggestion.anchorOn)}",
    ) {
        PillButton("C'est une récurrente", filled = true, enabled = state.actingOn == null, tag = UiTags.RECURRING_SUGGESTION_ACCEPT) {
            if (state.categoryOf(suggestion) == null) onChooseCategory() else onAccept()
        }
        PillButton("Non", filled = false, onClick = onDismiss)
    }
}
