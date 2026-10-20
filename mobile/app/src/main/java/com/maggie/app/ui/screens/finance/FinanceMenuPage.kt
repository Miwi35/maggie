package com.maggie.app.ui.screens.finance

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.FinanceTopBar
import com.maggie.app.ui.navigation.FinanceMenu

/**
 * One tab of a Finance menu page: [id] is what the route argument `?tab=` names, [block] what tells
 * whether the aspect has anything to show.
 */
data class FinanceAspect(val id: String, val title: String, val block: SummaryBlock)

/**
 * The aspects of a menu, in reading order.
 *
 * One per aspect that exists: a menu never lists what has no content yet, and an aspect that
 * arrives with its ticket (the merged operations list with MAG-478) is added here and nowhere
 * else. Menus whose home is a screen themselves (Dashboard, Comptes, Budget) have no page.
 */
fun financeAspects(menu: FinanceMenu): List<FinanceAspect> = when (menu) {
    FinanceMenu.REVIEW -> listOf(
        FinanceAspect("to-sort", "À qualifier", SummaryBlock.TO_SORT),
        FinanceAspect("to-control", "Reconnues à contrôler", SummaryBlock.TO_CONTROL),
        FinanceAspect("monthly-review", "Revue mensuelle", SummaryBlock.MONTHLY_REVIEW),
        FinanceAspect("rules", "Règles", SummaryBlock.RULES),
        FinanceAspect("recurring", "Opérations récurrentes", SummaryBlock.RECURRING_OPERATIONS),
    )
    FinanceMenu.TREASURY -> listOf(
        FinanceAspect("outlook", "Prévision", SummaryBlock.OUTLOOK),
        FinanceAspect("cushion", "Matelas", SummaryBlock.CUSHION),
        FinanceAspect("advances", "Avances d'épargne", SummaryBlock.SAVINGS_ADVANCES),
    )
    FinanceMenu.PLANNING -> listOf(
        FinanceAspect("projects", "Projets", SummaryBlock.PROJECTS),
        FinanceAspect("loans", "Prêts", SummaryBlock.LOANS),
        FinanceAspect("independence", "Indépendance", SummaryBlock.INDEPENDENCE),
    )
    FinanceMenu.SETTINGS -> listOf(
        FinanceAspect("banks", "Banques", SummaryBlock.BANKS),
        FinanceAspect("categories", "Catégories", SummaryBlock.CATEGORIES),
    )
    FinanceMenu.DASHBOARD, FinanceMenu.ACCOUNTS, FinanceMenu.BUDGET -> emptyList()
}

/**
 * The tab a menu opens on when nobody asked for one: the first aspect that has data, else the first.
 * `null` while an aspect before the answer is still loading — the page shows a loading state
 * rather than opening on a tab it would leave a moment later.
 */
fun defaultAspectId(aspects: List<FinanceAspect>, blocks: Map<SummaryBlock, BlockState>): String? {
    for (aspect in aspects) {
        when (val state = blocks[aspect.block]) {
            null, BlockState.Loading -> return null
            is BlockState.Ready -> if (state.hasData) return aspect.id
            is BlockState.Unavailable -> Unit
        }
    }

    return aspects.firstOrNull()?.id
}

/**
 * The home of Revue, Trésorerie, Planification and Configuration (MAG-426, MAG-518): a menu page
 * is the content of its aspects, one tab each, never a summary of them.
 *
 * The tab shown is, in order: the one the owner picked, the one the route asked for (`?tab=`),
 * the first aspect that has data. Pick and request survive a rotation and a process death; the
 * default, once known, is kept so that a refresh never moves the page under the owner's thumb.
 * A menu with a single aspect shows no tabs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinanceMenuPage(
    menu: FinanceMenu,
    viewModel: FinanceSummaryViewModel,
    requestedTab: String?,
    onBack: () -> Unit,
    aspects: List<FinanceAspect> = financeAspects(menu),
    content: @Composable (aspect: FinanceAspect, selectTab: (String) -> Unit, modifier: Modifier) -> Unit,
) {
    val blocks by viewModel.blocks.collectAsState()
    val requested = requestedTab?.takeIf { id -> aspects.any { it.id == id } }

    var picked by rememberSaveable(menu) { mutableStateOf(requested) }
    var handledRequest by rememberSaveable(menu) { mutableStateOf(requestedTab) }
    var resolved by rememberSaveable(menu) { mutableStateOf<String?>(null) }

    // A new request for the same page (a link to another tab) wins over the previous pick, once.
    if (requestedTab != handledRequest) {
        handledRequest = requestedTab
        if (requested != null) picked = requested
    }

    LaunchedEffect(menu) { viewModel.load(aspects.map { it.block }) }
    LaunchedEffect(blocks, picked) {
        if (picked == null && resolved == null) resolved = defaultAspectId(aspects, blocks)
    }

    val selectedId = picked ?: resolved
    val selected = aspects.firstOrNull { it.id == selectedId }

    Scaffold(
        topBar = {
            FinanceTopBar(menu.home) {
                TopAppBar(
                    title = { Text(menu.label) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                        }
                    },
                )
            }
        },
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            if (aspects.size > 1) {
                AspectTabs(aspects, selected, blocks, onSelect = { picked = it })
            }

            val body = Modifier.fillMaxSize()
            if (selected == null) {
                Box(body.testTag(UiTags.FINANCE_ASPECT_LOADING), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                key(selected.id) { content(selected, { picked = it }, body) }
            }
        }
    }
}

@Composable
private fun AspectTabs(
    aspects: List<FinanceAspect>,
    selected: FinanceAspect?,
    blocks: Map<SummaryBlock, BlockState>,
    onSelect: (String) -> Unit,
) {
    ScrollableTabRow(
        selectedTabIndex = aspects.indexOf(selected).coerceAtLeast(0),
        edgePadding = 8.dp,
        indicator = { positions ->
            if (selected != null) {
                with(TabRowDefaults) {
                    SecondaryIndicator(Modifier.tabIndicatorOffset(positions[aspects.indexOf(selected)]))
                }
            }
        },
    ) {
        aspects.forEach { aspect ->
            val needsAttention = (blocks[aspect.block] as? BlockState.Ready)?.needsAttention == true

            Tab(
                selected = aspect == selected,
                onClick = { onSelect(aspect.id) },
                text = {
                    BadgedBox(badge = { if (needsAttention) Badge() }) {
                        Text(aspect.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                modifier = Modifier.testTag(UiTags.financeTab(aspect.id)),
            )
        }
    }
}
