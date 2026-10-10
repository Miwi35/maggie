package com.maggie.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import com.maggie.app.data.fcm.PushIntents
import com.maggie.app.data.interruption.InterruptionCenter
import com.maggie.app.data.repository.UserPreferenceRepository
import com.maggie.app.ui.navigation.NavGraph
import com.maggie.app.ui.theme.MaggieTheme
import com.maggie.app.ui.uiTagRoot
import org.koin.android.ext.android.inject

class MainActivity : FragmentActivity() {
    private val userPreferenceRepository: UserPreferenceRepository by inject()
    private val interruptions: InterruptionCenter by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // A recreation (rotation, theme) replays the intent that started the activity: not a new tap.
        if (savedInstanceState == null) openedByPush(intent)
        enableEdgeToEdge()
        setContent {
            val preference by userPreferenceRepository.preference.collectAsState()
            MaggieTheme(themePreference = preference?.theme ?: "system") {
                // This window's tag root (MAG-98). It covers everything the
                // activity composes; the sheets and dialogs are separate windows
                // and carry their own — see UiTagRoot.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .uiTagRoot(),
                ) {
                    NavGraph()
                }
            }
        }
    }

    // A tap on a notification's body — Android's own or the app's — opens the app on what Maggie said.
    override fun onNewIntent(intent: Intent) {
        openedByPush(intent)
        super.onNewIntent(intent)
    }

    private fun openedByPush(intent: Intent) {
        PushIntents.closeNotificationOf(this, intent)
        PushIntents.interruptionOf(intent)?.let(interruptions::reopen)
    }
}
