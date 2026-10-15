package com.maggie.app.ui.screens.shared

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import com.maggie.app.ui.UiTags

/**
 * « Durée estimée » as typed (MAG-497): whole minutes, 1 to 1440 as the API takes them. An empty
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

/** « Durée estimée » and « Premier pas », the same on the create and the edit screen. */
@Composable
fun EstimateFields(
    estimate: String,
    onEstimateChange: (String) -> Unit,
    firstStep: String,
    onFirstStepChange: (String) -> Unit,
) {
    val valid = TaskEstimate.isValid(estimate)
    OutlinedTextField(
        value = estimate,
        onValueChange = { onEstimateChange(TaskEstimate.sanitize(it)) },
        label = { Text("Durée estimée (min)") },
        singleLine = true,
        isError = !valid,
        supportingText = if (valid) null else ({ Text("De 1 à ${TaskEstimate.MAX_MINUTES} minutes") }),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().testTag(UiTags.TASK_ESTIMATE),
    )
    OutlinedTextField(
        value = firstStep,
        onValueChange = { onFirstStepChange(it.take(TaskEstimate.FIRST_STEP_MAX)) },
        label = { Text("Premier pas") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag(UiTags.TASK_FIRST_STEP),
    )
}
