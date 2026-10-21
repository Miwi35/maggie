package com.maggie.app.ui.screens.tasks

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.Stars
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.theme.MaggieTokens

/** The stars of the month, lavender; the latest, orchid; a palier, gold. */
internal val StarColor = MaggieTokens.Brand.primaryHover
internal val LatestStarColor = MaggieTokens.Module.taches.second
internal val PalierColor = MaggieTokens.Module.saga.second

/**
 * The constellation of a month (MAG-509): one star for every task done, only those earned — never
 * an empty place to fill. The latest one glints unless [animate] is off. Ten to a row; past a
 * hundred, the last hundred and « +n ». With [onPrevious] the title carries the arrows between
 * months, [onNext] being `null` on the current one; [showTotal] adds « depuis toujours ».
 */
@Composable
internal fun Constellation(
    stars: Stars,
    animate: Boolean,
    modifier: Modifier = Modifier,
    showTotal: Boolean = false,
    onPrevious: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().testTag(UiTags.CONSTELLATION),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (onPrevious != null) {
                IconButton(onClick = onPrevious, modifier = Modifier.testTag(UiTags.CONSTELLATION_PREVIOUS)) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Mois précédent")
                }
            }
            Text(
                text = stars.title,
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).testTag(UiTags.CONSTELLATION_TITLE),
            )
            if (onPrevious != null) {
                IconButton(onClick = { onNext?.invoke() }, enabled = onNext != null, modifier = Modifier.testTag(UiTags.CONSTELLATION_NEXT)) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Mois suivant")
                }
            }
        }

        // The grid is a picture: the title already says the count.
        Column(modifier = Modifier.clearAndSetSemantics { }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val rows = (1..stars.drawn).chunked(Stars.ROW)
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { index -> StarDot(latest = index == stars.drawn, animate = animate) }
                }
            }
        }
        if (stars.hidden > 0) {
            Text(
                text = "+${stars.hidden}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.End).testTag(UiTags.CONSTELLATION_MORE),
            )
        }
        if (showTotal) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.testTag(UiTags.CONSTELLATION_TOTAL),
            ) {
                Icon(Icons.Default.Star, contentDescription = null, tint = StarColor, modifier = Modifier.size(16.dp))
                Text(
                    text = "${Stars.starsLabelOf(stars.total)} depuis toujours",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StarDot(latest: Boolean, animate: Boolean) {
    val glint = if (latest && animate) {
        rememberInfiniteTransition(label = "glint").animateFloat(
            initialValue = 1f,
            targetValue = 1.25f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 700), RepeatMode.Reverse),
            label = "glint",
        ).value
    } else {
        1f
    }
    Box(modifier = Modifier.testTag(if (latest) UiTags.CONSTELLATION_LATEST else UiTags.CONSTELLATION_STAR)) {
        Icon(
            Icons.Default.Star,
            contentDescription = null,
            tint = if (latest) LatestStarColor else StarColor,
            modifier = Modifier.size(22.dp).scale(glint),
        )
    }
}

/** The star a done task carries in « Mes 5 », with its name for the screen reader. */
@Composable
internal fun TaskStar(modifier: Modifier = Modifier) {
    Icon(
        Icons.Default.Star,
        contentDescription = "Étoile gagnée",
        tint = StarColor,
        modifier = modifier.size(20.dp).testTag(UiTags.TASK_STAR),
    )
}
