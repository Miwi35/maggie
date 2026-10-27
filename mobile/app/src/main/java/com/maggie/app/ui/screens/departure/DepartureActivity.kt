package com.maggie.app.ui.screens.departure

import android.content.ActivityNotFoundException
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.maggie.app.data.fcm.PushIntents
import com.maggie.app.data.fcm.PushNotifier
import com.maggie.app.data.fcm.PushPayload
import com.maggie.app.data.model.Trip
import com.maggie.app.data.repository.TripRepository
import com.maggie.app.ui.theme.MaggieTheme
import com.maggie.app.ui.uiTagRoot
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * What a departure alert opens, over the lock screen (MAG-658): « Pars maintenant pour … » with
 * « Y aller » and « Déjà en route ». Started with [PushIntents.ACTION_DEPARTURE_GO] it has no screen:
 * it hands the navigation link over and closes, which is what the notification's own « Y aller » does.
 */
class DepartureActivity : ComponentActivity() {

    private val trips: TripRepository by inject()
    private var trip by mutableStateOf<Trip?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val payload = PushIntents.payloadOf(intent)?.takeIf { it.isDeparture }
        if (payload == null) {
            finish()
            return
        }
        if (intent.action == PushIntents.ACTION_DEPARTURE_GO) {
            go(payload)
            return
        }

        enableEdgeToEdge()
        setContent {
            MaggieTheme {
                Box(modifier = Modifier.fillMaxSize().uiTagRoot()) {
                    DepartureScreen(
                        title = payload.title,
                        trip = trip,
                        now = ZonedDateTime.now(PARIS),
                        onGo = PushIntents.navigationOf(payload.link)?.let { { go(payload) } },
                        onAlreadyOnTheWay = { dismiss(payload) },
                    )
                }
            }
        }
        // The alert carries the navigation link, not the trip: the day's trips tell which one it is.
        lifecycleScope.launch {
            trip = trips.getTrips(LocalDate.now(PARIS)).getOrNull()?.firstOrNull { it.navigationUrl == payload.link }
        }
    }

    private fun go(payload: PushPayload) {
        PushNotifier(this).close(payload.systemTag)
        try {
            PushIntents.navigate(payload)?.let(::startActivity)
        } catch (_: ActivityNotFoundException) {
        }
        finish()
    }

    private fun dismiss(payload: PushPayload) {
        PushNotifier(this).close(payload.systemTag)
        finish()
    }

    private companion object {
        val PARIS: ZoneId = ZoneId.of("Europe/Paris")
    }
}
