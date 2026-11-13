package com.maggie.app.ui.screens.bilan

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

private val Context.bilanDataStore: DataStore<Preferences> by preferencesDataStore(name = "bilan_prefs")

/** The last day whose bilan ended with « Bonne nuit » (MAG-826): « Bonne nuit » deletes the status, so this is what keeps the Accueil tile away. */
interface BilanDoneStore {
    val doneDay: Flow<LocalDate?>

    suspend fun markDone(day: LocalDate)
}

class DataStoreBilanDoneStore(private val store: DataStore<Preferences>) : BilanDoneStore {
    constructor(context: Context) : this(context.bilanDataStore)

    private val key = stringPreferencesKey("done_day")

    override val doneDay: Flow<LocalDate?> = store.data.map { prefs ->
        prefs[key]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    }

    override suspend fun markDone(day: LocalDate) {
        store.edit { it[key] = day.toString() }
    }
}
