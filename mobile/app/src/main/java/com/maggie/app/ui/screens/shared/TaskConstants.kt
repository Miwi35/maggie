package com.maggie.app.ui.screens.shared

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object TaskConstants {
    val CRITICALITY_COLORS = mapOf(
        "low" to Color(0xFF4CAF50),
        "medium" to Color(0xFFFF9800),
        "high" to Color(0xFFF44336),
        "critical" to Color(0xFF9C27B0),
    )

    val CRITICALITY_LABELS = mapOf(
        "low" to "Faible",
        "medium" to "Moyen",
        "high" to "Élevé",
        "critical" to "Critique",
    )

    val CRITICALITY_ORDER = mapOf(
        "critical" to 0,
        "high" to 1,
        "medium" to 2,
        "low" to 3,
    )

    val PRIORITY_LABELS = mapOf(
        "low" to "Basse",
        "medium" to "Moyenne",
        "high" to "Haute",
        "urgent" to "Urgente",
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DropdownField(
    label: String,
    value: String,
    options: Map<String, String>,
    onValueChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = options[value] ?: value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { (key, displayLabel) ->
                DropdownMenuItem(
                    text = { Text(displayLabel) },
                    onClick = {
                        onValueChange(key)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
fun CriticalityChip(criticality: String) {
    val color = TaskConstants.CRITICALITY_COLORS[criticality] ?: TaskConstants.CRITICALITY_COLORS["low"]!!
    Surface(
        color = color,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.height(20.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = criticality,
                color = Color.White,
                fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
        }
    }
}
