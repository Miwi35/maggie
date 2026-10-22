package com.maggie.app.ui.screens.finance

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.Placement
import com.maggie.app.data.model.PlacementFrequency
import com.maggie.app.data.model.PlacementPoint
import com.maggie.app.data.model.PlacementSimulation
import com.maggie.app.data.model.independenceMonthLabel
import com.maggie.app.data.model.formatEuros
import com.maggie.app.data.model.formatPlacementRate
import com.maggie.app.data.model.placementTerms
import com.maggie.app.data.model.yearsLabel
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.EmptyState
import com.maggie.app.ui.components.ErrorSnackbar
import com.maggie.app.ui.components.FinanceTopBar
import com.maggie.app.ui.layout.WindowWidth
import com.maggie.app.ui.layout.rememberAppLayout
import com.maggie.app.ui.navigation.Screen
import com.maggie.app.ui.theme.MaggieTokens
import java.time.LocalDate

/**
 * « Placements » of Planification (MAG-555): the simulated placements and the projection of the one
 * selected — the capital it would reach, what was paid in, the compound interest, and the rent it
 * would pay at 4 %. On a phone, a tap on a row selects it and a tap on the selected row (or the
 * card's pencil) opens it in the full-screen form; « + » opens a new one. On an expanded window,
 * three columns: the list, the settings whose steppers PATCH the placement, and the projection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlacementListScreen(
    viewModel: PlacementViewModel,
    onBack: () -> Unit,
    onOpenForm: (placementId: String?) -> Unit,
) {
    val expanded = rememberAppLayout().width == WindowWidth.EXPANDED
    Scaffold(
        topBar = {
            FinanceTopBar(Screen.PlacementList.route, actions = {
                // The tablet's list has its own « + Placement » under the rows.
                if (!expanded) {
                    FilledIconButton(
                        onClick = { onOpenForm(null) },
                        modifier = Modifier.padding(end = 8.dp).testTag(UiTags.PLACEMENT_ADD),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Ajouter un placement")
                    }
                }
            })
        },
    ) { paddingValues ->
        PlacementListContent(viewModel, onOpenForm, expanded, Modifier.padding(paddingValues))
    }
}

@Composable
internal fun PlacementListContent(
    viewModel: PlacementViewModel,
    onOpenForm: (placementId: String?) -> Unit,
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    ErrorSnackbar(
        error = uiState.error,
        snackbarHostState = snackbarHostState,
        onDismiss = viewModel::clearError,
        onRetry = if (uiState.hasLoaded) null else viewModel::refresh,
        message = uiState.error.orEmpty(),
    )
    LaunchedEffect(uiState.message) {
        val message = uiState.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearMessage()
    }

    Box(modifier = modifier.fillMaxSize().testTag(UiTags.PLACEMENTS)) {
        val selected = uiState.selected
        when {
            uiState.isLoading && !uiState.hasLoaded -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            selected == null -> {
                EmptyState(
                    modifier = Modifier.testTag(UiTags.PLACEMENT_EMPTY),
                    title = if (uiState.hasLoaded) "Aucun placement" else "Placements indisponibles",
                    description = if (uiState.hasLoaded) {
                        "Simulez un placement : un versement initial, des versements réguliers, un rendement et une durée."
                    } else {
                        PlacementViewModel.MESSAGE_LOAD
                    },
                    actionLabel = if (uiState.hasLoaded) "Ajouter un placement" else "Réessayer",
                    onAction = if (uiState.hasLoaded) ({ onOpenForm(null) }) else viewModel::refresh,
                )
            }
            expanded -> PlacementColumns(
                state = uiState,
                selected = selected,
                onSelect = viewModel::select,
                onOpenForm = onOpenForm,
                onFrequency = viewModel::setFrequency,
                onStep = viewModel::step,
            )
            else -> PlacementPhoneList(
                state = uiState,
                selected = selected,
                onSelect = viewModel::select,
                onOpenForm = onOpenForm,
            )
        }
        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun PlacementPhoneList(
    state: PlacementUiState,
    selected: Placement,
    onSelect: (String) -> Unit,
    onOpenForm: (String?) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "card") {
            ProjectionCard(selected, state, onEdit = { onOpenForm(selected.id) })
        }
        items(state.placements, key = { it.id }) { placement ->
            val isSelected = placement.id == selected.id
            PlacementRow(placement, isSelected) { if (isSelected) onOpenForm(placement.id) else onSelect(placement.id) }
        }
    }
}

/** The selected placement in a card: what it reaches, the chart, what is paid and earned, the rent. */
@Composable
private fun ProjectionCard(placement: Placement, state: PlacementUiState, onEdit: () -> Unit) {
    val colors = placementChartColors()
    Card(modifier = Modifier.fillMaxWidth().testTag(UiTags.PLACEMENT_CARD)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${placement.name} · capital dans ${yearsLabel(placement.years)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onEdit, modifier = Modifier.testTag(UiTags.PLACEMENT_EDIT)) {
                    Icon(Icons.Default.Edit, contentDescription = "Modifier ${placement.name}")
                }
            }
            Text(
                formatEuros(placement.finalCents),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Light,
            )
            ChartSlot(state, placement, height = 120.dp, axes = false)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                LegendItem(colors.paid, "versements ${formatEuros(placement.paid)}")
                LegendItem(colors.capital, "intérêts ${formatEuros(placement.interest)}")
            }
            Text(
                "≈ ${formatEuros(placement.rent)} / mois de rente à 4 %",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.simulation?.takeIf { it.hasWithdrawals }?.let { WithdrawalSummary(it, showGap = true) }
        }
    }
}

/**
 * What the projects of the scenario take out of the placement (MAG-556): the capital and the 4 % rent
 * that remain, then each withdrawal — project, month, amount. A simulation, said as figures: no verdict.
 */
@Composable
private fun WithdrawalSummary(simulation: PlacementSimulation, showGap: Boolean) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HorizontalDivider()
        Text(
            "Après retrait pour ${simulation.withdrawalProjects}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.testTag(UiTags.PLACEMENT_AFTER_WITHDRAWAL), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "${formatEuros(simulation.finalWith)} après retrait",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Light,
            )
            Text(
                "≈ ${formatEuros(simulation.rentWith)} / mois de rente à 4 % après retrait",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (showGap) GapText(simulation)
        Column(modifier = Modifier.testTag(UiTags.PLACEMENT_WITHDRAWALS), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            simulation.withdrawals.forEach { withdrawal ->
                Row {
                    Text(
                        "${withdrawal.name} · ${independenceMonthLabel(withdrawal.month)}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text("− ${formatEuros(withdrawal.amountCents)}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** « Écart à la fin de la durée : 2 300 € »: the capital that is missing at the end with the withdrawals. */
@Composable
private fun GapText(simulation: PlacementSimulation) {
    Text(
        "Écart à la fin de la durée : ${formatEuros(simulation.gapAtEnd)}",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.testTag(UiTags.PLACEMENT_GAP),
    )
}

/** The chart once the curve is there; a spinner while it comes, a sentence when it failed. */
@Composable
private fun ChartSlot(state: PlacementUiState, placement: Placement, height: Dp, axes: Boolean) {
    val simulation = state.simulation
    when {
        simulation != null && simulation.curve.isNotEmpty() ->
            PlacementChart(simulation, placement.years, height = height, axes = axes)
        state.simulationFailed -> Box(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
            Text(
                PlacementViewModel.MESSAGE_SIMULATION,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        else -> Box(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun PlacementRow(placement: Placement, isSelected: Boolean, onClick: () -> Unit) {
    val ring = placementChartColors().capital
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        border = if (isSelected) BorderStroke(1.5.dp, ring) else null,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .semantics { selected = isSelected }
            .testTag(UiTags.PLACEMENT_ROW),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row {
                Text(placement.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(formatEuros(placement.finalCents), style = MaterialTheme.typography.titleMedium)
            }
            Text(
                placementTerms(placement),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The expanded window: the list, the settings of the selected placement, its projection. */
@Composable
private fun PlacementColumns(
    state: PlacementUiState,
    selected: Placement,
    onSelect: (String) -> Unit,
    onOpenForm: (String?) -> Unit,
    onFrequency: (PlacementFrequency) -> Unit,
    onStep: (PlacementStep, Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            modifier = Modifier.weight(0.3f).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "${formatEuros(state.monthlyDeposits)} par mois de versements en moyenne.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.placements.forEach { placement ->
                PlacementRow(placement, placement.id == selected.id) { onSelect(placement.id) }
            }
            OutlinedButton(
                onClick = { onOpenForm(null) },
                modifier = Modifier.fillMaxWidth().testTag(UiTags.PLACEMENT_ADD),
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text("Placement", modifier = Modifier.padding(start = 4.dp))
            }
        }
        SettingsPanel(selected, onFrequency, onStep, onEdit = { onOpenForm(selected.id) }, modifier = Modifier.weight(0.32f))
        ProjectionPanel(selected, state, modifier = Modifier.weight(0.38f))
    }
}

/** « Réglages du placement »: each tap changes the placement on screen, then on the server. */
@Composable
private fun SettingsPanel(
    placement: Placement,
    onFrequency: (PlacementFrequency) -> Unit,
    onStep: (PlacementStep, Boolean) -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxHeight().testTag(UiTags.PLACEMENT_SETTINGS)) {
        Column(
            modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(placement.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onEdit, modifier = Modifier.testTag(UiTags.PLACEMENT_EDIT)) {
                    Icon(Icons.Default.Edit, contentDescription = "Modifier ${placement.name}")
                }
            }
            Text(
                "VERSEMENTS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            FrequencyControl(PlacementFrequency.of(placement.frequency), onFrequency)
            StepperField("Versement", formatEuros(placement.deposit.toLong()), PlacementStep.DEPOSIT, placement, onStep, "le versement")
            SettingsField("Versement initial", formatEuros(placement.initial.toLong()))
            StepperField("Rendement annuel", formatPlacementRate(placement.annualRate), PlacementStep.RATE, placement, onStep, "le rendement")
            StepperField("Durée", yearsLabel(placement.years), PlacementStep.YEARS, placement, onStep, "la durée")
            SettingsField("Intérêts", "composés, réinvestis", divider = false)
        }
    }
}

/** Mensuel · Trimestriel · Annuel · Ponctuel, one pill each, the current one filled. */
@Composable
private fun FrequencyControl(current: PlacementFrequency, onPick: (PlacementFrequency) -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Row(modifier = Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            PlacementFrequency.entries.forEach { frequency ->
                val on = frequency == current
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .background(if (on) MaterialTheme.colorScheme.onSurface else Color.Transparent, RoundedCornerShape(50))
                        .clickable(role = Role.RadioButton) { if (!on) onPick(frequency) }
                        .semantics { selected = on }
                        .testTag(UiTags.placementFrequency(frequency.key)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        frequency.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (on) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsField(label: String, value: String, divider: Boolean = true) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (divider) HorizontalDivider()
    }
}

@Composable
private fun StepperField(
    label: String,
    value: String,
    step: PlacementStep,
    placement: Placement,
    onStep: (PlacementStep, Boolean) -> Unit,
    what: String,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            FilledTonalIconButton(
                onClick = { onStep(step, false) },
                enabled = canStepDown(step, placement),
                shape = CircleShape,
                modifier = Modifier.size(40.dp).testTag(UiTags.placementStep(step.key, plus = false)),
            ) {
                Icon(Icons.Default.Remove, contentDescription = "Diminuer $what")
            }
            Text(
                value,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(96.dp),
            )
            FilledTonalIconButton(
                onClick = { onStep(step, true) },
                enabled = canStepUp(step, placement),
                shape = CircleShape,
                modifier = Modifier.size(40.dp).testTag(UiTags.placementStep(step.key, plus = true)),
            ) {
                Icon(Icons.Default.Add, contentDescription = "Augmenter $what")
            }
        }
        HorizontalDivider()
    }
}

/** The three figures, the chart with its axes, and the rent the capital would pay at 4 %. */
@Composable
private fun ProjectionPanel(placement: Placement, state: PlacementUiState, modifier: Modifier = Modifier) {
    val colors = placementChartColors()
    Column(
        modifier = modifier.fillMaxHeight().verticalScroll(rememberScrollState()).testTag(UiTags.PLACEMENT_CARD),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FigureTile("Capital dans ${yearsLabel(placement.years)}", formatEuros(placement.finalCents), Modifier.weight(1f))
            FigureTile("Dont versements", formatEuros(placement.paid), Modifier.weight(1f))
            FigureTile("Dont intérêts composés", formatEuros(placement.interest), Modifier.weight(1f))
        }
        val withdrawals = state.simulation?.takeIf { it.hasWithdrawals }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (withdrawals != null) {
                    Text(
                        "${placement.name} · avec et sans retrait pour ${withdrawals.withdrawalProjects}",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.testTag(UiTags.PLACEMENT_CHART_TITLE),
                    )
                }
                ChartSlot(state, placement, height = 240.dp, axes = true)
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    LegendItem(colors.paid, "versements cumulés")
                    if (withdrawals != null) {
                        LegendItem(colors.capital, "capital avec retrait")
                        LegendItem(colors.capital.copy(alpha = 0.5f), "capital sans retrait (pointillés)")
                    } else {
                        LegendItem(colors.capital, "intérêts composés")
                    }
                }
                if (withdrawals != null) GapText(withdrawals)
            }
        }
        Text(
            "Retiré à 4 % par an, ce capital donnerait environ ${formatEuros(placement.rent)} par mois de rente.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (withdrawals != null) WithdrawalSummary(withdrawals, showGap = false)
    }
}

@Composable
private fun FigureTile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Light)
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).background(color, RoundedCornerShape(2.dp)))
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The capital (mint) and what was paid in (blue), the mock-ups' two hues, darker on a light theme. */
internal data class PlacementChartColors(val capital: Color, val paid: Color)

@Composable
internal fun placementChartColors(): PlacementChartColors {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return PlacementChartColors(
        capital = if (dark) MaggieTokens.Rhythm.onTrack.second else MaggieTokens.Rhythm.onTrack.first,
        paid = if (dark) MaggieTokens.feedbackDark.info else MaggieTokens.feedbackLight.info,
    )
}

/** What a screen reader says of the chart. */
internal fun placementChartDescription(simulation: PlacementSimulation, years: Int): String =
    "Capital projeté : ${formatEuros(simulation.finalCents)} dans ${yearsLabel(years)}, " +
        "dont ${formatEuros(simulation.paid)} de versements et ${formatEuros(simulation.interest)} d'intérêts composés." +
        if (simulation.hasWithdrawals) {
            " Avec retrait pour ${simulation.withdrawalProjects} : ${formatEuros(simulation.finalWith)}, " +
                "soit un écart de ${formatEuros(simulation.gapAtEnd)}."
        } else {
            ""
        }

/**
 * Two filled areas over the months: the whole capital, and under it what was paid in — the gap
 * between them is the compound interest. With [axes], the amounts on the left and the years below.
 */
@Composable
private fun PlacementChart(simulation: PlacementSimulation, years: Int, height: Dp, axes: Boolean) {
    val colors = placementChartColors()
    val baseline = MaterialTheme.colorScheme.outlineVariant
    // With withdrawals (the tablet): the capital without them is the curve, drawn dashed, and the one with them, solid.
    val withWithdrawals = axes && simulation.hasWithdrawals && simulation.curveWith.isNotEmpty()
    val curve = if (withWithdrawals) simulation.curveWithout.ifEmpty { simulation.curve } else simulation.curve
    val curveWith = if (withWithdrawals) simulation.curveWith else curve
    val lastMonth = curve.maxOf { it.month }.coerceAtLeast(1)
    val top = (maxOf(curve.maxOf { it.capital }, curveWith.maxOf { it.capital }).coerceAtLeast(1) * 1.05).toLong()
    val description = placementChartDescription(simulation, years)

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().height(height)) {
            if (axes) {
                Column(
                    modifier = Modifier.fillMaxHeight().padding(end = 8.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.End,
                ) {
                    AxisLabel(formatEuros(top))
                    AxisLabel(formatEuros(top / 2))
                    AxisLabel("0")
                }
            }
            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .semantics { contentDescription = description }
                    .testTag(UiTags.PLACEMENT_CHART),
            ) {
                fun x(month: Int) = month.toFloat() / lastMonth * size.width
                fun y(value: Long) = size.height - value.toFloat() / top * size.height
                fun area(value: (Int) -> Long): Path = Path().apply {
                    moveTo(0f, size.height)
                    curve.forEachIndexed { index, point -> lineTo(x(point.month), y(value(index))) }
                    lineTo(x(curve.last().month), size.height)
                    close()
                }
                fun line(points: List<PlacementPoint>): Path = Path().apply {
                    points.forEachIndexed { index, point ->
                        if (index == 0) moveTo(x(point.month), y(point.capital)) else lineTo(x(point.month), y(point.capital))
                    }
                }
                if (axes) {
                    drawLine(baseline.copy(alpha = 0.4f), Offset(0f, 0f), Offset(size.width, 0f))
                    drawLine(baseline.copy(alpha = 0.4f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2))
                }
                drawPath(area { curve[it].capital }, colors.capital.copy(alpha = 0.22f))
                drawPath(area { curve[it].paid }, colors.paid.copy(alpha = 0.30f))
                if (withWithdrawals) {
                    drawPath(
                        line(curve),
                        colors.capital.copy(alpha = 0.5f),
                        style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))),
                    )
                    drawPath(line(curveWith), colors.capital, style = Stroke(width = 2.5.dp.toPx()))
                } else if (axes) {
                    drawPath(line(curve), colors.capital, style = Stroke(width = 2.5.dp.toPx()))
                }
                drawLine(baseline, Offset(0f, size.height), Offset(size.width, size.height))
            }
        }
        if (axes) {
            val start = LocalDate.now().year
            Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                AxisLabel(start.toString())
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { AxisLabel((start + (years + 1) / 2).toString()) }
                AxisLabel((start + years).toString())
            }
        }
    }
}

@Composable
private fun AxisLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
