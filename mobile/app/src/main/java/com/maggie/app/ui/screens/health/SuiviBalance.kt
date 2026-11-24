package com.maggie.app.ui.screens.health

import com.maggie.app.data.model.DailyBalance

/** « +318 kcal », « −764 kcal » (a true minus), « 0 kcal »; without a balance, the eaten and planned kcal. */
fun suiviBalanceValue(balance: DailyBalance): String {
    val kcal = balance.balanceKcal ?: return formatKcal(balance.eatenKcal + balance.plannedKcal)
    return when {
        kcal > 0 -> "+${formatCount(kcal)} kcal"
        kcal < 0 -> "−${formatCount(-kcal)} kcal"
        else -> "0 kcal"
    }
}

/** « Déficit », « Équilibre » or « Excédent »; `null` without a balance. */
fun suiviBalanceWord(balanceKcal: Int?): String? = when (balanceZone(balanceKcal)) {
    null -> null
    BalanceZone.BALANCED -> "Équilibre"
    BalanceZone.SURPLUS -> "Excédent"
    BalanceZone.DEFICIT -> "Déficit"
}

/** « apport 1 980 · dépense 1 662 » (planned included); without an expenditure, « apport 800 ». */
fun suiviBalanceFigures(balance: DailyBalance): String {
    val intake = "apport ${formatCount(balance.eatenKcal + balance.plannedKcal)}"
    return balance.expendedKcal?.let { "$intake · dépense ${formatCount(it)}" } ?: intake
}
