package com.maggie.app.ui.screens.cookbook.grocery

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * How often a product is bought, said the short way (MAG-495): « tous les jours »,
 * « toutes les 2 sem. », « tous les 10 j », « tous les mois ».
 *
 * Whole weeks are said in weeks up to eight of them, other spans under four weeks
 * in days, anything longer in months. The admin applies the same rule.
 */
fun purchaseRhythmLabel(everyDays: Int): String {
    val days = max(1, everyDays)
    if (days == 1) return "tous les jours"
    if (days % 7 == 0 && days < 63) {
        val weeks = days / 7
        return if (weeks == 1) "toutes les sem." else "toutes les $weeks sem."
    }
    if (days < 28) return "tous les $days j"
    val months = max(1, (days / 30.0).roundToInt())
    return if (months == 1) "tous les mois" else "tous les $months mois"
}
