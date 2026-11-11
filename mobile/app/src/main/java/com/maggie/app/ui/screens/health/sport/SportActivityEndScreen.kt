package com.maggie.app.ui.screens.health.sport

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.activity.ActivityScaffold
import com.maggie.app.ui.theme.ModuleHue

/** The end of the Sport activity: the last set leads here (MAG-780), the status holds the whole `done`. Empty until Fin de séance. */
@Composable
fun SportActivityEndScreen(onQuit: () -> Unit, onOpenMaggie: () -> Unit) {
    ActivityScaffold(name = "la séance", hue = ModuleHue.HEALTH, onQuit = onQuit, onOpenMaggie = onOpenMaggie, quitFirst = true) {
        Box(Modifier.fillMaxSize().testTag(UiTags.SPORT_ACTIVITY_END))
    }
}
