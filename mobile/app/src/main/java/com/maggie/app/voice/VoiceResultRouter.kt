package com.maggie.app.voice

import com.maggie.app.ui.screens.chat.ChatViewModel

/**
 * Where a sentence the overlay heard goes (MAG-310): a clear yes or no to a held action
 * is its answer — decided on the phone, no model call, so the microphone is freed at once —
 * and anything else is a message for Maggie. Returns whether [screenContext] was used up:
 * it stays armed for the next sentence when this one was only an answer.
 */
internal fun routeVoiceResult(
    text: String,
    chat: ChatViewModel,
    voice: VoiceManager,
    screenContext: ScreenContext?,
): Boolean {
    if (chat.answerApprovalByVoice(text)) {
        voice.answerHandled()
        return false
    }
    chat.sendMessage(text, screenContext?.toPromptBlock())
    return true
}
