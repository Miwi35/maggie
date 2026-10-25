package com.maggie.app.ui.screens.home

import com.maggie.app.ui.layout.AppLayout
import com.maggie.app.ui.layout.WindowWidth

/** The format an Accueil is drawn for; each has its own section of the layout file. */
enum class HomeFormat { PHONE, TABLET, KIOSK }

/** Where a card goes in a format: [LAUNCH] cards head the screen, [CARD] cards are stacked under them, [KIOSK_TILE] are the kiosk's tiles. */
enum class HomeSlot(val specName: String) {
    LAUNCH("launch"),
    CARD("card"),
    KIOSK_TILE("kiosk-tile"),
}

/** One line of a section: a card [key] (a [HomeModules] constant), drawn unless it is not [enabled]. */
data class HomeCardSetting(val key: String, val enabled: Boolean = true)

/** The Accueil's layout file: for each format and slot, the cards in their order. A key no component is registered for is skipped by the screen. */
class HomeLayoutFile(private val sections: Map<HomeFormat, Map<HomeSlot, List<HomeCardSetting>>>) {

    /** The enabled card keys of [slot] in [format], in order. */
    fun cards(format: HomeFormat, slot: HomeSlot): List<String> =
        sections[format]?.get(slot).orEmpty().filter { it.enabled }.map { it.key }.distinct()

    /** What the screen of [format] stacks: the [HomeSlot.LAUNCH] cards, then the [HomeSlot.CARD] ones. */
    fun screenCards(format: HomeFormat): List<String> =
        (cards(format, HomeSlot.LAUNCH) + cards(format, HomeSlot.CARD)).distinct()
}

/** The one layout file of this front. */
object HomeLayout {
    private fun cards(vararg keys: String) = keys.map { HomeCardSetting(it) }

    private val SPEC_ORDER = cards(
        HomeModules.TASKS,
        HomeModules.AGENDA,
        HomeModules.GROCERY,
        HomeModules.NUTRITION,
        HomeModules.HEALTH,
        HomeModules.FINANCE,
        HomeModules.LEISURE,
    )

    val file: HomeLayoutFile = HomeLayoutFile(
        mapOf(
            HomeFormat.PHONE to mapOf(HomeSlot.LAUNCH to emptyList(), HomeSlot.CARD to SPEC_ORDER),
            HomeFormat.TABLET to mapOf(HomeSlot.LAUNCH to emptyList(), HomeSlot.CARD to SPEC_ORDER),
            HomeFormat.KIOSK to mapOf(HomeSlot.KIOSK_TILE to SPEC_ORDER),
        ),
    )

    fun cards(format: HomeFormat, slot: HomeSlot): List<String> = file.cards(format, slot)

    /** The format a window gets: a compact width is the phone, anything wider the tablet. The kiosk is never picked yet. */
    fun formatFor(layout: AppLayout): HomeFormat =
        if (layout.width == WindowWidth.COMPACT) HomeFormat.PHONE else HomeFormat.TABLET
}
