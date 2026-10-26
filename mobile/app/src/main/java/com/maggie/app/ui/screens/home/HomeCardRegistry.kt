package com.maggie.app.ui.screens.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import com.maggie.app.data.model.HomeDay

/** The modules of the Accueil; a card's [HomeCardEntry.module] and the keys of [HomeLayout] are these. */
object HomeModules {
    const val TASKS = "tasks"
    const val AGENDA = "agenda"
    const val GROCERY = "grocery"
    const val CUISINE = "cuisine"
    const val HEALTH = "health"
    const val FINANCE = "finance"
    const val LEISURE = "leisure"
}

/**
 * One card of the Accueil: the component of a [module]. The module's ticket registers it and draws the whole card
 * (a [HomeCard] or a [HomeActionCard]), from [content]'s `day`; a card with nothing to say draws nothing and takes no
 * room. Which cards the screen draws, and in which order, is [HomeLayout]'s.
 */
class HomeCardEntry(
    val module: String,
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
        HomeCardEntry(HomeModules.GROCERY) { GroceryHomeCard() },
        // MAG-561: today's tasks and the routines of the moment.
        HomeCardEntry(HomeModules.TASKS) { day -> TasksHomeCard(day) },
        HomeCardEntry(HomeModules.CUISINE) { day -> CuisineHomeCard(day) },
        HomeCardEntry(HomeModules.FINANCE) { FinanceHomeCard() },
        HomeCardEntry(HomeModules.AGENDA) { day -> AgendaCard(day) },
    )
}
