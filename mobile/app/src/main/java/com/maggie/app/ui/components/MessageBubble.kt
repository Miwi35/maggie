package com.maggie.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.ChatMessage
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.uiTagRoot

/**
 * @param onDelete when set, a long press opens a small menu with « Supprimer » (MAG-342).
 *   No confirmation: one message takes nothing else with it, and « Annuler » follows.
 *   A long press had no other use here, so the menu has no « Copier » to sit beside.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: ChatMessage,
    onClick: (() -> Unit)? = null,
    isHighlighted: Boolean = false,
    onDelete: (() -> Unit)? = null,
) {
    val isUser = message.role == "user"
    val highlightColor by animateColorAsState(
        targetValue = if (isHighlighted) MaterialTheme.colorScheme.primary else Color.Transparent,
        animationSpec = tween(durationMillis = 300),
        label = "highlight",
    )
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

    val gestures = if (onClick != null || onDelete != null) {
        Modifier.combinedClickable(
            onClick = { onClick?.invoke() },
            onLongClick = onDelete?.let {
                {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    menuOpen = true
                }
            },
            onLongClickLabel = onDelete?.let { "Supprimer le message" },
        )
    } else {
        Modifier
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
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
                    // The same action for a screen reader, which has no long press to offer.
                    .then(
                        if (onDelete != null) {
                            Modifier.semantics {
                                customActions = listOf(
                                    CustomAccessibilityAction("Supprimer le message") {
                                        onDelete()
                                        true
                                    },
                                )
                            }
                        } else {
                            Modifier
                        },
                    ),
            ) {
                Text(
                    text = message.content,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (onDelete != null) {
                // A popup is a window of its own, so it carries its own tag root (MAG-98).
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    modifier = Modifier.uiTagRoot(),
                ) {
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
