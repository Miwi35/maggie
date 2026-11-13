package com.maggie.app.ui.screens.health

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
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
import java.time.LocalDate
import com.maggie.app.data.model.DailyEnergy
import com.maggie.app.data.model.HealthDay
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.ConfirmDeleteDialog
import com.maggie.app.ui.components.EmptyState
import com.maggie.app.ui.components.ErrorSnackbar
import com.maggie.app.ui.components.MaggieCard
import com.maggie.app.ui.components.ModuleBreadcrumb
import com.maggie.app.ui.layout.WindowWidth
import com.maggie.app.ui.layout.rememberAppLayout
import com.maggie.app.ui.navigation.HealthSection
import com.maggie.app.ui.navigation.LocalModuleBar
import com.maggie.app.ui.theme.MaggieTokens
import com.maggie.app.ui.theme.chartColor
import com.maggie.app.ui.theme.isMaggieDark
import com.maggie.app.ui.theme.maggieSurface
import com.maggie.app.ui.uiTagRoot

/**
 * « Santé › Suivi » (MAG-706, mockups Suivi-Tel and Suivi-Tablette): the period selector — under the header on a
 * phone, in it on a tablet — and the Poids card: the weighings as dots (7 j, 30 j), the 7-day trend in coral, the
 * column chosen with its values in the header. A long press on a weighed day sets its weighing aside. The Pas and Énergie cards
 * (MAG-746) follow the same period and the same day. Today's « Bilan du jour » (MAG-748) opens the screen — above
 * the period selector on a phone, atop the right column on a tablet — and a tap on it opens Nutrition. The fourth
 * card, « Moral · fatigue » (MAG-750), draws the journal's scales over the same period — last on a phone, under Poids
 * on a tablet.
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
        SuiviContent(viewModel, expanded, onSelectSection, Modifier.padding(paddingValues))
    }
}

@Composable
private fun SuiviContent(
    viewModel: SuiviViewModel,
    expanded: Boolean,
    onSelectSection: (HealthSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val today = viewModel.today
    val chart = remember(uiState.measurements, uiState.period, today) {
        weightChart(uiState.measurements, uiState.period, today, viewModel.zone)
    }
    val energy = remember(uiState.energy, uiState.period, today) { energyWindow(uiState.energy, uiState.period, today) }
    val day = selectedEnergy(energy, uiState.selectedDate)
    val moods = remember(uiState.healthDays, uiState.period, today) { moodWindow(uiState.healthDays, uiState.period, today) }
    // Suivi opens on the day's bilan (MAG-748): a tap opens Nutrition, the profile hint Configuration.
    val bilan: @Composable () -> Unit = {
        uiState.balance?.let { balance ->
            BalanceCard(
                balance = balance,
                onCompleteProfile = { onSelectSection(HealthSection.CONFIGURATION) },
                compact = true,
                onOpen = { onSelectSection(HealthSection.NUTRITION) },
            )
        }
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
                    WeightCard(chart, uiState.selectedIndex, viewModel::selectColumn, viewModel::exclude, viewModel::delete, large = true)
                    Spacer(Modifier.height(14.dp))
                    MoodCard(moods, uiState, today, viewModel::selectDay, large = true)
                }
                // The right column of the mockup holds Nutrition: its bilan on top, then Pas and Énergie.
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    bilan()
                    StepsCard(energy, day, uiState, today, viewModel::selectDay, large = true)
                    EnergyCard(energy, day, uiState, today, viewModel::selectDay, large = true)
                }
            }
            else -> Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                bilan()
                PeriodSelector(uiState.period, viewModel::selectPeriod, segmentWidth = null, height = 36.dp)
                WeightCard(chart, uiState.selectedIndex, viewModel::selectColumn, viewModel::exclude, viewModel::delete, large = false)
                StepsCard(energy, day, uiState, today, viewModel::selectDay, large = false)
                EnergyCard(energy, day, uiState, today, viewModel::selectDay, large = false)
                MoodCard(moods, uiState, today, viewModel::selectDay, large = false)
            }
        }
        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

/** « 7 j · 30 j · 90 j · 1 an », a radio group: equal segments across the phone, 72 dp each in the tablet's header. */
@Composable
private fun PeriodSelector(selected: SuiviPeriod, onSelect: (SuiviPeriod) -> Unit, segmentWidth: Dp?, height: Dp) {
    val surface = maggieSurface()
    val track = surface.track
    val chosen = if (isMaggieDark()) surface.raised else surface.paper
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
    onDelete: (String) -> Unit,
    large: Boolean,
) {
    val columns = chart.columns
    val selected = selectedIndex?.takeIf { it in columns.indices } ?: columns.lastIndex
    val column = columns.getOrNull(selected)
    val surface = if (isMaggieDark()) MaggieTokens.surfaceDark else MaggieTokens.surfaceLight
    val accent = MaterialTheme.colorScheme.primary
    var menuFor by remember(chart) { mutableStateOf<Int?>(null) }
    var deleteId by remember { mutableStateOf<String?>(null) }

    deleteId?.let { id ->
        ConfirmDeleteDialog(
            title = "Supprimer cette pesée ?",
            onConfirm = {
                deleteId = null
                onDelete(id)
            },
            onDismiss = { deleteId = null },
            confirmLabel = "Supprimer",
        )
    }

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
                                    onLongClickLabel = if (canExclude) "Écarter ou supprimer la pesée" else null,
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
                                    DropdownMenuItem(
                                        text = { Text("Supprimer") },
                                        onClick = {
                                            menuFor = null
                                            deleteId = retained.id
                                        },
                                        modifier = Modifier.testTag(UiTags.WEIGHT_DELETE),
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

private fun dayTitle(day: DailyEnergy?, today: LocalDate): String {
    val date = day?.let { runCatching { LocalDate.parse(it.date) }.getOrNull() } ?: return ""
    return if (date == today) "Aujourd’hui" else dayLabel(date)
}

/** What a card says while the energy is read, or when it could not be: a sober line, never a verdict. */
@Composable
private fun EnergyPlaceholder(title: String, text: String, tag: String, large: Boolean) {
    val surface = if (isMaggieDark()) MaggieTokens.surfaceDark else MaggieTokens.surfaceLight
    MaggieCard(modifier = Modifier.fillMaxWidth().semantics { contentDescription = title }.testTag(tag)) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, fontSize = if (large) 15.sp else 14.sp, color = surface.textMuted)
            Text(text, fontSize = 13.sp, color = surface.caption)
        }
    }
}

/**
 * The bars of a window, one per day, the chosen one shaded; a tap on the chart chooses the day under the finger.
 * [draw] paints a day's bar of [width] at [x].
 */
@Composable
private fun DayBars(
    window: List<DailyEnergy>,
    selected: DailyEnergy?,
    onSelect: (LocalDate) -> Unit,
    height: Dp,
    draw: DrawScope.(day: DailyEnergy, x: Float, width: Float) -> Unit,
) {
    val shade = (if (isMaggieDark()) MaggieTokens.surfaceDark else MaggieTokens.surfaceLight).text.copy(alpha = 0.07f)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(window) {
                detectTapGestures { tap ->
                    val index = (tap.x / size.width * window.size).toInt().coerceIn(0, window.lastIndex)
                    onSelect(LocalDate.parse(window[index].date))
                }
            },
    ) {
        val slot = size.width / window.size
        val bar = (slot * 0.7f).coerceAtLeast(1f)
        window.forEachIndexed { i, day ->
            if (day.date == selected?.date) drawRect(shade, Offset(i * slot, 0f), Size(slot, size.height))
            draw(day, i * slot + (slot - bar) / 2f, bar)
        }
    }
}

/**
 * The Pas card (MAG-746): the steps of each day of the period as bars, the chosen day's figure — today's by default —
 * and the mean of the days that have any. No steps at all in the period: « Aucune donnée de pas », nothing projected.
 */
@Composable
private fun StepsCard(
    window: List<DailyEnergy>,
    day: DailyEnergy?,
    state: SuiviUiState,
    today: LocalDate,
    onSelect: (LocalDate) -> Unit,
    large: Boolean,
) {
    val surface = if (isMaggieDark()) MaggieTokens.surfaceDark else MaggieTokens.surfaceLight
    val accent = MaterialTheme.colorScheme.primary
    when {
        state.energyFailed && !state.energyLoaded ->
            return EnergyPlaceholder("Pas", "Les pas n'ont pas pu être chargés.", UiTags.STEPS_CARD, large)
        !state.energyLoaded -> return EnergyPlaceholder("Pas", "Chargement…", UiTags.STEPS_CARD, large)
        !hasSteps(window) ->
            return EnergyPlaceholder("Pas", "Aucune donnée de pas", UiTags.STEPS_EMPTY, large)
    }
    val top = window.maxOf { it.steps ?: 0 }.coerceAtLeast(1)
    MaggieCard(modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Pas" }.testTag(UiTags.STEPS_CARD)) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (large) 10.dp else 8.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Text("Pas", fontSize = if (large) 15.sp else 14.sp, color = surface.textMuted)
                Text(dayTitle(day, today), fontSize = 12.sp, color = surface.caption)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                Text(
                    day?.steps?.let(::formatCount) ?: DASH,
                    fontSize = if (large) 30.sp else 26.sp,
                    fontWeight = FontWeight.Light,
                    modifier = Modifier.testTag(UiTags.STEPS_VALUE),
                )
                Text(
                    "· moy. " + (averageSteps(window)?.let(::formatCount) ?: DASH) + " /j",
                    fontSize = if (large) 14.sp else 13.sp,
                    color = surface.textMuted,
                )
            }
            DayBars(window, day, onSelect, if (large) 120.dp else 76.dp) { d, x, width ->
                val h = (d.steps ?: 0) / top.toFloat() * size.height
                drawRect(accent, Offset(x, size.height - h), Size(width, h))
            }
        }
    }
}

/**
 * The Énergie card (MAG-746): each day's stack — base, pas, séances — and, for the chosen day, the API's total, never
 * a sum made here. A day without a base (profile incomplete) stacks what exists and shows « — » as its total.
 */
@Composable
private fun EnergyCard(
    window: List<DailyEnergy>,
    day: DailyEnergy?,
    state: SuiviUiState,
    today: LocalDate,
    onSelect: (LocalDate) -> Unit,
    large: Boolean,
) {
    val surface = if (isMaggieDark()) MaggieTokens.surfaceDark else MaggieTokens.surfaceLight
    val colors = MaterialTheme.colorScheme
    val base = surface.caption.copy(alpha = 0.55f)
    val steps = colors.primary
    val workouts = colors.tertiary
    when {
        state.energyFailed && !state.energyLoaded ->
            return EnergyPlaceholder("Énergie", "L'énergie n'a pas pu être chargée.", UiTags.ENERGY_CARD, large)
        !state.energyLoaded -> return EnergyPlaceholder("Énergie", "Chargement…", UiTags.ENERGY_CARD, large)
    }
    val top = maxStack(window).coerceAtLeast(1)
    val parts = day?.let(::energyParts)
    MaggieCard(modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Énergie" }.testTag(UiTags.ENERGY_CARD)) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (large) 10.dp else 8.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Text("Énergie", fontSize = if (large) 15.sp else 14.sp, color = surface.textMuted)
                Text(dayTitle(day, today), fontSize = 12.sp, color = surface.caption)
            }
            Text(
                formatKcal(day?.expendedKcal),
                fontSize = if (large) 30.sp else 26.sp,
                fontWeight = FontWeight.Light,
                modifier = Modifier.testTag(UiTags.ENERGY_TOTAL),
            )
            DayBars(window, day, onSelect, if (large) 120.dp else 76.dp) { d, x, width ->
                val p = energyParts(d)
                var bottom = size.height
                listOf(p.base to base, p.steps to steps, p.workouts to workouts).forEach { (kcal, color) ->
                    val h = kcal / top.toFloat() * size.height
                    drawRect(color, Offset(x, bottom - h), Size(width, h))
                    bottom -= h
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                EnergyLegend("Base", day?.bmrKcal, base, UiTags.ENERGY_BASE)
                EnergyLegend("Pas", day?.stepsKcal, steps, UiTags.ENERGY_STEPS)
                EnergyLegend("Séances", parts?.workouts, workouts, UiTags.ENERGY_WORKOUTS)
            }
        }
    }
}

@Composable
private fun EnergyLegend(label: String, kcal: Int?, color: Color, tag: String) {
    val surface = if (isMaggieDark()) MaggieTokens.surfaceDark else MaggieTokens.surfaceLight
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics(mergeDescendants = true) {}.testTag(tag),
    ) {
        Box(Modifier.width(8.dp).height(8.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Text("$label ${kcal?.let(::formatCount) ?: DASH}", fontSize = 12.sp, color = surface.caption)
    }
}

/**
 * « Moral · fatigue » (MAG-750): the journal's two scales over the period on 0–10 — the mood a solid amber line, the
 * fatigue a dotted blue one, both from the chart palette, never a verdict — and the chosen day's values. A line
 * bridges the days left blank; a tap chooses the day under the finger, as on Pas and Énergie.
 */
@Composable
private fun MoodCard(
    window: List<HealthDay>,
    state: SuiviUiState,
    today: LocalDate,
    onSelect: (LocalDate) -> Unit,
    large: Boolean,
) {
    val surface = if (isMaggieDark()) MaggieTokens.surfaceDark else MaggieTokens.surfaceLight
    val dark = isMaggieDark()
    val mood = chartColor(MOOD_SLOT, dark)
    val fatigue = chartColor(FATIGUE_SLOT, dark)
    val title = "Moral · fatigue"
    when {
        state.healthDaysFailed && !state.healthDaysLoaded ->
            return EnergyPlaceholder(title, "Le journal n'a pas pu être chargé.", UiTags.MOOD_CARD, large)
        !state.healthDaysLoaded -> return EnergyPlaceholder(title, "Chargement…", UiTags.MOOD_CARD, large)
        !hasScales(window) -> return EnergyPlaceholder(title, "Rien de noté sur la période", UiTags.MOOD_EMPTY, large)
    }
    val chosen = window.firstOrNull { it.date == state.selectedDate?.toString() } ?: window.last()
    val chosenDate = LocalDate.parse(chosen.date)
    MaggieCard(modifier = Modifier.fillMaxWidth().semantics { contentDescription = title }.testTag(UiTags.MOOD_CARD)) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (large) 10.dp else 8.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Text(title, fontSize = if (large) 15.sp else 14.sp, color = surface.textMuted)
                Text(if (chosenDate == today) "Aujourd’hui" else dayLabel(chosenDate), fontSize = 12.sp, color = surface.caption)
            }
            val shade = surface.text.copy(alpha = 0.07f)
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (large) 120.dp else 76.dp)
                    .pointerInput(window) {
                        detectTapGestures { tap ->
                            val index = (tap.x / size.width * window.size).toInt().coerceIn(0, window.lastIndex)
                            onSelect(LocalDate.parse(window[index].date))
                        }
                    },
            ) {
                val slot = size.width / window.size
                val selectedIndex = window.indexOf(chosen)
                if (selectedIndex >= 0) drawRect(shade, Offset(selectedIndex * slot, 0f), Size(slot, size.height))
                fun x(i: Int) = (i + 0.5f) * slot
                fun y(v: Int) = size.height - v.coerceIn(0, SCALE_TOP) / SCALE_TOP.toFloat() * size.height
                val width = (if (large) 2.5.dp else 2.dp).toPx()
                scaleLine(window.map { it.mood }, ::x, ::y, mood, Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
                scaleLine(
                    window.map { it.fatigue },
                    ::x,
                    ::y,
                    fatigue,
                    Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 5.dp.toPx()))),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ScaleLegend("Moral", chosen.mood, mood, dashed = false, tag = UiTags.MOOD_VALUE)
                ScaleLegend("Fatigue", chosen.fatigue, fatigue, dashed = true, tag = UiTags.FATIGUE_VALUE)
            }
        }
    }
}

/** One scale's line through the days that have a value; one lone value is a dot. */
private fun DrawScope.scaleLine(values: List<Int?>, x: (Int) -> Float, y: (Int) -> Float, color: Color, stroke: Stroke) {
    val points = values.mapIndexedNotNull { i, v -> v?.let { Offset(x(i), y(it)) } }
    if (points.isEmpty()) return
    if (points.size == 1) {
        drawCircle(color, stroke.width * 1.2f, points.single())
        return
    }
    val path = Path()
    points.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
    drawPath(path, color, style = stroke)
}

@Composable
private fun ScaleLegend(label: String, value: Int?, color: Color, dashed: Boolean, tag: String) {
    val surface = if (isMaggieDark()) MaggieTokens.surfaceDark else MaggieTokens.surfaceLight
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics(mergeDescendants = true) {}.testTag(tag),
    ) {
        Canvas(Modifier.width(16.dp).height(8.dp)) {
            drawLine(
                color,
                Offset(0f, size.height / 2),
                Offset(size.width, size.height / 2),
                strokeWidth = 2.dp.toPx(),
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx())) else null,
            )
        }
        Text("$label ${value ?: DASH}", fontSize = 12.sp, color = surface.caption)
    }
}

/** The palette's gold for the mood, its blue for the fatigue (1-based slots of [MaggieTokens.chartCategorical]). */
private const val MOOD_SLOT = 3
private const val FATIGUE_SLOT = 4

/** The top of the card's scale: 0–10, whatever the period holds. */
private const val SCALE_TOP = 10
