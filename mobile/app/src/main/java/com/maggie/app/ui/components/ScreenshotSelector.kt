package com.maggie.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.maggie.app.ui.UiTags
import com.maggie.app.voice.CropRegion

/**
 * The screenshot the assistant was summoned over, which the user circles to send only
 * the zone they are asking about (MAG-316). The zone is the rectangle around the finger's
 * stroke; [region] is the one chosen, [onSelect] gets a new one — or null to send the whole
 * screen again. Nothing circled is the behaviour of MAG-214.
 */
@Composable
fun ScreenshotSelector(
    image: ImageBitmap,
    region: CropRegion?,
    onSelect: (CropRegion?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var live by remember { mutableStateOf<CropRegion?>(null) }
    val currentOnSelect by rememberUpdatedState(onSelect)
    val scrim = Color.Black.copy(alpha = 0.45f)
    val outline = MaterialTheme.colorScheme.primary

    Column(modifier = modifier.fillMaxWidth()) {
        Image(
            bitmap = image,
            contentDescription = "Capture d'écran à envoyer",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .heightIn(max = 240.dp)
                .clip(RoundedCornerShape(8.dp))
                .testTag(UiTags.ASSIST_SCREENSHOT)
                .drawWithContent {
                    drawContent()
                    (live ?: region)?.let { zone ->
                        val topLeft = Offset(zone.left * size.width, zone.top * size.height)
                        val zoneSize = Size((zone.right - zone.left) * size.width, (zone.bottom - zone.top) * size.height)
                        // Everything but the zone, dimmed.
                        drawRect(scrim, Offset.Zero, Size(size.width, topLeft.y))
                        drawRect(scrim, Offset(0f, topLeft.y + zoneSize.height), Size(size.width, size.height - topLeft.y - zoneSize.height))
                        drawRect(scrim, Offset(0f, topLeft.y), Size(topLeft.x, zoneSize.height))
                        drawRect(scrim, Offset(topLeft.x + zoneSize.width, topLeft.y), Size(size.width - topLeft.x - zoneSize.width, zoneSize.height))
                        drawRect(outline, topLeft, zoneSize, style = Stroke(width = 3.dp.toPx()))
                    }
                }
                .pointerInput(Unit) {
                    var min = Offset.Zero
                    var max = Offset.Zero
                    fun extend(at: Offset) {
                        min = Offset(minOf(min.x, at.x), minOf(min.y, at.y))
                        max = Offset(maxOf(max.x, at.x), maxOf(max.y, at.y))
                        live = CropRegion.around(min.x, min.y, max.x, max.y, size.width.toFloat(), size.height.toFloat())
                    }
                    detectDragGestures(
                        onDragStart = { at ->
                            min = at
                            max = at
                            live = null
                        },
                        onDragEnd = {
                            // A stroke too small to be a zone changes nothing; « Tout l'écran » drops one.
                            live?.let(currentOnSelect)
                            live = null
                        },
                        onDragCancel = { live = null },
                        onDrag = { change, _ ->
                            change.consume()
                            extend(change.position)
                        },
                    )
                },
        )
        if (region != null) {
            TextButton(
                onClick = { onSelect(null) },
                modifier = Modifier.testTag(UiTags.ASSIST_SCREENSHOT_RESET),
            ) {
                Text("Tout l'écran")
            }
        } else {
            Text(
                text = "Entourez une zone pour ne l'envoyer qu'elle",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
