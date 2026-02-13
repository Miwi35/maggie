package com.maggie.app.ui.screens.fullcalendar

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.maggie.app.data.model.ExpandedEvent
import org.koin.androidx.compose.koinViewModel

@Composable
fun FullCalendarScreen(
    viewModel: FullCalendarViewModel = koinViewModel(),
    onCreateEvent: () -> Unit = {},
    onCreateTask: () -> Unit = {},
    onEventClick: (ExpandedEvent) -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    var fabExpanded by remember { mutableStateOf(false) }

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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            CalendarToolbar(
                title = viewModel.getTitle(),
                viewType = uiState.viewType,
                onViewTypeChange = { viewModel.setViewType(it) },
                onNavigateBackward = { viewModel.navigateBackward() },
                onNavigateForward = { viewModel.navigateForward() },
                onGoToToday = { viewModel.goToToday() },
            )

            when (uiState.viewType) {
                CalendarViewType.MONTH -> MonthCalendarView(
                    currentDate = uiState.currentDate,
                    events = uiState.expandedEvents,
                    onDateSelected = { viewModel.navigateToDate(it) },
                    onEventClick = onEventClick,
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
