package com.maggie.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.Task
import com.maggie.app.ui.UiTags
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The card of a task the thread or an interruption points at (MAG-717): its title, its day and its checklist count; a tap opens it. */
@Composable
fun TaskCard(task: Task, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag(UiTags.taskCard(task.id))
            .clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = task.title,
                style = MaterialTheme.typography.titleSmall,
                textDecoration = if (task.isDone) TextDecoration.LineThrough else null,
            )
            taskDayLabel(task)?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            checklistCount(task.description)?.let {
                Text(text = it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A task card that fetches its task by id with [load]; nothing is drawn while it loads or when it cannot be read. */
@Composable
fun TaskBlockCard(taskId: String, load: suspend (String) -> Task?, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val task by produceState<Task?>(null, taskId) { value = load(taskId) }
    task?.let { TaskCard(it, onOpen, modifier) }
}

private val CHECKBOX = Regex("""^\s*[-*+]\s+\[([ xX])]""", RegexOption.MULTILINE)
private val DAY_FORMAT = DateTimeFormatter.ofPattern("d MMM", Locale.FRENCH)

/** « 0/6 » for the `- [ ]` and `- [x]` lines of the notes; null without any box. The list itself is never shown. */
fun checklistCount(description: String?): String? {
    val boxes = CHECKBOX.findAll(description.orEmpty()).toList()
    if (boxes.isEmpty()) return null
    return "${boxes.count { it.groupValues[1] != " " }}/${boxes.size}"
}

fun taskDayLabel(task: Task): String? {
    task.scheduledDay?.let { day ->
        val moment = when (task.scheduledMoment) {
            Task.MOMENT_MORNING -> " · matin"
            Task.MOMENT_AFTERNOON -> " · après-midi"
            Task.MOMENT_EVENING -> " · soir"
            Task.MOMENT_TIME -> task.scheduledAt?.let { " à ${it.format(DateTimeFormatter.ofPattern("HH:mm"))}" }.orEmpty()
            else -> ""
        }
        return "Prévue le ${dayLabel(day)}$moment"
    }
    return task.dueDay?.let { "Pour le ${dayLabel(it)}" }
}

private fun dayLabel(day: LocalDate): String = day.format(DAY_FORMAT)
