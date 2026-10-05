package com.maggie.app.voice

import android.os.Bundle
import android.service.voice.VoiceInteractionService

class MaggieVoiceInteractionService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        instance = this
    }

    override fun onShutdown() {
        instance = null
        super.onShutdown()
    }

    companion object {
        @Volatile
        private var instance: MaggieVoiceInteractionService? = null

        /** False when the system has not bound the service, i.e. Maggie is not the default assistant. */
        fun showAssistantSession(): Boolean {
            val service = instance ?: return false
            service.showSession(Bundle(), 0)
            return true
        }
    }
}
