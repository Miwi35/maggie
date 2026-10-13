package com.maggie.app.ui.screens.finance

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private fun colorOf(hex: String): Color {
    val code = checkNotNull(CategoryColors.normalize(hex))
    return Color(code.substring(1).toLong(16) or 0xFF000000L)
}

@Composable
private fun Swatch(
    selected: Boolean,
    description: String,
    onClick: () -> Unit,
    fill: Color? = null,
) {
    val outline = MaterialTheme.colorScheme.outline
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .then(if (fill != null) Modifier.background(fill) else Modifier)
            .border(
                if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, outline),
                CircleShape,
            )
            .semantics { contentDescription = description }
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (fill == null) {
            Icon(Icons.Default.Close, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (selected) {
            Icon(Icons.Default.Check, contentDescription = null, tint = Color.White)
        }
    }
}

/**
 * Tap a colour of the palette, take it away, or compose another one with three sliders. A
 * colour the category already has that is not in the palette is shown among the swatches and
 * kept until another is chosen. [value] and [onChange] are `#RRGGBB`, empty for none.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CategoryColorPicker(value: String, onChange: (String) -> Unit) {
    val current = CategoryColors.normalize(value)
    val swatches = if (current != null && current !in CategoryColors.PALETTE) {
        listOf(current) + CategoryColors.PALETTE
    } else {
        CategoryColors.PALETTE
    }
    var composing by rememberSaveable { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Swatch(selected = current == null, description = "Aucune couleur", onClick = { onChange("") })
            swatches.forEach { hex ->
                Swatch(
                    selected = hex == current,
                    description = "Couleur $hex",
                    onClick = { onChange(hex) },
                    fill = colorOf(hex),
                )
            }
        }

        TextButton(onClick = { composing = !composing }) { Text("Autre couleur") }

        if (composing) {
            HsvSliders(value = current, onChange = onChange)
        }
    }
}

@Composable
private fun HsvSliders(value: String?, onChange: (String) -> Unit) {
    // Kept apart from the code: a grey has no hue and a black no saturation, and the sliders
    // must not jump back to zero when the one that was moved no longer shows in the hex.
    var hsv by remember { mutableStateOf(value?.let(CategoryColors::toHsv) ?: floatArrayOf(0f, 1f, 1f)) }
    LaunchedEffect(value) {
        val external = value?.let(CategoryColors::toHsv)
        if (external != null && CategoryColors.fromHsv(hsv[0], hsv[1], hsv[2]) != value) hsv = external
    }

    fun move(index: Int, to: Float) {
        hsv = hsv.copyOf().also { it[index] = to }
        onChange(CategoryColors.fromHsv(hsv[0], hsv[1], hsv[2]))
    }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(
            Triple("Teinte", 0, 0f..360f),
            Triple("Saturation", 1, 0f..1f),
            Triple("Luminosité", 2, 0f..1f),
        ).forEach { (label, index, range) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(88.dp))
                Slider(
                    value = hsv[index],
                    onValueChange = { move(index, it) },
                    valueRange = range,
                    modifier = Modifier.semantics { contentDescription = label },
                )
            }
        }
    }
}
