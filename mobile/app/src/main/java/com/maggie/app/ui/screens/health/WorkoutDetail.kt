package com.maggie.app.ui.screens.health

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maggie.app.data.model.Workout
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.uiTagRoot
import java.time.ZoneId

/** The small grey pill that says where a session comes from (« Saisie », « Montre »…). */
@Composable
internal fun SourcePill(source: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.height(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(workoutSourceLabel(source), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The round tile of a session's type, [size] wide. */
@Composable
internal fun WorkoutIcon(type: String, size: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.size(size.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(WorkoutType.of(type).icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
    }
}

/**
 * The detail of a session (MAG-705), in the phone's sheet and the tablet's pane: its type, title and date, the figures it
 * has, how it felt, then « Modifier » and « Supprimer » (the latter asks first).
 */
@Composable
fun WorkoutDetail(
    workout: Workout,
    zone: ZoneId,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmingDelete by rememberSaveable(workout.id) { mutableStateOf(false) }
    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Supprimer « ${workout.title} » ?") },
            text = { Text("Cette action est définitive.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDelete = false
                        onDelete()
                    },
                    modifier = Modifier.testTag(UiTags.WORKOUT_DELETE_CONFIRM),
                ) { Text("Supprimer", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Annuler") } },
            modifier = Modifier.uiTagRoot(),
        )
    }

    val metrics = listOfNotNull(
        "Durée" to formatWorkoutDuration(workout.durationMinutes),
        workout.distanceMeters?.let { "Distance" to formatWorkoutDistance(it) },
        workout.kcal?.let { "kcal" to it.toString() },
        workout.avgHeartRate?.let { "FC moy." to formatWorkoutHeartRate(it) },
    )
    val feeling = WorkoutFeeling.of(workout.feeling)?.takeUnless { workout.isPlanned }
    val day = workoutStart(workout, zone)?.toLocalDate()?.let(::formatWorkoutDate)

    Column(modifier = modifier.fillMaxWidth().testTag(UiTags.WORKOUT_DETAIL), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            WorkoutIcon(workout.type, size = 44)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(workout.title, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                Text(
                    listOfNotNull(WorkoutType.of(workout.type).label, day).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SourcePill(workout.source)
        }

        metrics.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                pair.forEach { (label, value) -> MetricTile(label, value, Modifier.weight(1f)) }
                if (pair.size == 1) Box(Modifier.weight(1f))
            }
        }

        feeling?.let {
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Ressenti", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(it.label)
            }
        }
        workout.notes?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onEdit, modifier = Modifier.weight(1f).testTag(UiTags.WORKOUT_EDIT)) { Text("Modifier") }
            OutlinedButton(onClick = { confirmingDelete = true }, modifier = Modifier.weight(1f).testTag(UiTags.WORKOUT_DELETE)) {
                Text("Supprimer", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun MetricTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 18.sp)
    }
}
