package com.maggie.app.ui.screens.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.DateField
import com.maggie.app.ui.components.LabeledField
import com.maggie.app.ui.components.TimeField

/** « Toute la journée », then Début and Fin in two columns: dates and times are picked, never typed. */
@Composable
fun EventDateFields(
    form: EventFormState,
    onChange: (EventFormState) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Toute la journée")
            Switch(
                checked = form.allDay,
                onCheckedChange = { onChange(form.withAllDay(it)) },
                modifier = Modifier.testTag(UiTags.EVENT_ALL_DAY),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            LabeledField(label = "Début", modifier = Modifier.weight(1f)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DateField(
                        label = "Date de début",
                        value = form.startDate,
                        onValueChange = { onChange(form.withStartDate(it)) },
                        tag = UiTags.EVENT_START_DATE,
                        filled = true,
                    )
                    if (!form.allDay) {
                        TimeField(
                            label = "Heure de début",
                            value = form.startTime,
                            onValueChange = { onChange(form.withStartTime(it)) },
                            tag = UiTags.EVENT_START_TIME,
                            filled = true,
                        )
                    }
                }
            }
            LabeledField(label = "Fin", modifier = Modifier.weight(1f)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DateField(
                        label = "Date de fin",
                        value = form.endDate,
                        onValueChange = { onChange(form.withEndDate(it)) },
                        isError = form.endsBeforeStart,
                        tag = UiTags.EVENT_END_DATE,
                        filled = true,
                    )
                    if (!form.allDay) {
                        TimeField(
                            label = "Heure de fin",
                            value = form.endTime,
                            onValueChange = { onChange(form.withEndTime(it)) },
                            isError = form.endsBeforeStart,
                            filled = true,
                        )
                    }
                    if (form.endsBeforeStart) {
                        Text(
                            "La fin précède le début",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}
