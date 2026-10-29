package com.maggie.app.ui.screens.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Tells [viewModel] while this surface (the chat screen, the sheet or the panel) is in front of the owner
 * (MAG-687): composed *and* the app started. A conversation behind a locked phone is not being read.
 */
@Composable
fun ChatDisplayed(viewModel: ChatViewModel) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, viewModel) {
        var shown = false
        fun show(now: Boolean) {
            if (now != shown) {
                shown = now
                viewModel.onChatDisplayed(now)
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> show(true)
                Lifecycle.Event.ON_STOP -> show(false)
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            show(false)
        }
    }
}
