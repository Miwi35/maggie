package com.maggie.app.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** « − 2 + »: a number of people by steps, never under one. Tagged `<tag>_minus`, `<tag>_value`, `<tag>_plus`. */
@Composable
fun GuestsStepper(
    value: Int,
    onStep: (delta: Int) -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = { onStep(-1) },
            enabled = enabled && value > 1,
            modifier = Modifier.testTag(tag + "_minus"),
        ) {
            Icon(Icons.Default.Remove, contentDescription = "Diminuer")
        }
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 32.dp).testTag(tag + "_value"),
        )
        IconButton(
            onClick = { onStep(1) },
            enabled = enabled,
            modifier = Modifier.testTag(tag + "_plus"),
        ) {
            Icon(Icons.Default.Add, contentDescription = "Augmenter")
        }
    }
}
