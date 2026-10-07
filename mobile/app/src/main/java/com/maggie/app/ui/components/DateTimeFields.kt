package com.maggie.app.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DatePickerState
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.uiTagRoot
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val FRENCH_DATE = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.FRENCH)
private val FRENCH_TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.FRENCH)

/** « mer. 8 oct. 2026 » — what a form shows for a date, whatever the phone's language. */
fun formatFrenchDate(date: LocalDate): String = FRENCH_DATE.format(date)

/** « 19:00 » — 24 h, as the pickers do. */
fun formatFrenchTime(time: LocalTime): String = FRENCH_TIME.format(time)

// Material 3's date picker speaks in UTC midnights, not in zoned dates.
internal fun LocalDate.toPickerMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

internal fun pickerMillisToDate(millis: Long): LocalDate =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

/**
 * The pickers' texts come from the context's resources, i.e. the phone's language.
 * Maggie is French, so a picker reads French on any phone. Applied *inside* a dialog's
 * content: a dialog is a window of its own and re-provides the phone's context to it.
 */
@Composable
private fun InFrench(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val french = remember(context) {
        val configuration = Configuration(context.resources.configuration).apply { setLocale(Locale.FRANCE) }
        context.createConfigurationContext(configuration)
    }
    CompositionLocalProvider(
        LocalContext provides french,
        LocalConfiguration provides french.resources.configuration,
        content = content,
    )
}

/** A read-only field whose whole surface opens [onClick]: nothing here is ever typed. */
@Composable
private fun PickerField(
    label: String,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    isError: Boolean,
    supportingText: String?,
    tag: String?,
    onClear: (() -> Unit)?,
) {
    Box(modifier = modifier) {
        OutlinedTextField(
            value = text,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            isError = isError,
            supportingText = supportingText?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .let { if (tag != null) it.testTag(tag) else it }
                // The overlay is the node the system sees: it carries the shown value, or no
                // service (TalkBack, Maestro) would ever read it.
                .semantics { contentDescription = if (text.isEmpty()) label else "$label, $text" }
                .clickable(onClickLabel = "Choisir : $label", role = Role.Button, onClick = onClick),
        )
        if (onClear != null && text.isNotEmpty()) {
            IconButton(onClick = onClear, modifier = Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Effacer : $label")
            }
        }
    }
}

/**
 * A date, shown « mer. 8 oct. 2026 » and chosen in a calendar (Monday first, in French).
 * [onClear] makes the date optional: it adds a button that empties the field.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(
    label: String,
    value: LocalDate?,
    onValueChange: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    onClear: (() -> Unit)? = null,
    isError: Boolean = false,
    supportingText: String? = null,
    tag: String? = null,
) {
    var open by remember { mutableStateOf(false) }

    PickerField(
        label = label,
        text = value?.let(::formatFrenchDate) ?: "",
        onClick = { open = true },
        modifier = modifier,
        isError = isError,
        supportingText = supportingText,
        tag = tag,
        onClear = onClear,
    )

    if (open) {
        // The calendar's month names and first weekday follow the locale of its state, not the context's.
        val state = remember {
            DatePickerState(
                locale = Locale.FRANCE,
                initialSelectedDateMillis = (value ?: LocalDate.now()).toPickerMillis(),
                initialDisplayedMonthMillis = null,
                yearRange = DatePickerDefaults.YearRange,
                initialDisplayMode = DisplayMode.Picker,
                selectableDates = DatePickerDefaults.AllDates,
            )
        }
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.selectedDateMillis?.let { onValueChange(pickerMillisToDate(it)) }
                        open = false
                    },
                    enabled = state.selectedDateMillis != null,
                    modifier = Modifier.testTag(UiTags.PICKER_CONFIRM),
                ) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { open = false }) { Text("Annuler") }
            },
            modifier = Modifier.uiTagRoot(),
        ) {
            InFrench { DatePicker(state = state) }
        }
    }
}

/** A time of day, shown « 19:00 » and chosen on a 24 h clock. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeField(
    label: String,
    value: LocalTime,
    onValueChange: (LocalTime) -> Unit,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    tag: String? = null,
) {
    var open by remember { mutableStateOf(false) }

    PickerField(
        label = label,
        text = formatFrenchTime(value),
        onClick = { open = true },
        modifier = modifier,
        isError = isError,
        supportingText = null,
        tag = tag,
        onClear = null,
    )

    if (open) {
        val state = rememberTimePickerState(
            initialHour = value.hour,
            initialMinute = value.minute,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(label) },
            text = { InFrench { TimePicker(state = state) } },
            confirmButton = {
                TextButton(
                    onClick = {
                        onValueChange(LocalTime.of(state.hour, state.minute))
                        open = false
                    },
                    modifier = Modifier.testTag(UiTags.PICKER_CONFIRM),
                ) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { open = false }) { Text("Annuler") }
            },
            modifier = Modifier.uiTagRoot(),
        )
    }
}
