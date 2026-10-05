package com.maggie.app.screentest

import com.maggie.app.data.model.Event
import com.maggie.app.data.model.GroceryItem
import com.maggie.app.data.model.GroceryList
import com.maggie.app.data.model.Store
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The fixture the screen tests read, named after the one the emulator journeys
 * read (MAG-242).
 *
 * Same shops, same lines, same witness event as `api/fixtures/e2e/`: a test that
 * replaces a flow should fail for the same reason the flow did, and that is
 * easier to see when « Halles du voisin » is still « Halles du voisin ». What is
 * *not* kept is the `MAG-178` suffix the flows appended to the lines they wrote:
 * it was there because those lines went into a server the browser suite shares,
 * and nothing here writes anywhere.
 *
 * Dates are fixed rather than relative to today. The journeys had no choice —
 * they read a server seeded « today + 5 days », so which week the event falls in
 * depended on the weekday the suite ran on, and `03-calendar-multi-day` could
 * only ever see one of the two cases per run. Here both are written down, so
 * both run on every pull request.
 */
object Seed {
    private val PARIS: ZoneId = ZoneId.of("Europe/Paris")

    /**
     * A Monday, derived rather than typed: `with(MONDAY)` on any date of that
     * week is the Monday of it, so there is no literal whose weekday could be
     * wrong.
     */
    val monday: LocalDate = LocalDate.of(2026, 1, 7).with(DayOfWeek.MONDAY)

    // ---------------------------------------------------------------------
    // The calendar witness (03-calendar-multi-day)
    // ---------------------------------------------------------------------

    /**
     * « Train de nuit pour Vienne », 21:00 → 08:00 the next morning, inside one
     * week. Timed and not all-day on purpose: an all-day row would not exercise
     * the range check the `76017dd` fix replaced.
     */
    val trainInsideTheWeek: Event = nightTrain(
        id = "event-train-midweek",
        firstDay = monday.plusDays(1),
    )

    /** The same train, leaving on the Sunday: its two days are in two weeks (`dfad086`). */
    val trainAcrossTheWeekend: Event = nightTrain(
        id = "event-train-weekend",
        firstDay = monday.plusDays(6),
    )

    /** A plain daytime event, to prove the span row is not the only thing drawn. */
    val lunchWithAlex: Event = Event(
        id = "event-lunch",
        summary = "Déjeuner avec Alex",
        startAt = instant(monday, LocalTime.of(12, 0)),
        endAt = instant(monday, LocalTime.of(13, 0)),
    )

    private fun nightTrain(id: String, firstDay: LocalDate) = Event(
        id = id,
        summary = "Train de nuit pour Vienne",
        startAt = instant(firstDay, LocalTime.of(21, 0)),
        endAt = instant(firstDay.plusDays(1), LocalTime.of(8, 0)),
    )

    /** An ISO instant, built through Paris so the day it lands on is the seed's day. */
    fun instant(date: LocalDate, time: LocalTime): String =
        ZonedDateTime.of(date, time, PARIS).toInstant().toString()

    // ---------------------------------------------------------------------
    // The neighbour's grocery list (07-grocery-errand, 09-grocery-deferred)
    // ---------------------------------------------------------------------

    val halles = Store(id = "store-halles", name = "Halles du voisin", visitOrder = 1)
    val epicerie = Store(id = "store-epicerie", name = "Épicerie du coin", visitOrder = 2)

    /** The shops, in the order the picker offers them — not the visiting order. */
    val stores: List<Store> = listOf(halles, epicerie)

    val leek = item(id = "item-leek", label = "Poireau du voisin", store = halles, position = 0)
    val capers = item(id = "item-capers", label = "Câpres", store = halles, position = 1)
    val pickles = item(id = "item-pickles", label = "Cornichons", store = halles, position = 2)
    val stamps = item(id = "item-stamps", label = "Timbres du voisin", store = epicerie, position = 0)
    val bags = item(id = "item-bags", label = "Sacs du voisin", store = null, position = 0)

    /**
     * « Liquide vaisselle », bought after a day that has not come: in the list the
     * server sends and not on the screen (MAG-101). The API serves it either way —
     * the filter is the app's (`GroceryViewModel.buildStoreGroups`), which is why
     * `09-grocery-deferred` had to look at a screen at all.
     *
     * The one date here that follows the clock, and it has to: the filter compares
     * `buyAfter` to the real today, so « later » can only be expressed relative to
     * it. A literal would stop being in the future.
     */
    val deferredDishSoap = item(
        id = "item-dish-soap",
        label = "Liquide vaisselle",
        store = epicerie,
        position = 1,
        buyAfter = LocalDate.now().plusDays(5).toString(),
    )

    val groceryList = GroceryList(
        id = "list-neighbour",
        items = listOf(leek, capers, pickles, stamps, bags, deferredDishSoap),
    )

    private fun item(
        id: String,
        label: String,
        store: Store?,
        position: Int,
        buyAfter: String? = null,
    ) = GroceryItem(
        id = id,
        // Both, because the two are read in different places: the API computes
        // `label` from the product or the custom one, and the row draws `label`.
        label = label,
        customLabel = label,
        store = store,
        position = position,
        buyAfter = buyAfter,
    )
}
