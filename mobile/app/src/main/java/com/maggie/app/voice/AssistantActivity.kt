package com.maggie.app.voice

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.maggie.app.ui.components.AssistantOverlay
import com.maggie.app.ui.screens.chat.ChatViewModel
import com.maggie.app.ui.theme.MaggieTheme
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class AssistantActivity : ComponentActivity() {

    private val voiceManager: VoiceManager by inject()
    private val chatViewModel: ChatViewModel by viewModel()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            voiceManager.startListening()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaggieTheme {
                AssistantOverlay(
                    viewModel = chatViewModel,
                    voiceManager = voiceManager,
                    onDismiss = { finish() },
                )
            }
        }

        requestMicAndListen()
    }

    private fun requestMicAndListen() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            voiceManager.startListening()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onDestroy() {
        voiceManager.cancelListening()
        voiceManager.stopSpeaking()
        super.onDestroy()
    }
}
