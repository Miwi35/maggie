package com.maggie.app.ui.screens.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.DateField
import com.maggie.app.ui.components.TimeField

/** « Toute la journée », then the start and the end: dates and times are picked, never typed. */
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
            Switch(checked = form.allDay, onCheckedChange = { onChange(form.withAllDay(it)) })
        }

        DateField(
            label = "Date de début",
            value = form.startDate,
            onValueChange = { onChange(form.withStartDate(it)) },
            tag = UiTags.EVENT_START_DATE,
        )

        if (!form.allDay) {
            TimeField(
                label = "Heure de début",
                value = form.startTime,
                onValueChange = { onChange(form.withStartTime(it)) },
                tag = UiTags.EVENT_START_TIME,
            )
        }

        DateField(
            label = "Date de fin",
            value = form.endDate,
            onValueChange = { onChange(form.withEndDate(it)) },
            isError = form.endsBeforeStart,
            supportingText = if (form.endsBeforeStart) "La fin précède le début" else null,
            tag = UiTags.EVENT_END_DATE,
        )

        if (!form.allDay) {
            TimeField(
                label = "Heure de fin",
                value = form.endTime,
                onValueChange = { onChange(form.withEndTime(it)) },
                isError = form.endsBeforeStart,
            )
        }
    }
}
