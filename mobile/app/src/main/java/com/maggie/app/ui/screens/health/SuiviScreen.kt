package com.maggie.app.ui.screens.health

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.EmptyState
import com.maggie.app.ui.components.ErrorSnackbar
import com.maggie.app.ui.components.MaggieCard
import com.maggie.app.ui.components.ModuleBreadcrumb
import com.maggie.app.ui.layout.WindowWidth
import com.maggie.app.ui.layout.rememberAppLayout
import com.maggie.app.ui.navigation.HealthSection
import com.maggie.app.ui.navigation.LocalModuleBar
import com.maggie.app.ui.theme.MaggieTokens
import com.maggie.app.ui.theme.isMaggieDark
import com.maggie.app.ui.uiTagRoot

/**
 * « Santé › Suivi » (MAG-706, mockups Suivi-Tel and Suivi-Tablette): the period selector — under the header on a
 * phone, in it on a tablet — and the Poids card: the weighings as dots (7 j, 30 j), the 7-day trend in coral, the
 * column chosen with its values in the header. A long press on a weighed day sets its weighing aside. The other cards
 * of the mockups (Nutrition, Pas, Énergie, Moral · fatigue) are not delivered: no placeholder stands for them.
 */
@Composable
fun SuiviScreen(
    viewModel: SuiviViewModel,
    onSelectSection: (HealthSection) -> Unit = {},
) {
    val expanded = rememberAppLayout().width == WindowWidth.EXPANDED
    val bar = LocalModuleBar.current
    val uiState by viewModel.uiState.collectAsState()
    Scaffold(
        topBar = {
            ModuleBreadcrumb(
                module = "Santé",
                sections = HealthSection.entries.map { it.breadcrumb },
                currentKey = HealthSection.SUIVI.key,
                onSelectSection = { picked ->
                    HealthSection.byKey(picked.key)?.takeIf { it != HealthSection.SUIVI }?.let(onSelectSection)
                },
                onOpenDrawer = bar.onOpenDrawer,
                actions = {
                    if (expanded) {
                        PeriodSelector(uiState.period, viewModel::selectPeriod, segmentWidth = 72.dp, height = 40.dp)
                    }
                    bar.actions(this)
                },
            )
        },
    ) { paddingValues ->
        SuiviContent(viewModel, expanded, Modifier.padding(paddingValues))
    }
}

@Composable
private fun SuiviContent(viewModel: SuiviViewModel, expanded: Boolean, modifier: Modifier = Modifier) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val today = viewModel.today
    val chart = remember(uiState.measurements, uiState.period, today) {
        weightChart(uiState.measurements, uiState.period, today, viewModel.zone)
    }

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

    Box(modifier = modifier.fillMaxSize().testTag(UiTags.SUIVI)) {
        when {
            uiState.isLoading && !uiState.hasLoaded -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            !uiState.hasLoaded -> EmptyState(
                modifier = Modifier.testTag(UiTags.SUIVI_EMPTY),
                title = "Pesées indisponibles",
                description = SuiviViewModel.MESSAGE_LOAD,
                actionLabel = "Réessayer",
                onAction = viewModel::refresh,
            )
            expanded -> Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                    WeightCard(chart, uiState.selectedIndex, viewModel::selectColumn, viewModel::exclude, large = true)
                }
                // The right column of the mockup holds Nutrition and Énergie, not delivered: it stays empty.
                Spacer(modifier = Modifier.weight(1f))
            }
            else -> Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PeriodSelector(uiState.period, viewModel::selectPeriod, segmentWidth = null, height = 36.dp)
                WeightCard(chart, uiState.selectedIndex, viewModel::selectColumn, viewModel::exclude, large = false)
            }
        }
        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

/** « 7 j · 30 j · 90 j · 1 an », a radio group: equal segments across the phone, 72 dp each in the tablet's header. */
@Composable
private fun PeriodSelector(selected: SuiviPeriod, onSelect: (SuiviPeriod) -> Unit, segmentWidth: Dp?, height: Dp) {
    val dark = isMaggieDark()
    val track = if (dark) Color(0xFF15141E) else MaggieTokens.surfaceLight.track
    val surface = if (dark) MaggieTokens.surfaceDark else MaggieTokens.surfaceLight
    val chosen = if (dark) surface.raised else surface.paper
    Row(
        modifier = Modifier
            .then(if (segmentWidth == null) Modifier.fillMaxWidth() else Modifier)
            .clip(RoundedCornerShape(999.dp))
            .background(track)
            .padding(4.dp)
            .selectableGroup()
            .semantics { contentDescription = "Période" }
            .testTag(UiTags.SUIVI_PERIODS),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SuiviPeriod.entries.forEach { period ->
            val isSelected = period == selected
            Box(
                modifier = Modifier
                    .then(if (segmentWidth == null) Modifier.weight(1f) else Modifier.width(segmentWidth))
                    .height(height)
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (isSelected) chosen else Color.Transparent)
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(period) })
                    .testTag(UiTags.suiviPeriod(period.key)),
                contentAlignment = Alignment.Center,
            ) {
                Text(period.label, fontSize = 14.sp, color = if (isSelected) surface.text else surface.caption)
            }
        }
    }
}

/**
 * The Poids card: « Poids » and the chosen column's caption, its weight, trend and body fat, the chart, and the axis —
 * first column, the trend's change, last column.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WeightCard(
    chart: WeightChart,
    selectedIndex: Int?,
    onSelect: (Int) -> Unit,
    onExclude: (String) -> Unit,
    large: Boolean,
) {
    val columns = chart.columns
    val selected = selectedIndex?.takeIf { it in columns.indices } ?: columns.lastIndex
    val column = columns.getOrNull(selected)
    val surface = if (isMaggieDark()) MaggieTokens.surfaceDark else MaggieTokens.surfaceLight
    val accent = MaterialTheme.colorScheme.primary
    var menuFor by remember(chart) { mutableStateOf<Int?>(null) }

    MaggieCard(modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Poids" }.testTag(UiTags.WEIGHT_CARD)) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (large) 10.dp else 8.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Text("Poids", fontSize = if (large) 15.sp else 14.sp, color = surface.textMuted)
                Text(column?.label.orEmpty(), fontSize = 12.sp, color = surface.caption)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(if (large) 12.dp else 10.dp), verticalAlignment = Alignment.Bottom) {
                Text(
                    buildAnnotatedString {
                        val weight = column?.weightKg
                        if (weight == null) {
                            append(DASH)
                        } else {
                            append(formatKg(weight))
                            withStyle(SpanStyle(fontSize = if (large) 16.sp else 15.sp)) { append(" kg") }
                        }
                    },
                    fontSize = if (large) 30.sp else 26.sp,
                    fontWeight = FontWeight.Light,
                    modifier = Modifier.testTag(UiTags.WEIGHT_VALUE),
                )
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = accent)) { append(DASH) }
                        append(" " + (column?.trendKg?.let { "${formatKg(it)} kg" } ?: DASH))
                    },
                    fontSize = if (large) 14.sp else 13.sp,
                    color = surface.textMuted,
                )
                Text(
                    "· " + (column?.bodyFatPercent?.let { "${formatKg(it)} % MG" } ?: "$DASH MG"),
                    fontSize = if (large) 14.sp else 13.sp,
                    color = surface.textMuted,
                )
            }
            Box(modifier = Modifier.fillMaxWidth().height(if (large) 180.dp else 76.dp)) {
                Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(if (large) 2.dp else 1.dp)) {
                    columns.forEachIndexed { index, col ->
                        val retained = col.retained
                        val canExclude = chart.period.perDay && retained != null
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (index == selected) surface.text.copy(alpha = 0.07f) else Color.Transparent)
                                .semantics(mergeDescendants = true) {
                                    contentDescription = col.description
                                    this.selected = index == selected
                                }
                                .combinedClickable(
                                    onClick = { onSelect(index) },
                                    onLongClickLabel = if (canExclude) "Écarter la pesée" else null,
                                    onLongClick = if (canExclude) {
                                        {
                                            onSelect(index)
                                            menuFor = index
                                        }
                                    } else {
                                        null
                                    },
                                )
                                .testTag(UiTags.weightColumn(index)),
                        ) {
                            if (menuFor == index && retained != null) {
                                DropdownMenu(expanded = true, onDismissRequest = { menuFor = null }, modifier = Modifier.uiTagRoot()) {
                                    DropdownMenuItem(
                                        text = { Text("Écarter") },
                                        onClick = {
                                            menuFor = null
                                            onExclude(retained.id)
                                        },
                                        modifier = Modifier.testTag(UiTags.WEIGHT_EXCLUDE),
                                    )
                                }
                            }
                        }
                    }
                }
                WeightCurve(chart, dots = surface.caption, trend = accent, large = large, modifier = Modifier.fillMaxSize())
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(chart.from, fontSize = if (large) 12.sp else 11.sp, color = surface.caption)
                Text(chart.delta, fontSize = if (large) 12.sp else 11.sp, color = surface.caption)
                Text(chart.to, fontSize = if (large) 12.sp else 11.sp, color = surface.caption)
            }
        }
    }
}

/**
 * The dots and the trend, drawn over the columns: each value at the centre of its column, the range of
 * [WeightChart.range]. The trend bridges the columns without one, as the mockup does; a weighing set aside is a
 * dimmed dot, held inside the range so a wrong value does not flatten the others.
 */
@Composable
private fun WeightCurve(chart: WeightChart, dots: Color, trend: Color, large: Boolean, modifier: Modifier = Modifier) {
    val range = chart.range ?: return
    val n = chart.columns.size
    Canvas(modifier = modifier) {
        val span = (range.endInclusive - range.start).takeIf { it > 0 } ?: 1.0
        fun x(i: Int) = ((i + 0.5f) * size.width / n)
        fun y(v: Double) = (size.height - ((v.coerceIn(range) - range.start) / span) * size.height).toFloat()
        val dotRadius = (if (large) 3.dp else 2.5.dp).toPx()

        if (chart.period.perDay) {
            chart.columns.forEachIndexed { i, col ->
                col.excludedWeights.forEach { drawCircle(dots.copy(alpha = 0.3f), dotRadius, Offset(x(i), y(it))) }
                col.weightKg?.let { drawCircle(dots, dotRadius, Offset(x(i), y(it))) }
            }
        }
        val path = Path()
        var started = false
        chart.columns.forEachIndexed { i, col ->
            val v = col.trendKg ?: return@forEachIndexed
            if (started) path.lineTo(x(i), y(v)) else path.moveTo(x(i), y(v))
            started = true
        }
        if (started) {
            drawPath(
                path,
                trend,
                style = Stroke(width = (if (large) 3.dp else 2.5.dp).toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}
