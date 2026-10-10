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
    private val chatViewModel: ChatViewModel by viewModel()

    /**
     * What the screen behind the overlay was showing, when Android told us
     * (MAG-30), until a sentence uses it: « ajoute ça à mon agenda » is about the
     * screen, the follow-up question is about the answer. Assigned afresh on every
     * invocation, so summoning Maggie twice from the same screen re-arms it.
     */
    private var pendingContext by mutableStateOf<ScreenContext?>(null)

    /** Always the session's own file: the intent only says whether there is one (MAG-214). */
    private val screenshot by lazy { PendingScreenshot(ScreenshotEncoder.file(this)) }

    private val sendVoiceResult: (String) -> Unit = { text ->
        val screen = pendingContext
        // Read, then deleted: the screenshot goes with this sentence and nowhere else (MAG-214).
        val takeImage = { if (screen?.hasScreenshot == true) screenshot.take() else null }
        if (routeVoiceResult(text, chatViewModel, voiceManager, screen, takeImage)) {
            pendingContext = null
        }
    }

    /**
     * A screenshot the user did not send is not kept: the overlay closed, or a new
     * invocation without one. One that brings a screenshot has already overwritten it.
     */
    private fun replaceContext(next: ScreenContext?) {
        if (pendingContext?.hasScreenshot == true && next?.hasScreenshot != true) screenshot.discard()
        pendingContext = next
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            voiceManager.startListening(sendVoiceResult)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isShowing = true
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        // Only on a first start. A recreation — a rotation, a theme change — is
        // not a new invocation: the screen the context described is long gone,
        // and reviving it would attach the whole block to the next sentence as
        // if it had never been used.
        pendingContext = if (savedInstanceState == null) contextOf(intent) else null
        setContent {
            MaggieTheme {
                AssistantOverlay(
                    viewModel = chatViewModel,
                    voiceManager = voiceManager,
                    onDismiss = { finish() },
                    pendingContext = pendingContext,
                    onVoiceResult = sendVoiceResult,
                    onListen = { requestMicAndListen() },
                )
            }
        }

        requestMicAndListen()
    }

    /**
     * The activity is `singleTask`, so every later invocation — the assistant key,
     * `ACTION_ASSIST` — lands here instead of `onCreate`. Both mean « I want to
     * talk now », so both start listening: a long press on an overlay that was
     * already open used to leave the microphone shut (MAG-30).
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Replaced, not merged: an invocation that brings no context — a plain
        // `ACTION_ASSIST` — is not about the previous screen.
        replaceContext(contextOf(intent))
        voiceManager.stopSpeaking()
        requestMicAndListen()
    }

    private fun requestMicAndListen() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            voiceManager.startListening(sendVoiceResult)
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /** The e2e flavor can also stage a screenshot from its own link; other builds never do. */
    private fun contextOf(intent: Intent): ScreenContext? =
        ScreenContext.fromIntent(intent) ?: fixtureScreenContext(this, intent)

    override fun onDestroy() {
        replaceContext(null)
        isShowing = false
        voiceManager.cancelListening()
        voiceManager.stopSpeaking()
        super.onDestroy()
    }

    companion object {
        @Volatile
        var isShowing = false
            private set
    }
}
