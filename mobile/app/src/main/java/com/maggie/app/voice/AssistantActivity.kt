package com.maggie.app.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.maggie.app.ui.components.AssistantOverlay
import com.maggie.app.ui.screens.chat.ChatViewModel
import com.maggie.app.ui.theme.MaggieTheme
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class AssistantActivity : ComponentActivity() {

    private val voiceManager: VoiceManager by inject()
    private val wakeWordManager: WakeWordManager by inject()
    private val chatViewModel: ChatViewModel by viewModel()

    /** What the screen behind the overlay was showing, when Android told us (MAG-30). */
    private var screenContext by mutableStateOf<ScreenContext?>(null)

    /**
     * Counts invocations, and is what the overlay keys its « context not used
     * yet » state on. The context itself cannot be that key: summoning Maggie
     * twice from the same screen hands over an equal [ScreenContext], and a
     * state keyed on equality would not re-arm — the second question would
     * silently lose the screen it is about.
     */
    private var invocation by mutableStateOf(0)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            voiceManager.startListening()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only on a first start. A recreation — a rotation, a theme change — is
        // not a new invocation: the screen the context described is long gone,
        // and reviving it would attach the whole block to the next sentence as
        // if it had never been used.
        screenContext = if (savedInstanceState == null) ScreenContext.fromIntent(intent) else null
        setContent {
            MaggieTheme {
                AssistantOverlay(
                    viewModel = chatViewModel,
                    voiceManager = voiceManager,
                    onDismiss = { finish() },
                    screenContext = screenContext,
                    invocation = invocation,
                )
            }
        }

        requestMicAndListen()
    }

    /**
     * The activity is `singleTask`, so every later invocation — the assistant key,
     * the wake word, `ACTION_ASSIST` — lands here instead of `onCreate`. All of
     * them mean « I want to talk now », so all of them start listening: the
     * previous code only did it for the wake word, and a long press on an overlay
     * that was already open left the microphone shut (MAG-30).
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Replaced, not merged: an invocation that brings no context — the wake
        // word, a plain `ACTION_ASSIST` — is not about the previous screen.
        screenContext = ScreenContext.fromIntent(intent)
        invocation++
        voiceManager.stopSpeaking()
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
        wakeWordManager.resumeListening()
        super.onDestroy()
    }
}
