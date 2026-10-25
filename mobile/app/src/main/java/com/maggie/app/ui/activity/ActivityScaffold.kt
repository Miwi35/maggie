package com.maggie.app.ui.activity

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.theme.MaggieDarkColorScheme
import com.maggie.app.ui.theme.MaggieShapes
import com.maggie.app.ui.theme.ModuleHue
import com.maggie.app.ui.theme.inModule
import com.maggie.app.ui.uiTagRoot

/** The header's two buttons: Maggie and ✕. */
private val HEADER_BUTTON = 56.dp

/**
 * The frame every activity shares (MAG-580): the whole screen, kept on, with no app chrome; a header with
 * the activity's own [progress], Maggie and ✕; the system's back, which does what ✕ does. With [confirmQuit] —
 * something would be lost, such as a running timer — leaving asks « Quitter l'activité ? » first, and
 * [confirmDetail] says what.
 *
 * The background is the night with the module's colour as a radial halo (MAG-617); no activity draws its own.
 *
 * The immersion is taken when the frame enters the composition and given back when it leaves it, whichever way
 * it leaves: ✕, back, a link, the process.
 */
@Composable
fun ActivityScaffold(
    name: String,
    hue: ModuleHue,
    onQuit: () -> Unit,
    onOpenMaggie: () -> Unit,
    modifier: Modifier = Modifier,
    confirmQuit: Boolean = false,
    confirmDetail: String? = null,
    immersion: ActivityImmersion = rememberWindowImmersion(),
    progress: @Composable () -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    val requestQuit = { if (confirmQuit) confirming = true else onQuit() }

    DisposableEffect(immersion) {
        immersion.enter()
        onDispose { immersion.exit() }
    }

    BackHandler(onBack = requestQuit)

    MaterialTheme(colorScheme = remember(hue) { MaggieDarkColorScheme.inModule(hue) }, shapes = MaggieShapes) {
        if (confirming && confirmQuit) {
            AlertDialog(
                modifier = Modifier.uiTagRoot().testTag(UiTags.ACTIVITY_QUIT_CONFIRM),
                onDismissRequest = { confirming = false },
                title = { Text("Quitter l'activité ?") },
                text = confirmDetail?.let { { Text(it) } },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirming = false
                            onQuit()
                        },
                        modifier = Modifier.heightIn(min = HEADER_BUTTON).testTag(UiTags.ACTIVITY_QUIT_LEAVE),
                    ) { Text("Quitter") }
                },
                dismissButton = {
                    TextButton(
                        onClick = { confirming = false },
                        modifier = Modifier.heightIn(min = HEADER_BUTTON).testTag(UiTags.ACTIVITY_QUIT_STAY),
                    ) { Text("Rester") }
                },
            )
        }

        Surface(
            modifier = modifier.fillMaxSize().testTag(UiTags.ACTIVITY_SCAFFOLD),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier.fillMaxSize().activityHalo(MaterialTheme.colorScheme.primary).safeDrawingPadding(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.weight(1f)) { progress() }
                    FilledTonalIconButton(
                        onClick = onOpenMaggie,
                        modifier = Modifier.size(HEADER_BUTTON).testTag(UiTags.ACTIVITY_MAGGIE),
                    ) {
                        Icon(Icons.Outlined.AutoAwesome, contentDescription = "Demander à Maggie")
                    }
                    IconButton(
                        onClick = requestQuit,
                        modifier = Modifier.size(HEADER_BUTTON).testTag(UiTags.ACTIVITY_QUIT),
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Quitter $name")
                    }
                }
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), content = content)
            }
        }
    }
}
