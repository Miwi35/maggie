package com.maggie.app.ui.navigation

import android.Manifest
import android.content.Context as AndroidContext
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
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
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.auth.BiometricLockManager
import com.maggie.app.ui.screens.lock.LockScreen
import com.maggie.app.data.model.ExpandedEvent
import com.maggie.app.data.model.OPERATION_SERIES
import com.maggie.app.data.model.Task
import com.maggie.app.data.model.TaskView
import com.maggie.app.data.model.Transaction
import com.maggie.app.data.repository.EventRepository
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
import com.maggie.app.data.fcm.PushIntents
import com.maggie.app.ui.components.ChatSheet
import com.maggie.app.ui.interruption.InterruptionHost
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
import com.maggie.app.ui.screens.cookbook.grocery.PastErrandsScreen
import com.maggie.app.ui.screens.cookbook.grocery.PastErrandsViewModel
import com.maggie.app.ui.screens.cookbook.grocery.RecurringGroceryViewModel
import com.maggie.app.ui.screens.finance.AccountListScreen
import com.maggie.app.ui.screens.finance.AccountPagerScreen
import com.maggie.app.ui.screens.finance.AccountTimelineActions
import com.maggie.app.ui.screens.finance.AccountTimelineDetail
import com.maggie.app.ui.screens.finance.AccountViewModel
import com.maggie.app.ui.screens.finance.BankConnectionListScreen
import com.maggie.app.ui.screens.finance.BankConnectionViewModel
import com.maggie.app.ui.screens.finance.BudgetScreen
import com.maggie.app.ui.screens.finance.RecurringOperationListScreen
import com.maggie.app.ui.screens.finance.RecurringOperationViewModel
import com.maggie.app.ui.screens.finance.RuleSuggestionListScreen
import com.maggie.app.ui.screens.finance.RuleSuggestionViewModel
import com.maggie.app.ui.screens.finance.CategorizationRuleListScreen
import com.maggie.app.ui.screens.finance.CushionScreen
import com.maggie.app.ui.screens.finance.PeerDebtsScreen
import com.maggie.app.ui.screens.finance.PeerDebtsViewModel
import com.maggie.app.ui.screens.finance.SavingsAdvancesScreen
import com.maggie.app.ui.screens.finance.SavingsAdvancesViewModel
import com.maggie.app.ui.screens.finance.LoanDetailViewModel
import com.maggie.app.ui.screens.finance.LoanListScreen
import com.maggie.app.ui.screens.finance.LoanSheetScreen
import com.maggie.app.ui.screens.finance.FinanceDashboardScreen
import com.maggie.app.ui.screens.finance.FinanceDashboardViewModel
import com.maggie.app.ui.screens.finance.IndependenceScreen
import com.maggie.app.ui.screens.finance.UpcomingViewModel
import com.maggie.app.ui.screens.finance.MonthBalanceViewModel
import com.maggie.app.ui.screens.finance.BlockState
import com.maggie.app.ui.screens.finance.FinanceSummaryViewModel
import com.maggie.app.ui.screens.finance.SummaryBlock
import com.maggie.app.ui.screens.finance.ReviewActivityRoute
import com.maggie.app.ui.screens.finance.ReviewListScreen
import com.maggie.app.ui.screens.finance.ReviewListViewModel
import com.maggie.app.data.model.ReviewPreset
import com.maggie.app.data.model.ReviewQueueItem
import com.maggie.app.ui.screens.finance.PeriodReviewViewModel
import com.maggie.app.ui.screens.finance.ReviewActivityViewModel
import com.maggie.app.ui.screens.finance.LoanViewModel
import com.maggie.app.ui.screens.finance.PlacementFormScreen
import com.maggie.app.ui.screens.finance.PlacementListScreen
import com.maggie.app.ui.screens.finance.PlacementViewModel
import com.maggie.app.ui.screens.finance.ProjectFormScreen
import com.maggie.app.ui.screens.finance.ProjectListScreen
import com.maggie.app.ui.screens.finance.ProjectSheetScreen
import com.maggie.app.ui.screens.finance.ProjectViewModel
import com.maggie.app.ui.screens.finance.ScenarioViewModel
import com.maggie.app.ui.screens.finance.CushionViewModel
import com.maggie.app.ui.screens.finance.TreasuryScreen
import com.maggie.app.ui.screens.finance.TreasuryViewModel
import com.maggie.app.data.model.TransactionPrefill
import com.maggie.app.ui.screens.finance.CategorizationRuleViewModel
import com.maggie.app.ui.screens.finance.BudgetViewModel
import com.maggie.app.ui.screens.finance.CategoryListScreen
import com.maggie.app.ui.screens.finance.CategoryViewModel
import com.maggie.app.ui.screens.finance.ContactScreen
import com.maggie.app.ui.screens.finance.ContactViewModel
import com.maggie.app.ui.screens.finance.EnvelopeAdjustmentsViewModel
import com.maggie.app.ui.screens.finance.ToQualifyViewModel
import com.maggie.app.ui.screens.finance.TransactionListScreen
import com.maggie.app.ui.screens.finance.TransactionViewModel
import com.maggie.app.ui.screens.grocery.ProductListScreen
import com.maggie.app.ui.screens.grocery.ProductViewModel
import com.maggie.app.ui.screens.grocery.StoreAislesScreen
import com.maggie.app.ui.screens.grocery.StoreAislesViewModel
import com.maggie.app.ui.screens.grocery.StoreListScreen
import com.maggie.app.ui.screens.grocery.StoreViewModel
import com.maggie.app.data.model.Meal
import com.maggie.app.ui.screens.cookbook.meals.MealCreateDialog
import com.maggie.app.ui.screens.cookbook.meals.MealDetailDialog
import com.maggie.app.ui.screens.cookbook.meals.MealDetailViewModel
import com.maggie.app.ui.screens.cookbook.meals.MealCreateViewModel
import com.maggie.app.ui.screens.cookbook.meals.MealIngredientChoiceScreen
import com.maggie.app.ui.screens.cookbook.meals.MealIngredientChoiceViewModel
import com.maggie.app.ui.screens.cookbook.meals.MealsWeekViewModel
import com.maggie.app.ui.screens.cookbook.recipes.RecipeCreateScreen
import com.maggie.app.ui.screens.cookbook.recipes.RecipeCreateViewModel
import com.maggie.app.ui.screens.cookbook.recipes.RecipeDetailScreen
import com.maggie.app.ui.screens.cookbook.recipes.RecipeEditScreen
import com.maggie.app.ui.screens.cookbook.recipes.RecipeDetailViewModel
import com.maggie.app.ui.screens.cookbook.recipes.RecipeEditViewModel
import com.maggie.app.ui.screens.cookbook.recipes.RecipeListViewModel
import com.maggie.app.ui.screens.home.HomeScreen
import com.maggie.app.ui.screens.home.HomeViewModel
import com.maggie.app.ui.screens.fullcalendar.FullCalendarScreen
import com.maggie.app.ui.screens.fullcalendar.FullCalendarViewModel
import com.maggie.app.ui.screens.fullcalendar.MEALS_FILTER_ID
import com.maggie.app.ui.screens.fullcalendar.PROACTIONS_FILTER_ID
import com.maggie.app.ui.screens.chat.ChatScreen
import com.maggie.app.ui.screens.notifications.NotificationScreen
import com.maggie.app.ui.screens.notifications.NotificationViewModel
import com.maggie.app.ui.screens.proactions.ProactionDetailContent
import com.maggie.app.ui.screens.proactions.ProactionDetailSheet
import com.maggie.app.ui.screens.proactions.ProactionScreen
import com.maggie.app.ui.screens.search.SearchScreen
import com.maggie.app.ui.screens.shared.EventCreateScreen
import com.maggie.app.ui.screens.shared.EventDetailContent
import com.maggie.app.ui.screens.shared.EventDetailSheet
import com.maggie.app.ui.screens.shared.EventEditScreen
import com.maggie.app.ui.screens.shared.RecurrenceAction
import com.maggie.app.ui.screens.shared.RecurrenceConfirmDialog
import com.maggie.app.ui.screens.shared.RecurringEventEditor
import com.maggie.app.ui.screens.shared.cancelledOccurrence
import com.maggie.app.ui.screens.shared.occurrenceStartKey
import com.maggie.app.ui.screens.shared.SubtaskActions
import com.maggie.app.ui.screens.shared.SubtasksViewModel
import com.maggie.app.ui.screens.shared.TaskCreateScreen
import com.maggie.app.ui.screens.shared.TaskDetailContent
import com.maggie.app.ui.screens.shared.TaskDetailSheet
import com.maggie.app.ui.screens.shared.TaskEditScreen
import com.maggie.app.ui.screens.tasks.BravoScreen
import com.maggie.app.ui.screens.tasks.BravoViewModel
import com.maggie.app.ui.screens.tasks.FocusScreen
import com.maggie.app.ui.screens.tasks.FocusViewModel
import com.maggie.app.ui.screens.tasks.RoutinesScreen
import com.maggie.app.ui.screens.tasks.RoutinesViewModel
import com.maggie.app.ui.screens.tasks.TasksScreen
import com.maggie.app.ui.screens.tasks.TasksViewModel
import com.maggie.app.ui.screens.login.LoginScreen
import com.maggie.app.ui.screens.login.LoginViewModel
import com.maggie.app.ui.screens.loading.LoadingScreen
import com.maggie.app.ui.screens.settings.SettingsScreen
import com.maggie.app.util.EventExpander
import com.maggie.app.util.RruleUtils
import com.maggie.app.voice.VoiceManager
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import org.koin.compose.koinInject
import java.time.Instant

sealed class Screen(val route: String, val label: String) {
    data object Loading : Screen("loading", "Chargement")
    data object Login : Screen("login", "Connexion")
    data object Home : Screen("home", "Accueil")
    data object Calendar : Screen("calendar", "Calendrier")
    data object Tasks : Screen("tasks", "Tâches")
    data object EventCreate : Screen("event/create", "Nouvel événement")
    data object TaskCreate : Screen("task/create", "Nouvelle tâche")
    data object EventEdit : Screen("event/edit", "Modifier l'événement")
    data object TaskEdit : Screen("task/edit", "Modifier la tâche")
    data object TaskFocus : Screen("task/focus", "Focus")
    data object TaskBravo : Screen("task/bravo", "Bravo")
    data object Chat : Screen("chat", "Chat")
    data object Settings : Screen("settings", "Paramètres")
    data object Notifications : Screen("notifications", "Notifications")
    data object Search : Screen("search", "Rechercher")
    data object Proactions : Screen("proactions", "Proactions")
    data object Cookbook : Screen("cookbook", "Cuisine")
    data object Grocery : Screen("grocery", "Courses")
    data object ProductList : Screen("products", "Produits")
    data object StoreList : Screen("stores", "Magasins")
    data object PastErrands : Screen("past_errands", "Courses passées")
    data object StoreAisles : Screen("stores/aisles", "Rayons")
    data object AccountList : Screen("accounts", "Comptes")
    data object Contacts : Screen("contacts", "Contacts")
    data object CategoryList : Screen("categories", "Catégories")
    data object BudgetList : Screen("budgets", "Budgets")
    data object CategorizationRuleList : Screen("categorization_rules", "Règles")
    data object RuleSuggestions : Screen("rule_suggestions", "Suggestions")
    /** La Revue (MAG-539): one list of the lines to qualify and the recognised ones, with its fiche. */
    data object ReviewList : Screen("finance_review_list", "Revue")
    data object RecurringOperations : Screen("recurring_operations", "Opérations récurrentes")
    data object BankConnectionList : Screen("bank_connections", "Banques")
    data object Cushion : Screen("cushion", "Matelas")
    data object Treasury : Screen("treasury", "Trésorerie")
    data object SavingsAdvances : Screen("savings_advances", "Avances d'épargne")
    data object PeerDebts : Screen("peer_debts", "Créances et dettes")
    data object LoanList : Screen("loans", "Prêts")
    data object ProjectList : Screen("finance_projects", "Projets")
    /** The full-screen form of a project (MAG-519): `id` absent for a new one. Outside the menus, so drawn without chrome. */
    data object ProjectForm : Screen("finance_projects/form?${DeepLinks.ARG_ID}={${DeepLinks.ARG_ID}}", "Projet") {
        fun of(projectId: String?): String =
            if (projectId == null) "finance_projects/form" else "finance_projects/form?${DeepLinks.ARG_ID}=$projectId"
    }
    /** The sheet of a project (MAG-525): its funding and its three priced options. Outside the menus, so drawn without chrome. */
    data object ProjectSheet : Screen("finance_projects/sheet/{${DeepLinks.ARG_ID}}", "Fiche du projet") {
        fun of(projectId: String): String = "finance_projects/sheet/$projectId"
    }
    /** The sheet of a loan (MAG-523): outside the menus, so drawn without chrome. */
    data object LoanDetail : Screen("finance_loans/{${DeepLinks.ARG_ID}}", "Prêt") {
        fun of(loanId: String): String = "finance_loans/$loanId"
    }
    data object PlacementList : Screen("finance_placements", "Placements")
    /** The full-screen form of a placement (MAG-555): `id` absent for a new one. Outside the menus, so drawn without chrome. */
    data object PlacementForm : Screen("finance_placements/form?${DeepLinks.ARG_ID}={${DeepLinks.ARG_ID}}", "Placement") {
        fun of(placementId: String?): String =
            if (placementId == null) "finance_placements/form" else "finance_placements/form?${DeepLinks.ARG_ID}=$placementId"
    }
    /** Activité Comptes · la revue (MAG-456): full screen, outside the Finance menus, so drawn without chrome. */
    data object FinanceReviewActivity : Screen("finance/review/activity", "Revue du mois")
    data object FinanceDashboard : Screen("finance_dashboard", "Finance")
    /** Planification · Indépendance (MAG-537): the independence counter and the net saving capacity, a sub-view of its own. */
    data object Independence : Screen("finance_independence", "Indépendance")
    data object AccountTransactions : Screen("account_transactions", "Transactions")
    data object RecipeDetail : Screen("recipe/detail", "Recette")
    data object RecipeCreate : Screen("recipe/create", "Nouvelle recette")
    data object RecipeEdit : Screen("recipe/edit", "Modifier la recette")
    data object MealCreate : Screen("meal/create", "Nouveau repas")
    data object MealIngredientChoice : Screen("meal/ingredients", "Ingrédients du repas")
}

private const val LINK_NOT_FOUND = "Cet élément n'existe plus."
private const val DEFAULT_ACCOUNT_NAME = "Compte"

private val MAIN_SCREENS = setOf(
    Screen.Home.route,
    Screen.Calendar.route,
    Screen.Tasks.route,
    Screen.Chat.route,
    Screen.Cookbook.route,
    Screen.Grocery.route,
)

// The full-screen chat has its own input: a second way in would duplicate it
// The Finance screens keep it too: the bottom of the screen is Maggie's; Finance has no rail to hold her buttons.
internal fun showsChatBottomBar(route: String?): Boolean =
    (route in MAIN_SCREENS && route != Screen.Chat.route) || FinanceSection.of(route) != null

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
    /** Details open as a sheet over the screen: the window has no pane for them, or the screen is the Accueil's. */
    val detailsAreSheets: Boolean,
)

// The screens whose list has a detail beside it. The Accueil's cards list events too, but it has no pane: their events stay sheets.
private val DETAIL_PANE_ROUTES = setOf(
    Screen.Calendar.route,
    Screen.Tasks.route,
    Screen.Cookbook.route,
    Screen.Grocery.route,
    Screen.AccountList.route,
    Screen.Contacts.route,
    Screen.PeerDebts.route,
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

/**
 * The route that opens an account from a link of the dashboard (the « N rejets » pill).
 *
 * With a detail pane the account opens in the list's pane: going through the detail route would let
 * it consume its one-shot request (the Incidents tab), then be folded into the list, which would
 * open the account afresh on Transactions.
 */
internal fun accountRouteFor(detailPaneFits: Boolean): String =
    if (detailPaneFits) Screen.AccountList.route else Screen.AccountTransactions.route

/** The Accueil keeps its own sheets: what a pane held while the window was wide must not open over it. */
internal fun dropsPaneSelection(route: String?, detailPaneFits: Boolean): Boolean =
    detailPaneFits && route == Screen.Home.route

/**
 * The detail route to open again once the window has lost its detail pane (MAG-263).
 *
 * Turning a phone from landscape back to portrait takes the pane away: the item that
 * was open in it must not vanish with it. `null` when nothing was open, or the route
 * is not one whose detail has a route of its own.
 */
internal fun reopensDetailRoute(route: String?, recipeOpen: Boolean, accountOpen: Boolean): String? =
    when {
        route == Screen.Cookbook.route && recipeOpen -> Screen.RecipeDetail.route
        route == Screen.AccountList.route && accountOpen -> Screen.AccountTransactions.route
        else -> null
    }

private val SelectionJson = Json { ignoreUnknownKeys = true }

@Composable
private inline fun <reified T : Any> rememberSavedSelection(): MutableState<T?> =
    rememberSaveable(
        stateSaver = Saver<T?, String>(
            save = { value -> value?.let { SelectionJson.encodeToString(it) } },
            restore = { runCatching { SelectionJson.decodeFromString<T>(it) }.getOrNull() },
        ),
    ) { mutableStateOf<T?>(null) }

/**
 * A route that shows a selection leaves when the selection is gone, instead of drawing nothing.
 *
 * Only while it is the current entry: the screen that just closed itself clears its
 * selection a frame before its exit animation ends, and must not pop a second time.
 */
@Composable
private fun PopWhenMissing(navController: NavController, entry: NavBackStackEntry, present: Boolean) {
    LaunchedEffect(present) {
        if (!present && navController.currentBackStackEntry == entry) navController.popBackStack()
    }
}

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
    // The rail serves the drawer's destinations, so it appears where the drawer
    // did: on the main screens. A detail route keeps the whole width, as today.
    val showsRail = layout.navigation == NavigationKind.RAIL && route in MAIN_SCREENS
    return Chrome(
        showsRail = showsRail,
        // Exactly one of the three, and the window says which: the panel *is* the
        // conversation, and the rail's header is where a window too short for a band
        // under the content puts the band's three buttons.
        showsChatPanel = chatReachable && layout.chatEntry == ChatEntry.PANEL,
        // A window too short for a band keeps the rail's header for the main screens; Finance has no rail (MAG-537),
        // so there the same window gets the compact bar.
        showsChatBar = chatReachable && (layout.chatEntry == ChatEntry.BOTTOM_BAR || (layout.chatEntry == ChatEntry.RAIL && !showsRail)),
        showsChatInRail = chatReachable && layout.chatEntry == ChatEntry.RAIL && showsRail,
        denseTopBar = layout.denseTopBar,
        showsDetailPane = layout.detailPaneFits && route in DETAIL_PANE_ROUTES,
        detailsAreSheets = !layout.detailPaneFits || route == Screen.Home.route,
    )
}

/**
 * The bottom of the route stops where the system's navigation bar starts (MAG-531).
 *
 * `ChatBottomBar` pays it when it is there. Without it — a tablet's chat is a panel, and a
 * route such as a form has none — nothing under the route did, and the launcher's
 * taskbar covered its last row. Either way it is consumed, so the route's own `Scaffold`s
 * and `navigationBarsPadding()` do not pay it a second time.
 */
@Composable
private fun Modifier.clearOfNavigationBar(paidByChatBar: Boolean): Modifier {
    val navigationBar = WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)
    return if (paidByChatBar) consumeWindowInsets(navigationBar) else windowInsetsPadding(navigationBar)
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

/** The Revue's single list and its fiche (MAG-539); out of [NavGraph]'s lambda, which the compiler already finds heavy. */
@Composable
private fun ReviewListPage(
    navController: NavController,
    preset: ReviewPreset?,
    onPresetConsumed: () -> Unit,
    showsDetailPane: Boolean,
    onOpenTransaction: (ReviewQueueItem) -> Unit,
    onOpenOperation: (ReviewQueueItem) -> Unit,
) {
    val viewModel: ReviewListViewModel = koinViewModel()
    ReviewListScreen(
        viewModel = viewModel,
        onBack = { navController.backInFinance() },
        preset = preset,
        onPresetConsumed = onPresetConsumed,
        showsDetailPane = showsDetailPane,
        onOpenTransaction = onOpenTransaction,
        onOpenOperation = onOpenOperation,
        onOpenRules = { navController.navigate(Screen.CategorizationRuleList.route) { launchSingleTop = true } },
        onOpenRecurring = { navController.navigate(Screen.RecurringOperations.route) { launchSingleTop = true } },
    )
}

/** « Projets » of Planification (MAG-519); out of [NavGraph]'s lambda, which the compiler already finds heavy. */
@Composable
private fun ProjectListPage(navController: NavController) {
    val projectViewModel: ProjectViewModel = koinViewModel()
    val scenarioViewModel: ScenarioViewModel = koinViewModel()
    ProjectListScreen(
        viewModel = projectViewModel,
        scenarios = scenarioViewModel,
        onBack = { navController.backInFinance() },
        onOpenForm = { id -> navController.navigate(Screen.ProjectForm.of(id)) { launchSingleTop = true } },
        onOpenSheet = { id -> navController.navigate(Screen.ProjectSheet.of(id)) { launchSingleTop = true } },
    )
}

/**
 * The sheet of a project (MAG-525), on the list's ViewModel: what it saves is what the list shows. Editing the
 * project leaves the sheet first, so the form's own « previous entry » is the list again.
 */
@Composable
private fun ProjectSheetPage(navController: NavController, entry: NavBackStackEntry) {
    val listEntry = remember(entry) { navController.previousBackStackEntry }
    val projectViewModel: ProjectViewModel = koinViewModel(viewModelStoreOwner = listEntry ?: entry)
    val projectId = entry.arguments?.getString(DeepLinks.ARG_ID)?.takeIf(DeepLinks::isValidId)
    DisposableEffect(projectViewModel) { onDispose { projectViewModel.closeSheet() } }
    if (projectId == null) {
        LaunchedEffect(Unit) { navController.popBackStack() }
        return
    }
    ProjectSheetScreen(
        viewModel = projectViewModel,
        projectId = projectId,
        onBack = { navController.popBackStack() },
        onEdit = { id ->
            navController.popBackStack()
            navController.navigate(Screen.ProjectForm.of(id)) { launchSingleTop = true }
        },
    )
}

/**
 * The full-screen form of a project, on the list's ViewModel: the form is only what is in front of
 * the list, and the list shows what it saved without a request of its own.
 */
@Composable
private fun ProjectFormPage(navController: NavController, entry: NavBackStackEntry) {
    // The list is the entry under the form.
    val listEntry = remember(entry) { navController.previousBackStackEntry }
    val projectViewModel: ProjectViewModel = koinViewModel(viewModelStoreOwner = listEntry ?: entry)
    ProjectFormScreen(
        viewModel = projectViewModel,
        projectId = entry.arguments?.getString(DeepLinks.ARG_ID)?.takeIf(DeepLinks::isValidId),
        onClose = { navController.popBackStack() },
    )
}

/** The sheet of a loan (MAG-523); leaving it refreshes the list it came from, which a save may have changed. */
@Composable
private fun LoanDetailPage(navController: NavController, entry: NavBackStackEntry) {
    val listEntry = remember(entry) { navController.previousBackStackEntry }
    val listViewModel: LoanViewModel = koinViewModel(viewModelStoreOwner = listEntry ?: entry)
    val loanId = entry.arguments?.getString(DeepLinks.ARG_ID)?.takeIf(DeepLinks::isValidId)
    val leave = {
        listViewModel.refresh()
        navController.popBackStack()
        Unit
    }
    BackHandler(onBack = leave)
    if (loanId == null) {
        LaunchedEffect(Unit) { navController.popBackStack() }
        return
    }
    LoanSheetScreen(viewModel = koinViewModel<LoanDetailViewModel>(), loanId = loanId, onBack = leave)
}

/** « Placements » of Planification (MAG-555), out of [NavGraph]'s lambda like the projects. */
@Composable
private fun PlacementListPage(navController: NavController) {
    val placementViewModel: PlacementViewModel = koinViewModel()
    PlacementListScreen(
        viewModel = placementViewModel,
        onBack = { navController.backInFinance() },
        onOpenForm = { id -> navController.navigate(Screen.PlacementForm.of(id)) { launchSingleTop = true } },
    )
}

/** The full-screen form of a placement, on the list's ViewModel: the list shows what it saved without a request of its own. */
@Composable
private fun PlacementFormPage(navController: NavController, entry: NavBackStackEntry) {
    val listEntry = remember(entry) { navController.previousBackStackEntry }
    val placementViewModel: PlacementViewModel = koinViewModel(viewModelStoreOwner = listEntry ?: entry)
    PlacementFormScreen(
        viewModel = placementViewModel,
        placementId = entry.arguments?.getString(DeepLinks.ARG_ID)?.takeIf(DeepLinks::isValidId),
        onClose = { navController.popBackStack() },
    )
}

// Out of NavGraph's body, like FinanceSummaryPage: that one function is already as large as the compiler takes.
@Composable
private fun ReviewActivityPage(navController: NavController, onOpenMaggie: () -> Unit) {
    ReviewActivityRoute(
        viewModel = koinViewModel<ReviewActivityViewModel>(),
        monthBalanceViewModel = koinViewModel<MonthBalanceViewModel>(),
        adjustmentsViewModel = koinViewModel<EnvelopeAdjustmentsViewModel>(),
        upcomingViewModel = koinViewModel<UpcomingViewModel>(),
        periodReviewViewModel = koinViewModel<PeriodReviewViewModel>(),
        onQuit = { navController.popBackStack() },
        onOpenMaggie = onOpenMaggie,
        onOpen = { route -> navController.navigate(route) { launchSingleTop = true } },
    )
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
    val financeSubviews = rememberFinanceSubviews()
    val scope = rememberCoroutineScope()

    val authRepository: AuthRepository = koinInject()
    val isAuthenticated by authRepository.isAuthenticated.collectAsState(initial = null)
    val loginViewModel: LoginViewModel = koinViewModel()

    val biometricLockManager: BiometricLockManager = koinInject()
    val isLocked by biometricLockManager.isLocked.collectAsState()

    val chatViewModel: ChatViewModel = koinViewModel()
    val contextViewModel: ContextViewModel = koinViewModel()
    val homeViewModel: HomeViewModel = koinViewModel()
    val calendarViewModel: FullCalendarViewModel = koinViewModel()
    val tasksViewModel: TasksViewModel = koinViewModel()
    val focusViewModel: FocusViewModel = koinViewModel()
    val bravoViewModel: BravoViewModel = koinViewModel()
    val routinesViewModel: RoutinesViewModel = koinViewModel()
    val notificationViewModel: NotificationViewModel = koinViewModel()
    val recipeListViewModel: RecipeListViewModel = koinViewModel()
    val mealsWeekViewModel: MealsWeekViewModel = koinViewModel()
    val mealCreateViewModel: MealCreateViewModel = koinViewModel()
    val mealDetailViewModel: MealDetailViewModel = koinViewModel()
    val mealsCalendarViewModel: FullCalendarViewModel = koinViewModel(key = "mealsCalendar") { parametersOf("cookbook") }
    val groceryViewModel: GroceryViewModel = koinViewModel()
    val subtasksViewModel: SubtasksViewModel = koinViewModel()
    val recurringGroceryViewModel: RecurringGroceryViewModel = koinViewModel()
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

    // The chat follows the thread list: a thread being deleted takes its messages off the
    // screen, « Annuler » brings them back (MAG-342).
    LaunchedEffect(Unit) {
        contextViewModel.threadEvents.collect { chatViewModel.onThreadEvent(it) }
    }

    // Sheet states (kept as overlays)
    // Selections are saved: a rotation recreates the activity and restores the back stack, so a detail route
    // restored over a forgotten selection would be a blank screen.
    var selectedEvent by rememberSavedSelection<ExpandedEvent>()
    var selectedTask by rememberSavedSelection<Task>()
    // One of Maggie's runs: kept by id and read from the calendar's list, so it follows a move and closes on a delete.
    var selectedProactionId by rememberSaveable { mutableStateOf<String?>(null) }
    var recurrenceConfirm by remember { mutableStateOf<Pair<ExpandedEvent, Boolean>?>(null) }

    // Transient state for edit screens
    var editingEvent by rememberSavedSelection<ExpandedEvent>()
    var editingRecurrenceAction by rememberSaveable { mutableStateOf<RecurrenceAction?>(null) }
    val recurringEventEditor = remember(eventRepository) { RecurringEventEditor(eventRepository) }
    var editingTask by rememberSavedSelection<Task>()
    // The task in focus (MAG-499): kept by id, the Focus reads it whole.
    var focusTaskId by rememberSaveable { mutableStateOf<String?>(null) }

    // The subtasks of the task open, wherever it is drawn: sheet, pane or edit screen (MAG-404).
    val subtasksState by subtasksViewModel.uiState.collectAsState()
    val subtaskActions = remember(subtasksViewModel) { SubtaskActions.of(subtasksViewModel) }
    LaunchedEffect(selectedTask?.id) { selectedTask?.let(subtasksViewModel::load) }

    // Cookbook transient state
    var selectedAccount by rememberSaveable { mutableStateOf<Pair<String, String>?>(null) }
    // The contact a link asked for, handed to the contacts screen once and cleared (MAG-450).
    var linkedContactId by rememberSaveable { mutableStateOf<String?>(null) }
    var startContactCreation by rememberSaveable { mutableStateOf(false) }
    // A line of the account timeline was touched (MAG-422): the account's full list opens, on that line when given.
    var accountDrillDown by rememberSaveable { mutableStateOf(false) }
    // The transaction « En faire une opération récurrente » was tapped on: the form opens filled from it (MAG-309).
    var recurringSeed by rememberSavedSelection<Transaction>()
    // The advance « Préparer l'avance » filled in: the new-transaction form opens with it, nothing saved (MAG-280).
    var transactionPrefill by rememberSavedSelection<TransactionPrefill>()
    // The line « Voir la fiche » was tapped on in « À qualifier »: its account opens with the sheet of that line.
    var transactionToOpen by rememberSaveable { mutableStateOf<String?>(null) }
    // Set by the « N rejets » pill: the account opens on its Incidents tab.
    var incidentsToOpen by rememberSaveable { mutableStateOf(false) }
    // The series « Voir l'opération récurrente » was tapped on in « Reconnues à contrôler »: its editor opens once the list has loaded.
    var seriesToOpen by rememberSaveable { mutableStateOf<String?>(null) }
    // Where the Revue's list opens (MAG-539): set by the summary block, a deep link or a todo pill, cleared once the list took it.
    var reviewPreset by rememberSaveable { mutableStateOf<ReviewPreset?>(null) }
    val openReview: (ReviewPreset) -> Unit = { preset ->
        reviewPreset = preset
        navController.navigate(Screen.ReviewList.route) { launchSingleTop = true }
    }
    var detailRecipeId by rememberSaveable { mutableStateOf<String?>(null) }
    var groceryItemToOpen by rememberSaveable { mutableStateOf<String?>(null) }
    var groceryPaneItemId by rememberSaveable { mutableStateOf<String?>(null) }
    // Read through a State: the NavHost graph is rebuilt when the builder's captures change.
    val paneShown by rememberUpdatedState(chrome.showsDetailPane)
    val paneFits by rememberUpdatedState(layout.detailPaneFits)
    val openRecurringFrom: (Transaction) -> Unit = { transaction ->
        recurringSeed = transaction
        navController.navigate(Screen.RecurringOperations.route) { launchSingleTop = true }
    }
    var editRecipeId by rememberSaveable { mutableStateOf<String?>(null) }
    var mealCreateState by rememberSaveable { mutableStateOf<Pair<String, String>?>(null) }
    var openedMeal by remember { mutableStateOf<Meal?>(null) }
    var mealToChooseIngredientsOf by rememberSaveable { mutableStateOf<String?>(null) }
    // The store whose aisles are open (MAG-493): its id, and its name for the title.
    var aislesStoreId by rememberSaveable { mutableStateOf<String?>(null) }
    var aislesStoreName by rememberSaveable { mutableStateOf("") }

    val calendarUiState by calendarViewModel.uiState.collectAsState()
    val agendas = calendarUiState.agendas
    val selectedProaction = calendarUiState.proactions.firstOrNull { it.id == selectedProactionId }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            voiceModeActive = true
            showChatSheet = true
        }
    }

    // Auth redirect — wait until lock screen is dismissed so we don't
    // overwrite the restored navigation state with a fresh Accueil route.
    // Keyed on the route too: a deep link opened from a cold start sits on top of Loading,
    // and going back to it must land on the Accueil, not on a spinner.
    LaunchedEffect(isAuthenticated, isLocked, currentRoute) {
        val liveRoute = navController.currentBackStackEntry?.destination?.route
        when (authRedirect(isAuthenticated, isLocked, liveRoute)) {
            AuthRedirect.LOGIN -> navController.navigate(Screen.Login.route) {
                popUpTo(0) { inclusive = true }
            }
            AuthRedirect.HOME -> navController.navigate(Screen.Home.route) {
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
        Screen.Home.route -> Screen.Home.label
        Screen.Calendar.route -> Screen.Calendar.label
        Screen.Tasks.route -> Screen.Tasks.label
        Screen.Chat.route -> Screen.Chat.label
        Screen.Cookbook.route -> Screen.Cookbook.label
        Screen.Grocery.route -> Screen.Grocery.label
        else -> "Maggie"
    }

    fun refreshAll() {
        calendarViewModel.refresh()
        // The tasks screen reads a view that a tick, an edit or a deletion moves tasks out of.
        if (currentRoute == Screen.Tasks.route) tasksViewModel.refresh()
    }

    fun navigateTo(route: String) {
        navController.navigate(route) { launchSingleTop = true }
    }

    // The sections are siblings: Dashboard → Trésorerie → Revue goes back to the Dashboard in one step.
    fun selectFinanceSection(section: FinanceSection) {
        val dashboardInBackStack = runCatching { navController.getBackStackEntry(Screen.FinanceDashboard.route) }.isSuccess
        val action = financeSectionAction(section, dashboardInBackStack)
        val currentId = navController.currentBackStackEntry?.destination?.id
        navController.navigate(action.route) {
            if (action.popToDashboard) {
                popUpTo(Screen.FinanceDashboard.route) { inclusive = false }
            } else if (currentId != null) {
                popUpTo(currentId) { inclusive = true }
            }
            launchSingleTop = true
        }
    }

    // A sub-view with a route of its own replaces the page it is on; the others are a state the graph keeps.
    fun selectFinanceSubview(section: FinanceSection, subview: FinanceSubview) {
        val route = subview.route
        if (route == null) {
            financeSubviews.select(section, subview.key)
            return
        }
        val currentId = navController.currentBackStackEntry?.destination?.id
        navController.navigate(route) {
            if (currentId != null) popUpTo(currentId) { inclusive = true }
            launchSingleTop = true
        }
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

    // « Lancer » on the review appointment (MAG-456): the activity opens full screen, the detail closes behind it.
    fun launchActivity(event: ExpandedEvent) {
        if (!event.launchesFinanceReview) return
        selectedEvent = null
        navController.navigate(Screen.FinanceReviewActivity.route) { launchSingleTop = true }
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

    // The pane's selection is not the Accueil's sheet: entering the Accueil on a wide window starts clean.
    LaunchedEffect(currentRoute) {
        if (dropsPaneSelection(currentRoute, layout.detailPaneFits)) {
            selectedEvent = null
            selectedTask = null
            selectedProactionId = null
        }
    }

    // Unfolding with a detail route on screen: the list takes its place and the detail moves into the pane.
    LaunchedEffect(currentRoute, layout.detailPaneFits) {
        val route = currentRoute ?: return@LaunchedEffect
        val list = foldsDetailRouteIntoPane(route, layout.detailPaneFits) ?: return@LaunchedEffect
        navController.foldRouteInto(route, list)
    }

    // Turning back to a window without a pane: what was open in it opens again, as a route or a sheet.
    // Saved, because the rotation that takes the pane away recreates the activity.
    var paneWasFitting by rememberSaveable { mutableStateOf(layout.detailPaneFits) }
    LaunchedEffect(currentRoute, layout.detailPaneFits) {
        if (layout.detailPaneFits) {
            paneWasFitting = true
            return@LaunchedEffect
        }
        // The graph is not set yet on the first frames after a recreation.
        val route = currentRoute ?: return@LaunchedEffect
        if (!paneWasFitting) return@LaunchedEffect
        paneWasFitting = false
        reopensDetailRoute(route, detailRecipeId != null, selectedAccount != null)
            ?.let { navController.navigate(it) { launchSingleTop = true } }
        if (route == Screen.Grocery.route) {
            groceryPaneItemId?.let {
                groceryItemToOpen = it
                groceryPaneItemId = null
            }
        }
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

    val financeNav = FinanceNav(
        subviews = financeSubviews,
        onSelectSection = ::selectFinanceSection,
        onSelectSubview = ::selectFinanceSubview,
        onOpenDrawer = { scope.launch { drawerState.open() } },
    )
    CompositionLocalProvider(LocalFinanceNav provides financeNav) {
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
                        onOpenThreads = { showContextSheet = true },
                    )
                }
            } else {
                null
            },
        ) {
            Scaffold(
                modifier = Modifier.weight(1f),
                // Nothing from here: each piece pays its own inset (`ChatBottomBar`, `ChatPanel`,
                // `ChatScreen`), and the route's bottom is `clearOfNavigationBar`'s. A `Scaffold`
                // nested in a route (Cuisine, Calendrier) keeps its own `safeDrawing` for the rest,
                // minus the navigation bar that is consumed above it. Material 3 1.3 does not
                // consume `contentWindowInsets` for the body, so handing one down here would pay
                // the gesture bar twice.
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
                        )
                    }
                },
            ) { paddingValues ->
                ModuleNavHost(
                    route = currentRoute,
                    navController = navController,
                    startDestination = Screen.Loading.route,
                    modifier = Modifier.padding(paddingValues).clearOfNavigationBar(paidByChatBar = chrome.showsChatBar),
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
                    // « Je m'y mets » of a task reminder (MAG-510): the Focus of that task, over wherever the app was.
                    composable(DeepLinks.TASK_FOCUS_ROUTE, deepLinks = DeepLinks.forRoute(DeepLinks.TASK_FOCUS_ROUTE)) { entry ->
                        val id = entry.arguments?.getString(DeepLinks.ARG_ID)
                        LoadingScreen()
                        LaunchedEffect(id, linkReady) {
                            if (!linkReady) return@LaunchedEffect
                            if (DeepLinks.isValidId(id)) {
                                focusTaskId = id
                                navController.replaceLink(entry, Screen.TaskFocus.route, singleTop = true)
                            } else {
                                Toast.makeText(context, LINK_NOT_FOUND, Toast.LENGTH_SHORT).show()
                                navController.replaceLink(entry, Screen.Calendar.route, singleTop = true)
                            }
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
                    composable(DeepLinks.CONTACT_ROUTE, deepLinks = DeepLinks.forRoute(DeepLinks.CONTACT_ROUTE)) { entry ->
                        val id = entry.arguments?.getString(DeepLinks.ARG_ID)
                        LoadingScreen()
                        LaunchedEffect(id, linkReady) {
                            if (!linkReady) return@LaunchedEffect
                            if (DeepLinks.isValidId(id)) {
                                linkedContactId = id
                            } else {
                                Toast.makeText(context, LINK_NOT_FOUND, Toast.LENGTH_SHORT).show()
                            }
                            navController.replaceLink(entry, Screen.Contacts.route, singleTop = true)
                        }
                    }
                    composable(Screen.Home.route, deepLinks = DeepLinks.forRoute(Screen.Home.route)) {
                        HomeScreen(
                            viewModel = homeViewModel,
                            onNavigate = { route -> navController.navigate(route) { launchSingleTop = true } },
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
                        val proaction = selectedProaction
                        ListDetailPane(
                            showsDetailPane = paneShown,
                            list = {
                                FullCalendarScreen(
                                    viewModel = calendarViewModel,
                                    onCreateEvent = { navController.navigate(Screen.EventCreate.route) },
                                    onCreateTask = { navController.navigate(Screen.TaskCreate.route) },
                                    onOpenTasks = { navigateTo(Screen.Tasks.route) },
                                    onEventClick = {
                                        // A meal is edited in the Cuisine module, not as an event.
                                        if (it.agendaIri == PROACTIONS_FILTER_ID) {
                                            selectedTask = null
                                            selectedEvent = null
                                            selectedProactionId = it.id
                                        } else if (it.agendaIri != MEALS_FILTER_ID) {
                                            selectedTask = null
                                            selectedProactionId = null
                                            selectedEvent = it
                                        }
                                    },
                                )
                            },
                            detail = when {
                                proaction != null -> ({
                                    ProactionDetailContent(
                                        proaction = proaction,
                                        onMove = { calendarViewModel.moveProaction(proaction.id, it) },
                                        onDelete = {
                                            selectedProactionId = null
                                            calendarViewModel.deleteProaction(proaction.id)
                                        },
                                        modifier = Modifier.verticalScroll(rememberScrollState()),
                                    )
                                })
                                event != null -> ({
                                    EventDetailContent(
                                        event = event,
                                        onEdit = { editEvent(event) },
                                        onDelete = { deleteEvent(event) },
                                        modifier = Modifier.verticalScroll(rememberScrollState()),
                                        onLaunch = { launchActivity(event) },
                                    )
                                })
                                task != null -> ({
                                    TaskDetailContent(
                                        task = task,
                                        subtasks = subtasksState,
                                        subtaskActions = subtaskActions,
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
                    composable(Screen.Tasks.route, deepLinks = DeepLinks.forRoute(Screen.Tasks.route)) {
                        val task = selectedTask
                        ListDetailPane(
                            showsDetailPane = paneShown,
                            list = {
                                TasksScreen(
                                    viewModel = tasksViewModel,
                                    onTaskClick = {
                                        selectedEvent = null
                                        selectedProactionId = null
                                        selectedTask = it
                                    },
                                    onFocus = {
                                        focusTaskId = it
                                        navController.navigate(Screen.TaskFocus.route) { launchSingleTop = true }
                                    },
                                    onBravo = {
                                        bravoViewModel.open(it)
                                        navController.navigate(Screen.TaskBravo.route) { launchSingleTop = true }
                                    },
                                    routines = { RoutinesScreen(viewModel = routinesViewModel) },
                                )
                            },
                            detail = task?.let { open ->
                                {
                                    TaskDetailContent(
                                        task = open,
                                        subtasks = subtasksState,
                                        subtaskActions = subtaskActions,
                                        onEdit = { editTask(open) },
                                        onDelete = { deleteTask(open) },
                                        onToggleDone = { done -> toggleTaskDone(open, done) },
                                        modifier = Modifier.verticalScroll(rememberScrollState()),
                                    )
                                }
                            },
                            placeholder = "Touchez une tâche pour la voir ici.",
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
                    composable(Screen.TaskFocus.route) { entry ->
                        val focusId = focusTaskId
                        PopWhenMissing(navController, entry, focusId != null)
                        if (focusId != null) {
                            FocusScreen(
                                taskId = focusId,
                                viewModel = focusViewModel,
                                onExit = {
                                    focusViewModel.close()
                                    focusTaskId = null
                                    navController.popBackStack()
                                },
                                // « C'est fait »: the Bravo takes the Focus's place, so back from it is the day.
                                onDone = {
                                    focusViewModel.close()
                                    focusTaskId = null
                                    bravoViewModel.open(it)
                                    navController.navigate(Screen.TaskBravo.route) {
                                        popUpTo(Screen.TaskFocus.route) { inclusive = true }
                                        launchSingleTop = true
                                    }
                                },
                            )
                        }
                    }
                    // The Bravo (MAG-508): « Suivante » opens the next task's Focus in its place, « Pause méritée » the day.
                    composable(Screen.TaskBravo.route) { entry ->
                        val bravo by bravoViewModel.uiState.collectAsState()
                        PopWhenMissing(navController, entry, bravo.taskId != null)
                        if (bravo.taskId != null) {
                            BravoScreen(
                                viewModel = bravoViewModel,
                                onNext = { taskId ->
                                    bravoViewModel.close()
                                    focusTaskId = taskId
                                    navController.navigate(Screen.TaskFocus.route) {
                                        popUpTo(Screen.TaskBravo.route) { inclusive = true }
                                        launchSingleTop = true
                                    }
                                },
                                onPause = {
                                    bravoViewModel.close()
                                    tasksViewModel.selectView(TaskView.TODAY)
                                    navController.popBackStack()
                                },
                            )
                        }
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
                    composable(Screen.EventEdit.route) { entry ->
                        val event = editingEvent
                        PopWhenMissing(navController, entry, event != null)
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
                    composable(Screen.TaskEdit.route) { entry ->
                        val task = editingTask
                        PopWhenMissing(navController, entry, task != null)
                        if (task != null) {
                            LaunchedEffect(task.id) { subtasksViewModel.load(task) }
                            TaskEditScreen(
                                task = task,
                                subtasks = subtasksState,
                                subtaskActions = subtaskActions,
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
                                    mealsCalendarViewModel = mealsCalendarViewModel,
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
                                    onOpenMeal = { meal ->
                                        // Started before the sheet draws: a meal reopened never shows the last opening's state.
                                        mealDetailViewModel.open(meal)
                                        openedMeal = meal
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
                        val recurringState by recurringGroceryViewModel.uiState.collectAsState()
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
                                        recurringViewModel = recurringGroceryViewModel,
                                        openItemId = groceryItemToOpen,
                                        onOpenItemHandled = { groceryItemToOpen = null },
                                        onNavigateToProducts = {
                                            navController.navigate(Screen.ProductList.route) { launchSingleTop = true }
                                        },
                                        onNavigateToStores = {
                                            navController.navigate(Screen.StoreList.route) { launchSingleTop = true }
                                        },
                                        onNavigateToPastErrands = {
                                            navController.navigate(Screen.PastErrands.route) { launchSingleTop = true }
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
                                            onSave = { label, quantity, storeId, storeName, category ->
                                                item.id?.let { id ->
                                                    groceryViewModel.updateItem(id, label, quantity?.quantity, quantity?.unit, storeId, storeName, category, quantity?.packagings)
                                                }
                                                groceryPaneItemId = null
                                            },
                                            modifier = Modifier.verticalScroll(rememberScrollState()),
                                            recurring = recurringState.forProduct(item.product?.id)?.frequency,
                                            onRecurringChange = item.product?.id?.let { productId ->
                                                { frequency -> recurringGroceryViewModel.setFrequency(productId, frequency) }
                                            },
                                            aislesByStore = groceryState.aislesByStore,
                                            onLoadAisles = { groceryViewModel.loadAisles(it) },
                                            onSaveAisle = { storeId, aisle -> groceryViewModel.setItemAisle(item, storeId, aisle) },
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
                            recurringViewModel = recurringGroceryViewModel,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable(Screen.StoreList.route) {
                        val storeViewModel: StoreViewModel = koinViewModel()
                        StoreListScreen(
                            viewModel = storeViewModel,
                            onBack = { navController.popBackStack() },
                            onOpenAisles = { store ->
                                aislesStoreId = store.id
                                aislesStoreName = store.name
                                navController.navigate(Screen.StoreAisles.route) { launchSingleTop = true }
                            },
                        )
                    }
                    composable(Screen.PastErrands.route) {
                        val pastErrandsViewModel: PastErrandsViewModel = koinViewModel()
                        PastErrandsScreen(
                            viewModel = pastErrandsViewModel,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable(Screen.StoreAisles.route) { entry ->
                        val id = aislesStoreId
                        PopWhenMissing(navController, entry, id != null)
                        if (id != null) {
                            StoreAislesScreen(
                                storeName = aislesStoreName,
                                viewModel = koinViewModel<StoreAislesViewModel>(key = "aisles-$id") { parametersOf(id) },
                                onBack = { navController.popBackStack() },
                            )
                        }
                    }
                    composable(Screen.AccountList.route, deepLinks = DeepLinks.forRoute(Screen.AccountList.route)) {
                        val accountViewModel: AccountViewModel = koinViewModel()
                        val summaryViewModel: FinanceSummaryViewModel = koinViewModel()
                        val summaryBlocks by summaryViewModel.blocks.collectAsState()
                        LaunchedEffect(Unit) { summaryViewModel.load(listOf(SummaryBlock.LAST_SYNC)) }
                        val account = selectedAccount
                        val openContacts = {
                            navController.navigate(Screen.Contacts.route) { launchSingleTop = true }
                        }
                        val timelineActions = AccountTimelineActions(
                            onOpenTransaction = { accountId, accountName, transactionId ->
                                selectedAccount = accountId to accountName
                                transactionToOpen = transactionId
                                accountDrillDown = true
                                if (!paneShown) {
                                    navController.navigate(Screen.AccountTransactions.route) { launchSingleTop = true }
                                }
                            },
                        )
                        if (!paneShown) {
                            // One account at a time, swiped (MAG-422); a touch on a line goes on to its sheet.
                            AccountPagerScreen(
                                viewModel = accountViewModel,
                                onBack = { navController.backInFinance() },
                                actions = timelineActions,
                                onOpenContacts = openContacts,
                            )
                        } else {
                            ListDetailPane(
                                showsDetailPane = paneShown,
                                list = {
                                    AccountListScreen(
                                        viewModel = accountViewModel,
                                        onBack = { navController.backInFinance() },
                                        onOpenContacts = openContacts,
                                        lastSync = (summaryBlocks[SummaryBlock.LAST_SYNC] as? BlockState.Ready)?.lines?.firstOrNull(),
                                        onOpenAccount = { accountId, accountName ->
                                            selectedAccount = accountId to accountName
                                            accountDrillDown = false
                                            transactionToOpen = null
                                            accountViewModel.selectAccount(accountId)
                                        },
                                    )
                                },
                                detail = account?.let {
                                    {
                                        if (accountDrillDown || transactionPrefill != null) {
                                            AccountTransactions(
                                                it,
                                                onBack = {
                                                    accountDrillDown = false
                                                    transactionToOpen = null
                                                },
                                                onMakeRecurring = openRecurringFrom,
                                                prefill = transactionPrefill,
                                                onPrefillConsumed = { transactionPrefill = null },
                                                openTransactionId = transactionToOpen,
                                                onTransactionOpened = { transactionToOpen = null },
                                                onOpenPeerDebts = { navController.navigate(Screen.PeerDebts.route) { launchSingleTop = true } },
                                                openIncidents = incidentsToOpen,
                                                onIncidentsOpened = { incidentsToOpen = false },
                                            )
                                        } else {
                                            AccountTimelineDetail(
                                                viewModel = accountViewModel,
                                                accountId = it.first,
                                                actions = timelineActions,
                                            )
                                        }
                                    }
                                },
                                placeholder = "Touchez un compte pour voir ses transactions ici.",
                            )
                        }
                    }
                    composable(Screen.Contacts.route, deepLinks = DeepLinks.forRoute(Screen.Contacts.route)) {
                        val contactViewModel: ContactViewModel = koinViewModel()
                        ContactScreen(
                            viewModel = contactViewModel,
                            onBack = { navController.backInFinance() },
                            showsDetailPane = paneShown,
                            openContactId = linkedContactId,
                            onOpenConsumed = { linkedContactId = null },
                            startCreation = startContactCreation,
                            onCreationStarted = { startContactCreation = false },
                        )
                    }
                    composable(Screen.PeerDebts.route, deepLinks = DeepLinks.forRoute(Screen.PeerDebts.route)) {
                        val peerDebtsViewModel: PeerDebtsViewModel = koinViewModel()
                        PeerDebtsScreen(
                            viewModel = peerDebtsViewModel,
                            onBack = { navController.backInFinance() },
                            showsDetailPane = paneShown,
                            onOpenContact = { id ->
                                linkedContactId = id
                                navController.navigate(Screen.Contacts.route) { launchSingleTop = true }
                            },
                            onAddPerson = {
                                startContactCreation = true
                                navController.navigate(Screen.Contacts.route) { launchSingleTop = true }
                            },
                        )
                    }
                    composable(Screen.CategoryList.route, deepLinks = DeepLinks.forRoute(Screen.CategoryList.route)) {
                        val categoryViewModel: CategoryViewModel = koinViewModel()
                        CategoryListScreen(
                            viewModel = categoryViewModel,
                            onBack = { navController.backInFinance() },
                            showsDetailPane = paneShown,
                            onOpenRules = {
                                navController.navigate(Screen.CategorizationRuleList.route) {
                                    launchSingleTop = true
                                }
                            },
                        )
                    }
                    composable(Screen.BudgetList.route, deepLinks = DeepLinks.forRoute(Screen.BudgetList.route)) {
                        val budgetViewModel: BudgetViewModel = koinViewModel()
                        val toQualify by koinViewModel<ToQualifyViewModel>().pile.collectAsState()
                        BudgetScreen(
                            viewModel = budgetViewModel,
                            onBack = { navController.backInFinance() },
                            canEditSplit = paneShown,
                            showsDetailPane = paneShown,
                            toQualify = toQualify,
                            onOpenToQualify = { openReview(ReviewPreset.TO_SORT) },
                            adjustments = koinViewModel<EnvelopeAdjustmentsViewModel>(),
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
                    composable(Screen.ReviewList.route, deepLinks = DeepLinks.forRoute(Screen.ReviewList.route)) {
                        ReviewListPage(
                            navController = navController,
                            preset = reviewPreset,
                            onPresetConsumed = { reviewPreset = null },
                            showsDetailPane = paneShown,
                            onOpenTransaction = { line ->
                                line.accountId?.let { accountId ->
                                    selectedAccount = accountId to (line.accountName?.takeIf { it.isNotBlank() } ?: DEFAULT_ACCOUNT_NAME)
                                    transactionToOpen = line.id
                                    accountDrillDown = true
                                    navController.navigate(Screen.AccountTransactions.route) { launchSingleTop = true }
                                }
                            },
                            onOpenOperation = { line ->
                                // A series has its editor; a rule has no screen of its own, only the list of the rules.
                                val seriesId = line.qualifiedBySeriesId
                                if (line.qualifiedBy == OPERATION_SERIES && seriesId != null) {
                                    seriesToOpen = seriesId
                                    navController.navigate(Screen.RecurringOperations.route) { launchSingleTop = true }
                                } else {
                                    navController.navigate(Screen.CategorizationRuleList.route) { launchSingleTop = true }
                                }
                            },
                        )
                    }
                    // The old addresses of the three screens the Revue's list replaced: each opens it, pre-filtered (MAG-539).
                    listOf(
                        DeepLinks.REVIEW_TO_SORT_ROUTE to ReviewPreset.TO_SORT,
                        DeepLinks.REVIEW_TO_CONTROL_ROUTE to ReviewPreset.TO_CONTROL,
                        DeepLinks.REVIEW_MONTHLY_ROUTE to ReviewPreset.MONTHLY,
                    ).forEach { (route, preset) ->
                        composable(route, deepLinks = DeepLinks.forRoute(route)) { entry ->
                            LoadingScreen()
                            LaunchedEffect(linkReady) {
                                if (!linkReady) return@LaunchedEffect
                                reviewPreset = preset
                                navController.replaceLink(entry, Screen.ReviewList.route, singleTop = true)
                            }
                        }
                    }
                    listOf(
                        DeepLinks.FINANCE_DASHBOARD_SUBVIEW_ROUTE to FinanceSection.DASHBOARD,
                        DeepLinks.FINANCE_BUDGET_SUBVIEW_ROUTE to FinanceSection.BUDGET,
                    ).forEach { (route, section) ->
                        composable(route, deepLinks = DeepLinks.forRoute(route)) { entry ->
                            val key = entry.arguments?.getString(DeepLinks.ARG_SUBVIEW)
                            LoadingScreen()
                            LaunchedEffect(linkReady) {
                                if (!linkReady) return@LaunchedEffect
                                // An unknown sub-view still lands on the section.
                                section.subview(key)?.takeIf { it.route == null }?.let { financeSubviews.select(section, it.key) }
                                navController.replaceLink(entry, section.home, singleTop = true)
                            }
                        }
                    }
                    composable(Screen.RecurringOperations.route, deepLinks = DeepLinks.forRoute(Screen.RecurringOperations.route)) {
                        val recurringViewModel: RecurringOperationViewModel = koinViewModel()
                        RecurringOperationListScreen(
                            viewModel = recurringViewModel,
                            onBack = { navController.backInFinance() },
                            seed = recurringSeed,
                            onSeedConsumed = { recurringSeed = null },
                            onBackToTransaction = { navController.popBackStack() },
                            openSeriesId = seriesToOpen,
                            onSeriesOpened = { seriesToOpen = null },
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
                    composable(Screen.Treasury.route, deepLinks = DeepLinks.forRoute(Screen.Treasury.route)) {
                        val treasuryViewModel: TreasuryViewModel = koinViewModel()
                        TreasuryScreen(
                            viewModel = treasuryViewModel,
                            onBack = { navController.backInFinance() },
                            onOpenCushion = { navController.navigate(Screen.Cushion.route) { launchSingleTop = true } },
                            onOpenSavingsAdvances = { navController.navigate(Screen.SavingsAdvances.route) { launchSingleTop = true } },
                            onPrepareAdvance = { accountId, accountName, prefill ->
                                selectedAccount = accountId to accountName.ifBlank { DEFAULT_ACCOUNT_NAME }
                                transactionPrefill = prefill
                                navController.navigate(Screen.AccountTransactions.route) { launchSingleTop = true }
                            },
                        )
                    }
                    composable(Screen.SavingsAdvances.route, deepLinks = DeepLinks.forRoute(Screen.SavingsAdvances.route)) {
                        val savingsAdvancesViewModel: SavingsAdvancesViewModel = koinViewModel()
                        SavingsAdvancesScreen(
                            viewModel = savingsAdvancesViewModel,
                            onBack = { navController.backInFinance() },
                        )
                    }
                    composable(Screen.LoanList.route, deepLinks = DeepLinks.forRoute(Screen.LoanList.route)) {
                        val loanViewModel: LoanViewModel = koinViewModel()
                        LoanListScreen(
                            viewModel = loanViewModel,
                            onOpenLoan = { id -> navController.navigate(Screen.LoanDetail.of(id)) { launchSingleTop = true } },
                            onBack = { navController.backInFinance() },
                        )
                    }
                    composable(
                        Screen.LoanDetail.route,
                        arguments = listOf(navArgument(DeepLinks.ARG_ID) { type = NavType.StringType }),
                    ) { entry ->
                        LoanDetailPage(navController, entry)
                    }
                    composable(Screen.ProjectList.route, deepLinks = DeepLinks.forRoute(Screen.ProjectList.route)) {
                        ProjectListPage(navController)
                    }
                    composable(
                        Screen.ProjectSheet.route,
                        arguments = listOf(navArgument(DeepLinks.ARG_ID) { type = NavType.StringType }),
                    ) { entry ->
                        ProjectSheetPage(navController, entry)
                    }
                    composable(
                        Screen.ProjectForm.route,
                        arguments = listOf(
                            navArgument(DeepLinks.ARG_ID) {
                                type = NavType.StringType
                                nullable = true
                                defaultValue = null
                            },
                        ),
                    ) { entry ->
                        ProjectFormPage(navController, entry)
                    }
                    composable(Screen.PlacementList.route, deepLinks = DeepLinks.forRoute(Screen.PlacementList.route)) {
                        PlacementListPage(navController)
                    }
                    composable(
                        Screen.PlacementForm.route,
                        arguments = listOf(
                            navArgument(DeepLinks.ARG_ID) {
                                type = NavType.StringType
                                nullable = true
                                defaultValue = null
                            },
                        ),
                    ) { entry ->
                        PlacementFormPage(navController, entry)
                    }
                    composable(Screen.FinanceReviewActivity.route, deepLinks = DeepLinks.forRoute(Screen.FinanceReviewActivity.route)) {
                        ReviewActivityPage(navController, onOpenMaggie = { showChatSheet = true })
                    }
                    composable(Screen.Independence.route, deepLinks = DeepLinks.forRoute(Screen.Independence.route)) {
                        IndependenceScreen(koinViewModel<FinanceDashboardViewModel>())
                    }
                    composable(Screen.FinanceDashboard.route, deepLinks = DeepLinks.forRoute(Screen.FinanceDashboard.route)) {
                        val dashboardViewModel: FinanceDashboardViewModel = koinViewModel()
                        FinanceDashboardScreen(
                            viewModel = dashboardViewModel,
                            monthBalanceViewModel = koinViewModel<MonthBalanceViewModel>(),
                            upcomingViewModel = koinViewModel<UpcomingViewModel>(),
                            onOpen = { route -> navController.navigate(route) { launchSingleTop = true } },
                            onOpenIncidents = { account ->
                                selectedAccount = account.id to account.name.ifBlank { DEFAULT_ACCOUNT_NAME }
                                incidentsToOpen = true
                                accountDrillDown = true
                                navController.navigate(accountRouteFor(paneFits)) { launchSingleTop = true }
                            },
                        )
                    }
                    composable(Screen.AccountTransactions.route) { entry ->
                        val account = selectedAccount
                        // With a pane the fold effect replaces this route by the list: popping too would take the list away.
                        PopWhenMissing(navController, entry, account != null || paneFits)
                        val leave = {
                            selectedAccount = null
                            accountDrillDown = false
                            transactionToOpen = null
                            incidentsToOpen = false
                            navController.backInFinance()
                        }
                        BackHandler(onBack = leave)
                        if (account != null) {
                            AccountTransactions(
                                account,
                                onBack = leave,
                                onMakeRecurring = openRecurringFrom,
                                prefill = transactionPrefill,
                                onPrefillConsumed = { transactionPrefill = null },
                                openTransactionId = transactionToOpen,
                                onTransactionOpened = { transactionToOpen = null },
                                onOpenPeerDebts = { navController.navigate(Screen.PeerDebts.route) { launchSingleTop = true } },
                                openIncidents = incidentsToOpen,
                                onIncidentsOpened = { incidentsToOpen = false },
                            )
                        }
                    }
                    composable(Screen.RecipeDetail.route) { entry ->
                        val id = detailRecipeId
                        PopWhenMissing(navController, entry, id != null || paneFits)
                        val leave = {
                            detailRecipeId = null
                            navController.popBackStack()
                        }
                        BackHandler(onBack = { leave() })
                        if (id != null) {
                            RecipeDetailScreen(
                                recipeId = id,
                                recipeRepository = recipeRepository,
                                viewModel = koinViewModel<RecipeDetailViewModel>(key = id) { parametersOf(id) },
                                onBack = { leave() },
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
                            viewModel = koinViewModel<RecipeCreateViewModel>(),
                            onSaved = {
                                navController.popBackStack()
                                recipeListViewModel.refresh()
                            },
                            onBack = { navController.popBackStack() },
                            onSearchCiqual = { query -> apiService.searchCiqualFoods(query) },
                            onLoadUtensils = { recipeRepository.getUtensils().getOrThrow() },
                        )
                    }
                    composable(Screen.MealIngredientChoice.route) { entry ->
                        val id = mealToChooseIngredientsOf
                        PopWhenMissing(navController, entry, id != null)
                        if (id != null) {
                            MealIngredientChoiceScreen(
                                viewModel = koinViewModel<MealIngredientChoiceViewModel>(key = id) { parametersOf(id) },
                                onLater = { navController.popBackStack() },
                                onDone = {
                                    navController.popBackStack()
                                    groceryViewModel.refresh()
                                },
                            )
                        }
                    }
                    composable(Screen.RecipeEdit.route) { entry ->
                        val id = editRecipeId
                        PopWhenMissing(navController, entry, id != null)
                        if (id != null) {
                            RecipeEditScreen(
                                viewModel = koinViewModel<RecipeEditViewModel>(key = id) { parametersOf(id) },
                                onSaved = {
                                    editRecipeId = null
                                    navController.popBackStack()
                                    recipeListViewModel.refresh()
                                },
                                onBack = {
                                    editRecipeId = null
                                    navController.popBackStack()
                                },
                                onSearchCiqual = { query -> apiService.searchCiqualFoods(query) },
                                onLoadUtensils = { recipeRepository.getUtensils().getOrThrow() },
                            )
                        }
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
            viewModel = mealCreateViewModel,
            recipeListViewModel = recipeListViewModel,
            onCreated = { mealId, withRecipes ->
                mealCreateState = null
                mealsWeekViewModel.refresh()
                // A meal with no recipe has no ingredient to choose from.
                if (withRecipes) {
                    mealToChooseIngredientsOf = mealId
                    navController.navigate(Screen.MealIngredientChoice.route)
                }
            },
            onDismiss = { mealCreateState = null },
        )
    }

    // Meal sheet: a slot already planned opens it instead of a second creation (MAG-378).
    openedMeal?.let { meal ->
        MealDetailDialog(
            meal = meal,
            viewModel = mealDetailViewModel,
            recipeListViewModel = recipeListViewModel,
            onSaved = { saved ->
                openedMeal = null
                mealsWeekViewModel.mealSaved(saved)
            },
            onDeleted = {
                openedMeal = null
                mealsWeekViewModel.mealDeleted(meal.id)
            },
            onChooseIngredients = {
                openedMeal = null
                mealToChooseIngredientsOf = meal.id
                navController.navigate(Screen.MealIngredientChoice.route)
            },
            onDismiss = { openedMeal = null },
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
            onOpenThreads = { showContextSheet = true },
        )
    }

    // Threads sheet — opened from the chat header's icon (MAG-342)
    if (showContextSheet) {
        ContextListSheet(
            sheetState = contextSheetState,
            uiState = contextUiState,
            onDismiss = { showContextSheet = false },
            onRequestDelete = contextViewModel::requestDeletion,
            onConfirmDelete = contextViewModel::confirmDeletion,
            onCancelDelete = contextViewModel::cancelDeletionRequest,
            onUndoDelete = contextViewModel::undoDeletion,
            onDeleteFailedShown = contextViewModel::consumeDeleteFailed,
            onOpened = contextViewModel::refresh,
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
                onLaunch = { launchActivity(event) },
            )
        }

        selectedProaction?.let { proaction ->
            ProactionDetailSheet(
                proaction = proaction,
                onDismiss = { selectedProactionId = null },
                onMove = { calendarViewModel.moveProaction(proaction.id, it) },
                onDelete = {
                    selectedProactionId = null
                    calendarViewModel.deleteProaction(proaction.id)
                },
            )
        }

        selectedTask?.let { task ->
            TaskDetailSheet(
                task = task,
                subtasks = subtasksState,
                subtaskActions = subtaskActions,
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
                            eventRepository.createEvent(cancelledOccurrence(event, masterId))
                        }
                        isDelete && action == RecurrenceAction.THIS_AND_FOLLOWING -> {
                            val newRrule = RruleUtils.addUntilToRrule(
                                event.masterRrule!!,
                                Instant.parse(occurrenceStartKey(event)),
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

    // Maggie speaking on her own (MAG-314), over whatever screen is open — never behind the lock.
    if (linkReady) {
        InterruptionHost(
            onOpenLink = { navController.handleDeepLink(PushIntents.open(context, it, toLink = true)) },
            onOpenChat = { showChatSheet = true },
        )
    }

    // Biometric lock overlay
    if (isAuthenticated == true && isLocked) {
        LockScreen(lockManager = biometricLockManager)
    }
}

// One account's transactions: the route of its own on a narrow window, the detail pane beside the accounts on a wide one.
@Composable
private fun AccountTransactions(
    account: Pair<String, String>,
    onBack: (() -> Unit)?,
    onMakeRecurring: (Transaction) -> Unit,
    prefill: TransactionPrefill?,
    onPrefillConsumed: () -> Unit,
    openTransactionId: String?,
    onTransactionOpened: () -> Unit,
    onOpenPeerDebts: () -> Unit,
    openIncidents: Boolean,
    onIncidentsOpened: () -> Unit,
) {
    // Keyed by account: the pane keeps one nav entry while the selection changes under it.
    val transactionViewModel: TransactionViewModel = koinViewModel(key = account.first) { parametersOf(account.first) }
    TransactionListScreen(
        viewModel = transactionViewModel,
        accountName = account.second,
        onBack = onBack,
        onMakeRecurring = onMakeRecurring,
        prefill = prefill,
        onPrefillConsumed = onPrefillConsumed,
        openTransactionId = openTransactionId,
        onTransactionOpened = onTransactionOpened,
        onOpenPeerDebts = onOpenPeerDebts,
        openIncidents = openIncidents,
        onIncidentsOpened = onIncidentsOpened,
    )
}
