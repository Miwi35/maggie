package com.maggie.app.ui.screens.health

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maggie.app.data.model.Workout
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.EmptyState
import com.maggie.app.ui.components.ErrorSnackbar
import com.maggie.app.ui.components.MaggieCard
import com.maggie.app.ui.components.ModuleBreadcrumb
import com.maggie.app.ui.layout.WindowWidth
import com.maggie.app.ui.layout.rememberAppLayout
import com.maggie.app.ui.navigation.HealthSection
import com.maggie.app.ui.navigation.LocalModuleBar
import com.maggie.app.ui.uiTagRoot
import java.time.LocalDate
import java.time.ZoneId

/**
 * « Santé › Sport » (MAG-705): « Cette semaine » (seven days, done filled, planned ringed), then the sessions, newest
 * first. A phone lists the sessions done and opens one in a sheet; an expanded window lists them all (planned ones
 * too) beside the detail of the selected one, the first by default. « + » opens the form.
 */
@Composable
fun SportScreen(
    viewModel: SportViewModel,
    onOpenForm: (workoutId: String?) -> Unit,
) {
    val expanded = rememberAppLayout().width == WindowWidth.EXPANDED
    val bar = LocalModuleBar.current
    Scaffold(
        topBar = {
            ModuleBreadcrumb(
                module = "Santé",
                sections = HealthSection.entries.map { it.breadcrumb },
                currentKey = HealthSection.SPORT.key,
                onSelectSection = {},
                onOpenDrawer = bar.onOpenDrawer,
                actions = {
                    IconButton(onClick = { onOpenForm(null) }, modifier = Modifier.testTag(UiTags.SPORT_ADD)) {
                        Icon(Icons.Default.Add, contentDescription = "Ajouter une séance")
                    }
                    bar.actions(this)
                },
            )
        },
    ) { paddingValues ->
        SportContent(viewModel, onOpenForm, expanded, Modifier.padding(paddingValues))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SportContent(
    viewModel: SportViewModel,
    onOpenForm: (workoutId: String?) -> Unit,
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val zone = viewModel.zone
    val today = LocalDate.now(viewModel.clock)

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

    // The phone lists the sessions done; the tablet lists every one, planned included (decision 4).
    val listed = if (expanded) uiState.workouts else uiState.workouts.filterNot { it.isPlanned }
    val week = weekOf(uiState.workouts, today, zone)

    Box(modifier = modifier.fillMaxSize().testTag(UiTags.SPORT)) {
        when {
            uiState.isLoading && !uiState.hasLoaded -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            !uiState.hasLoaded -> EmptyState(
                modifier = Modifier.testTag(UiTags.SPORT_EMPTY),
                title = "Séances indisponibles",
                description = SportViewModel.MESSAGE_LOAD,
                actionLabel = "Réessayer",
                onAction = viewModel::refresh,
            )
            listed.isEmpty() -> EmptyState(
                modifier = Modifier.testTag(UiTags.SPORT_EMPTY),
                title = "Aucune séance",
                description = "Ajoutez une séance : un type, une durée, et si vous le voulez une distance et un ressenti.",
                actionLabel = "Ajouter une séance",
                onAction = { onOpenForm(null) },
            )
            expanded -> {
                val shown = listed.firstOrNull { it.id == uiState.selectedId } ?: listed.first()
                Row(modifier = Modifier.fillMaxSize()) {
                    SessionList(
                        week = week,
                        workouts = listed,
                        selectedId = shown.id,
                        zone = zone,
                        highlight = true,
                        onSelect = viewModel::select,
                        modifier = Modifier.width(460.dp).fillMaxHeight(),
                    )
                    Column(
                        modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(24.dp),
                    ) {
                        WorkoutDetail(
                            workout = shown,
                            zone = zone,
                            onEdit = { onOpenForm(shown.id) },
                            onDelete = { viewModel.delete(shown.id) },
                        )
                    }
                }
            }
            else -> SessionList(
                week = week,
                workouts = listed,
                selectedId = null,
                zone = zone,
                highlight = false,
                onSelect = viewModel::select,
                modifier = Modifier.fillMaxSize(),
            )
        }
        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }

    val sheetWorkout = uiState.selected
    if (!expanded && uiState.detailOpen && sheetWorkout != null) {
        ModalBottomSheet(
            onDismissRequest = viewModel::closeDetail,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            modifier = Modifier.uiTagRoot(),
        ) {
            WorkoutDetail(
                workout = sheetWorkout,
                zone = zone,
                onEdit = {
                    viewModel.closeDetail()
                    onOpenForm(sheetWorkout.id)
                },
                onDelete = { viewModel.delete(sheetWorkout.id) },
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp).navigationBarsPadding(),
            )
        }
    }
}

@Composable
private fun SessionList(
    week: WeekSummary,
    workouts: List<Workout>,
    selectedId: String?,
    zone: ZoneId,
    highlight: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.testTag(UiTags.SPORT_LIST),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item(key = "week") { WeekCard(week) }
        item(key = "title") {
            Text(
                "Séances",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp, start = 8.dp),
            )
        }
        items(workouts, key = { it.id }) { workout ->
            WorkoutRow(workout, zone, selected = highlight && workout.id == selectedId) { onSelect(workout.id) }
        }
    }
}

/** « Cette semaine »: Monday to Sunday, a filled day for a session done, a ring for one planned, today circled. */
@Composable
private fun WeekCard(week: WeekSummary) {
    MaggieCard(modifier = Modifier.fillMaxWidth().testTag(UiTags.SPORT_WEEK)) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
                Text("Cette semaine", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(week.counter, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                week.days.forEach { day -> WeekDayPill(day) }
            }
        }
    }
}

@Composable
private fun WeekDayPill(day: WeekDay) {
    val accent = MaterialTheme.colorScheme.primary
    val done = day.state == DayState.DONE
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.width(40.dp).semantics { contentDescription = day.description }.testTag(UiTags.sportWeekDay(day.date.dayOfMonth)),
    ) {
        Text(day.letter, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // The outer ring is today's; the inner disc says what the day holds.
        Box(
            modifier = Modifier.size(38.dp).then(if (day.isToday) Modifier.border(BorderStroke(1.5.dp, accent), CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier.size(32.dp).clip(CircleShape)
                    .background(if (done) accent else androidx.compose.ui.graphics.Color.Transparent)
                    .then(if (day.state == DayState.PLANNED) Modifier.border(BorderStroke(1.5.dp, accent), CircleShape) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    day.date.dayOfMonth.toString(),
                    fontSize = 13.sp,
                    color = when (day.state) {
                        DayState.DONE -> MaterialTheme.colorScheme.onPrimary
                        DayState.PLANNED -> accent
                        DayState.NONE -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/** A session: its type tile, title, « Lun. 20 oct. · 32 min · 5,4 km », and on the right its kcal above its source. */
@Composable
private fun WorkoutRow(workout: Workout, zone: ZoneId, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .testTag(UiTags.workoutRow(workout.id)),
    ) {
        WorkoutIcon(workout.type, size = 38)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(workout.title, fontSize = 15.sp, maxLines = 1)
            Text(
                workoutMeta(workout, zone),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            workout.kcal?.let { Text(formatWorkoutKcal(it), fontSize = 14.sp, fontWeight = FontWeight.Normal) }
            SourcePill(workout.source)
        }
    }
}

