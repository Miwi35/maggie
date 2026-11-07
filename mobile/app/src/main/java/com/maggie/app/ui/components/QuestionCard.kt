package com.maggie.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.QuestionBlock
import com.maggie.app.ui.UiTags

/**
 * A question Maggie put in the thread (MAG-717): boxes to tick (one only unless the question takes several) and
 * « Valider ». Answered, the card fades, shows what was chosen and offers nothing.
 */
@Composable
fun QuestionCard(
    block: QuestionBlock,
    checked: Set<String>,
    onToggle: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    answering: Boolean = false,
    failed: Boolean = false,
) {
    val answered = block.answered
    Card(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (answered != null) ANSWERED_ALPHA else 1f)
            .testTag(UiTags.questionCard(block.id)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text = block.text, style = MaterialTheme.typography.titleSmall)
            ChoiceBoxes(
                options = block.options,
                checked = answered?.toSet() ?: checked,
                enabled = answered == null && !answering,
                onToggle = onToggle,
            )
            if (answered == null) {
                if (failed) {
                    Text(
                        text = "Réponse non transmise, réessaie.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Button(
                    onClick = onSubmit,
                    enabled = checked.isNotEmpty() && !answering,
                    modifier = Modifier.align(Alignment.End).testTag(UiTags.questionSubmit(block.id)),
                ) { Text("Valider") }
            }
        }
    }
}

/** The options of a question as boxes, the same in the thread and in the interruption. */
@Composable
fun ChoiceBoxes(
    options: List<String>,
    checked: Set<String>,
    enabled: Boolean,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        options.forEach { option ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled, role = Role.Checkbox) { onToggle(option) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = option in checked, onCheckedChange = null, enabled = enabled)
                Text(text = option, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

private const val ANSWERED_ALPHA = 0.6f
