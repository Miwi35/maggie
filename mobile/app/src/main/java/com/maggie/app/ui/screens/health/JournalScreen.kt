package com.maggie.app.ui.screens.health

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maggie.app.data.model.HealthDay
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.EmptyState
import com.maggie.app.ui.components.ErrorSnackbar
import com.maggie.app.ui.components.MaggieCard
import com.maggie.app.ui.components.ModuleBreadcrumb
import com.maggie.app.ui.layout.WindowWidth
import com.maggie.app.ui.layout.rememberAppLayout
import com.maggie.app.ui.navigation.HealthSection
import com.maggie.app.ui.navigation.LocalModuleBar
import com.maggie.app.ui.theme.maggieSurface
import java.time.LocalDate
import java.time.ZoneId

/**
 * « Santé › Journal » (MAG-750), for the day chosen — today by default, « ‹ › » to move: « Moral » and « Fatigue » on
 * ten pastilles each, written at the tap; « avec Maggie · 21:42 » when she noted the day; a free note and its
 * « Enregistrer »; the day's indicators, each opening its section (Poids, Pas → Suivi, Bilan → Nutrition, Sport →
 * Sport); then « Jours précédents », a tap choosing the day. On a tablet the days take the left column, the entry the right.
 */
@Composable
fun JournalScreen(
    viewModel: JournalViewModel,
    onSelectSection: (HealthSection) -> Unit = {},
) {
    val expanded = rememberAppLayout().width == WindowWidth.EXPANDED
    val bar = LocalModuleBar.current
    Scaffold(
        topBar = {
            ModuleBreadcrumb(
                module = "Santé",
                sections = HealthSection.entries.map { it.breadcrumb },
                currentKey = HealthSection.JOURNAL.key,
                onSelectSection = { picked ->
                    HealthSection.byKey(picked.key)?.takeIf { it != HealthSection.JOURNAL }?.let(onSelectSection)
                },
                onOpenDrawer = bar.onOpenDrawer,
                actions = { bar.actions(this) },
            )
        },
    ) { paddingValues ->
        JournalContent(viewModel, expanded, onSelectSection, Modifier.padding(paddingValues))
    }
}

@Composable
private fun JournalContent(
    viewModel: JournalViewModel,
    expanded: Boolean,
    onSelectSection: (HealthSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val today = viewModel.today
    val history = remember(uiState.days, today) { journalHistory(uiState.days, today) }

    ErrorSnackbar(
        error = uiState.error,
        snackbarHostState = snackbarHostState,
        onDismiss = viewModel::clearError,
        onRetry = if (uiState.hasLoaded) null else viewModel::refresh,
        message = uiState.error.orEmpty(),
    )
    LaunchedEffect(uiState.message) {
        val message = uiState.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearMessage()
    }

    val entry: @Composable () -> Unit = {
        DayHeader(uiState.date, today, viewModel::previousDay, viewModel::nextDay)
        ScalesCard(uiState.entry, viewModel.zone, viewModel::pickMood, viewModel::pickFatigue)
        NoteCard(uiState.date, uiState.entry?.note, uiState.savingNote, viewModel::saveNote)
        FiguresCard(uiState.entry, viewModel.zone, onSelectSection)
    }

    Box(modifier = modifier.fillMaxSize().testTag(UiTags.JOURNAL)) {
        when {
            uiState.isLoading && !uiState.hasLoaded -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            !uiState.hasLoaded -> EmptyState(
                modifier = Modifier.testTag(UiTags.JOURNAL_EMPTY),
                title = "Journal indisponible",
                description = JournalViewModel.MESSAGE_LOAD,
                actionLabel = "Réessayer",
                onAction = viewModel::refresh,
            )
            expanded -> Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(modifier = Modifier.weight(0.4f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                    HistoryCard(history, uiState.date, viewModel::selectDate)
                }
                Column(
                    modifier = Modifier.weight(0.6f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    entry()
                }
            }
            else -> Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                entry()
                HistoryCard(history, uiState.date, viewModel::selectDate)
            }
        }
        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

/** « ‹ Aujourd’hui samedi 7 nov. › »: no day after today. */
@Composable
private fun DayHeader(date: LocalDate, today: LocalDate, onPrevious: () -> Unit, onNext: () -> Unit) {
    val surface = maggieSurface()
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrevious, modifier = Modifier.testTag(UiTags.JOURNAL_PREVIOUS)) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Jour précédent")
        }
        Text(
            journalDayTitle(date, today),
            fontSize = 16.sp,
            color = surface.text,
            modifier = Modifier.weight(1f).testTag(UiTags.JOURNAL_TITLE),
            textAlign = TextAlign.Center,
        )
        IconButton(onClick = onNext, enabled = date < today, modifier = Modifier.testTag(UiTags.JOURNAL_NEXT)) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Jour suivant")
        }
    }
}

/** « Moral » and « Fatigue », ten pastilles each; « avec Maggie · 21:42 » under them when she noted the day. */
@Composable
private fun ScalesCard(entry: HealthDay?, zone: ZoneId, onMood: (Int) -> Unit, onFatigue: (Int) -> Unit) {
    val surface = maggieSurface()
    MaggieCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Scale("Moral", entry?.mood, onMood, UiTags.JOURNAL_MOOD, UiTags::journalMood)
            Scale("Fatigue", entry?.fatigue, onFatigue, UiTags.JOURNAL_FATIGUE, UiTags::journalFatigue)
            notedByMaggie(entry, zone)?.let {
                Text(it, fontSize = 12.sp, color = surface.caption, modifier = Modifier.testTag(UiTags.JOURNAL_NOTED_BY))
            }
        }
    }
}

/**
 * One scale of 1–10, a radio group: the chosen pastille filled. Ten across a phone leave each one a tenth of the
 * width and the full 48 dp of height to tap.
 */
@Composable
private fun Scale(label: String, value: Int?, onPick: (Int) -> Unit, tag: String, pastilleTag: (Int) -> String) {
    val surface = maggieSurface()
    val accent = MaterialTheme.colorScheme.primary
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 14.sp, color = surface.textMuted)
        Row(
            modifier = Modifier.fillMaxWidth().selectableGroup().semantics { contentDescription = label }.testTag(tag),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            HealthDay.SCALE.forEach { n ->
                val chosen = n == value
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .selectable(selected = chosen, role = Role.RadioButton, onClick = { onPick(n) })
                        .semantics { contentDescription = "$label $n" }
                        .testTag(pastilleTag(n)),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .background(if (chosen) accent else Color.Transparent, CircleShape)
                            .border(1.dp, if (chosen) accent else surface.caption.copy(alpha = 0.5f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "$n",
                            fontSize = 13.sp,
                            color = if (chosen) MaterialTheme.colorScheme.onPrimary else surface.text,
                        )
                    }
                }
            }
        }
    }
}

/** The free note: « Enregistrer » once it differs from the one saved, and while none is on its way. */
@Composable
private fun NoteCard(date: LocalDate, saved: String?, saving: Boolean, onSave: (String) -> Unit) {
    val surface = maggieSurface()
    var text by rememberSaveable(date, saved) { mutableStateOf(saved.orEmpty()) }
    MaggieCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Note", fontSize = 14.sp, color = surface.textMuted)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Comment s’est passée la journée ?") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth().testTag(UiTags.JOURNAL_NOTE),
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                FilledTonalButton(
                    onClick = { onSave(text) },
                    enabled = !saving && text.trim() != saved.orEmpty(),
                    modifier = Modifier.testTag(UiTags.JOURNAL_NOTE_SAVE),
                ) {
                    Text("Enregistrer")
                }
            }
        }
    }
}

/** The day's indicators, the API's figures; each row opens the section it comes from. */
@Composable
private fun FiguresCard(entry: HealthDay?, zone: ZoneId, onSelectSection: (HealthSection) -> Unit) {
    val surface = maggieSurface()
    MaggieCard(modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Indicateurs du jour" }.testTag(UiTags.JOURNAL_FIGURES)) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Text("Indicateurs du jour", fontSize = 14.sp, color = surface.textMuted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            Figure("Poids", journalWeight(entry, zone), UiTags.JOURNAL_WEIGHT, UiTags.JOURNAL_WEIGHT_VALUE) { onSelectSection(HealthSection.SUIVI) }
            Figure("Pas", entry?.steps?.let(::formatCount) ?: DASH, UiTags.JOURNAL_STEPS, UiTags.JOURNAL_STEPS_VALUE) { onSelectSection(HealthSection.SUIVI) }
            Figure("Bilan", balanceLabel(entry?.balanceKcal) ?: DASH, UiTags.JOURNAL_BALANCE, UiTags.JOURNAL_BALANCE_VALUE) { onSelectSection(HealthSection.NUTRITION) }
            Figure("Sport", journalSport(entry?.sportMinutes), UiTags.JOURNAL_SPORT, UiTags.JOURNAL_SPORT_VALUE) { onSelectSection(HealthSection.SPORT) }
        }
    }
}

@Composable
private fun Figure(label: String, value: String, tag: String, valueTag: String, onOpen: () -> Unit) {
    val surface = maggieSurface()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClickLabel = "Ouvrir", role = Role.Button, onClick = onOpen)
            .padding(horizontal = 16.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 14.sp, color = surface.caption, modifier = Modifier.weight(1f))
        Text(value, fontSize = 15.sp, color = surface.text, modifier = Modifier.testTag(valueTag))
    }
}

/** « Jours précédents »: the journal's days before today, newest first — date, note, « M 7 · F 4 »; a tap chooses the day. */
@Composable
private fun HistoryCard(days: List<HealthDay>, selected: LocalDate, onSelect: (LocalDate) -> Unit) {
    val surface = maggieSurface()
    MaggieCard(modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Jours précédents" }.testTag(UiTags.JOURNAL_HISTORY)) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Text("Jours précédents", fontSize = 14.sp, color = surface.textMuted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            if (days.isEmpty()) {
                Text(
                    "Rien de noté ces derniers jours",
                    fontSize = 13.sp,
                    color = surface.caption,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).testTag(UiTags.JOURNAL_HISTORY_EMPTY),
                )
            }
            days.forEach { day ->
                val date = runCatching { LocalDate.parse(day.date) }.getOrNull() ?: return@forEach
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .background(if (date == selected) surface.text.copy(alpha = 0.07f) else Color.Transparent)
                        .selectable(selected = date == selected, onClick = { onSelect(date) })
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag(UiTags.journalPast(day.date)),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(dayLabel(date), fontSize = 14.sp, color = surface.text)
                        day.note?.let { Text(it, fontSize = 12.sp, color = surface.caption, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                    Text(journalScales(day), fontSize = 13.sp, color = surface.textMuted)
                }
            }
        }
    }
}
