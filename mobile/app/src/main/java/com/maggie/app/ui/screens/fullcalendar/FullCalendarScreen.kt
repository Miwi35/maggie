package com.maggie.app.ui.screens.fullcalendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.ExpandedEvent
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullCalendarScreen(
    viewModel: FullCalendarViewModel = koinViewModel(),
    onCreateEvent: () -> Unit = {},
    onCreateTask: () -> Unit = {},
    onEventClick: (ExpandedEvent) -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    var fabExpanded by remember { mutableStateOf(false) }
    var showAgendaCreate by remember { mutableStateOf(false) }
    var showGoogleImport by remember { mutableStateOf(false) }
    var moreMenuExpanded by remember { mutableStateOf(false) }
    var exportMenuAgendaId by remember { mutableStateOf<String?>(null) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    text = "Agendas",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                )

                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(uiState.agendas) { agenda ->
                        val color = try {
                            Color(android.graphics.Color.parseColor(agenda.color))
                        } catch (_: Exception) {
                            MaterialTheme.colorScheme.primary
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.toggleAgenda(agenda.id) }
                                .padding(horizontal = 12.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = agenda.id in uiState.enabledAgendas,
                                onCheckedChange = { viewModel.toggleAgenda(agenda.id) },
                            )
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(color),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = agenda.name,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            if (agenda.googleCalendarId != null) {
                                Icon(
                                    Icons.Outlined.Sync,
                                    contentDescription = "Synchro Google",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            if (!agenda.isDefault) {
                                IconButton(
                                    onClick = { viewModel.deleteAgenda(agenda.id) },
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Supprimer",
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                            if (agenda.googleCalendarId == null) {
                                IconButton(
                                    onClick = {
                                        exportMenuAgendaId = agenda.id
                                        viewModel.exportToGoogleCalendar(agenda.id)
                                    },
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(
                                        Icons.Outlined.CloudUpload,
                                        contentDescription = "Exporter vers Google",
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = {
                        showAgendaCreate = true
                        scope.launch { drawerState.close() }
                    }) {
                        Text("+ Agenda")
                    }
                    TextButton(onClick = {
                        showGoogleImport = true
                        viewModel.loadGoogleCalendars()
                        scope.launch { drawerState.close() }
                    }) {
                        Text("Importer Google")
                    }
                }
            }
        },
    ) {
        Scaffold(
            floatingActionButton = {
                Column {
                    DropdownMenu(
                        expanded = fabExpanded,
                        onDismissRequest = { fabExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Événement") },
                            onClick = {
                                fabExpanded = false
                                onCreateEvent()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Tâche") },
                            onClick = {
                                fabExpanded = false
                                onCreateTask()
                            },
                        )
                    }
                    FloatingActionButton(
                        onClick = { fabExpanded = !fabExpanded },
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Créer")
                    }
                }
            },
        ) { padding ->
            PullToRefreshBox(
                isRefreshing = uiState.isLoading,
                onRefresh = { viewModel.refresh() },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            CalendarToolbar(
                                title = viewModel.getTitle(),
                                viewType = uiState.viewType,
                                onViewTypeChange = { viewModel.setViewType(it) },
                                onNavigateBackward = { viewModel.navigateBackward() },
                                onNavigateForward = { viewModel.navigateForward() },
                                onGoToToday = { viewModel.goToToday() },
                            )
                        }
                        Box {
                            IconButton(onClick = { moreMenuExpanded = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "Options")
                            }
                            DropdownMenu(
                                expanded = moreMenuExpanded,
                                onDismissRequest = { moreMenuExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Agendas") },
                                    onClick = {
                                        moreMenuExpanded = false
                                        scope.launch { drawerState.open() }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Importer Google Calendar") },
                                    onClick = {
                                        moreMenuExpanded = false
                                        showGoogleImport = true
                                        viewModel.loadGoogleCalendars()
                                    },
                                )
                            }
                        }
                    }

                    when (uiState.viewType) {
                        CalendarViewType.MONTH -> MonthCalendarView(
                            currentDate = uiState.currentDate,
                            events = uiState.expandedEvents,
                            onDateSelected = { viewModel.navigateToDate(it) },
                            onEventClick = onEventClick,
                            onMonthChange = { yearMonth ->
                                viewModel.navigateToDate(yearMonth.atDay(1))
                            },
                        )
                        CalendarViewType.WEEK -> WeekTimelineView(
                            currentDate = uiState.currentDate,
                            events = uiState.expandedEvents,
                            onNavigateForward = { viewModel.navigateForward() },
                            onNavigateBackward = { viewModel.navigateBackward() },
                            onEventClick = onEventClick,
                        )
                        CalendarViewType.DAY -> DayTimelineView(
                            currentDate = uiState.currentDate,
                            events = uiState.expandedEvents,
                            onNavigateForward = { viewModel.navigateForward() },
                            onNavigateBackward = { viewModel.navigateBackward() },
                            onEventClick = onEventClick,
                        )
                    }
                }
            }
        }
    }

    if (showAgendaCreate) {
        AgendaCreateDialog(
            onConfirm = { name, color, description ->
                viewModel.createAgenda(name, color, description)
                showAgendaCreate = false
            },
            onDismiss = { showAgendaCreate = false },
        )
    }

    if (showGoogleImport) {
        GoogleCalendarDialog(
            calendars = uiState.googleCalendars,
            isLoading = uiState.isLoadingGoogle,
            onImport = { cal ->
                viewModel.importGoogleCalendar(cal.id, cal.summary, cal.backgroundColor)
                showGoogleImport = false
            },
            onDismiss = { showGoogleImport = false },
        )
    }
}
