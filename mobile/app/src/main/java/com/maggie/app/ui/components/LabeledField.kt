package com.maggie.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maggie.app.ui.theme.isMaggieDark

/** The corner radius of a filled field, as the mock-ups draw it. */
internal val FIELD_RADIUS = 14.dp

/** A form field as the mock-ups draw it: its label above in soft ink (13 px), then [content]. */
@Composable
fun LabeledField(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
    }
}

/** The filled field: the `paper` surface, no outline, [FIELD_RADIUS] corners. */
@Composable
fun filledFieldColors(): TextFieldColors {
    val container: Color = maggieCardContainer(isMaggieDark(), raised = false)
    val scheme = MaterialTheme.colorScheme

    return TextFieldDefaults.colors(
        focusedContainerColor = container,
        unfocusedContainerColor = container,
        disabledContainerColor = container,
        errorContainerColor = container,
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent,
        disabledIndicatorColor = Color.Transparent,
        errorIndicatorColor = Color.Transparent,
        disabledTextColor = scheme.onSurface,
    )
}

/** A typed field with its label above and a filled surface. */
@Composable
fun LabeledTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
) {
    LabeledField(label = label, modifier = Modifier.fillMaxWidth()) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            shape = RoundedCornerShape(FIELD_RADIUS),
            colors = filledFieldColors(),
            modifier = modifier.fillMaxWidth(),
        )
    }
}
