package com.maggie.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.ChatMessage
import com.maggie.app.voice.ScreenshotEncoder

/**
 * [thumbnail] is the screenshot this device sent with the question, as JPEG bytes
 * (MAG-214). A message that had one but whose bytes are gone — a reload, another
 * device — only says so: the image is stored nowhere. The screen context itself is
 * never shown.
 */
@Composable
fun MessageBubble(
    message: ChatMessage,
    onClick: (() -> Unit)? = null,
    isHighlighted: Boolean = false,
    thumbnail: ByteArray? = null,
) {
    val isUser = message.role == "user"
    val highlightColor by animateColorAsState(
        targetValue = if (isHighlighted) MaterialTheme.colorScheme.primary else Color.Transparent,
        animationSpec = tween(durationMillis = 300),
        label = "highlight",
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = if (isUser)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant,
            border = if (isHighlighted) BorderStroke(2.dp, highlightColor) else null,
            modifier = Modifier
                .widthIn(max = 280.dp)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (isUser && message.hasImage) {
                    ScreenshotThumbnail(thumbnail)
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun ScreenshotThumbnail(bytes: ByteArray?) {
    val image = remember(bytes) { bytes?.let { ScreenshotEncoder.decodeThumbnail(it)?.asImageBitmap() } }
    if (image != null) {
        ScreenshotImage(image, contentDescription = "Capture d'écran")
    } else {
        Text(
            text = "Capture d'écran (non conservée)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A screenshot drawn small: a phone screen is tall, so the height is what is capped. */
@Composable
fun ScreenshotImage(image: ImageBitmap, contentDescription: String, modifier: Modifier = Modifier) {
    Image(
        bitmap = image,
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .heightIn(max = 160.dp)
            .clip(RoundedCornerShape(8.dp)),
    )
}
