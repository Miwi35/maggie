package com.maggie.app

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
import com.maggie.app.data.repository.UserPreferenceRepository
import com.maggie.app.ui.navigation.NavGraph
import com.maggie.app.ui.theme.MaggieTheme
import com.maggie.app.ui.uiTagRoot
import com.maggie.app.voice.WakeWordManager
import org.koin.android.ext.android.inject

class MainActivity : FragmentActivity() {
    private val userPreferenceRepository: UserPreferenceRepository by inject()
    private val wakeWordManager: WakeWordManager by inject()

    override fun onStart() {
        super.onStart()
        wakeWordManager.restoreIfEnabled()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
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
}
