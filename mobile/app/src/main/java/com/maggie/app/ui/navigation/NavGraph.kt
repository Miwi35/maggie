package com.maggie.app.ui.navigation

import android.Manifest
import android.content.Context as AndroidContext
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
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
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.NotificationPermissionPrompt
import com.maggie.app.ui.components.AppDrawerContent
import com.maggie.app.data.model.Context
import com.maggie.app.ui.components.ChatBottomBar
import com.maggie.app.ui.components.ChatPanel
import com.maggie.app.ui.components.ChatRailActions
import com.maggie.app.ui.components.ChatSheet
import com.maggie.app.ui.components.ContextListSheet
import com.maggie.app.ui.components.MaggieNavigationRail
import com.maggie.app.ui.components.MaggieTopBar
import com.maggie.app.ui.layout.AppLayout
import com.maggie.app.ui.layout.AppShell
import com.maggie.app.ui.layout.ChatEntry
import com.maggie.app.ui.layout.ListDetailPane
import com.maggie.app.ui.layout.NavigationKind
import com.maggie.app.ui.layout.rememberAppLayout
import com.maggie.app.ui.screens.contexts.ContextViewModel
import com.maggie.app.ui.screens.chat.ChatViewModel
import com.maggie.app.ui.screens.cookbook.CookbookScreen
import com.maggie.app.ui.screens.cookbook.grocery.GroceryScreen
import com.maggie.app.ui.screens.cookbook.grocery.GroceryViewModel
import com.maggie.app.ui.screens.cookbook.grocery.ItemDetailContent
import com.maggie.app.ui.screens.finance.AccountListScreen
import com.maggie.app.ui.screens.finance.AccountViewModel
import com.maggie.app.ui.screens.finance.BankConnectionListScreen
import com.maggie.app.ui.screens.finance.BankConnectionViewModel
import com.maggie.app.ui.screens.finance.BudgetScreen
import com.maggie.app.ui.screens.finance.RuleSuggestionListScreen
import com.maggie.app.ui.screens.finance.RuleSuggestionViewModel
import com.maggie.app.ui.screens.finance.CategorizationRuleListScreen
import com.maggie.app.ui.screens.finance.CushionScreen
import com.maggie.app.ui.screens.finance.LoanListScreen
import com.maggie.app.ui.screens.finance.FinanceDashboardScreen
import com.maggie.app.ui.screens.finance.FinanceDashboardViewModel
import com.maggie.app.ui.screens.finance.MonthlyReviewScreen
import com.maggie.app.ui.screens.finance.MonthlyReviewViewModel
import com.maggie.app.ui.screens.finance.LoanViewModel
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
import com.maggie.app.ui.screens.cookbook.recipes.RecipeDetailViewModel
import com.maggie.app.ui.screens.cookbook.recipes.RecipeEditViewModel
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
import com.maggie.app.ui.screens.shared.EventDetailContent
import com.maggie.app.ui.screens.shared.EventDetailSheet
import com.maggie.app.ui.screens.shared.EventEditScreen
import com.maggie.app.ui.screens.shared.RecurrenceAction
import com.maggie.app.ui.screens.shared.RecurrenceConfirmDialog
import com.maggie.app.ui.screens.shared.RecurringEventEditor
import com.maggie.app.ui.screens.shared.TaskCreateScreen
import com.maggie.app.ui.screens.shared.TaskDetailContent
import com.maggie.app.ui.screens.shared.TaskDetailSheet
import com.maggie.app.ui.screens.shared.TaskEditScreen
import com.maggie.app.ui.screens.login.LoginScreen
import com.maggie.app.ui.screens.login.LoginViewModel
import com.maggie.app.ui.screens.loading.LoadingScreen
import com.maggie.app.ui.screens.settings.SettingsScreen
import com.maggie.app.util.EventExpander
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
    data object RuleSuggestions : Screen("rule_suggestions", "Suggestions")
    data object BankConnectionList : Screen("bank_connections", "Banques")
    data object Cushion : Screen("cushion", "Matelas")
    data object LoanList : Screen("loans", "Prêts")
    data object MonthlyReview : Screen("monthly_review", "Revue mensuelle")
    data object FinanceDashboard : Screen("finance_dashboard", "Finance")
    data object AccountTransactions : Screen("account_transactions", "Transactions")
    data object RecipeDetail : Screen("recipe/detail", "Recette")
    data object RecipeCreate : Screen("recipe/create", "Nouvelle recette")
    data object RecipeEdit : Screen("recipe/edit", "Modifier la recette")
    data object MealCreate : Screen("meal/create", "Nouveau repas")
}

private const val LINK_NOT_FOUND = "Cet élément n'existe plus."
private const val DEFAULT_ACCOUNT_NAME = "Compte"

private val MAIN_SCREENS = setOf(
    Screen.Dashboard.route,
    Screen.Calendar.route,
    Screen.Chat.route,
    Screen.Cookbook.route,
    Screen.Grocery.route,
)

// The full-screen chat has its own input: a second way in would duplicate it
internal fun showsChatBottomBar(route: String?): Boolean = route in MAIN_SCREENS && route != Screen.Chat.route

/** Which parts of the frame this window and this route call for (MAG-35). */
internal data class Chrome(
    val showsRail: Boolean,
    val showsChatPanel: Boolean,
    val showsChatBar: Boolean,
    /** The conversation is reached from the rail's header, the window being too short for a bar. */
    val showsChatInRail: Boolean,
    /** A 48 dp top bar instead of 64 — every dp a short window can give the content. */
    val denseTopBar: Boolean,
    /** This screen draws its list and the detail of the selected item side by side (MAG-263). */
    val showsDetailPane: Boolean,
    /** Details open as a sheet over the screen: the window has no pane for them, or the screen is the dashboard's. */
    val detailsAreSheets: Boolean,
)

// The screens whose list has a detail beside it. The dashboard lists events too, but it has no pane: its events stay sheets.
private val DETAIL_PANE_ROUTES = setOf(
    Screen.Calendar.route,
    Screen.Cookbook.route,
    Screen.Grocery.route,
    Screen.AccountList.route,
)

// A detail that is a route of its own on a narrow window, and the list route whose pane takes it over.
private val DETAIL_ROUTE_LISTS = mapOf(
    Screen.RecipeDetail.route to Screen.Cookbook.route,
    Screen.AccountTransactions.route to Screen.AccountList.route,
)

/**
 * The list route to replace `route` with once the detail pane has room (MAG-263), or `null` when `route` stays.
 *
 * Unfolding a foldable while a recipe fills the screen — or a link or a search result
 * landing on the detail route of a tablet — would otherwise draw the detail route
 * beside its own list. The selection lives outside the back stack, so replacing the
 * route loses nothing: the list comes back with that item in its pane.
 */
internal fun foldsDetailRouteIntoPane(route: String?, showsDetailPane: Boolean): String? =
    if (showsDetailPane) DETAIL_ROUTE_LISTS[route] else null

/** The dashboard keeps its own sheets: what a pane held while the window was wide must not open over it. */
internal fun dropsPaneSelection(route: String?, detailPaneFits: Boolean): Boolean =
    detailPaneFits && route == Screen.Dashboard.route

/** [detail] leaves the back stack for [list], which is not duplicated when it is already underneath. */
internal fun NavController.foldRouteInto(detail: String, list: String) {
    navigate(list) {
        popUpTo(detail) { inclusive = true }
        launchSingleTop = true
    }
}

/**
 * The window's size meets the current route.
 *
 * Here rather than in `ui/layout/` because every one of these four answers needs a
 * route as much as a width, and the routes live in this file. It is a function of
 * two values so the rules are a unit test (`AdaptiveNavigationTest`) instead of
 * something to reproduce by resizing an emulator.
 */
internal fun chromeFor(layout: AppLayout, route: String?): Chrome {
    // Where this route offers the conversation at all: not on the chat screen, which is it.
    val chatReachable = showsChatBottomBar(route)
    return Chrome(
        // The rail serves the drawer's destinations, so it appears where the drawer
        // did: on the main screens. A detail route keeps the whole width, as today.
        showsRail = layout.navigation == NavigationKind.RAIL && route in MAIN_SCREENS,
        // Exactly one of the three, and the window says which: the panel *is* the
        // conversation, and the rail's header is where a window too short for a band
        // under the content puts the band's three buttons.
        showsChatPanel = chatReachable && layout.chatEntry == ChatEntry.PANEL,
        showsChatBar = chatReachable && layout.chatEntry == ChatEntry.BOTTOM_BAR,
        showsChatInRail = chatReachable && layout.chatEntry == ChatEntry.RAIL,
        denseTopBar = layout.denseTopBar,
        showsDetailPane = layout.detailPaneFits && route in DETAIL_PANE_ROUTES,
        detailsAreSheets = !layout.detailPaneFits || route == Screen.Dashboard.route,
    )
}

/**
 * With the conversation already on screen, only voice mode still needs the sheet —
 * a push-to-talk session wants `VoiceControlBar`, which the panel does not carry.
 */
internal fun showsChatSheet(requested: Boolean, voiceMode: Boolean, hasChatPanel: Boolean): Boolean =
    requested && (voiceMode || !hasChatPanel)

private tailrec fun AndroidContext.findComponentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findComponentActivity()
    else -> null
}

// Swaps a `link/…` entry for the screen it resolved to. Nothing happens if the user already went back.
private fun NavController.replaceLink(entry: NavBackStackEntry, route: String, singleTop: Boolean = false) {
    if (currentBackStackEntry?.id != entry.id) return
    navigate(route) {
        popUpTo(entry.destination.id) { inclusive = true }
        launchSingleTop = singleTop
    }
}

private fun NavController.backInFinance() {
    val currentId = currentBackStackEntry?.destination?.id ?: return
    when (financeBackAction(previousBackStackEntry?.destination?.route)) {
        FinanceBack.POP -> popBackStack()
        FinanceBack.REPLACE_WITH_DASHBOARD -> navigate(Screen.FinanceDashboard.route) {
            popUpTo(currentId) { inclusive = true }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavGraph() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val layout = rememberAppLayout()
    val chrome = chromeFor(layout, currentRoute)

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
    var editingRecurrenceAction by remember { mutableStateOf<RecurrenceAction?>(null) }
    val recurringEventEditor = remember(eventRepository) { RecurringEventEditor(eventRepository) }
    var editingTask by remember { mutableStateOf<Task?>(null) }

    // Cookbook transient state
    var selectedAccount by remember { mutableStateOf<Pair<String, String>?>(null) }
    var detailRecipeId by remember { mutableStateOf<String?>(null) }
    var groceryItemToOpen by remember { mutableStateOf<String?>(null) }
    var groceryPaneItemId by remember { mutableStateOf<String?>(null) }
    // Read through a State: the NavHost graph is rebuilt when the builder's captures change.
    val paneShown by rememberUpdatedState(chrome.showsDetailPane)
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
        }
    }

    // Auth redirect — wait until lock screen is dismissed so we don't
    // overwrite the restored navigation state with a fresh Dashboard route.
    // Keyed on the route too: a deep link opened from a cold start sits on top of Loading,
    // and going back to it must land on the dashboard, not on a spinner.
    LaunchedEffect(isAuthenticated, isLocked, currentRoute) {
        val liveRoute = navController.currentBackStackEntry?.destination?.route
        when (authRedirect(isAuthenticated, isLocked, liveRoute)) {
            AuthRedirect.LOGIN -> navController.navigate(Screen.Login.route) {
                popUpTo(0) { inclusive = true }
            }
            AuthRedirect.DASHBOARD -> navController.navigate(Screen.Dashboard.route) {
                popUpTo(0) { inclusive = true }
            }
            AuthRedirect.NONE -> {}
        }
    }

    // MainActivity is singleTop: a link opened while the app runs arrives here, not in a new activity.
    val activity = remember(context) { context.findComponentActivity() }
    DisposableEffect(activity, navController) {
        val listener = Consumer<Intent> { navController.handleDeepLink(it) }
        activity?.addOnNewIntentListener(listener)
        onDispose { activity?.removeOnNewIntentListener(listener) }
    }

    // A link names an entity only once the user is signed in and unlocked.
    val linkReady = isAuthenticated == true && !isLocked

    // Android 13+ shows nothing until it is asked: once, after the sign-in, with the reason.
    if (linkReady) NotificationPermissionPrompt()

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

    fun navigateTo(route: String) {
        navController.navigate(route) { launchSingleTop = true }
    }

    // The detail of an event or a task acts the same in its sheet and in the calendar's detail pane.
    fun editEvent(event: ExpandedEvent) {
        selectedEvent = null
        if (event.masterEventId != null && event.isVirtualOccurrence) {
            recurrenceConfirm = event to false
        } else {
            editingEvent = event
            editingRecurrenceAction = null
            navController.navigate(Screen.EventEdit.route)
        }
    }

    fun deleteEvent(event: ExpandedEvent) {
        selectedEvent = null
        if (event.masterEventId != null) {
            recurrenceConfirm = event to true
        } else {
            scope.launch {
                eventRepository.deleteEvent(event.id)
                refreshAll()
            }
        }
    }

    fun editTask(task: Task) {
        selectedTask = null
        editingTask = task
        navController.navigate(Screen.TaskEdit.route)
    }

    fun deleteTask(task: Task) {
        scope.launch {
            taskRepository.deleteTask(task.id)
            selectedTask = null
            refreshAll()
        }
    }

    fun toggleTaskDone(task: Task, done: Boolean) {
        scope.launch {
            taskRepository.toggleDone(task.id, done)
            selectedTask = null
            refreshAll()
        }
    }

    fun editRecipe(recipeId: String) {
        editRecipeId = recipeId
        navController.navigate(Screen.RecipeEdit.route)
    }

    fun deleteRecipe(recipeId: String, leaveDetail: () -> Unit) {
        scope.launch {
            recipeRepository.deleteRecipe(recipeId)
            leaveDetail()
            recipeListViewModel.refresh()
        }
    }

    // The pane's selection is not the dashboard's sheet: entering the dashboard on a wide window starts clean.
    LaunchedEffect(currentRoute) {
        if (dropsPaneSelection(currentRoute, layout.detailPaneFits)) {
            selectedEvent = null
            selectedTask = null
        }
    }

    // Unfolding with a detail route on screen: the list takes its place and the detail moves into the pane.
    LaunchedEffect(currentRoute, layout.detailPaneFits) {
        val route = currentRoute ?: return@LaunchedEffect
        val list = foldsDetailRouteIntoPane(route, layout.detailPaneFits) ?: return@LaunchedEffect
        navController.foldRouteInto(route, list)
    }

    // The panel only suppresses the sheet, so the request would survive it: open the
    // chat on a tablet in portrait, turn to landscape (the sheet gives way to the
    // panel), then open Paramètres — the panel goes and the sheet would come back
    // over the settings. Same for a drawer left open when the rail replaces it.
    LaunchedEffect(chrome.showsChatPanel) {
        if (chrome.showsChatPanel && !voiceModeActive) showChatSheet = false
    }
    LaunchedEffect(chrome.showsRail) {
        if (chrome.showsRail) drawerState.close()
    }

    // The mic: voice mode needs the permission first, and it always opens the sheet —
    // even behind the panel, which has no push-to-talk bar of its own.
    fun startVoiceMode() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            voiceModeActive = true
            showChatSheet = true
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    AppShell(
        drawerState = drawerState,
        drawerGesturesEnabled = isMainScreen,
        drawer = {
            AppDrawerContent(
                currentRoute = currentRoute,
                onNavigate = ::navigateTo,
                onCloseDrawer = { scope.launch { drawerState.close() } },
            )
        },
        rail = if (chrome.showsRail) {
            {
                MaggieNavigationRail(
                    currentRoute = currentRoute,
                    onNavigate = ::navigateTo,
                    chatAction = if (chrome.showsChatInRail) {
                        {
                            ChatRailActions(
                                onOpenChat = { showChatSheet = true },
                                onMicClick = ::startVoiceMode,
                                onBrainClick = { showContextSheet = true },
                                activeContextCount = contextUiState.activeCount,
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        } else {
            null
        },
        chatPanel = if (chrome.showsChatPanel) {
            {
                ChatPanel(
                    viewModel = chatViewModel,
                    onMicClick = ::startVoiceMode,
                    onBrainClick = { showContextSheet = true },
                    activeContextCount = contextUiState.activeCount,
                )
            }
        } else {
            null
        },
    ) {
        Scaffold(
            modifier = Modifier.weight(1f),
            // Still nothing from here, band or no band: each piece pays its own inset
            // (`ChatBottomBar`, `ChatPanel`, `ChatScreen`), and the screens that nest a
            // `Scaffold` of their own — Cuisine, Calendrier — already get `safeDrawing`
            // from it. Material 3 1.3 does not consume `contentWindowInsets` for the
            // body, so handing one down here would pay the gesture bar twice on exactly
            // the screens this ticket is giving height back to.
            contentWindowInsets = WindowInsets(0),
            topBar = {
                if (isMainScreen) {
                    MaggieTopBar(
                        title = title,
                        onMenuClick = if (chrome.showsRail) {
                            null
                        } else {
                            { scope.launch { drawerState.open() } }
                        },
                        unreadCount = notificationUiState.unreadCount,
                        onNotificationsClick = { navigateTo(Screen.Notifications.route) },
                        onSearchClick = { navigateTo(Screen.Search.route) },
                        dense = chrome.denseTopBar,
                    )
                }
            },
            bottomBar = {
                if (chrome.showsChatBar) {
                    ChatBottomBar(
                        onOpenChat = { showChatSheet = true },
                        onMicClick = ::startVoiceMode,
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
                composable(DeepLinks.EVENT_ROUTE, deepLinks = DeepLinks.forRoute(DeepLinks.EVENT_ROUTE)) { entry ->
                    val id = entry.arguments?.getString(DeepLinks.ARG_ID)
                    LoadingScreen()
                    LaunchedEffect(id, linkReady) {
                        if (!linkReady) return@LaunchedEffect
                        val event = id?.takeIf(DeepLinks::isValidId)?.let { eventRepository.findEvent(it) }
                        if (event != null) {
                            val master = event.recurringEvent?.let { eventRepository.findEvent(it.removePrefix("/api/events/")) }
                            selectedTask = null
                            selectedEvent = EventExpander.single(event, master, calendarViewModel.uiState.value.agendas.associateBy { it.id })
                        } else {
                            Toast.makeText(context, LINK_NOT_FOUND, Toast.LENGTH_SHORT).show()
                        }
                        navController.replaceLink(entry, Screen.Calendar.route, singleTop = true)
                    }
                }
                composable(DeepLinks.TASK_ROUTE, deepLinks = DeepLinks.forRoute(DeepLinks.TASK_ROUTE)) { entry ->
                    val id = entry.arguments?.getString(DeepLinks.ARG_ID)
                    LoadingScreen()
                    LaunchedEffect(id, linkReady) {
                        if (!linkReady) return@LaunchedEffect
                        val task = id?.takeIf(DeepLinks::isValidId)?.let { taskRepository.findTask(it) }
                        if (task != null) {
                            selectedEvent = null
                            selectedTask = task
                        } else {
                            Toast.makeText(context, LINK_NOT_FOUND, Toast.LENGTH_SHORT).show()
                        }
                        navController.replaceLink(entry, Screen.Calendar.route, singleTop = true)
                    }
                }
                composable(DeepLinks.GROCERY_ROUTE, deepLinks = DeepLinks.forRoute(DeepLinks.GROCERY_ROUTE)) { entry ->
                    val id = entry.arguments?.getString(DeepLinks.ARG_ID)
                    LoadingScreen()
                    LaunchedEffect(id, linkReady) {
                        if (!linkReady) return@LaunchedEffect
                        groceryItemToOpen = id?.takeIf(DeepLinks::isValidId)
                        navController.replaceLink(entry, Screen.Grocery.route, singleTop = true)
                    }
                }
                composable(DeepLinks.RECIPE_ROUTE, deepLinks = DeepLinks.forRoute(DeepLinks.RECIPE_ROUTE)) { entry ->
                    val id = entry.arguments?.getString(DeepLinks.ARG_ID)
                    LoadingScreen()
                    LaunchedEffect(id, linkReady) {
                        if (!linkReady) return@LaunchedEffect
                        if (DeepLinks.isValidId(id)) {
                            detailRecipeId = id
                            navController.replaceLink(entry, Screen.RecipeDetail.route)
                        } else {
                            Toast.makeText(context, LINK_NOT_FOUND, Toast.LENGTH_SHORT).show()
                            navController.replaceLink(entry, Screen.Cookbook.route, singleTop = true)
                        }
                    }
                }
                composable(
                    DeepLinks.ACCOUNT_ROUTE,
                    arguments = listOf(
                        navArgument(DeepLinks.ARG_NAME) {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                    ),
                    deepLinks = DeepLinks.forRoute(DeepLinks.ACCOUNT_ROUTE),
                ) { entry ->
                    val id = entry.arguments?.getString(DeepLinks.ARG_ID)
                    val name = entry.arguments?.getString(DeepLinks.ARG_NAME)
                    LoadingScreen()
                    LaunchedEffect(id, name, linkReady) {
                        if (!linkReady) return@LaunchedEffect
                        if (DeepLinks.isValidId(id)) {
                            selectedAccount = id!! to (name?.takeIf { it.isNotBlank() } ?: DEFAULT_ACCOUNT_NAME)
                            navController.replaceLink(entry, Screen.AccountTransactions.route)
                        } else {
                            Toast.makeText(context, LINK_NOT_FOUND, Toast.LENGTH_SHORT).show()
                            navController.replaceLink(entry, Screen.AccountList.route, singleTop = true)
                        }
                    }
                }
                composable(Screen.Dashboard.route) {
                    DashboardScreen(
                        viewModel = dashboardViewModel,
                        onEventClick = { selectedEvent = it },
                    )
                }
                composable(
                    Screen.Chat.route,
                    arguments = listOf(
                        navArgument(DeepLinks.ARG_MESSAGE) {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                    ),
                    deepLinks = DeepLinks.forRoute(Screen.Chat.route),
                ) { entry ->
                    ChatScreen(
                        viewModel = chatViewModel,
                        draft = DeepLinks.chatDraft(entry.arguments?.getString(DeepLinks.ARG_MESSAGE)),
                    )
                }
                composable(Screen.Calendar.route) {
                    val event = selectedEvent
                    val task = selectedTask
                    ListDetailPane(
                        showsDetailPane = paneShown,
                        list = {
                            FullCalendarScreen(
                                viewModel = calendarViewModel,
                                onCreateEvent = { navController.navigate(Screen.EventCreate.route) },
                                onCreateTask = { navController.navigate(Screen.TaskCreate.route) },
                                onEventClick = {
                                    selectedTask = null
                                    selectedEvent = it
                                },
                            )
                        },
                        detail = when {
                            event != null -> ({
                                EventDetailContent(
                                    event = event,
                                    onEdit = { editEvent(event) },
                                    onDelete = { deleteEvent(event) },
                                    modifier = Modifier.verticalScroll(rememberScrollState()),
                                )
                            })
                            task != null -> ({
                                TaskDetailContent(
                                    task = task,
                                    onEdit = { editTask(task) },
                                    onDelete = { deleteTask(task) },
                                    onToggleDone = { done -> toggleTaskDone(task, done) },
                                    modifier = Modifier.verticalScroll(rememberScrollState()),
                                )
                            })
                            else -> null
                        },
                        placeholder = "Touchez un événement pour le voir ici.",
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
                                    recurringEventEditor.edit(event, editingRecurrenceAction, data)
                                    editingEvent = null
                                    editingRecurrenceAction = null
                                    navController.popBackStack()
                                    refreshAll()
                                }
                            },
                            onBack = {
                                editingEvent = null
                                editingRecurrenceAction = null
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
                    val paneRecipeId = detailRecipeId
                    ListDetailPane(
                        showsDetailPane = paneShown,
                        list = {
                            CookbookScreen(
                                recipeListViewModel = recipeListViewModel,
                                mealsWeekViewModel = mealsWeekViewModel,
                                onRecipeClick = { id ->
                                    detailRecipeId = id
                                    if (!paneShown) navController.navigate(Screen.RecipeDetail.route)
                                },
                                onCreateRecipe = {
                                    navController.navigate(Screen.RecipeCreate.route)
                                },
                                onCreateMeal = { day, slot ->
                                    mealCreateState = day to slot
                                },
                            )
                        },
                        detail = paneRecipeId?.let { id ->
                            {
                                key(id) {
                                    RecipeDetailScreen(
                                        recipeId = id,
                                        recipeRepository = recipeRepository,
                                        viewModel = koinViewModel<RecipeDetailViewModel>(key = id) { parametersOf(id) },
                                        onBack = null,
                                        onEdit = ::editRecipe,
                                        onDelete = { recipeId -> deleteRecipe(recipeId) { detailRecipeId = null } },
                                    )
                                }
                            }
                        },
                        placeholder = "Touchez une recette pour la voir ici.",
                    )
                }
                composable(Screen.Grocery.route) {
                    val groceryState by groceryViewModel.uiState.collectAsState()
                    // Resolved on every state: an item deleted or changed elsewhere is not edited from a stale copy.
                    val paneItem = groceryPaneItemId?.let { id ->
                        (groceryState.groceryList?.items.orEmpty() + groceryState.laterItems).firstOrNull { it.id == id }
                    }
                    ListDetailPane(
                        showsDetailPane = paneShown,
                        list = {
                            Box(Modifier.fillMaxSize().testTag(UiTags.GROCERY)) {
                                GroceryScreen(
                                    viewModel = groceryViewModel,
                                    openItemId = groceryItemToOpen,
                                    onOpenItemHandled = { groceryItemToOpen = null },
                                    onNavigateToProducts = {
                                        navController.navigate(Screen.ProductList.route) { launchSingleTop = true }
                                    },
                                    onNavigateToStores = {
                                        navController.navigate(Screen.StoreList.route) { launchSingleTop = true }
                                    },
                                    onOpenItemInPane = if (paneShown) {
                                        { groceryPaneItemId = it.id }
                                    } else {
                                        null
                                    },
                                )
                            }
                        },
                        detail = paneItem?.let { item ->
                            {
                                // The form remembers what was typed: another item is another form.
                                key(item.id) {
                                    ItemDetailContent(
                                        item = item,
                                        products = groceryState.products,
                                        stores = groceryState.stores,
                                        onSave = { label, quantity, unit, storeId, storeName, category ->
                                            item.id?.let { id ->
                                                groceryViewModel.updateItem(id, label, quantity, unit, storeId, storeName, category)
                                            }
                                            groceryPaneItemId = null
                                        },
                                        modifier = Modifier.verticalScroll(rememberScrollState()),
                                    )
                                }
                            }
                        },
                        placeholder = "Touchez un article pour le modifier ici.",
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
                composable(Screen.AccountList.route, deepLinks = DeepLinks.forRoute(Screen.AccountList.route)) {
                    val accountViewModel: AccountViewModel = koinViewModel()
                    val account = selectedAccount
                    ListDetailPane(
                        showsDetailPane = paneShown,
                        list = {
                            AccountListScreen(
                                viewModel = accountViewModel,
                                onBack = { navController.backInFinance() },
                                onOpenAccount = { accountId, accountName ->
                                    selectedAccount = accountId to accountName
                                    if (!paneShown) {
                                        navController.navigate(Screen.AccountTransactions.route) { launchSingleTop = true }
                                    }
                                },
                            )
                        },
                        detail = account?.let { { AccountTransactions(it, onBack = null) } },
                        placeholder = "Touchez un compte pour voir ses transactions ici.",
                    )
                }
                composable(Screen.CategoryList.route, deepLinks = DeepLinks.forRoute(Screen.CategoryList.route)) {
                    val categoryViewModel: CategoryViewModel = koinViewModel()
                    CategoryListScreen(
                        viewModel = categoryViewModel,
                        onBack = { navController.backInFinance() },
                        onOpenRules = {
                            navController.navigate(Screen.CategorizationRuleList.route) {
                                launchSingleTop = true
                            }
                        },
                    )
                }
                composable(Screen.BudgetList.route, deepLinks = DeepLinks.forRoute(Screen.BudgetList.route)) {
                    val budgetViewModel: BudgetViewModel = koinViewModel()
                    BudgetScreen(
                        viewModel = budgetViewModel,
                        onBack = { navController.backInFinance() },
                    )
                }
                composable(Screen.CategorizationRuleList.route, deepLinks = DeepLinks.forRoute(Screen.CategorizationRuleList.route)) {
                    val ruleViewModel: CategorizationRuleViewModel = koinViewModel()
                    CategorizationRuleListScreen(
                        viewModel = ruleViewModel,
                        onBack = { navController.backInFinance() },
                        onOpenSuggestions = {
                            navController.navigate(Screen.RuleSuggestions.route) {
                                launchSingleTop = true
                            }
                        },
                    )
                }
                composable(Screen.RuleSuggestions.route, deepLinks = DeepLinks.forRoute(Screen.RuleSuggestions.route)) {
                    val suggestionViewModel: RuleSuggestionViewModel = koinViewModel()
                    RuleSuggestionListScreen(
                        viewModel = suggestionViewModel,
                        onBack = { navController.backInFinance() },
                    )
                }
                composable(Screen.BankConnectionList.route, deepLinks = DeepLinks.forRoute(Screen.BankConnectionList.route)) {
                    val bankViewModel: BankConnectionViewModel = koinViewModel()
                    BankConnectionListScreen(
                        viewModel = bankViewModel,
                        onBack = { navController.backInFinance() },
                    )
                }
                composable(Screen.Cushion.route, deepLinks = DeepLinks.forRoute(Screen.Cushion.route)) {
                    val cushionViewModel: CushionViewModel = koinViewModel()
                    CushionScreen(
                        viewModel = cushionViewModel,
                        onBack = { navController.backInFinance() },
                    )
                }
                composable(Screen.LoanList.route, deepLinks = DeepLinks.forRoute(Screen.LoanList.route)) {
                    val loanViewModel: LoanViewModel = koinViewModel()
                    LoanListScreen(
                        viewModel = loanViewModel,
                        onBack = { navController.backInFinance() },
                    )
                }
                composable(Screen.MonthlyReview.route, deepLinks = DeepLinks.forRoute(Screen.MonthlyReview.route)) {
                    val reviewViewModel: MonthlyReviewViewModel = koinViewModel()
                    MonthlyReviewScreen(
                        viewModel = reviewViewModel,
                        onBack = { navController.backInFinance() },
                    )
                }
                composable(Screen.FinanceDashboard.route, deepLinks = DeepLinks.forRoute(Screen.FinanceDashboard.route)) {
                    val dashboardViewModel: FinanceDashboardViewModel = koinViewModel()
                    FinanceDashboardScreen(
                        viewModel = dashboardViewModel,
                        onBack = { navController.popBackStack() },
                        onOpen = { route -> navController.navigate(route) { launchSingleTop = true } },
                    )
                }
                composable(Screen.AccountTransactions.route) {
                    val account = selectedAccount
                    if (account != null) {
                        AccountTransactions(account, onBack = { navController.backInFinance() })
                    }
                }
                composable(Screen.RecipeDetail.route) {
                    val id = detailRecipeId
                    if (id != null) {
                        RecipeDetailScreen(
                            recipeId = id,
                            recipeRepository = recipeRepository,
                            viewModel = koinViewModel<RecipeDetailViewModel>(key = id) { parametersOf(id) },
                            onBack = { navController.popBackStack() },
                            onEdit = ::editRecipe,
                            onDelete = { recipeId ->
                                deleteRecipe(recipeId) {
                                    navController.popBackStack()
                                    detailRecipeId = null
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
                            viewModel = koinViewModel<RecipeEditViewModel>(key = id) { parametersOf(id) },
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

    // Chat sheet — on a wide window the conversation is already in the panel, and
    // only voice mode still opens it (MAG-35).
    if (showsChatSheet(showChatSheet, voiceModeActive, chrome.showsChatPanel)) {
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

    // Event and task detail sheets — on a window with a detail pane the calendar draws them itself
    if (chrome.detailsAreSheets) {
        selectedEvent?.let { event ->
            EventDetailSheet(
                event = event,
                onDismiss = { selectedEvent = null },
                onEdit = { editEvent(event) },
                onDelete = { deleteEvent(event) },
            )
        }

        selectedTask?.let { task ->
            TaskDetailSheet(
                task = task,
                onDismiss = { selectedTask = null },
                onEdit = { editTask(task) },
                onDelete = { deleteTask(task) },
                onToggleDone = { done -> toggleTaskDone(task, done) },
            )
        }
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
                        !isDelete -> {
                            recurrenceConfirm = null
                            editingEvent = event
                            editingRecurrenceAction = action
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

// One account's transactions: the route of its own on a narrow window, the detail pane beside the accounts on a wide one.
@Composable
private fun AccountTransactions(account: Pair<String, String>, onBack: (() -> Unit)?) {
    // Keyed by account: the pane keeps one nav entry while the selection changes under it.
    val transactionViewModel: TransactionViewModel = koinViewModel(key = account.first) { parametersOf(account.first) }
    TransactionListScreen(
        viewModel = transactionViewModel,
        accountName = account.second,
        onBack = onBack,
    )
}
