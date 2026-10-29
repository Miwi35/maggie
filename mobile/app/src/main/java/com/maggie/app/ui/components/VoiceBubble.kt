package com.maggie.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.AudioAttachment
import com.maggie.app.ui.UiTags
import com.maggie.app.util.stripDeliveryTags
import com.maggie.app.voice.VoicePlayback
import com.maggie.app.voice.VoicePlaybackState
import org.koin.core.context.GlobalContext

/** The player of the voice bubbles, set by a screen test; the app finds it in Koin. */
val LocalVoicePlayback = staticCompositionLocalOf<VoicePlayback?> { null }

/** The one player the bubbles share, or `null` where there is none (previews: the bubble stays still). */
@Composable
fun rememberVoicePlayback(): VoicePlayback? {
    val provided = LocalVoicePlayback.current
    return remember(provided) {
        provided ?: runCatching { GlobalContext.get().get<VoicePlayback>() }.getOrNull()
    }
}

/**
 * A voice message of Maggie's, like a WhatsApp one (MAG-686): play/pause, progress to drag, duration,
 * speed ×1 → ×1,5 → ×2, and the transcript under it, folded. A playback error is said in the bubble.
 */
@Composable
fun VoiceBubble(messageId: String, audio: AudioAttachment, playback: VoicePlayback?, modifier: Modifier = Modifier) {
    val idle = remember { VoicePlaybackState() }
    val state = playback?.state?.collectAsState()?.value ?: idle
    val status = state.statusOf(messageId)
    val mine = state.messageId == messageId
    val duration = if (mine && state.durationMs > 0) state.durationMs else audio.durationMs
    val position = if (mine) state.positionMs else 0
    var transcriptOpen by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf<Float?>(null) }

    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { playback?.toggle(messageId, audio.durationMs) },
                modifier = Modifier
                    .testTag(UiTags.VOICE_PLAY)
                    .semantics {
                        contentDescription = if (status == VoicePlaybackState.Status.PLAYING) "Mettre en pause" else "Écouter le message vocal"
                    },
            ) {
                Icon(
                    if (status == VoicePlaybackState.Status.PLAYING) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = null,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Slider(
                    value = dragging ?: position.toFloat(),
                    onValueChange = { dragging = it },
                    onValueChangeFinished = {
                        dragging?.let { playback?.seekTo(messageId, it.toInt()) }
                        dragging = null
                    },
                    valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                    enabled = mine && status != VoicePlaybackState.Status.LOADING && status != VoicePlaybackState.Status.ERROR,
                    modifier = Modifier.testTag(UiTags.VOICE_SEEK),
                )
                Text(
                    text = if (mine && position > 0) "${clock(position)} / ${clock(duration)}" else clock(duration),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp).testTag(UiTags.VOICE_DURATION),
                )
            }
            TextButton(onClick = { playback?.cycleSpeed() }, modifier = Modifier.testTag(UiTags.VOICE_SPEED)) {
                Text(speedLabel(state.speed))
            }
        }
        if (status == VoicePlaybackState.Status.ERROR) {
            Text(
                text = "Lecture impossible : l'audio est introuvable ou indisponible.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(UiTags.VOICE_ERROR),
            )
        }
        TextButton(
            onClick = { transcriptOpen = !transcriptOpen },
            modifier = Modifier.testTag(UiTags.VOICE_TRANSCRIPT_TOGGLE),
        ) {
            Text(if (transcriptOpen) "Masquer la transcription" else "Afficher la transcription")
        }
        if (transcriptOpen) {
            Text(
                text = stripDeliveryTags(audio.transcript),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().testTag(UiTags.VOICE_TRANSCRIPT),
            )
        }
    }
}

/** m:ss */
internal fun clock(ms: Int): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

internal fun speedLabel(speed: Float): String = when (speed) {
    1.5f -> "×1,5"
    2f -> "×2"
    else -> "×1"
}
