package com.maggie.app.ui.screens.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.maggie.app.util.RruleUtils
import java.time.DayOfWeek
import java.time.LocalDate

private data class FreqOption(val value: String?, val label: String)

private val FREQ_OPTIONS = listOf(
    FreqOption(null, "Ne se répète pas"),
    FreqOption("DAILY", "Tous les jours"),
    FreqOption("WEEKLY", "Toutes les semaines"),
    FreqOption("MONTHLY", "Tous les mois"),
    FreqOption("YEARLY", "Tous les ans"),
)

private val DAY_BUTTONS = listOf(
    "MO" to "L", "TU" to "M", "WE" to "M", "TH" to "J", "FR" to "V", "SA" to "S", "SU" to "D",
)

private val JS_TO_RRULE_DAY = mapOf(
    DayOfWeek.MONDAY to "MO", DayOfWeek.TUESDAY to "TU", DayOfWeek.WEDNESDAY to "WE",
    DayOfWeek.THURSDAY to "TH", DayOfWeek.FRIDAY to "FR", DayOfWeek.SATURDAY to "SA", DayOfWeek.SUNDAY to "SU",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecurrencePicker(
    value: String?,
    onChange: (String?) -> Unit,
    eventStartDate: LocalDate?,
) {
    var freq by remember { mutableStateOf<String?>(null) }
    var interval by remember { mutableIntStateOf(1) }
    val byweekday = remember { mutableStateListOf<String>() }
    var endType by remember { mutableStateOf("never") }
    var count by remember { mutableIntStateOf(10) }
    var until by remember { mutableStateOf<LocalDate?>(null) }

    // Sync from external value
    LaunchedEffect(value) {
        if (value == null) {
            freq = null
            interval = 1
            byweekday.clear()
            eventStartDate?.let {
                JS_TO_RRULE_DAY[it.dayOfWeek]?.let { day -> byweekday.add(day) }
            }
            endType = "never"
            count = 10
            until = null
        }
    }

    // Build and emit RRULE when state changes
    LaunchedEffect(freq, interval, byweekday.toList(), endType, count, until) {
        if (freq == null) {
            if (value != null) onChange(null)
            return@LaunchedEffect
        }

        val rrule = RruleUtils.buildRruleString(
            freq = freq!!,
            interval = interval,
            byweekday = if (freq == "WEEKLY" && byweekday.isNotEmpty()) byweekday.toList() else null,
            count = if (endType == "count" && count > 0) count else null,
            until = if (endType == "until") until else null,
        )
        if (rrule != value) onChange(rrule)
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Frequency dropdown
        var freqExpanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = freqExpanded,
            onExpandedChange = { freqExpanded = it },
        ) {
            OutlinedTextField(
                value = FREQ_OPTIONS.find { it.value == freq }?.label ?: "Ne se répète pas",
                onValueChange = {},
                readOnly = true,
                label = { Text("Récurrence") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = freqExpanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(
                expanded = freqExpanded,
                onDismissRequest = { freqExpanded = false },
            ) {
                FREQ_OPTIONS.forEach { opt ->
                    DropdownMenuItem(
                        text = { Text(opt.label) },
                        onClick = {
                            freq = opt.value
                            freqExpanded = false
                        },
                    )
                }
            }
        }

        if (freq != null) {
            // Interval
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = if (freq == "WEEKLY") "Toutes les" else "Tous les",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = interval.toString(),
                    onValueChange = { interval = it.toIntOrNull()?.coerceAtLeast(1) ?: 1 },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(64.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(textAlign = TextAlign.Center),
                    singleLine = true,
                )
                Text(
                    text = when (freq) {
                        "DAILY" -> "jours"
                        "WEEKLY" -> "semaines"
                        "MONTHLY" -> "mois"
                        "YEARLY" -> "ans"
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            // Weekly day toggles
            if (freq == "WEEKLY") {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    DAY_BUTTONS.forEachIndexed { index, (code, label) ->
                        val selected = code in byweekday
                        SegmentedButton(
                            selected = selected,
                            onClick = {
                                if (selected && byweekday.size > 1) byweekday.remove(code)
                                else if (!selected) byweekday.add(code)
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = DAY_BUTTONS.size),
                        ) {
                            Text(label)
                        }
                    }
                }
            }

            // End condition
            var endExpanded by remember { mutableStateOf(false) }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ExposedDropdownMenuBox(
                    expanded = endExpanded,
                    onExpandedChange = { endExpanded = it },
                ) {
                    OutlinedTextField(
                        value = when (endType) {
                            "never" -> "Jamais"
                            "count" -> "Après"
                            "until" -> "Le"
                            else -> "Jamais"
                        },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Se termine") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = endExpanded) },
                        modifier = Modifier
                            .width(140.dp)
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(
                        expanded = endExpanded,
                        onDismissRequest = { endExpanded = false },
                    ) {
                        listOf("never" to "Jamais", "count" to "Après", "until" to "Le").forEach { (value, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    endType = value
                                    endExpanded = false
                                },
                            )
                        }
                    }
                }

                if (endType == "count") {
                    OutlinedTextField(
                        value = count.toString(),
                        onValueChange = { count = it.toIntOrNull()?.coerceAtLeast(1) ?: 1 },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(80.dp),
                        singleLine = true,
                    )
                    Text("occurrences", style = MaterialTheme.typography.bodyMedium)
                }
            }

            // Summary
            if (value != null) {
                Text(
                    text = RruleUtils.rruleToFrenchText(value),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
