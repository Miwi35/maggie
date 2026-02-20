package com.maggie.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import com.maggie.app.data.repository.UserPreferenceRepository
import com.maggie.app.ui.navigation.NavGraph
import com.maggie.app.ui.theme.MaggieTheme
import org.koin.android.ext.android.inject

class MainActivity : FragmentActivity() {
    private val userPreferenceRepository: UserPreferenceRepository by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val preference by userPreferenceRepository.preference.collectAsState()
            MaggieTheme(themePreference = preference?.theme ?: "system") {
                NavGraph()
            }
        }
    }
}
