package com.maggie.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.maggie.app.ui.UiTags
import com.maggie.app.voice.VoiceManager
import com.maggie.app.voice.VoiceState

@Composable
fun VoiceControlBar(
    voiceManager: VoiceManager,
    modifier: Modifier = Modifier,
) {
    val voiceState by voiceManager.state.collectAsState()
    val duration by voiceManager.duration.collectAsState()
    val errorMessage by voiceManager.errorMessage.collectAsState()

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
        VoiceState.IDLE -> "Appuyez pour parler"
        VoiceState.LISTENING -> "Je vous écoute... ${duration}s"
        VoiceState.TRANSCRIBING -> "Transcription..."
        VoiceState.PROCESSING -> "Maggie réfléchit..."
        VoiceState.SPEAKING -> "Maggie parle..."
        VoiceState.ERROR -> errorMessage ?: "Erreur — appuyez pour réessayer"
    }

    val icon = when (voiceState) {
        VoiceState.LISTENING -> Icons.Default.Stop
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
            FilledIconButton(
                onClick = {
                    when (voiceState) {
                        VoiceState.IDLE, VoiceState.ERROR -> voiceManager.startListening()
                        VoiceState.LISTENING -> voiceManager.stopAndTranscribe()
                        VoiceState.SPEAKING -> {
                            voiceManager.stopSpeaking()
                            voiceManager.startListening()
                        }
                        VoiceState.PROCESSING, VoiceState.TRANSCRIBING -> { /* wait */ }
                    }
                },
                modifier = Modifier
                    .size(64.dp)
                    .scale(if (isListening) pulseScale else 1f),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = buttonColor,
                ),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = stateLabel,
                    modifier = Modifier.size(32.dp),
                )
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
    }
}
