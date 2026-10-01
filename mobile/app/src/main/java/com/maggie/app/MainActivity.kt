package com.maggie.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import com.maggie.app.data.repository.UserPreferenceRepository
import com.maggie.app.ui.navigation.NavGraph
import com.maggie.app.ui.theme.MaggieTheme
import org.koin.android.ext.android.inject

class MainActivity : FragmentActivity() {
    private val userPreferenceRepository: UserPreferenceRepository by inject()

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val preference by userPreferenceRepository.preference.collectAsState()
            MaggieTheme(themePreference = preference?.theme ?: "system") {
                // Publishes every `testTag` under the tree as the resource id
                // UiAutomator reports, which is the only way a Maestro `id:`
                // selector can see a Compose node (MAG-98, see ui/UiTags.kt).
                // Set once at the root: the flag is inherited, and a tag added
                // anywhere below is addressable without touching this file.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics { testTagsAsResourceId = true },
                ) {
                    NavGraph()
                }
            }
        }
    }
}
