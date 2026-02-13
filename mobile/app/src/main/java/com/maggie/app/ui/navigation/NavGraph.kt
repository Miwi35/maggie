package com.maggie.app.ui.navigation

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.maggie.app.data.api.EventCreateRequest
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.model.ExpandedEvent
import com.maggie.app.data.model.Task
import com.maggie.app.ui.components.AppDrawerContent
import com.maggie.app.ui.components.ChatBottomBar
import com.maggie.app.ui.components.ChatSheet
import com.maggie.app.ui.components.MaggieTopBar
import com.maggie.app.ui.screens.chat.ChatViewModel
import com.maggie.app.ui.screens.dashboard.DashboardScreen
import com.maggie.app.ui.screens.dashboard.DashboardViewModel
import com.maggie.app.ui.screens.fullcalendar.FullCalendarScreen
import com.maggie.app.ui.screens.fullcalendar.FullCalendarViewModel
import com.maggie.app.ui.screens.shared.EventCreateScreen
import com.maggie.app.ui.screens.shared.EventDetailSheet
import com.maggie.app.ui.screens.shared.EventEditScreen
import com.maggie.app.ui.screens.shared.RecurrenceAction
import com.maggie.app.ui.screens.shared.RecurrenceConfirmDialog
import com.maggie.app.ui.screens.shared.TaskCreateScreen
import com.maggie.app.ui.screens.shared.TaskDetailSheet
import com.maggie.app.ui.screens.shared.TaskEditScreen
import com.maggie.app.ui.screens.login.LoginScreen
import com.maggie.app.ui.screens.login.LoginViewModel
import com.maggie.app.data.repository.EventRepository
import com.maggie.app.data.repository.TaskRepository
import com.maggie.app.data.repository.AgendaRepository
import com.maggie.app.util.RruleUtils
import com.maggie.app.voice.VoiceManager
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.time.Instant

sealed class Screen(val route: String, val label: String) {
    data object Login : Screen("login", "Connexion")
    data object Dashboard : Screen("dashboard", "Tableau de bord")
    data object Calendar : Screen("calendar", "Calendrier")
    data object EventCreate : Screen("event/create", "Nouvel événement")
    data object TaskCreate : Screen("task/create", "Nouvelle tâche")
    data object EventEdit : Screen("event/edit", "Modifier l'événement")
    data object TaskEdit : Screen("task/edit", "Modifier la tâche")
}

private val MAIN_SCREENS = setOf(Screen.Dashboard.route, Screen.Calendar.route)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavGraph() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val authRepository: AuthRepository = koinInject()
    val isAuthenticated by authRepository.isAuthenticated.collectAsState(initial = null)
    val loginViewModel: LoginViewModel = koinViewModel()

    val chatViewModel: ChatViewModel = koinViewModel()
    val dashboardViewModel: DashboardViewModel = koinViewModel()
    val calendarViewModel: FullCalendarViewModel = koinViewModel()
    var showChatSheet by rememberSaveable { mutableStateOf(false) }
    val chatSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val voiceManager: VoiceManager = koinInject()
    var voiceModeActive by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    val eventRepository: EventRepository = koinInject()
    val taskRepository: TaskRepository = koinInject()
    val agendaRepository: AgendaRepository = koinInject()

    // Sheet states (kept as overlays)
    var selectedEvent by remember { mutableStateOf<ExpandedEvent?>(null) }
    var selectedTask by remember { mutableStateOf<Task?>(null) }
    var recurrenceConfirm by remember { mutableStateOf<Pair<ExpandedEvent, Boolean>?>(null) }

    // Transient state for edit screens
    var editingEvent by remember { mutableStateOf<ExpandedEvent?>(null) }
    var editingTask by remember { mutableStateOf<Task?>(null) }

    val calendarUiState by calendarViewModel.uiState.collectAsState()
    val agendas = calendarUiState.agendas

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            voiceModeActive = true
            showChatSheet = true
            voiceManager.startListening()
        }
    }

    // Auth redirect
    LaunchedEffect(isAuthenticated) {
        when (isAuthenticated) {
            false -> navController.navigate(Screen.Login.route) {
                popUpTo(0) { inclusive = true }
            }
            true -> if (currentRoute == Screen.Login.route) {
                navController.navigate(Screen.Dashboard.route) {
                    popUpTo(0) { inclusive = true }
                }
            }
            null -> {} // Still loading
        }
    }

    val isMainScreen = currentRoute in MAIN_SCREENS

    val title = when (currentRoute) {
        Screen.Dashboard.route -> Screen.Dashboard.label
        Screen.Calendar.route -> Screen.Calendar.label
        else -> "Maggie"
    }

    fun refreshAll() {
        dashboardViewModel.refresh()
        calendarViewModel.refresh()
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = isMainScreen,
        drawerContent = {
            AppDrawerContent(
                currentRoute = currentRoute,
                onNavigate = { route ->
                    navController.navigate(route) { launchSingleTop = true }
                },
                onCloseDrawer = { scope.launch { drawerState.close() } },
            )
        },
    ) {
        Scaffold(
            topBar = {
                if (isMainScreen) {
                    MaggieTopBar(
                        title = title,
                        onMenuClick = { scope.launch { drawerState.open() } },
                    )
                }
            },
            bottomBar = {
                if (isMainScreen) {
                    ChatBottomBar(
                        onOpenChat = { showChatSheet = true },
                        onMicClick = {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                                == PackageManager.PERMISSION_GRANTED
                            ) {
                                voiceModeActive = true
                                showChatSheet = true
                                voiceManager.startListening()
                            } else {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                    )
                }
            },
        ) { paddingValues ->
            NavHost(
                navController = navController,
                startDestination = Screen.Dashboard.route,
                modifier = Modifier.padding(paddingValues),
            ) {
                composable(Screen.Login.route) {
                    LoginScreen(viewModel = loginViewModel)
                }
                composable(Screen.Dashboard.route) {
                    DashboardScreen(
                        viewModel = dashboardViewModel,
                        onEventClick = { selectedEvent = it },
                    )
                }
                composable(Screen.Calendar.route) {
                    FullCalendarScreen(
                        viewModel = calendarViewModel,
                        onCreateEvent = { navController.navigate(Screen.EventCreate.route) },
                        onCreateTask = { navController.navigate(Screen.TaskCreate.route) },
                        onEventClick = { selectedEvent = it },
                    )
                }
                composable(Screen.EventCreate.route) {
                    EventCreateScreen(
                        agendas = agendas,
                        onConfirm = { request ->
                            scope.launch {
                                eventRepository.createEvent(request)
                                navController.popBackStack()
                                refreshAll()
                            }
                        },
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Screen.TaskCreate.route) {
                    TaskCreateScreen(
                        onConfirm = { request ->
                            scope.launch {
                                taskRepository.createTask(request)
                                navController.popBackStack()
                                refreshAll()
                            }
                        },
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Screen.EventEdit.route) {
                    val event = editingEvent
                    if (event != null) {
                        EventEditScreen(
                            event = event,
                            agendas = agendas,
                            onConfirm = { data ->
                                scope.launch {
                                    val id = event.masterEventId ?: event.id
                                    eventRepository.updateEvent(id, data)
                                    editingEvent = null
                                    navController.popBackStack()
                                    refreshAll()
                                }
                            },
                            onBack = {
                                editingEvent = null
                                navController.popBackStack()
                            },
                        )
                    }
                }
                composable(Screen.TaskEdit.route) {
                    val task = editingTask
                    if (task != null) {
                        TaskEditScreen(
                            task = task,
                            onConfirm = { data ->
                                scope.launch {
                                    taskRepository.updateTask(task.id, data)
                                    editingTask = null
                                    navController.popBackStack()
                                    refreshAll()
                                }
                            },
                            onBack = {
                                editingTask = null
                                navController.popBackStack()
                            },
                        )
                    }
                }
            }
        }
    }

    // Chat sheet
    if (showChatSheet) {
        ChatSheet(
            sheetState = chatSheetState,
            viewModel = chatViewModel,
            onDismiss = {
                if (voiceModeActive) {
                    voiceManager.cancelListening()
                    voiceManager.stopSpeaking()
                    voiceModeActive = false
                }
                showChatSheet = false
                refreshAll()
            },
            voiceManager = if (voiceModeActive) voiceManager else null,
        )
    }

    // Event detail sheet
    selectedEvent?.let { event ->
        EventDetailSheet(
            event = event,
            onDismiss = { selectedEvent = null },
            onEdit = {
                selectedEvent = null
                if (event.masterEventId != null && event.isVirtualOccurrence) {
                    recurrenceConfirm = event to false
                } else {
                    editingEvent = event
                    navController.navigate(Screen.EventEdit.route)
                }
            },
            onDelete = {
                selectedEvent = null
                if (event.masterEventId != null) {
                    recurrenceConfirm = event to true
                } else {
                    scope.launch {
                        eventRepository.deleteEvent(event.id)
                        refreshAll()
                    }
                }
            },
        )
    }

    // Task detail sheet
    selectedTask?.let { task ->
        TaskDetailSheet(
            task = task,
            onDismiss = { selectedTask = null },
            onEdit = {
                selectedTask = null
                editingTask = task
                navController.navigate(Screen.TaskEdit.route)
            },
            onDelete = {
                scope.launch {
                    taskRepository.deleteTask(task.id)
                    selectedTask = null
                    refreshAll()
                }
            },
            onToggleDone = { done ->
                scope.launch {
                    taskRepository.toggleDone(task.id, done)
                    selectedTask = null
                    refreshAll()
                }
            },
        )
    }

    // Recurrence confirm dialog (stays as dialog — it's a quick choice)
    recurrenceConfirm?.let { (event, isDelete) ->
        RecurrenceConfirmDialog(
            isDelete = isDelete,
            onConfirm = { action ->
                scope.launch {
                    val masterId = event.masterEventId ?: event.id
                    when {
                        isDelete && action == RecurrenceAction.THIS -> {
                            eventRepository.createEvent(
                                EventCreateRequest(
                                    summary = event.summary,
                                    startAt = event.originalStartAt ?: event.startAt,
                                    endAt = event.originalStartAt ?: event.startAt,
                                    allDay = event.allDay,
                                    timeZone = event.timeZone,
                                    agenda = event.agendaIri,
                                    recurringEvent = "/api/events/$masterId",
                                    originalStartAt = event.originalStartAt ?: event.startAt,
                                    status = "cancelled",
                                ),
                            )
                        }
                        isDelete && action == RecurrenceAction.THIS_AND_FOLLOWING -> {
                            val newRrule = RruleUtils.addUntilToRrule(
                                event.masterRrule!!,
                                Instant.parse(event.originalStartAt ?: event.startAt),
                            )
                            eventRepository.updateEvent(masterId, buildJsonObject { put("rrule", newRrule) })
                        }
                        isDelete && action == RecurrenceAction.ALL -> {
                            eventRepository.deleteEvent(masterId)
                        }
                        !isDelete && action == RecurrenceAction.THIS -> {
                            recurrenceConfirm = null
                            editingEvent = event
                            navController.navigate(Screen.EventEdit.route)
                            return@launch
                        }
                        !isDelete && action == RecurrenceAction.THIS_AND_FOLLOWING -> {
                            recurrenceConfirm = null
                            editingEvent = event
                            navController.navigate(Screen.EventEdit.route)
                            return@launch
                        }
                        !isDelete && action == RecurrenceAction.ALL -> {
                            recurrenceConfirm = null
                            editingEvent = event
                            navController.navigate(Screen.EventEdit.route)
                            return@launch
                        }
                    }
                    recurrenceConfirm = null
                    refreshAll()
                }
            },
            onDismiss = { recurrenceConfirm = null },
        )
    }
}
