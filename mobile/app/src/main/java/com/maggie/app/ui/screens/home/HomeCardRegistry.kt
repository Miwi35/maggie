package com.maggie.app.ui.screens.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import com.maggie.app.data.model.HomeDay

/** Where a card goes on the Accueil: [ACTION] cards head the screen, [MAIN] cards are stacked under them. */
enum class HomeZone { ACTION, MAIN }

/** The modules of the Accueil, in the order of the spec; a card's [HomeCardEntry.module] is one of these. */
object HomeModules {
    const val TASKS = "tasks"
    const val AGENDA = "agenda"
    const val GROCERY = "grocery"
    const val NUTRITION = "nutrition"
    const val HEALTH = "health"
    const val FINANCE = "finance"
    const val LEISURE = "leisure"

    val ORDER: List<String> = listOf(TASKS, AGENDA, GROCERY, NUTRITION, HEALTH, FINANCE, LEISURE)

    /** The rank of [module] in [ORDER]; a module the list does not know comes last. */
    fun orderOf(module: String): Int = ORDER.indexOf(module).let { if (it < 0) ORDER.size else it }
}

/**
 * One card of the Accueil. The module's ticket registers it and draws the whole card (a [HomeCard] or a
 * [HomeActionCard]), from [content]'s `day`; a card with nothing to say draws nothing and takes no room.
 *
 * @param order the rank among the cards of the same zone; by default the module's place in [HomeModules.ORDER].
 */
class HomeCardEntry(
    val module: String,
    val zone: HomeZone,
    val order: Int = HomeModules.orderOf(module),
    val content: @Composable (day: HomeDay) -> Unit,
)

/**
 * How a card opens a route of the app: the Accueil provides it, since it alone holds the navigation.
 * A card reads it where it draws, so [HomeCardEntry.content] stays `(day)`.
 */
val LocalHomeNavigation = staticCompositionLocalOf<(String) -> Unit> { {} }

/**
 * The cards of the Accueil, one line per module ticket. With none, the screen is the top bar and the
 * selector, and nothing else.
 */
object HomeCardRegistry {
    val entries: List<HomeCardEntry> = listOf(
        HomeCardEntry(module = HomeModules.GROCERY, zone = HomeZone.MAIN) { GroceryHomeCard() },
    )
}

/** [entries] as the screen stacks them: the action cards first, then the others, each zone by [HomeCardEntry.order]. */
internal fun List<HomeCardEntry>.inScreenOrder(): Pair<List<HomeCardEntry>, List<HomeCardEntry>> =
    filter { it.zone == HomeZone.ACTION }.sortedBy { it.order } to
        filter { it.zone == HomeZone.MAIN }.sortedBy { it.order }
