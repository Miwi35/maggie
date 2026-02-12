package com.maggie.app

import android.app.Application
import androidx.room.Room
import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.local.MaggieDatabase
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.repository.ChatRepository
import com.maggie.app.data.repository.EventRepository
import com.maggie.app.ui.screens.calendar.CalendarViewModel
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
            // Database
            single {
                Room.databaseBuilder(
                    androidContext(),
                    MaggieDatabase::class.java,
                    "maggie-database",
                ).build()
            }
            single { get<MaggieDatabase>().eventDao() }
            single { get<MaggieDatabase>().chatMessageDao() }

            // Network
            single { MaggieApiService() }
            single { MercureService() }

            // Repositories
            single { EventRepository(get(), get()) }
            single { ChatRepository(get(), get()) }

            // Other
            single { VoiceManager(androidContext()) }

            // ViewModels
            viewModel { CalendarViewModel(get(), get()) }
            viewModel { ChatViewModel(get(), get()) }
        }

        startKoin {
            androidContext(this@MaggieApp)
            modules(appModule)
        }

        get<VoiceManager>().initialize()
    }
}
