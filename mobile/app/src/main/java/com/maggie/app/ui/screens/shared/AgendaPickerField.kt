package com.maggie.app.ui.screens.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.Agenda
import com.maggie.app.ui.components.FIELD_RADIUS
import com.maggie.app.ui.components.LabeledField
import com.maggie.app.ui.components.filledFieldColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgendaPickerField(
    agendas: List<Agenda>,
    selectedAgendaIri: String?,
    onAgendaSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedAgenda = agendas.find { "/api/agendas/${it.id}" == selectedAgendaIri }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        LabeledField(label = "Agenda", modifier = Modifier.fillMaxWidth()) {
        TextField(
            value = selectedAgenda?.name ?: "Aucun agenda",
            onValueChange = {},
            readOnly = true,
            shape = RoundedCornerShape(FIELD_RADIUS),
            colors = filledFieldColors(),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            leadingIcon = selectedAgenda?.let {
                {
                    val color = parseColor(it.color)
                    if (color != null) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .background(color, CircleShape),
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        }

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            agendas.forEach { agenda ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val color = parseColor(agenda.color)
                            if (color != null) {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .background(color, CircleShape),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(agenda.name)
                        }
                    },
                    onClick = {
                        onAgendaSelected("/api/agendas/${agenda.id}")
                        expanded = false
                    },
                )
            }
        }
    }
}
