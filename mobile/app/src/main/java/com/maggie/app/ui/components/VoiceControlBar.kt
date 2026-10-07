package com.maggie.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.maggie.app.ui.UiTags
import com.maggie.app.voice.VoiceManager
import com.maggie.app.voice.VoiceState

private val MIC_SIZE = 64.dp

/** How far past the button a held finger may drift before it counts as sliding away to cancel. */
private val SLIDE_OUT_MARGIN = 48.dp

@Composable
fun VoiceControlBar(
    voiceManager: VoiceManager,
    onResult: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnResult by rememberUpdatedState(onResult)
    val voiceState by voiceManager.state.collectAsState()
    val duration by voiceManager.duration.collectAsState()
    val errorMessage by voiceManager.errorMessage.collectAsState()
    val handsFree by voiceManager.handsFree.collectAsState()
    val holdHint by voiceManager.holdHint.collectAsState()
    val partialText by voiceManager.partialText.collectAsState()

    val isListening = voiceState == VoiceState.LISTENING
    val infiniteTransition = rememberInfiniteTransition(label = "mic_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isListening) 1.15f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "mic_scale",
    )

    val buttonColor by animateColorAsState(
        targetValue = when (voiceState) {
            VoiceState.LISTENING -> MaterialTheme.colorScheme.error
            VoiceState.SPEAKING -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.primary
        },
        label = "mic_color",
    )

    val stateLabel = when (voiceState) {
        VoiceState.IDLE -> if (holdHint) "Maintenez le bouton pour parler" else "Maintenez pour parler"
        VoiceState.LISTENING -> if (handsFree) {
            "Je vous écoute... ${duration}s"
        } else {
            "Relâchez pour envoyer... ${duration}s"
        }
        VoiceState.TRANSCRIBING -> "Transcription..."
        VoiceState.PROCESSING -> "Maggie réfléchit..."
        VoiceState.SPEAKING -> "Maggie parle..."
        VoiceState.ERROR -> errorMessage ?: "Erreur — maintenez pour réessayer"
    }

    val icon = when (voiceState) {
        VoiceState.LISTENING -> if (handsFree) Icons.Default.Stop else Icons.Default.Mic
        VoiceState.SPEAKING -> Icons.Default.MicOff
        else -> Icons.Default.Mic
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (voiceState == VoiceState.TRANSCRIBING) {
            CircularProgressIndicator(
                modifier = Modifier.size(64.dp),
                strokeWidth = 4.dp,
            )
        } else {
            // The touch target is the unscaled box: the pulse only moves what is drawn,
            // so the edge of the hit area never swims under a held finger (MAG-221).
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(MIC_SIZE)
                    .testTag(UiTags.VOICE_MIC)
                    .semantics {
                        role = Role.Button
                        contentDescription = stateLabel
                    }
                    .pointerInput(voiceManager) {
                        val slideOutMargin = SLIDE_OUT_MARGIN.toPx()
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            voiceManager.pressDown { currentOnResult(it) }
                            // A finger is wider than the button and never keeps still:
                            // only a deliberate slide, well past the edge, cancels.
                            val bounds = Rect(Offset.Zero, Size(size.width.toFloat(), size.height.toFloat()))
                                .inflate(slideOutMargin)
                            var slidOut = false
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                // Ours until it lifts: a sheet or a list around the button
                                // must not read the drift of a held finger as its own drag.
                                change.consume()
                                if (!bounds.contains(change.position)) {
                                    slidOut = true
                                    break
                                }
                                if (!change.pressed) break
                            }
                            if (slidOut) voiceManager.pressCancel() else voiceManager.pressRelease()
                        }
                    },
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(MIC_SIZE)
                        .scale(if (isListening) pulseScale else 1f)
                        .clip(CircleShape)
                        .background(buttonColor),
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = contentColorFor(buttonColor),
                    )
                }
            }
        }

        Text(
            text = stateLabel,
            modifier = Modifier.testTag(UiTags.VOICE_STATE),
            style = MaterialTheme.typography.bodyMedium,
            color = if (voiceState == VoiceState.ERROR) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )

        // What the phone's own engine is hearing, live (MAG-222). It is also the one
        // visible sign of which of the two transcriptions is at work: Whisper only
        // speaks once the button is up.
        if (isListening && partialText.isNotBlank()) {
            Text(
                text = partialText,
                modifier = Modifier.testTag(UiTags.VOICE_PARTIAL),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
