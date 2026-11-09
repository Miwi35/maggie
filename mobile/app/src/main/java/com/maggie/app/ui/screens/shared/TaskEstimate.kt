package com.maggie.app.ui.screens.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.maggie.app.ui.UiTags

/**
 * « Durée » as typed (MAG-497): whole minutes, 1 to 1440 as the API takes them. An empty
 * field is no estimate, sent as null so clearing it clears the task's.
 */
object TaskEstimate {
    const val MAX_MINUTES = 1440

    /** Digits only, and no more than the largest estimate needs. */
    fun sanitize(input: String): String = input.filter(Char::isDigit).take(MAX_MINUTES.toString().length)

    fun isValid(text: String): Boolean = text.isBlank() || text.toIntOrNull() in 1..MAX_MINUTES

    /** The minutes to send: null for an empty field, or one the API would refuse. */
    fun toApi(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in 1..MAX_MINUTES }

    /** « Premier pas » as typed: trimmed, null when empty, 255 characters at most. */
    fun firstStepToApi(text: String): String? = text.trim().take(FIRST_STEP_MAX).ifEmpty { null }

    const val FIRST_STEP_MAX = 255
}

/**
 * « Durée » with « Chronométrée » beside it (MAG-766, mock-up `Tache-Creer-Tel`), and « Premier pas », the same on
 * the create and the edit screen. Chronométrée: the task is done with a timer for its duration (« méditer 20 min »),
 * which the day offers; a mere estimate stays off.
 */
@Composable
fun EstimateFields(
    estimate: String,
    onEstimateChange: (String) -> Unit,
    firstStep: String,
    onFirstStepChange: (String) -> Unit,
    timed: Boolean = false,
    onTimedChange: (Boolean) -> Unit = {},
) {
    val valid = TaskEstimate.isValid(estimate)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        OutlinedTextField(
            value = estimate,
            onValueChange = { onEstimateChange(TaskEstimate.sanitize(it)) },
            label = { Text("Durée") },
            suffix = { Text("min") },
            singleLine = true,
            isError = !valid,
            supportingText = if (valid) null else ({ Text("De 1 à ${TaskEstimate.MAX_MINUTES} minutes") }),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f).testTag(UiTags.TASK_ESTIMATE),
        )
        TimedSwitch(timed = timed, onTimedChange = onTimedChange, modifier = Modifier.padding(top = 8.dp))
    }
    OutlinedTextField(
        value = firstStep,
        onValueChange = { onFirstStepChange(it.take(TaskEstimate.FIRST_STEP_MAX)) },
        label = { Text("Premier pas") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag(UiTags.TASK_FIRST_STEP),
    )
}

/** « Chronométrée », the whole pill a switch. */
@Composable
private fun TimedSwitch(timed: Boolean, onTimedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (timed) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (timed) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .heightIn(min = 52.dp)
            .toggleable(value = timed, role = Role.Switch, onValueChange = onTimedChange)
            .testTag(UiTags.TASK_TIMED),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Default.Timer, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("Chronométrée", style = MaterialTheme.typography.bodyLarge)
            // The pill toggles: the switch only shows it.
            Switch(checked = timed, onCheckedChange = null)
        }
    }
}
