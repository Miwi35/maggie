package com.maggie.app.ui.screens.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.maggie.app.data.model.HomeDay
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.navigation.Screen
import com.maggie.app.ui.screens.bilan.BilanResumeViewModel
import com.maggie.app.ui.screens.bilan.BilanTile
import org.koin.androidx.compose.koinViewModel

/**
 * The Bilan de la journée tile (MAG-751, MAG-826): « Reprendre » at its step once started, « Ouvrir » from the review
 * time before any answer. It draws nothing after « Bonne nuit » or the next morning.
 */
@Composable
fun BilanHomeCard(day: HomeDay, viewModel: BilanResumeViewModel = koinViewModel()) {
    val tile by viewModel.tile.collectAsState()
    val open = LocalHomeNavigation.current
    BilanHomeCardContent(day, tile, onOpen = { open(Screen.BilanActivity.route) })
}

@Composable
internal fun BilanHomeCardContent(day: HomeDay, tile: BilanTile?, onOpen: () -> Unit) {
    val shown = if (day == HomeDay.TODAY) tile else null
    ReportHomeCard(HomeModules.BILAN, if (shown != null) HomeCardStatus.FILLED else HomeCardStatus.EMPTY)
    if (shown == null) return
    HomeActionCard(
        icon = Icons.Filled.NightsStay,
        title = "Bilan de la journée",
        moment = shown.session?.let { "Étape ${it.step.label}" } ?: "Faites le point sur votre journée",
        actionLabel = if (shown.session != null) "Reprendre" else "Ouvrir",
        onAction = onOpen,
        module = HomeModules.BILAN,
        modifier = Modifier.testTag(UiTags.HOME_BILAN_RESUME),
    )
}
