package com.maggie.app.ui.screens.digest

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip

import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.AgendaHomeEvent
import com.maggie.app.data.model.CuisineHomeState
import com.maggie.app.data.model.FinanceHomeRing
import com.maggie.app.data.model.HomeTask
import com.maggie.app.data.model.Trip
import com.maggie.app.data.model.formatCentsRounded
import com.maggie.app.data.model.label
import com.maggie.app.data.model.missingLabel
import com.maggie.app.data.model.recipesLabel
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.ChatBottomBar
import com.maggie.app.ui.components.MaggieAvatar
import com.maggie.app.ui.components.VoiceBubble
import com.maggie.app.ui.components.rememberVoicePlayback
import com.maggie.app.ui.layout.rememberAppLayout
import com.maggie.app.ui.navigation.Screen
import com.maggie.app.ui.screens.home.HomeCard
import com.maggie.app.ui.screens.home.HomeModules
import com.maggie.app.ui.screens.home.rowTimeLabel
import com.maggie.app.ui.screens.tasks.lateLabel
import com.maggie.app.util.DateRanges
import com.maggie.app.util.stripDeliveryTags
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dateFormat = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH)

internal fun digestDateLabel(date: LocalDate): String =
    date.format(dateFormat).replaceFirstChar { it.uppercase() }

/**
 * The Digest du matin (MAG-710): the date, Maggie's voice and word, then a card per module that has something to say —
 * the weather first. Each card opens its module through [onNavigate]; « Répondre à Maggie… » opens the conversation.
 */
@Composable
fun DigestScreen(
    viewModel: DigestViewModel,
    onBack: () -> Unit,
    onNavigate: (String) -> Unit,
    onOpenChat: () -> Unit,
) {
    val ui by viewModel.uiState.collectAsState()
    DigestContent(ui, onBack, onNavigate, onOpenChat)
}

@Composable
internal fun DigestContent(
    ui: DigestUiState,
    onBack: () -> Unit,
    onNavigate: (String) -> Unit,
    onOpenChat: () -> Unit,
) {
    val twoColumns = rememberAppLayout().detailPaneFits
    Column(modifier = Modifier.fillMaxSize().testTag(UiTags.DIGEST_SCREEN)) {
        Row(modifier = Modifier.fillMaxWidth().statusBarsPadding().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.testTag(UiTags.DIGEST_BACK)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
            }
            ui.date?.let {
                Text(digestDateLabel(it), style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag(UiTags.DIGEST_DATE))
            }
        }
        if (ui.isLoading) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Greeting(ui)
                val cards = digestCards(ui, onNavigate)
                if (twoColumns) {
                    val half = (cards.size + 1) / 2
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) { cards.take(half).forEach { it() } }
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) { cards.drop(half).forEach { it() } }
                    }
                } else {
                    cards.forEach { it() }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        ChatBottomBar(onOpenChat = onOpenChat, placeholder = "Répondre à Maggie…")
    }
}

@Composable
private fun Greeting(ui: DigestUiState) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MaggieAvatar(size = 40.dp)
        Text("Bonjour", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag(UiTags.DIGEST_GREETING))
    }
    ui.voice?.let { voice -> voice.audio?.let { VoiceBubble(messageId = voice.id, audio = it, playback = rememberVoicePlayback()) } }
    val text = ui.message?.let { stripDeliveryTags(it.content) }.orEmpty()
    if (text.isNotEmpty()) {
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag(UiTags.DIGEST_MESSAGE_TEXT))
    }
}

private fun digestCards(ui: DigestUiState, onNavigate: (String) -> Unit): List<@Composable () -> Unit> = buildList {
    ui.weather?.let { add { WeatherCard(it) } }
    if (ui.showsAgenda) add { AgendaDigestCard(ui, onOpen = { onNavigate(Screen.Calendar.route) }) }
    if (ui.showsTasks) add { TasksDigestCard(ui.tasks!!.tasks.filter { !it.isSubtask }, onOpen = { onNavigate(Screen.Tasks.route) }) }
    if (ui.showsCuisine) add { CuisineDigestCard(ui.cuisine!!, onOpen = { onNavigate(Screen.Cookbook.route) }) }
    if (ui.showsFinance) add { FinanceDigestCard(ui.finance!!.week, onOpen = { onNavigate(Screen.FinanceDashboard.route) }) }
}

@Composable
private fun AgendaDigestCard(ui: DigestUiState, onOpen: () -> Unit) {
    val state = ui.agenda ?: return
    val zone = DateRanges.zone
    val now = ui.now
    val eventsById = state.events.associateBy { it.id }
    HomeCard(title = "Agenda", module = HomeModules.AGENDA, onOpen = onOpen) {
        state.events.forEach { event -> EventRow(event, state.date) }
        ui.trips.forEach { trip ->
            val title = eventsById[trip.eventId]?.summary
            TripRow(trip, title, zone, now)
        }
    }
}

@Composable
private fun EventRow(event: AgendaHomeEvent, date: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(rowTimeLabel(event, date), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(64.dp))
        Text(event.summary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun TripRow(trip: Trip, title: String?, zone: java.time.ZoneId, now: java.time.Instant?) {
    val headline = trip.headline(zone, now ?: java.time.Instant.now())
    Text(
        text = if (title == null) headline else "$headline · $title",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 2.dp).testTag(UiTags.DIGEST_TRIP),
    )
}

@Composable
private fun TasksDigestCard(tasks: List<HomeTask>, onOpen: () -> Unit) {
    HomeCard(title = "Tâches", module = HomeModules.TASKS, onOpen = onOpen) {
        tasks.forEach { task ->
            Column(modifier = Modifier.padding(vertical = 2.dp)) {
                Text(task.title, style = MaterialTheme.typography.bodyMedium)
                val late = task.overdueDays?.takeIf { it > 0 && !task.done }
                if (late != null) {
                    Text(
                        text = if (late == 1) "reportée hier" else lateLabel(late),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun CuisineDigestCard(state: CuisineHomeState, onOpen: () -> Unit) {
    HomeCard(title = "Cuisine", module = HomeModules.CUISINE, onOpen = onOpen) {
        state.slots.filter { it.meals.isNotEmpty() }.forEach { slot ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(slot.label(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(88.dp))
                Column(modifier = Modifier.weight(1f)) {
                    slot.meals.forEach { meal ->
                        Text(meal.recipesLabel(), style = MaterialTheme.typography.bodyMedium)
                        meal.missingLabel()?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
}

@Composable
private fun FinanceDigestCard(week: FinanceHomeRing, onOpen: () -> Unit) {
    HomeCard(title = "Finance", module = HomeModules.FINANCE, onOpen = onOpen) {
        val planned = week.plannedCents.coerceAtLeast(1).toFloat()
        val spent = (week.spentCents / planned).coerceIn(0f, 1f)
        val expected = (week.expectedSpentCents / planned).coerceIn(0f, 1f)
        Text(
            text = "Semaine : ${formatCentsRounded(week.spentCents)} dépensés sur ${formatCentsRounded(week.plannedCents)}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(8.dp))
        WeekBar(spent = spent, expected = expected)
    }
}

/** The week as a bar: what was spent, and a tick at where the day stands (what was expected by now). */
@Composable
private fun WeekBar(spent: Float, expected: Float) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val fill = MaterialTheme.colorScheme.primary
    val tick = MaterialTheme.colorScheme.onSurface
    Box(modifier = Modifier.fillMaxWidth().height(12.dp).testTag(UiTags.DIGEST_FINANCE_WEEK)) {
        Box(Modifier.fillMaxSize().clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp)).background(track))
        Box(Modifier.fillMaxWidth(spent).fillMaxSize().clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp)).background(fill))
        Row(Modifier.fillMaxSize()) {
            if (expected > 0f) Spacer(Modifier.weight(expected))
            Box(Modifier.width(2.dp).fillMaxSize().background(tick))
            if (expected < 1f) Spacer(Modifier.weight(1f - expected))
        }
    }
}


