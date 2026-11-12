package com.maggie.app.ui.screens.home

import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.maggie.app.data.model.ChatMessage
import com.maggie.app.data.model.HomeDay
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.digestMoment
import com.maggie.app.ui.navigation.DeepLinks
import org.koin.androidx.compose.koinViewModel
import java.time.LocalDate

/**
 * The morning digest at the head of the Accueil (MAG-808): shown for today while it is announced and not opened,
 * the tile and its « Écouter » pill both open the digest screen. It draws nothing otherwise.
 */
@Composable
fun DigestHomeCard(day: HomeDay, viewModel: DigestTileViewModel = koinViewModel()) {
    val digest by viewModel.digest.collectAsState()
    val open = LocalHomeNavigation.current
    DigestHomeCardContent(day, digest, onOpen = { id -> open(DeepLinks.digestRoute(id)) })
}

@Composable
internal fun DigestHomeCardContent(day: HomeDay, message: ChatMessage?, onOpen: (String) -> Unit) {
    val block = if (day == HomeDay.TODAY) message?.digest else null
    ReportHomeCard(HomeModules.DIGEST, if (block != null) HomeCardStatus.FILLED else HomeCardStatus.EMPTY)
    if (block == null || message == null) return
    val date = runCatching { LocalDate.parse(block.day) }.getOrNull()
    HomeActionCard(
        icon = Icons.Filled.WbSunny,
        title = "Digest du matin",
        moment = if (date != null) digestMoment(date, message.audio) else "",
        actionLabel = "Écouter",
        onAction = { onOpen(message.id) },
        module = HomeModules.DIGEST,
        modifier = Modifier
            .testTag(UiTags.HOME_DIGEST)
            .clickable { onOpen(message.id) },
    )
}
