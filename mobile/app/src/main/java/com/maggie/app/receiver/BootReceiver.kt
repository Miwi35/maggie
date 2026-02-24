package com.maggie.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.maggie.app.voice.WakeWordManager
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class BootReceiver : BroadcastReceiver(), KoinComponent {

    private val wakeWordManager: WakeWordManager by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            wakeWordManager.restoreIfEnabled()
        }
    }
}
