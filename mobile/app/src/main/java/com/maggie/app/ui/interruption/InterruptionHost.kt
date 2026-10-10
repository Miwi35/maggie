package com.maggie.app.ui.interruption

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.maggie.app.data.fcm.PushActionKind
import com.maggie.app.data.fcm.PushPayload
import com.maggie.app.data.interruption.InterruptionAlert
import com.maggie.app.data.interruption.key
import com.maggie.app.ui.uiTagRoot
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

const val INTERRUPTION_AUTO_CLOSE_MS = 30_000L

/**
 * Shows what Maggie has to say over whichever screen is open (MAG-314). Mounted by every
 * activity that can be in front — the app and the assistant overlay — and shown only while that
 * activity is resumed, so a message is never said in a window nobody looks at, nor twice.
 *
 * A message nobody answers goes away after thirty seconds and stays unread.
 */
@Composable
fun InterruptionHost(
    onOpenLink: (PushPayload) -> Unit,
    onOpenChat: () -> Unit,
    viewModel: InterruptionViewModel = koinViewModel(),
    alert: InterruptionAlert = koinInject(),
) {
    val state by viewModel.uiState.collectAsState()
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val reducedMotion = rememberReducedMotion()

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is InterruptionEvent.OpenLink -> onOpenLink(event.payload)
                InterruptionEvent.OpenChat -> onOpenChat()
            }
        }
    }

    val payload = state.payload
    if (payload == null || !lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) return

    val key = payload.key
    LaunchedEffect(key) {
        if (viewModel.announce(key)) alert.alert()
    }
    LaunchedEffect(key) {
        delay(INTERRUPTION_AUTO_CLOSE_MS)
        viewModel.timedOut(key)
    }

    Dialog(
        onDismissRequest = { viewModel.answer(PushActionKind.LATER) },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Box(Modifier.fillMaxSize().uiTagRoot()) {
            MaggieToaster(
                payload = payload,
                busy = state.busy,
                note = state.note,
                reducedMotion = reducedMotion,
                onAction = viewModel::answer,
            )
        }
    }
}
