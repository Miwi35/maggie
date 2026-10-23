package com.maggie.app.ui.screens.finance

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.Scenario
import com.maggie.app.data.model.ScenarioComparison
import com.maggie.app.data.model.orderedScenarios
import com.maggie.app.data.model.scenarioCurrentSubtitle
import com.maggie.app.data.model.scenarioCurrentTitle
import com.maggie.app.data.model.scenarioCurveDescription
import com.maggie.app.data.model.scenarioCurveSpan
import com.maggie.app.data.model.scenarioKindLabel
import com.maggie.app.data.model.scenarioMonthLabel
import com.maggie.app.data.model.scenarioNLabel
import com.maggie.app.data.model.scenarioTableRows
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.theme.chartColor
import com.maggie.app.ui.uiTagRoot

/** What the panels do with a scenario; [ScenarioActions.of] wires them to the ViewModel. */
class ScenarioActions(
    val onMakeCurrent: (String) -> Unit,
    val onClearCurrent: () -> Unit,
    val onDuplicate: (String) -> Unit,
    val onToggleCompared: (String) -> Unit,
    val onClearComparison: () -> Unit,
    val onAdd: () -> Unit,
    val onEdit: (String) -> Unit,
    val onDelete: (String) -> Unit,
    val onDismiss: (String) -> Unit,
    val onRetry: () -> Unit,
) {
    companion object {
        fun of(viewModel: ScenarioViewModel) = ScenarioActions(
            onMakeCurrent = viewModel::makeCurrent,
            onClearCurrent = viewModel::clearCurrent,
            onDuplicate = viewModel::duplicate,
            onToggleCompared = viewModel::toggleCompared,
            onClearComparison = viewModel::clearComparison,
            onAdd = { viewModel.openEditor(null) },
            onEdit = viewModel::openEditor,
            onDelete = viewModel::delete,
            onDismiss = viewModel::dismissSuggestion,
            onRetry = viewModel::refresh,
        )
    }
}

/**
 * The phone's way into the scenarios: « Scénario courant · Équilibré » and its date N above the planning,
 * or « Aucun scénario courant » when the owner chose none. A tap opens the sheet « Scénarios ».
 */
@Composable
fun ScenarioCurrentCard(state: ScenarioUiState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val unavailable = !state.hasLoaded && state.error != null
    Card(onClick = onClick, modifier = modifier.fillMaxWidth().testTag(UiTags.SCENARIO_CURRENT_CARD)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (unavailable) "Scénarios" else if (state.hasLoaded) scenarioCurrentTitle(state.current) else "Scénarios…",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    if (unavailable) "Indisponibles" else if (state.hasLoaded) scenarioCurrentSubtitle(state.current) else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

/** The bottom sheet « Scénarios » of the phone: the list, then the comparison. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScenarioSheet(state: ScenarioUiState, actions: ScenarioActions, onDismiss: () -> Unit) {
    ModalBottomSheet(
        modifier = Modifier.uiTagRoot(),
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        ScenarioPanel(
            state = state,
            actions = actions,
            manage = false,
            modifier = Modifier.testTag(UiTags.SCENARIO_SHEET).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/**
 * The scenarios, as the sheet of the phone and the panel of the tablet show them: the current one, every scenario
 * with « Rendre courant » and « Dupliquer », « + Scénario », and the comparison of the ticked ones. [manage] (the
 * tablet) adds editing and deleting. Statements only: nothing is called better, nothing is explained.
 */
@Composable
fun ScenarioPanel(state: ScenarioUiState, actions: ScenarioActions, manage: Boolean, modifier: Modifier = Modifier) {
    var toDelete by remember { mutableStateOf<Scenario?>(null) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Scénarios", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = actions.onAdd, modifier = Modifier.testTag(UiTags.SCENARIO_ADD)) { Text("+ Scénario") }
        }
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(UiTags.SCENARIO_ERROR))
        }
        state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

        when {
            !state.hasLoaded && state.isLoading -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            !state.hasLoaded -> TextButton(onClick = actions.onRetry) { Text("Réessayer") }
            else -> {
                CurrentBlock(state.current, manage, actions)
                orderedScenarios(state.scenarios).forEach { scenario ->
                    ScenarioRow(
                        scenario = scenario,
                        gap = state.gaps[scenario.id].takeIf { !scenario.current },
                        busy = state.busyId != null,
                        compared = scenario.id in state.selected,
                        manage = manage,
                        actions = actions,
                        onAskDelete = { toDelete = scenario },
                    )
                }
                ComparisonBlock(state, actions)
            }
        }
    }

    toDelete?.let { scenario ->
        AlertDialog(
            modifier = Modifier.uiTagRoot(),
            onDismissRequest = { toDelete = null },
            title = { Text("Supprimer « ${scenario.name} » ?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        toDelete = null
                        actions.onDelete(scenario.id)
                    },
                    modifier = Modifier.testTag(UiTags.SCENARIO_DELETE_CONFIRM),
                ) { Text("Supprimer", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Annuler") } },
        )
    }
}

/** « Scénario courant · Équilibré » with its N; « Aucun scénario courant » and the way to pick one when there is none. */
@Composable
private fun CurrentBlock(current: Scenario?, manage: Boolean, actions: ScenarioActions) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (current == null) {
                Text("Aucun scénario courant", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag(UiTags.SCENARIO_NONE))
                Text("Choisir un scénario", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(scenarioCurrentTitle(current), style = MaterialTheme.typography.titleMedium)
                Text(scenarioNLabel(current.nDate), style = MaterialTheme.typography.bodyMedium)
                if (manage) {
                    TextButton(onClick = actions.onClearCurrent, modifier = Modifier.testTag(UiTags.SCENARIO_CLEAR_CURRENT)) {
                        Text("Ne plus avoir de scénario courant")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScenarioRow(
    scenario: Scenario,
    gap: String?,
    busy: Boolean,
    compared: Boolean,
    manage: Boolean,
    actions: ScenarioActions,
    onAskDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().testTag(UiTags.scenarioRow(scenario.id))) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            scenarioKindLabel(scenario)?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(scenario.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (scenario.current) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.testTag(UiTags.scenarioBadge(scenario.id)),
                    ) {
                        Text("Courant", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp))
                    }
                }
            }
            Text(scenarioNLabel(scenario.nDate), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(UiTags.scenarioN(scenario.id)))
            // Worded by the API, shown as it is.
            gap?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag(UiTags.scenarioGap(scenario.id)))
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = compared, onCheckedChange = { actions.onToggleCompared(scenario.id) }, modifier = Modifier.testTag(UiTags.scenarioCompare(scenario.id)))
                    Text("Comparer", style = MaterialTheme.typography.labelLarge)
                }
                if (!scenario.current) {
                    Button(onClick = { actions.onMakeCurrent(scenario.id) }, enabled = !busy, modifier = Modifier.testTag(UiTags.scenarioMakeCurrent(scenario.id))) {
                        Text("Rendre courant")
                    }
                }
                if (scenario.isAdvisor) {
                    TextButton(onClick = { actions.onEdit(scenario.id) }, modifier = Modifier.testTag(UiTags.scenarioEdit(scenario.id))) { Text("Voir") }
                    TextButton(onClick = { actions.onDismiss(scenario.id) }, enabled = !busy, modifier = Modifier.testTag(UiTags.scenarioDismiss(scenario.id))) { Text("Ignorer") }
                } else {
                    TextButton(onClick = { actions.onDuplicate(scenario.id) }, enabled = !busy, modifier = Modifier.testTag(UiTags.scenarioDuplicate(scenario.id))) { Text("Dupliquer") }
                    if (manage && scenario.isUser) {
                        TextButton(onClick = { actions.onEdit(scenario.id) }, modifier = Modifier.testTag(UiTags.scenarioEdit(scenario.id))) { Text("Modifier") }
                        TextButton(onClick = onAskDelete, enabled = !busy, modifier = Modifier.testTag(UiTags.scenarioDelete(scenario.id))) {
                            Text("Supprimer", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

// ---- The comparison -------------------------------------------------------------------------------------------------

/** Two to six curves on one axis, the figures side by side, and the gaps of N in the API's words. */
@Composable
private fun ComparisonBlock(state: ScenarioUiState, actions: ScenarioActions) {
    if (state.selected.isEmpty()) return
    HorizontalDivider()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Comparaison", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = actions.onClearComparison, modifier = Modifier.testTag(UiTags.SCENARIO_COMPARE_CLEAR)) { Text("Effacer") }
    }
    val comparison = state.comparison
    when {
        state.selected.size < 2 -> Text("Cochez un second scénario.", style = MaterialTheme.typography.bodyMedium)
        state.comparisonError != null ->
            Text(state.comparisonError, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(UiTags.SCENARIO_COMPARE_ERROR))
        comparison == null || state.isComparing -> Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else -> ComparisonContent(comparison)
    }
}

@Composable
internal fun ComparisonContent(comparison: ScenarioComparison) {
    // The reference first, then the others as they were ticked.
    val scenarios = comparison.scenarios
    val colors = scenarioCurveColors()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScenarioCurves(scenarios, colors)
        ScenarioTable(scenarios, colors)
        val sentences = scenarios.mapNotNull { s -> comparison.differenceOf(s.id)?.let { s.name to it } }
        if (sentences.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                sentences.forEach { (name, sentence) ->
                    Text("$name · $sentence", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun scenarioCurveColors(): List<Color> {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return List(6) { chartColor(it % 5 + 1, dark) }
}

/** The coverage of the train de vie of each scenario, superimposed on a fixed 0–100 % axis. */
@Composable
internal fun ScenarioCurves(scenarios: List<Scenario>, colors: List<Color>) {
    val span = scenarioCurveSpan(scenarios)
    val axis = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = MaterialTheme.typography.labelSmall
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val description = scenarios.joinToString(" ") { scenarioCurveDescription(it) }

    Column(modifier = Modifier.fillMaxWidth().testTag(UiTags.SCENARIO_CURVES)) {
        if (span == null) {
            Text("Aucune courbe de couverture à comparer.", style = MaterialTheme.typography.bodyMedium)
        } else {
            Row(modifier = Modifier.fillMaxWidth().semantics { contentDescription = description }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(modifier = Modifier.height(140.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    listOf("100 %", "50 %", "0 %").forEach { Text(it, style = labelStyle, color = labelColor) }
                }
                Canvas(modifier = Modifier.weight(1f).height(140.dp)) {
                    listOf(0f, 0.5f, 1f).forEach { level ->
                        val y = size.height * (1f - level)
                        drawLine(axis, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                    }
                    scenarios.forEachIndexed { index, scenario ->
                        val color = colors[index % colors.size]
                        val points = scenario.curve.mapNotNull { point ->
                            span.position(point.month)?.let { Offset(size.width * it, size.height * (1f - point.coverage.toFloat().coerceIn(0f, 1f))) }
                        }
                        if (points.size == 1) {
                            drawCircle(color, 3.dp.toPx(), points.first())
                        } else if (points.size > 1) {
                            val path = Path().apply {
                                moveTo(points.first().x, points.first().y)
                                points.drop(1).forEach { lineTo(it.x, it.y) }
                            }
                            drawPath(path, color, style = Stroke(width = 2.5.dp.toPx()))
                        }
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(start = 36.dp, top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(scenarioMonthLabel(span.first.toString()), style = labelStyle, color = labelColor)
                Text(scenarioMonthLabel(span.last.toString()), style = labelStyle, color = labelColor)
            }
        }
        Legend(scenarios, colors)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend(scenarios: List<Scenario>, colors: List<Color>) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        scenarios.forEachIndexed { index, scenario ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(10.dp).background(colors[index % colors.size], CircleShape))
                Text(scenario.name, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** One column per scenario, one row per figure; scrolls sideways when the scenarios do not fit. */
@Composable
internal fun ScenarioTable(scenarios: List<Scenario>, colors: List<Color>) {
    val rows = scenarioTableRows(scenarios)
    Column(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag(UiTags.SCENARIO_TABLE)) {
        Row {
            Spacer(Modifier.width(LABEL_WIDTH))
            scenarios.forEachIndexed { index, scenario ->
                Text(
                    scenario.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors[index % colors.size],
                    modifier = Modifier.width(CELL_WIDTH).padding(end = 8.dp),
                )
            }
        }
        rows.forEach { row ->
            HorizontalDivider()
            Row(modifier = Modifier.padding(vertical = 6.dp)) {
                Text(row.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(LABEL_WIDTH))
                row.cells.forEach { cell ->
                    Text(cell, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(CELL_WIDTH).padding(end = 8.dp))
                }
            }
        }
    }
}

private val LABEL_WIDTH = 104.dp
private val CELL_WIDTH = 112.dp
