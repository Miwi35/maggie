package com.maggie.app.data.health

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.time.Instant

private val Context.healthSyncDataStore: DataStore<Preferences> by preferencesDataStore(name = "health_sync_prefs")

/** When the last Health Connect batch was taken by the API (MAG-708): the next read starts from there. */
class HealthSyncPreferences(private val store: DataStore<Preferences>) {

    constructor(context: Context) : this(context.healthSyncDataStore)

    private val lastSyncKey = longPreferencesKey("last_sync_ms")

    suspend fun lastSync(): Instant? = store.data.first()[lastSyncKey]?.let(Instant::ofEpochMilli)

    /** Never backwards: a slow pass that ends after a newer one leaves the newer mark. */
    suspend fun advanceTo(at: Instant) {
        store.edit { prefs ->
            val current = prefs[lastSyncKey]
            if (current == null || at.toEpochMilli() > current) prefs[lastSyncKey] = at.toEpochMilli()
        }
    }

    /** Signed out: the next account starts from its own 30 days. */
    suspend fun clear() {
        store.edit { it.remove(lastSyncKey) }
    }
}
