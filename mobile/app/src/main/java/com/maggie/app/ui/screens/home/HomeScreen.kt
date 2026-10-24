package com.maggie.app.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.HomeDay
import com.maggie.app.ui.UiTags
import org.koin.androidx.compose.koinViewModel

private val DAYS = listOf(
    Triple(HomeDay.TODAY, "Aujourd'hui", UiTags.HOME_DAY_TODAY),
    Triple(HomeDay.TOMORROW, "Demain", UiTags.HOME_DAY_TOMORROW),
)

/**
 * The Accueil (MAG-560): the selector « Aujourd'hui · Demain », the action cards in head, then the cards
 * of [registry] stacked in the spec's order; [onNavigate] is how a card opens its module. The title is the top bar's, drawn by the navigation. A card
 * that has nothing to say for the day draws nothing and takes no room; when every card is in that case the screen
 * says one calm sentence instead (MAG-594). An empty registry leaves the selector alone.
 */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = koinViewModel(),
    registry: List<HomeCardEntry> = HomeCardRegistry.entries,
    onNavigate: (String) -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    CompositionLocalProvider(LocalHomeNavigation provides onNavigate) {
        HomeContent(day = uiState.day, onSelectDay = viewModel::selectDay, registry = registry)
    }
}

@Composable
internal fun HomeContent(
    day: HomeDay,
    onSelectDay: (HomeDay) -> Unit,
    registry: List<HomeCardEntry>,
) {
    val (actions, cards) = registry.inScreenOrder()
    val statuses = remember { mutableStateMapOf<String, HomeCardStatus>() }
    val report = remember<(String, HomeCardStatus?) -> Unit> {
        { module, status ->
            if (status == null) statuses.remove(module) else statuses.put(module, status)
            Unit
        }
    }
    val everyCardIsEmpty = registry.isNotEmpty() && registry.all { statuses[it.module] == HomeCardStatus.EMPTY }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag(UiTags.HOME)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(UiTags.HOME_DAY_SELECTOR),
        ) {
            DAYS.forEachIndexed { index, (value, label, tag) ->
                SegmentedButton(
                    selected = day == value,
                    onClick = { onSelectDay(value) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = DAYS.size),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag(tag),
                ) {
                    Text(label)
                }
            }
        }

        CompositionLocalProvider(LocalHomeCardReporter provides report) {
            (actions + cards).forEach { entry -> key(entry.zone, entry.module) { entry.content(day) } }
        }

        if (everyCardIsEmpty) {
            Text(
                text = if (day == HomeDay.TODAY) "Rien de prévu aujourd'hui." else "Rien de prévu demain.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(UiTags.HOME_EMPTY),
            )
        }
    }
}
