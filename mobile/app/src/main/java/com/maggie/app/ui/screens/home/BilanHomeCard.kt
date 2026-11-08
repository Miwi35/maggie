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
import com.maggie.app.ui.screens.bilan.BilanSession
import org.koin.androidx.compose.koinViewModel

/**
 * « Reprendre » the Bilan de la journée (MAG-751): shown only for today while a bilan is left open, so it
 * disappears the next morning with the status. It draws nothing otherwise.
 */
@Composable
fun BilanHomeCard(day: HomeDay, viewModel: BilanResumeViewModel = koinViewModel()) {
    val resume by viewModel.resume.collectAsState()
    val open = LocalHomeNavigation.current
    BilanHomeCardContent(day, resume, onResume = { open(Screen.BilanActivity.route) })
}

@Composable
internal fun BilanHomeCardContent(day: HomeDay, resume: BilanSession?, onResume: () -> Unit) {
    val shown = if (day == HomeDay.TODAY) resume else null
    ReportHomeCard(HomeModules.BILAN, if (shown != null) HomeCardStatus.FILLED else HomeCardStatus.EMPTY)
    if (shown == null) return
    HomeActionCard(
        icon = Icons.Filled.NightsStay,
        title = "Bilan de la journée",
        moment = "Étape ${shown.step.label}",
        actionLabel = "Reprendre",
        onAction = onResume,
        module = HomeModules.BILAN,
        modifier = Modifier.testTag(UiTags.HOME_BILAN_RESUME),
    )
}
