package com.maggie.app.ui.screens.finance

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.maggie.app.ui.UiTags

/**
 * Where the cursor goes when its line leaves the list (qualified, corrected): the line that followed it, else the
 * one before, else nothing. [before] is the order it was read in, [after] the order now (MAG-507).
 */
fun neighbourAfterRemoval(before: List<String>, removedId: String, after: List<String>): String? {
    val present = after.toSet()
    val at = before.indexOf(removedId)
    if (at < 0) return after.firstOrNull()
    return before.drop(at + 1).firstOrNull { it in present }
        ?: before.take(at).lastOrNull { it in present }
}

/** « Précédent », « 3 / 12 », « Suivant » over a fiche. */
@Composable
fun ReviewSheetNav(
    position: Int,
    total: Int,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onPrevious, enabled = hasPrevious, modifier = Modifier.testTag(UiTags.REVIEW_PREVIOUS)) {
            Text("Précédent")
        }
        Text(
            "$position / $total",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(UiTags.REVIEW_POSITION),
        )
        TextButton(onClick = onNext, enabled = hasNext, modifier = Modifier.testTag(UiTags.REVIEW_NEXT)) {
            Text("Suivant")
        }
    }
}

private val SWIPE_THRESHOLD = 72.dp

/** A swipe to the left asks for the next line, to the right the previous one; a short drag does nothing. */
fun Modifier.swipeToNavigate(onPrevious: () -> Unit, onNext: () -> Unit): Modifier =
    pointerInput(onPrevious, onNext) {
        var travelled = 0f
        val threshold = SWIPE_THRESHOLD.toPx()
        detectHorizontalDragGestures(
            onDragStart = { travelled = 0f },
            onDragEnd = {
                when {
                    travelled <= -threshold -> onNext()
                    travelled >= threshold -> onPrevious()
                }
            },
            onDragCancel = { travelled = 0f },
            onHorizontalDrag = { _, delta -> travelled += delta },
        )
    }
