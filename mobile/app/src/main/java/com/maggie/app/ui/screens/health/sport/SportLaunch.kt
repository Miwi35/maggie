package com.maggie.app.ui.screens.health.sport

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.Workout
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.MaggieCard
import com.maggie.app.ui.screens.health.formatWorkoutDuration
import com.maggie.app.ui.screens.health.workoutStart
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** What the « Séance de ce soir » card of Santé › Sport shows (MAG-779). */
data class SportLaunch(
    val workoutId: String,
    val title: String,
    val subtitle: String,
    /** « Lancer » or « ▶ Reprendre · 2/6 ». */
    val buttonLabel: String,
    val resuming: Boolean,
)

/** « 18 h 30 », « 9 h ». */
internal fun clockLabel(hour: Int, minute: Int): String = if (minute == 0) "$hour h" else "$hour h ${minute.toString().padStart(2, '0')}"

/**
 * The card's content: today's earliest launchable planned session — a strength one with exercises —, or null (tomorrow's,
 * without exercises, none: no card). With a status on that very session the subtitle says where it is and for how long.
 */
fun sportLaunchOf(workouts: List<Workout>, today: LocalDate, zone: ZoneId, resume: SportResume?, now: Instant): SportLaunch? {
    val workout = workouts
        .filter { it.isLaunchableOn(today, zone) }
        .minByOrNull { workoutStart(it, zone)?.toInstant() ?: Instant.MAX }
        ?: return null
    val start = workoutStart(workout, zone) ?: return null
    val moment = momentLabel(start.toLocalTime())
    val active = resume.takeIf { it.resumes(workout.id) }
    val subtitle = if (active != null) {
        val elapsed = active.startedAt?.let { Duration.between(it, now).toMinutes().coerceAtLeast(0) }
        listOfNotNull("En cours", "exercice ${active.exerciseIndex + 1} / ${active.exerciseCount}", elapsed?.let { "$it min" })
            .joinToString(" · ")
    } else {
        listOf(
            moment,
            clockLabel(start.hour, start.minute),
            formatWorkoutDuration(workout.durationMinutes),
            exerciseCountLabel(workout.exercises.size),
        ).joinToString(" · ")
    }
    return SportLaunch(
        workoutId = workout.id,
        title = "Séance de ${moment.replaceFirstChar { it.lowercase() }}",
        subtitle = subtitle,
        buttonLabel = if (active != null) "▶ Reprendre · ${active.progress}" else "Lancer",
        resuming = active != null,
    )
}

/** The 44 dp pill. */
@Composable
fun SportLaunchButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 44.dp).testTag(UiTags.SPORT_LAUNCH_BUTTON),
        shape = RoundedCornerShape(22.dp),
    ) { Text(label) }
}

@Composable
fun SportLaunchCard(launch: SportLaunch, onLaunch: (String) -> Unit, modifier: Modifier = Modifier) {
    MaggieCard(modifier = modifier.fillMaxWidth().testTag(UiTags.SPORT_LAUNCH_CARD)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(launch.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Text(
                    launch.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(UiTags.SPORT_LAUNCH_SUBTITLE),
                )
            }
            SportLaunchButton(launch.buttonLabel, onClick = { onLaunch(launch.workoutId) })
        }
    }
}
