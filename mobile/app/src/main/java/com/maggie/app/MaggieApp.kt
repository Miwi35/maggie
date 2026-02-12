package com.maggie.app

import android.app.Application
import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.repository.ChatRepository
import com.maggie.app.data.repository.EventRepository
import com.maggie.app.ui.screens.agenda.AgendaViewModel
import com.maggie.app.ui.screens.chat.ChatViewModel
import com.maggie.app.voice.VoiceManager
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

class MaggieApp : Application() {
    override fun onCreate() {
        super.onCreate()

        val appModule = module {
            single { MaggieApiService() }
            single { MercureService() }
            single { EventRepository(get()) }
            single { ChatRepository(get()) }
            single { VoiceManager(androidContext()) }
            viewModel { AgendaViewModel(get(), get()) }
            viewModel { ChatViewModel(get(), get()) }
        }

        startKoin {
            androidContext(this@MaggieApp)
            modules(appModule)
        }

        get<VoiceManager>().initialize()
    }
}
