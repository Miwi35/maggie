package com.maggie.app.ui.screens.fullcalendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun CalendarToolbar(
    title: String,
    viewType: CalendarViewType,
    onViewTypeChange: (CalendarViewType) -> Unit,
    onNavigateBackward: () -> Unit,
    onNavigateForward: () -> Unit,
    onGoToToday: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalButton(
                onClick = onGoToToday,
            ) {
                Text("Aujourd'hui")
            }

            IconButton(onClick = onNavigateBackward) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Précédent")
            }
            IconButton(onClick = onNavigateForward) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Suivant")
            }

            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
        }

        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            val options = listOf(
                CalendarViewType.MONTH to "Mois",
                CalendarViewType.WEEK to "Semaine",
                CalendarViewType.DAY to "Jour",
            )
            options.forEachIndexed { index, (type, label) ->
                SegmentedButton(
                    selected = viewType == type,
                    onClick = { onViewTypeChange(type) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                ) {
                    Text(label)
                }
            }
        }
    }
}
