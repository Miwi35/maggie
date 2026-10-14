package com.maggie.app.ui.screens.finance

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.SPLIT_STEP_CENTS
import com.maggie.app.data.model.SplitShares
import com.maggie.app.data.model.formatCents
import kotlin.math.abs

private val HANDLE_WIDTH = 16.dp

/**
 * A mother envelope shared between her daughters: one segment each, the rest « Libre ».
 *
 * Read-only on the phone. When [onMoveBoundary] is given (the tablet, while editing) a
 * handle sits on every boundary and dragging it trades whole euros with the neighbour on
 * its right — the next daughter, or the free part for the last handle.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EnvelopeSplitBar(
    names: List<String>,
    shares: SplitShares,
    totalCents: Int,
    currency: String,
    modifier: Modifier = Modifier,
    onMoveBoundary: ((index: Int, deltaCents: Int) -> Unit)? = null,
) {
    val colors = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.tertiary,
    )
    val outline = MaterialTheme.colorScheme.outline

    Column(modifier) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(28.dp)) {
            val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
            Row(modifier = Modifier.fillMaxWidth().fillMaxHeight().clip(RoundedCornerShape(4.dp))) {
                shares.amounts.forEachIndexed { i, amount ->
                    Box(
                        modifier = Modifier
                            .weight(amount.coerceAtLeast(1).toFloat())
                            .fillMaxHeight()
                            .background(colors[i % colors.size].copy(alpha = 0.85f)),
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(shares.freeCents.coerceAtLeast(1).toFloat())
                        .fillMaxHeight()
                        .border(1.dp, outline),
                )
            }

            if (onMoveBoundary != null) {
                var before = 0
                shares.amounts.forEachIndexed { i, amount ->
                    before += amount
                    val x = if (totalCents > 0) maxWidth * (before.toFloat() / totalCents) else maxWidth * 0f
                    val label = "Frontière entre ${names[i]} et ${names.getOrNull(i + 1) ?: "libre"}"
                    BoundaryHandle(
                        description = label,
                        widthPx = widthPx,
                        totalCents = totalCents,
                        onMove = { delta -> onMoveBoundary(i, delta) },
                        modifier = Modifier.offset(x = x - HANDLE_WIDTH / 2),
                    )
                }
            }
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            shares.amounts.forEachIndexed { i, amount ->
                Text(
                    text = "${names[i]} ${formatCents(amount, currency)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                text = "Libre ${formatCents(shares.freeCents, currency)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BoundaryHandle(
    description: String,
    widthPx: Float,
    totalCents: Int,
    onMove: (deltaCents: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val move by rememberUpdatedState(onMove)
    val bar by rememberUpdatedState(widthPx to totalCents)
    var carriedCents by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .width(HANDLE_WIDTH)
            .fillMaxHeight()
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(2.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(4.dp))
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(onDragEnd = { carriedCents = 0f }) { _, dragPx ->
                    val (width, total) = bar
                    if (width <= 0f) return@detectHorizontalDragGestures
                    // Whole euros only; what does not make one yet waits for the next move.
                    carriedCents += dragPx / width * total
                    val steps = (carriedCents / SPLIT_STEP_CENTS).toInt()
                    if (abs(steps) >= 1) {
                        carriedCents -= steps * SPLIT_STEP_CENTS
                        move(steps * SPLIT_STEP_CENTS)
                    }
                }
            },
    )
}
