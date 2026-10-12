package com.maggie.app.ui.interruption

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maggie.app.R
import com.maggie.app.data.fcm.PushActionKind
import com.maggie.app.data.fcm.PushPayload
import com.maggie.app.data.interruption.key
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.theme.MaggieTokens

/** Whether the owner asked the system to remove animations (« Supprimer les animations »). */
@Composable
fun rememberReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/**
 * Maggie speaks on her own (MAG-314): a veil over the app, her avatar with a spring and a halo, one
 * bubble with the message, the proposed action and « Plus tard ». With [reducedMotion] everything
 * fades instead of moving.
 *
 * Stateless: [InterruptionHost] decides when it is shown and for how long.
 */
@Composable
fun MaggieToaster(
    payload: PushPayload,
    busy: Boolean,
    note: String?,
    reducedMotion: Boolean,
    onAction: (PushActionKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = isSystemInDarkTheme()
    val surface = if (dark) MaggieTokens.surfaceDark else MaggieTokens.surfaceLight
    val bubbleColor = if (dark) MaggieTokens.Maggie.bubble.second else MaggieTokens.Maggie.bubble.first

    val enter = remember(payload.key) { Animatable(0f) }
    LaunchedEffect(payload.key, reducedMotion) {
        enter.animateTo(1f, tween(if (reducedMotion) FADE_REDUCED_MS else FADE_MS, easing = LinearEasing))
    }
    val pop = remember(payload.key) { Animatable(if (reducedMotion) 1f else POP_FROM) }
    LaunchedEffect(payload.key, reducedMotion) {
        if (!reducedMotion) {
            pop.animateTo(
                1f,
                spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = MaggieTokens.Motion.SPRING_STIFFNESS),
            )
        }
    }

    val primary = payload.actions().filter { it != PushActionKind.LATER }.take(2)
        .ifEmpty { listOf(PushActionKind.REPLY) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    listOf(
                        MaggieTokens.Night.background.copy(alpha = VEIL_CENTER_ALPHA),
                        MaggieTokens.Night.background.copy(alpha = VEIL_EDGE_ALPHA),
                    ),
                ),
            )
            .alpha(enter.value),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Avatar(scale = pop.value, reducedMotion = reducedMotion)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(bubbleColor, BUBBLE_SHAPE)
                    .padding(20.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .testTag(UiTags.INTERRUPTION),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "MAGGIE · maintenant",
                    color = if (dark) MaggieTokens.Brand.primary else MaggieTokens.Brand.primaryLight,
                    fontSize = MaggieTokens.Typography.xs,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp,
                )
                Text(
                    text = payload.title,
                    color = surface.text,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                payload.text?.let {
                    Text(text = it, color = surface.textMuted, style = MaterialTheme.typography.bodyMedium)
                }
                note?.let {
                    Text(text = it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = { onAction(PushActionKind.LATER) },
                        enabled = !busy,
                        modifier = Modifier.testTag(UiTags.INTERRUPTION_LATER),
                    ) { Text(PushActionKind.LATER.label) }

                    primary.forEachIndexed { index, kind ->
                        Button(
                            onClick = { onAction(kind) },
                            enabled = !busy,
                            modifier = Modifier.testTag(if (index == 0) UiTags.INTERRUPTION_ACTION else UiTags.INTERRUPTION_SECOND),
                        ) { Text(labelOf(kind, payload)) }
                    }
                }
            }
        }
    }
}

private fun labelOf(kind: PushActionKind, payload: PushPayload): String =
    if (kind == PushActionKind.GO) payload.actionLabel ?: kind.label else kind.label

@Composable
private fun Avatar(scale: Float, reducedMotion: Boolean) {
    val halo = if (reducedMotion) {
        HALO_STILL
    } else {
        val pulse = rememberInfiniteTransition(label = "halo")
        pulse.animateFloat(
            initialValue = HALO_MIN,
            targetValue = HALO_MAX,
            animationSpec = infiniteRepeatable(tween(HALO_MS), RepeatMode.Reverse),
            label = "halo",
        ).value
    }
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(AVATAR_SIZE + HALO_SPREAD)) {
        Box(
            modifier = Modifier
                .size(AVATAR_SIZE + HALO_SPREAD)
                .scale(halo)
                .alpha(HALO_ALPHA)
                .background(
                    Brush.radialGradient(listOf(MaggieTokens.Maggie.avatarFrom, Color.Transparent)),
                    CircleShape,
                ),
        )
        Image(
            painter = painterResource(R.drawable.maggie),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(AVATAR_SIZE)
                .scale(scale)
                .clip(CircleShape),
        )
    }
}

// A background shape, not a clip: a clipped layer swallows the taps of the buttons it holds under Robolectric.
private val BUBBLE_SHAPE = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp, bottomEnd = 30.dp, bottomStart = 8.dp)
private val AVATAR_SIZE = 72.dp
private val HALO_SPREAD = 36.dp
private const val HALO_MIN = 0.85f
private const val HALO_MAX = 1f
private const val HALO_STILL = 0.92f
private const val HALO_ALPHA = 0.55f
private const val HALO_MS = 1400
private const val POP_FROM = 0.5f
private const val FADE_MS = 500
private const val FADE_REDUCED_MS = 300
private const val VEIL_CENTER_ALPHA = 0.62f
private const val VEIL_EDGE_ALPHA = 0.88f
