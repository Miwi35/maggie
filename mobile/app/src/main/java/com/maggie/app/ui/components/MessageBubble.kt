package com.maggie.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.ChatMessage
import com.maggie.app.data.model.MessageFeedback
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.uiTagRoot
import com.maggie.app.util.stripDeliveryTags
import com.maggie.app.voice.ScreenshotEncoder

/** The user's answer to a request of Maggie's: a discreet centred line, not a bubble (MAG-336). */
@Composable
fun DecisionLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp)
            .testTag(UiTags.CHAT_DECISION),
    )
}

/**
 * [thumbnail] is the screenshot this device sent with the question, as JPEG bytes
 * (MAG-214). A message that had one but whose bytes are gone — a reload, another
 * device — only says so: the image is stored nowhere. The screen context itself is
 * never shown.
 *
 * @param onDelete when set, a long press opens a small menu with « Supprimer » (MAG-342).
 *   No confirmation: one message takes nothing else with it, and « Annuler » follows.
 *   A long press had no other use here, so the menu has no « Copier » to sit beside.
 * @param onEdit set on the last message of the user only: « Modifier » puts it back in the input (MAG-320).
 * @param onRate when set, a long press on an answer of Maggie's offers a thumb up and a thumb
 *   down (MAG-23): called with the new rating — `null` when the selected thumb is chosen again.
 *   Never on a question. The mock-ups draw no thumbs under the answers (MAG-624), so they live
 *   in the menu rather than leave the app. The word on why that follows a thumb down is
 *   [FeedbackCommentDialog].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: ChatMessage,
    onClick: (() -> Unit)? = null,
    isHighlighted: Boolean = false,
    thumbnail: ByteArray? = null,
    onDelete: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onRate: ((rating: String?) -> Unit)? = null,
) {
    if (message.isDecision) {
        DecisionLine(message.content)
        return
    }
    val isUser = message.role == "user"
    val highlightColor by animateColorAsState(
        targetValue = if (isHighlighted) MaterialTheme.colorScheme.primary else Color.Transparent,
        animationSpec = tween(durationMillis = 300),
        label = "highlight",
    )
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val canRate = onRate != null && !isUser
    val hasMenu = onDelete != null || canRate

    val gestures = if (onClick != null || hasMenu) {
        Modifier.combinedClickable(
            onClick = { onClick?.invoke() },
            onLongClick = if (hasMenu) {
                {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    menuOpen = true
                }
            } else {
                null
            },
            onLongClickLabel = when {
                onDelete != null && canRate -> "Ouvrir le menu du message"
                onDelete != null -> "Supprimer le message"
                canRate -> "Noter la réponse"
                else -> null
            },
        )
    } else {
        Modifier
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column {
            Box {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = if (isUser)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant,
                    border = if (isHighlighted) BorderStroke(2.dp, highlightColor) else null,
                    modifier = Modifier
                        .widthIn(max = 280.dp)
                        .then(gestures)
                        // The same actions for a screen reader, which has no long press to offer.
                        .then(
                            if (onDelete != null || canRate) {
                                Modifier.semantics {
                                    customActions = buildList {
                                        if (canRate) {
                                            val up = message.feedback == MessageFeedback.UP
                                            val down = message.feedback == MessageFeedback.DOWN
                                            add(
                                                CustomAccessibilityAction("Réponse utile") {
                                                    onRate?.invoke(MessageFeedback.UP.takeIf { !up })
                                                    true
                                                },
                                            )
                                            add(
                                                CustomAccessibilityAction("Réponse à revoir") {
                                                    onRate?.invoke(MessageFeedback.DOWN.takeIf { !down })
                                                    true
                                                },
                                            )
                                        }
                                        if (onDelete != null) {
                                            add(
                                                CustomAccessibilityAction("Supprimer le message") {
                                                    onDelete()
                                                    true
                                                },
                                            )
                                        }
                                    }
                                }
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        if (isUser && message.hasImage) {
                            ScreenshotThumbnail(thumbnail)
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        val linkColor = MaterialTheme.colorScheme.primary
                        Text(
                            text = remember(message.content, linkColor) {
                                linkified(if (isUser) message.content else stripDeliveryTags(message.content), linkColor)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                if (hasMenu) {
                    // A popup is a window of its own, so it carries its own tag root (MAG-98).
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        modifier = Modifier.uiTagRoot(),
                    ) {
                        if (onEdit != null) {
                            DropdownMenuItem(
                                text = { Text("Modifier") },
                                leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onEdit()
                                },
                                modifier = Modifier.testTag(UiTags.MESSAGE_EDIT),
                            )
                        }
                        if (canRate) {
                            FeedbackMenuItems(
                                feedback = message.feedback,
                                onRate = {
                                    menuOpen = false
                                    onRate(it)
                                },
                            )
                        }
                        if (onDelete != null) {
                            DropdownMenuItem(
                                text = { Text("Supprimer") },
                                leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onDelete()
                                },
                                modifier = Modifier.testTag(UiTags.MESSAGE_DELETE),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The two thumbs in the menu of an answer (MAG-23). Choosing the selected one takes it back; a
 * thumb down is recorded at once, and the ViewModel then offers [FeedbackCommentDialog].
 */
@Composable
private fun FeedbackMenuItems(feedback: String?, onRate: (rating: String?) -> Unit) {
    val up = feedback == MessageFeedback.UP
    val down = feedback == MessageFeedback.DOWN
    DropdownMenuItem(
        text = { Text("Réponse utile") },
        leadingIcon = {
            Icon(if (up) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp, contentDescription = null)
        },
        onClick = { onRate(MessageFeedback.UP.takeIf { !up }) },
        modifier = Modifier
            .testTag(UiTags.MESSAGE_THUMB_UP)
            .semantics { selected = up },
    )
    DropdownMenuItem(
        text = { Text("Réponse à revoir") },
        leadingIcon = {
            Icon(if (down) Icons.Filled.ThumbDown else Icons.Outlined.ThumbDown, contentDescription = null)
        },
        onClick = { onRate(MessageFeedback.DOWN.takeIf { !down }) },
        modifier = Modifier
            .testTag(UiTags.MESSAGE_THUMB_DOWN)
            .semantics { selected = down },
    )
}

/**
 * The optional word on why, offered once a thumb down is recorded (MAG-23), shown while
 * [ChatUiState.commentingMessageId][com.maggie.app.ui.screens.chat.ChatUiState.commentingMessageId]
 * is set. « Envoyer » needs a comment; « Passer » and a tap outside keep the thumb down as it is.
 */
@Composable
fun FeedbackCommentDialog(onSend: (String) -> Unit, onSkip: () -> Unit) {
    var comment by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onSkip,
        modifier = Modifier.uiTagRoot(),
        title = { Text("Réponse à revoir") },
        text = {
            OutlinedTextField(
                value = comment,
                onValueChange = { comment = it.take(MessageFeedback.MAX_COMMENT_LENGTH) },
                label = { Text("Qu'est-ce qui n'allait pas ? (facultatif)") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSend(comment) }, enabled = comment.isNotBlank()) {
                Text("Envoyer")
            }
        },
        dismissButton = {
            TextButton(onClick = onSkip) {
                Text("Passer")
            }
        },
    )
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
