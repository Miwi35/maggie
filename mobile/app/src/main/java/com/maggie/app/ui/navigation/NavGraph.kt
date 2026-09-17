package com.maggie.app.ui.navigation

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.WindowInsets
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
import com.maggie.app.data.auth.BiometricLockManager
import com.maggie.app.ui.screens.lock.LockScreen
import com.maggie.app.data.model.ExpandedEvent
import com.maggie.app.data.model.Task
import com.maggie.app.data.repository.EventRepository
import com.maggie.app.data.repository.MealRepository
import com.maggie.app.data.repository.RecipeRepository
import com.maggie.app.data.repository.TaskRepository
import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.repository.AgendaRepository
import com.maggie.app.ui.components.AppDrawerContent
import com.maggie.app.data.model.Context
import com.maggie.app.ui.components.ChatBottomBar
import com.maggie.app.ui.components.ChatSheet
import com.maggie.app.ui.components.ContextListSheet
import com.maggie.app.ui.components.MaggieTopBar
import com.maggie.app.ui.screens.contexts.ContextViewModel
import com.maggie.app.ui.screens.chat.ChatViewModel
import com.maggie.app.ui.screens.cookbook.CookbookScreen
import com.maggie.app.ui.screens.cookbook.grocery.GroceryScreen
import com.maggie.app.ui.screens.cookbook.grocery.GroceryViewModel
import com.maggie.app.ui.screens.finance.AccountListScreen
import com.maggie.app.ui.screens.finance.AccountViewModel
import com.maggie.app.ui.screens.finance.BudgetScreen
import com.maggie.app.ui.screens.finance.CategorizationRuleListScreen
import com.maggie.app.ui.screens.finance.CushionScreen
import com.maggie.app.ui.screens.finance.CushionViewModel
import com.maggie.app.ui.screens.finance.CategorizationRuleViewModel
import com.maggie.app.ui.screens.finance.BudgetViewModel
import com.maggie.app.ui.screens.finance.CategoryListScreen
import com.maggie.app.ui.screens.finance.CategoryViewModel
import com.maggie.app.ui.screens.finance.TransactionListScreen
import com.maggie.app.ui.screens.finance.TransactionViewModel
import com.maggie.app.ui.screens.grocery.ProductListScreen
import com.maggie.app.ui.screens.grocery.ProductViewModel
import com.maggie.app.ui.screens.grocery.StoreListScreen
import com.maggie.app.ui.screens.grocery.StoreViewModel
import com.maggie.app.ui.screens.cookbook.meals.MealCreateDialog
import com.maggie.app.ui.screens.cookbook.meals.MealsWeekViewModel
import com.maggie.app.ui.screens.cookbook.recipes.RecipeCreateScreen
import com.maggie.app.ui.screens.cookbook.recipes.RecipeDetailScreen
import com.maggie.app.ui.screens.cookbook.recipes.RecipeEditScreen
import com.maggie.app.ui.screens.cookbook.recipes.RecipeListViewModel
import com.maggie.app.ui.screens.dashboard.DashboardScreen
import com.maggie.app.ui.screens.dashboard.DashboardViewModel
import com.maggie.app.ui.screens.fullcalendar.FullCalendarScreen
import com.maggie.app.ui.screens.fullcalendar.FullCalendarViewModel
import com.maggie.app.ui.screens.chat.ChatScreen
import com.maggie.app.ui.screens.notifications.NotificationScreen
import com.maggie.app.ui.screens.notifications.NotificationViewModel
import com.maggie.app.ui.screens.proactions.ProactionScreen
import com.maggie.app.ui.screens.search.SearchScreen
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
import com.maggie.app.ui.screens.loading.LoadingScreen
import com.maggie.app.ui.screens.settings.SettingsScreen
import com.maggie.app.util.RruleUtils
import com.maggie.app.voice.VoiceManager
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import org.koin.compose.koinInject
import java.time.Instant

sealed class Screen(val route: String, val label: String) {
    data object Loading : Screen("loading", "Chargement")
    data object Login : Screen("login", "Connexion")
    data object Dashboard : Screen("dashboard", "Tableau de bord")
    data object Calendar : Screen("calendar", "Calendrier")
    data object EventCreate : Screen("event/create", "Nouvel événement")
    data object TaskCreate : Screen("task/create", "Nouvelle tâche")
    data object EventEdit : Screen("event/edit", "Modifier l'événement")
    data object TaskEdit : Screen("task/edit", "Modifier la tâche")
    data object Chat : Screen("chat", "Chat")
    data object Settings : Screen("settings", "Paramètres")
    data object Notifications : Screen("notifications", "Notifications")
    data object Search : Screen("search", "Rechercher")
    data object Proactions : Screen("proactions", "Proactions")
    data object Cookbook : Screen("cookbook", "Cuisine")
    data object Grocery : Screen("grocery", "Courses")
    data object ProductList : Screen("products", "Produits")
    data object StoreList : Screen("stores", "Magasins")
    data object AccountList : Screen("accounts", "Comptes")
    data object CategoryList : Screen("categories", "Catégories")
    data object BudgetList : Screen("budgets", "Budgets")
    data object CategorizationRuleList : Screen("categorization_rules", "Règles")
    data object Cushion : Screen("cushion", "Matelas")
    data object AccountTransactions : Screen("account_transactions", "Opérations")
    data object RecipeDetail : Screen("recipe/detail", "Recette")
    data object RecipeCreate : Screen("recipe/create", "Nouvelle recette")
    data object RecipeEdit : Screen("recipe/edit", "Modifier la recette")
    data object MealCreate : Screen("meal/create", "Nouveau repas")
}

private val MAIN_SCREENS = setOf(
    Screen.Dashboard.route,
    Screen.Calendar.route,
    Screen.Chat.route,
    Screen.Cookbook.route,
    Screen.Grocery.route,
)

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

    val biometricLockManager: BiometricLockManager = koinInject()
    val isLocked by biometricLockManager.isLocked.collectAsState()

    val chatViewModel: ChatViewModel = koinViewModel()
    val contextViewModel: ContextViewModel = koinViewModel()
    val dashboardViewModel: DashboardViewModel = koinViewModel()
    val calendarViewModel: FullCalendarViewModel = koinViewModel()
    val notificationViewModel: NotificationViewModel = koinViewModel()
    val recipeListViewModel: RecipeListViewModel = koinViewModel()
    val mealsWeekViewModel: MealsWeekViewModel = koinViewModel()
    val groceryViewModel: GroceryViewModel = koinViewModel()
    var showChatSheet by rememberSaveable { mutableStateOf(false) }
    val chatSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showContextSheet by rememberSaveable { mutableStateOf(false) }
    val contextSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val voiceManager: VoiceManager = koinInject()
    var voiceModeActive by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    val eventRepository: EventRepository = koinInject()
    val taskRepository: TaskRepository = koinInject()
    val agendaRepository: AgendaRepository = koinInject()
    val recipeRepository: RecipeRepository = koinInject()
    val mealRepository: MealRepository = koinInject()
    val apiService: MaggieApiService = koinInject()

    // Notification unread count
    val notificationUiState by notificationViewModel.uiState.collectAsState()

    // Context state
    val contextUiState by contextViewModel.uiState.collectAsState()

    // Bridge context updates from chat stream to context ViewModel
    LaunchedEffect(Unit) {
        chatViewModel.contextUpdates.collect { update ->
            contextViewModel.handleStreamUpdate(
                Context(id = update.id, label = update.label, status = update.status),
            )
        }
    }

    // Sheet states (kept as overlays)
    var selectedEvent by remember { mutableStateOf<ExpandedEvent?>(null) }
    var selectedTask by remember { mutableStateOf<Task?>(null) }
    var recurrenceConfirm by remember { mutableStateOf<Pair<ExpandedEvent, Boolean>?>(null) }

    // Transient state for edit screens
    var editingEvent by remember { mutableStateOf<ExpandedEvent?>(null) }
    var editingTask by remember { mutableStateOf<Task?>(null) }

    // Cookbook transient state
    var selectedAccount by remember { mutableStateOf<Pair<String, String>?>(null) }
    var detailRecipeId by remember { mutableStateOf<String?>(null) }
    var editRecipeId by remember { mutableStateOf<String?>(null) }
    var mealCreateState by remember { mutableStateOf<Pair<String, String>?>(null) }

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

    // Auth redirect — wait until lock screen is dismissed so we don't
    // overwrite the restored navigation state with a fresh Dashboard route.
    LaunchedEffect(isAuthenticated, isLocked) {
        when (isAuthenticated) {
            false -> navController.navigate(Screen.Login.route) {
                popUpTo(0) { inclusive = true }
            }
            true -> if (!isLocked && (currentRoute == null || currentRoute == Screen.Login.route || currentRoute == Screen.Loading.route)) {
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
        Screen.Chat.route -> Screen.Chat.label
        Screen.Cookbook.route -> Screen.Cookbook.label
        Screen.Grocery.route -> Screen.Grocery.label
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
            contentWindowInsets = WindowInsets(0),
            topBar = {
                if (isMainScreen) {
                    MaggieTopBar(
                        title = title,
                        onMenuClick = { scope.launch { drawerState.open() } },
                        unreadCount = notificationUiState.unreadCount,
                        onNotificationsClick = {
                            navController.navigate(Screen.Notifications.route) { launchSingleTop = true }
                        },
                        onSearchClick = {
                            navController.navigate(Screen.Search.route) { launchSingleTop = true }
                        },
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
                        onBrainClick = { showContextSheet = true },
                        activeContextCount = contextUiState.activeCount,
                    )
                }
            },
        ) { paddingValues ->
            NavHost(
                navController = navController,
                startDestination = Screen.Loading.route,
                modifier = Modifier.padding(paddingValues),
                enterTransition = { fadeIn() },
                exitTransition = { fadeOut() },
                popEnterTransition = { fadeIn() },
                popExitTransition = { fadeOut() },
            ) {
                composable(Screen.Loading.route) {
                    LoadingScreen()
                }
                composable(Screen.Login.route) {
                    LoginScreen(viewModel = loginViewModel)
                }
                composable(Screen.Dashboard.route) {
                    DashboardScreen(
                        viewModel = dashboardViewModel,
                        onEventClick = { selectedEvent = it },
                    )
                }
                composable(Screen.Chat.route) {
                    ChatScreen(viewModel = chatViewModel)
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
                composable(Screen.Settings.route) {
                    SettingsScreen(
                        onBack = { navController.popBackStack() },
                        onNavigateToProactions = {
                            navController.navigate(Screen.Proactions.route) { launchSingleTop = true }
                        },
                    )
                }
                composable(Screen.Notifications.route) {
                    NotificationScreen(
                        viewModel = notificationViewModel,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Screen.Search.route) {
                    SearchScreen(
                        onBack = { navController.popBackStack() },
                        onResultClick = { index, id ->
                            when (index) {
                                "events", "meals" -> {
                                    navController.popBackStack()
                                    navController.navigate(Screen.Calendar.route) { launchSingleTop = true }
                                }
                                "recipes" -> {
                                    detailRecipeId = id
                                    navController.navigate(Screen.RecipeDetail.route) { launchSingleTop = true }
                                }
                                else -> navController.popBackStack()
                            }
                        },
                    )
                }
                composable(Screen.Proactions.route) {
                    ProactionScreen(
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Screen.Cookbook.route) {
                    CookbookScreen(
                        recipeListViewModel = recipeListViewModel,
                        mealsWeekViewModel = mealsWeekViewModel,
                        onRecipeClick = { id ->
                            detailRecipeId = id
                            navController.navigate(Screen.RecipeDetail.route)
                        },
                        onCreateRecipe = {
                            navController.navigate(Screen.RecipeCreate.route)
                        },
                        onCreateMeal = { day, slot ->
                            mealCreateState = day to slot
                        },
                    )
                }
                composable(Screen.Grocery.route) {
                    GroceryScreen(
                        viewModel = groceryViewModel,
                        onNavigateToProducts = {
                            navController.navigate(Screen.ProductList.route) { launchSingleTop = true }
                        },
                        onNavigateToStores = {
                            navController.navigate(Screen.StoreList.route) { launchSingleTop = true }
                        },
                    )
                }
                composable(Screen.ProductList.route) {
                    val productViewModel: ProductViewModel = koinViewModel()
                    ProductListScreen(
                        viewModel = productViewModel,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Screen.StoreList.route) {
                    val storeViewModel: StoreViewModel = koinViewModel()
                    StoreListScreen(
                        viewModel = storeViewModel,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Screen.AccountList.route) {
                    val accountViewModel: AccountViewModel = koinViewModel()
                    AccountListScreen(
                        viewModel = accountViewModel,
                        onBack = { navController.popBackStack() },
                        onOpenAccount = { accountId, accountName ->
                            selectedAccount = accountId to accountName
                            navController.navigate(Screen.AccountTransactions.route) { launchSingleTop = true }
                        },
                    )
                }
                composable(Screen.CategoryList.route) {
                    val categoryViewModel: CategoryViewModel = koinViewModel()
                    CategoryListScreen(
                        viewModel = categoryViewModel,
                        onBack = { navController.popBackStack() },
                        onOpenRules = {
                            navController.navigate(Screen.CategorizationRuleList.route) {
                                launchSingleTop = true
                            }
                        },
                    )
                }
                composable(Screen.BudgetList.route) {
                    val budgetViewModel: BudgetViewModel = koinViewModel()
                    BudgetScreen(
                        viewModel = budgetViewModel,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Screen.CategorizationRuleList.route) {
                    val ruleViewModel: CategorizationRuleViewModel = koinViewModel()
                    CategorizationRuleListScreen(
                        viewModel = ruleViewModel,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Screen.Cushion.route) {
                    val cushionViewModel: CushionViewModel = koinViewModel()
                    CushionScreen(
                        viewModel = cushionViewModel,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Screen.AccountTransactions.route) {
                    val account = selectedAccount
                    if (account != null) {
                        val transactionViewModel: TransactionViewModel = koinViewModel { parametersOf(account.first) }
                        TransactionListScreen(
                            viewModel = transactionViewModel,
                            accountName = account.second,
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
                composable(Screen.RecipeDetail.route) {
                    val id = detailRecipeId
                    if (id != null) {
                        RecipeDetailScreen(
                            recipeId = id,
                            recipeRepository = recipeRepository,
                            onBack = { navController.popBackStack() },
                            onEdit = { recipeId ->
                                editRecipeId = recipeId
                                navController.navigate(Screen.RecipeEdit.route)
                            },
                            onDelete = { recipeId ->
                                scope.launch {
                                    recipeRepository.deleteRecipe(recipeId)
                                    navController.popBackStack()
                                    recipeListViewModel.refresh()
                                }
                            },
                        )
                    }
                }
                composable(Screen.RecipeCreate.route) {
                    RecipeCreateScreen(
                        onConfirm = { request ->
                            scope.launch {
                                recipeRepository.createRecipe(request)
                                navController.popBackStack()
                                recipeListViewModel.refresh()
                            }
                        },
                        onBack = { navController.popBackStack() },
                        onSearchCiqual = { query -> apiService.searchCiqualFoods(query) },
                    )
                }
                composable(Screen.RecipeEdit.route) {
                    val id = editRecipeId
                    if (id != null) {
                        RecipeEditScreen(
                            recipeId = id,
                            recipeRepository = recipeRepository,
                            onConfirm = { recipeId, data ->
                                scope.launch {
                                    recipeRepository.updateRecipe(recipeId, data)
                                    editRecipeId = null
                                    navController.popBackStack()
                                    recipeListViewModel.refresh()
                                }
                            },
                            onBack = {
                                editRecipeId = null
                                navController.popBackStack()
                            },
                            onSearchCiqual = { query -> apiService.searchCiqualFoods(query) },
                        )
                    }
                }
            }
        }
    }

    // Meal create dialog
    mealCreateState?.let { (day, slot) ->
        MealCreateDialog(
            date = day,
            slot = slot,
            recipeListViewModel = recipeListViewModel,
            onConfirm = { request ->
                scope.launch {
                    mealRepository.createMeal(request)
                    mealCreateState = null
                    mealsWeekViewModel.refresh()
                }
            },
            onDismiss = { mealCreateState = null },
        )
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

    // Context sheet
    if (showContextSheet) {
        ContextListSheet(
            sheetState = contextSheetState,
            uiState = contextUiState,
            onDismiss = { showContextSheet = false },
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

    // Biometric lock overlay
    if (isAuthenticated == true && isLocked) {
        LockScreen(lockManager = biometricLockManager)
    }
}
