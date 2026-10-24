package com.maggie.app.ui.screens.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * What the Accueil needs to know of a card: only [EMPTY] takes it off the page. A card still loading, or whose
 * read failed, is not empty — it stays, with its progress or its error (MAG-594).
 */
enum class HomeCardStatus { LOADING, ERROR, EMPTY, FILLED }

/**
 * The status of a card from its read. [isEmpty] is the module's own definition of « nothing to show » for the
 * chosen day. A read in flight (the day just changed) or failed is never empty, whatever the previous state.
 */
internal fun <T : Any> homeCardStatus(state: T?, loading: Boolean, failed: Boolean, isEmpty: (T) -> Boolean): HomeCardStatus = when {
    failed -> HomeCardStatus.ERROR
    loading || state == null -> HomeCardStatus.LOADING
    isEmpty(state) -> HomeCardStatus.EMPTY
    else -> HomeCardStatus.FILLED
}

/** Where a card tells the Accueil its status; outside the Accueil (a card drawn alone) nobody listens. */
val LocalHomeCardReporter = staticCompositionLocalOf<(module: String, status: HomeCardStatus?) -> Unit> { { _, _ -> } }

/** Tells the Accueil the card's status, and forgets it when the card goes. */
@Composable
internal fun ReportHomeCard(module: String, status: HomeCardStatus) {
    val report = LocalHomeCardReporter.current
    DisposableEffect(report, module, status) {
        report(module, status)
        onDispose {}
    }
    DisposableEffect(report, module) {
        onDispose { report(module, null) }
    }
}
