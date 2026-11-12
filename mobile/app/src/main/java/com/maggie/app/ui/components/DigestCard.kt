package com.maggie.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.AudioAttachment
import com.maggie.app.data.model.DigestBlock
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.screens.digest.digestDateLabel
import java.time.LocalDate

const val DIGEST_CARD_TITLE = "Digest du matin"

/** « Jeudi 12 novembre · 1:12 »: the day of the digest and the length of its vocal; the day alone without a vocal. */
fun digestMoment(day: LocalDate, audio: AudioAttachment?): String =
    listOfNotNull(digestDateLabel(day), audio?.durationMs?.takeIf { it > 0 }?.let { clock(it) }).joinToString(" · ")

/** The card of the morning digest in the chat (MAG-808): a play disc, its title, its day and length, a chevron; a tap opens the screen. */
@Composable
fun DigestCard(block: DigestBlock, audio: AudioAttachment?, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val day = runCatching { LocalDate.parse(block.day) }.getOrNull()
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag(UiTags.CHAT_DIGEST_CARD)
            .clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(DIGEST_CARD_TITLE, style = MaterialTheme.typography.titleSmall)
                day?.let {
                    Text(
                        text = digestMoment(it, audio),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
