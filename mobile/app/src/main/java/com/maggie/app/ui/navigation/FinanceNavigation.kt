package com.maggie.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import com.maggie.app.ui.components.BreadcrumbSection
import com.maggie.app.ui.components.SubviewItem

/**
 * A section's sub-view (MAG-537). It is either a route of its own ([route] set: Planification's four, Configuration's
 * two) or a state of the section's home screen ([route] null: the dashboard's two, Budget's two, Revue's three).
 */
data class FinanceSubview(val key: String, val label: String, val route: String? = null)

/**
 * The seven Finance sections (owner, 15 Oct.; spec « Sept menus Finance, peu de pages »), the entries of the breadcrumb's
 * menu « Finance › Section ▾ » (MAG-537).
 *
 * A section lists only the screens that exist: nothing here is a placeholder. [pages] are the screens that wear the
 * breadcrumb and the row of [subviews]; [details] are the screens opened from them (a fiche, a list behind an icon),
 * which wear a back arrow and their title instead. The first page is the section's [home].
 */
enum class FinanceSection(
    val key: String,
    val label: String,
    val pages: List<String>,
    val details: List<String> = emptyList(),
    val subviews: List<FinanceSubview> = emptyList(),
) {
    DASHBOARD(
        "dashboard", "Dashboard",
        pages = listOf(Screen.FinanceDashboard.route),
        subviews = listOf(FinanceSubview("current", "État courant"), FinanceSubview("upcoming", "À venir")),
    ),
    ACCOUNTS(
        "accounts", "Comptes",
        pages = listOf(Screen.AccountList.route),
        details = listOf(Screen.AccountTransactions.route, Screen.Contacts.route, Screen.PeerDebts.route),
    ),
    REVIEW(
        "review", "Revue",
        pages = listOf(Screen.ReviewList.route),
        details = listOf(
            Screen.CategorizationRuleList.route,
            Screen.RuleSuggestions.route,
            Screen.RecurringOperations.route,
        ),
        subviews = listOf(
            FinanceSubview("all", "Toutes"),
            FinanceSubview("to_qualify", "À qualifier"),
            FinanceSubview("recognised", "Reconnues"),
        ),
    ),
    BUDGET(
        "budget", "Budget",
        pages = listOf(Screen.BudgetList.route),
        subviews = listOf(FinanceSubview("month", "Mois"), FinanceSubview("outside", "Hors budget")),
    ),
    TREASURY(
        "treasury", "Trésorerie",
        pages = listOf(Screen.Treasury.route),
        details = listOf(Screen.Cushion.route, Screen.SavingsAdvances.route),
    ),
    PLANNING(
        "planning", "Planification",
        pages = listOf(Screen.ProjectList.route, Screen.LoanList.route, Screen.PlacementList.route, Screen.Independence.route),
        subviews = listOf(
            FinanceSubview("projects", "Projets", Screen.ProjectList.route),
            FinanceSubview("loans", "Prêts", Screen.LoanList.route),
            FinanceSubview("placements", "Placements", Screen.PlacementList.route),
            FinanceSubview("independence", "Indépendance", Screen.Independence.route),
        ),
    ),
    SETTINGS(
        "settings", "Configuration",
        pages = listOf(Screen.CategoryList.route, Screen.BankConnectionList.route),
        subviews = listOf(
            FinanceSubview("categories", "Catégories", Screen.CategoryList.route),
            FinanceSubview("banks", "Banques", Screen.BankConnectionList.route),
        ),
    ),
    ;

    val home: String get() = pages.first()

    /** Where a tap on the section in the menu lands: its home. */
    val firstSubview: FinanceSubview? get() = subviews.firstOrNull()

    /** The sub-view [route] is, for a section whose sub-views are routes of their own. */
    fun subviewOf(route: String?): FinanceSubview? = subviews.firstOrNull { it.route != null && it.route == route }

    fun subview(key: String?): FinanceSubview? = subviews.firstOrNull { it.key == key }

    val breadcrumb: BreadcrumbSection get() = BreadcrumbSection(key, label)

    companion object {
        /** The section a finance route belongs to, `null` for a route outside the module. */
        fun of(route: String?): FinanceSection? =
            entries.firstOrNull { route in it.pages || route in it.details }

        /** The section whose breadcrumb [route] wears, `null` for a detail screen or a route outside the module. */
        fun pageOf(route: String?): FinanceSection? = entries.firstOrNull { route in it.pages }

        fun byKey(key: String?): FinanceSection? = entries.firstOrNull { it.key == key }
    }
}

private val FINANCE_ROUTES: Set<String> = FinanceSection.entries.flatMap { it.pages + it.details }.toSet()

enum class FinanceBack { POP, REPLACE_WITH_DASHBOARD }

/**
 * A finance screen goes back to the screen it was opened from when that is a finance
 * screen (the dashboard, or the list a detail belongs to). Opened from outside — a
 * notification, a link — it has no finance screen under it, so it is swapped for the
 * dashboard instead of leaving the finance module.
 */
internal fun financeBackAction(previousRoute: String?): FinanceBack =
    if (previousRoute in FINANCE_ROUTES) FinanceBack.POP else FinanceBack.REPLACE_WITH_DASHBOARD

/**
 * What a tap on a section does to the back stack.
 *
 * The sections are siblings, not a trail: Dashboard → Trésorerie → Revue goes back to the
 * Dashboard in one step, not through Trésorerie. With the dashboard underneath, everything
 * above it goes; without it (the module was opened by a link on a deeper screen) the
 * current screen is the one replaced, and its back swaps in the dashboard like any other.
 */
internal data class FinanceSectionAction(val route: String, val popToDashboard: Boolean)

internal fun financeSectionAction(target: FinanceSection, dashboardInBackStack: Boolean): FinanceSectionAction =
    FinanceSectionAction(route = target.home, popToDashboard = dashboardInBackStack)

/**
 * The sub-view each section shows when it is a state of its home screen (MAG-537).
 *
 * Kept above the screens, saved with the activity: rotating the phone or coming back from a detail finds
 * « À venir », « Hors budget »… where it was left. A section that was never touched shows its first sub-view.
 */
class FinanceSubviews {
    private val chosen = mutableStateMapOf<String, String>()

    fun current(section: FinanceSection): FinanceSubview {
        val own = section.subviews.filter { it.route == null }
        return own.firstOrNull { it.key == chosen[section.key] } ?: own.first()
    }

    fun select(section: FinanceSection, key: String) {
        chosen[section.key] = key
    }

    internal fun snapshot(): Map<String, String> = chosen.toMap()

    internal fun restore(saved: Map<String, String>) {
        chosen.putAll(saved)
    }

    companion object {
        val Saver: Saver<FinanceSubviews, ArrayList<String>> = Saver(
            save = { subviews -> ArrayList(subviews.snapshot().flatMap { (section, key) -> listOf(section, key) }) },
            restore = { flat ->
                FinanceSubviews().also { subviews ->
                    subviews.restore(flat.chunked(2).associate { it[0] to it[1] })
                }
            },
        )
    }
}

@Composable
fun rememberFinanceSubviews(): FinanceSubviews = rememberSaveable(saver = FinanceSubviews.Saver) { FinanceSubviews() }

/**
 * What the Finance header needs from the graph: the sections menu, the drawer, the sub-views held above the screens.
 * Absent (`null`) wherever a finance screen is drawn without the graph, which keeps its own sub-view state.
 */
class FinanceNav(
    val subviews: FinanceSubviews,
    val onSelectSection: (FinanceSection) -> Unit,
    val onSelectSubview: (FinanceSection, FinanceSubview) -> Unit,
    val onOpenDrawer: (() -> Unit)?,
)

val LocalFinanceNav = compositionLocalOf<FinanceNav?> { null }

/**
 * Gives a screen that holds state sub-views one holder shared by its header and its body. In the app the graph's
 * [FinanceNav] already is that holder; drawn alone (a screen test), the screen keeps its own.
 */
@Composable
fun ProvideFinanceNav(content: @Composable () -> Unit) {
    if (LocalFinanceNav.current != null) {
        content()
        return
    }
    val local = remember { FinanceSubviews() }
    val standalone = remember(local) {
        FinanceNav(
            subviews = local,
            onSelectSection = {},
            onSelectSubview = { section, subview -> local.select(section, subview.key) },
            onOpenDrawer = null,
        )
    }
    CompositionLocalProvider(LocalFinanceNav provides standalone, content = content)
}

/** The state sub-view of [section] on screen, and how to change it — from the graph when there is one, else local. */
@Composable
fun financeSubviewState(section: FinanceSection): Pair<FinanceSubview, (FinanceSubview) -> Unit> {
    val nav = LocalFinanceNav.current
    val local = remember { FinanceSubviews() }
    val holder = nav?.subviews ?: local
    return holder.current(section) to { subview ->
        if (nav != null) nav.onSelectSubview(section, subview) else holder.select(section, subview.key)
    }
}

internal fun FinanceSection.subviewItems(counts: Map<String, Int> = emptyMap()): List<SubviewItem> =
    subviews.map { SubviewItem(it.key, it.label, counts[it.key]) }
